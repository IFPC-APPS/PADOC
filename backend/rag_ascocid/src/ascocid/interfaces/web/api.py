"""API HTTP du chatbot AsCoCid — le contrat de la spec 07 §2, servi.

Ce qui existait jusqu'ici : une chaîne complète (`application/requete/pipeline`)
et un CLI qui l'exerce. Ce module est la couche manquante entre les deux et la
plateforme : les mêmes événements, en SSE.

Trois partis pris, hérités des specs :

* **Le flux avant tout.** Les sources et les illustrations partent avant le
  premier mot rédigé (spec 05 §4) : le modèle met plusieurs secondes, la page a
  de quoi se composer dès 200 ms.
* **Sans état.** L'historique arrive dans la requête, il n'est jamais conservé
  ici (spec 07 §8) — la plateforme porte les sessions, ce service reste
  horizontalement duplicable.
* **Un chargement, pas un par requête.** L'embedder (400 Mo de poids) et l'index
  vectoriel sont chargés au démarrage ; seule la connexion SQLite est ouverte par
  requête, parce qu'un objet `sqlite3.Connection` n'est pas partageable entre
  threads et que Starlette exécute les générateurs synchrones sur un pool.

Le service tourne avec le répertoire du projet pour racine : `data/`, `config/`
et `.env` sont résolus relativement à lui (voir le script `./serve`).
"""

from __future__ import annotations

import json
import os
import pathlib
import time
from collections.abc import Iterator
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse
from dotenv import load_dotenv
from pydantic import BaseModel, Field

from ascocid.application.requete import prompt as gabarit
from ascocid.application.requete.analyse import Analyseur, RegistreOutils
from ascocid.application.requete.pipeline import Pipeline
from ascocid.application.requete.recherche import RechercheHybride
from ascocid.application.requete.verification import verifier
from ascocid.infrastructure.graphe.sqlite import MagasinSqlite

PREFIXE = "/api/ldc"
# Toutes les données du corpus sont sous une même racine : le répertoire du
# projet en local, un volume monté en production (ASCOCID_DATA=/data).
RACINE = pathlib.Path(os.environ.get("ASCOCID_DATA", "data"))
BASE_DONNEES = os.environ.get("ASCOCID_SQLITE", str(RACINE / "ascocid.sqlite"))
BLOBS = RACINE / "blobs"
MANIFESTE = RACINE / "manifeste.json"
RETOURS = RACINE / "retours.jsonl"
SUGGESTIONS = pathlib.Path("config/suggestions.json")
URL_ASCOCID = "https://ascocid.fr/ldc/view.php?id_document={idoc}"

# Le corpus ne bouge qu'à la réindexation : une illustration peut être mise en
# cache très longtemps par le navigateur et les intermédiaires.
CACHE_ILLUSTRATION = "public, max-age=604800, immutable"

SUGGESTIONS_DEFAUT = [
    "À quelle température conduire la fermentation d'un cidre ?",
    "Comment se déroule la clarification par défécation ?",
    "Qu'est-ce que la fermentation malolactique et à quoi sert-elle ?",
    "Quelles sont les étapes de l'extraction du jus ?",
]


# ── Moteur partagé ───────────────────────────────────────────────────────────


class Moteur:
    """Ce qui coûte cher à construire, construit une fois.

    L'embedder et l'index sont en lecture seule une fois chargés : les partager
    entre requêtes est sûr, et c'est la seule façon de tenir le budget de
    latence — charger le modèle E5 prend plusieurs secondes.
    """

    def __init__(self) -> None:
        self.registre = RegistreOutils()
        self.embedder: Any = None
        self.index: Any = None
        self.analyseur_modele: Any = None
        self.generateur: Any = None
        self.carte = ""
        self.generation_disponible = False
        self.modele_redaction = ""
        self.modele_analyse = ""
        self.charge_le = 0.0

    def charger(self) -> None:
        # Mêmes clés, même fichier que le CLI : un seul endroit à renseigner.
        load_dotenv(os.environ.get("ASCOCID_ENV", ".env"))
        debut = time.perf_counter()
        try:
            from ascocid.infrastructure.embeddings.e5 import EmbedderE5
            from ascocid.infrastructure.index.memoire import IndexMemoire

            index = IndexMemoire(RACINE / "index")
            index.charger()
            self.index = index
            self.embedder = EmbedderE5()
            # Les poids du modèle ne sont chargés qu'au premier encodage : sans
            # ce préchauffage, la première question du premier utilisateur
            # paierait seule les ~6 s de chargement.
            self.embedder.encoder(["préchauffage"], requete=True)
        except (ImportError, FileNotFoundError) as exc:
            # La recherche lexicale seule reste utile : mieux vaut un service
            # dégradé qui le dit qu'un service qui refuse de démarrer.
            print(f"[ldc] index vectoriel indisponible ({type(exc).__name__}: {exc}) "
                  f"— recherche lexicale seule")

        with MagasinSqlite(BASE_DONNEES) as magasin:
            self.carte = gabarit.carte_du_corpus(magasin)

        from ascocid.infrastructure.llm import fabrique

        if (fournisseur := fabrique.fournisseur_actif()):
            self.analyseur_modele = fabrique.analyseur(self.registre, fournisseur)
            self.modele_analyse = self.analyseur_modele.identifiant_modele
            # Les deux adaptateurs sont sans état d'une requête à l'autre :
            # seul le prompt change.
            self.generateur = fabrique.generateur(fournisseur)
            self.generateur.carte = self.carte
            self.modele_redaction = self.generateur.identifiant_modele
            self.generation_disponible = True
            print(f"[ldc] fournisseur {fournisseur} — rédaction "
                  f"{self.modele_redaction}, analyse {self.modele_analyse}")
        else:
            print("[ldc] aucune clé de modèle (ARGO_API_KEY ou GEMINI_API_KEY) — "
                  "la rédaction est indisponible, seules les sources seront renvoyées")
        self.charge_le = time.perf_counter() - debut

    def pipeline(self, magasin: MagasinSqlite, k: int) -> Pipeline:
        """Une chaîne par requête, autour de la connexion SQLite de la requête."""
        recherche = RechercheHybride(magasin, self.embedder, self.index)
        analyseur = Analyseur(magasin, self.analyseur_modele, self.registre)
        return Pipeline(analyseur, recherche, self.generateur, verifier, magasin, k=k,
                        base_illustrations=f"{PREFIXE}/illustration")


moteur = Moteur()


@asynccontextmanager
async def cycle_de_vie(_: FastAPI):  # noqa: ANN201
    moteur.charger()
    yield


app = FastAPI(
    title="AsCoCid — Livre de Connaissances",
    description="Question/réponse sourcée sur le Livre de Connaissances AsCoCid.",
    version="0.1.0",
    lifespan=cycle_de_vie,
)

# Le front passe par le proxy Next.js (même origine) ; CORS n'est là que pour
# les appels directs en développement.
app.add_middleware(
    CORSMiddleware,
    allow_origins=os.environ.get("LDC_ORIGINES", "*").split(","),
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


# ── Authentification ─────────────────────────────────────────────────────────


def utilisateur(request: Request) -> dict | None:
    """Jeton de la plateforme, vérifié si — et seulement si — une clé est fournie.

    Chaque question consomme un appel payant au modèle. Exposée sans contrôle
    derrière le proxy, la route `/ask` est une facture ouverte : dès que
    `JWT_SECRET` est défini (déploiement), le jeton devient obligatoire. En
    développement, sans clé partagée, le service reste ouvert et le dit au
    démarrage.
    """
    secret = os.environ.get("JWT_SECRET", "").strip()
    if not secret or os.environ.get("LDC_AUTH", "").strip() == "0":
        return None

    import base64

    import jwt

    entete = request.headers.get("authorization", "")
    if not entete.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Jeton absent.")
    try:
        return jwt.decode(entete[7:], base64.b64decode(secret), algorithms=["HS256"])
    except Exception as exc:  # noqa: BLE001 — toute erreur de jeton vaut refus
        raise HTTPException(status_code=401, detail="Jeton invalide.") from exc


# ── Contrats ─────────────────────────────────────────────────────────────────


class Demande(BaseModel):
    question: str = Field(min_length=1, max_length=2000)
    # Tours précédents, transmis par la plateforme : l'analyse s'en sert pour
    # rendre une relance autonome (« et pour les pommes douces ? »).
    historique: list[str] = Field(default_factory=list, max_length=8)
    k: int = Field(default=8, ge=1, le=20)


class Retour(BaseModel):
    question: str = ""
    reponse: str = ""
    utile: bool
    motifs: list[str] = Field(default_factory=list)
    commentaire: str = ""
    sources: list[int] = Field(default_factory=list)


# ── Flux de réponse ──────────────────────────────────────────────────────────


def _sse(evenement: str, charge: dict) -> str:
    return f"event: {evenement}\ndata: {json.dumps(charge, ensure_ascii=False)}\n\n"


def _flux(demande: Demande) -> Iterator[str]:
    """Traduit les événements du pipeline en SSE, sans en changer l'ordre."""
    magasin = MagasinSqlite(BASE_DONNEES, multi_threads=True)
    try:
        chaine = moteur.pipeline(magasin, demande.k)
        for evenement in chaine.repondre(demande.question, demande.historique or None):
            type_ = evenement.pop("type")
            yield _sse(type_, evenement)
    except Exception as exc:  # noqa: BLE001
        # Une erreur au milieu d'un flux SSE ne peut plus être un code HTTP :
        # sans cet événement, la page attendrait indéfiniment.
        yield _sse("erreur", {"code": type(exc).__name__, "message": str(exc)})
    finally:
        magasin.cx.close()


@app.post(f"{PREFIXE}/ask")
def ask(demande: Demande, _: dict | None = Depends(utilisateur)) -> StreamingResponse:
    """Réponse en flux : analyse → sources → illustrations → texte → fin."""
    if not moteur.generation_disponible:
        raise HTTPException(
            status_code=503,
            detail="Rédaction indisponible : GEMINI_API_KEY n'est pas configurée.")
    return StreamingResponse(
        _flux(demande),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "Connection": "keep-alive",
            # nginx bufferise les réponses par défaut, ce qui annule le
            # streaming : la réponse arriverait d'un bloc au bout de 15 s.
            "X-Accel-Buffering": "no",
        },
    )


# ── Consultation ─────────────────────────────────────────────────────────────


def _blob(idoc: int) -> tuple[pathlib.Path, str] | None:
    manifeste = json.loads(MANIFESTE.read_text()) if MANIFESTE.exists() else {}
    sha = manifeste.get(f"{idoc}:img")
    if not sha:
        return None
    chemin = BLOBS / sha[:2] / sha
    return (chemin, sha) if chemin.exists() else None


@app.get(f"{PREFIXE}/illustration/{{idoc}}")
def illustration(idoc: int) -> FileResponse:
    """Le SVG ou le PNG d'origine (spec 07 §9)."""
    with MagasinSqlite(BASE_DONNEES) as magasin:
        ligne = magasin.cx.execute(
            "SELECT illustration FROM fiche WHERE idoc=?", (idoc,)).fetchone()
    if not ligne or not ligne["illustration"]:
        raise HTTPException(status_code=404, detail="Aucune illustration.")
    trouve = _blob(idoc)
    if not trouve:
        raise HTTPException(status_code=404, detail="Fichier absent du magasin.")
    chemin, sha = trouve
    est_svg = ligne["illustration"].lower().endswith(".svg")
    return FileResponse(
        chemin,
        media_type="image/svg+xml" if est_svg else "image/png",
        headers={"Cache-Control": CACHE_ILLUSTRATION, "ETag": f'"{sha[:16]}"'},
    )


@app.get(f"{PREFIXE}/fiche/{{idoc}}")
def fiche(idoc: int) -> dict:
    """Une fiche entière : texte, voisinage, et zones si c'est un schéma.

    Une seule route plutôt que trois : la panneau de lecture ouvert depuis une
    source a besoin des trois d'un coup, et un aller-retour de plus se voit.
    """
    with MagasinSqlite(BASE_DONNEES) as magasin:
        q = magasin.cx.execute
        f = q("SELECT * FROM fiche WHERE idoc=?", (idoc,)).fetchone()
        if not f:
            raise HTTPException(status_code=404, detail="Fiche inconnue.")
        blocs = q("SELECT type, titre_section, terme, texte FROM bloc"
                  " WHERE fiche_idoc=? ORDER BY ordre", (idoc,)).fetchall()
        carte = q("SELECT largeur, hauteur FROM carte WHERE idoc=?", (idoc,)).fetchone()
        zones = q("SELECT libelle, cible_idoc, x, y FROM zone WHERE carte_idoc=?"
                  " ORDER BY ordre", (idoc,)).fetchall() if carte else []
        voisins = q("SELECT r.cible_idoc, f.titre FROM renvoi r"
                    " JOIN fiche f ON f.idoc=r.cible_idoc WHERE r.source_idoc=?"
                    " LIMIT 12", (idoc,)).fetchall()

    largeur = carte["largeur"] if carte else 0
    hauteur = carte["hauteur"] if carte else 0
    return {
        "idoc": f["idoc"], "code": f["code"], "type": f["type"], "titre": f["titre"],
        "modifie_le": f["modifie_le"],
        "auteurs": json.loads(f["auteurs"] or "[]"),
        "source_url": f["source_url"] or URL_ASCOCID.format(idoc=idoc),
        "illustration": f"{PREFIXE}/illustration/{idoc}" if f["illustration"] else "",
        "blocs": [dict(b) for b in blocs],
        "carte": ({"largeur": largeur, "hauteur": hauteur,
                   # En pourcentage, comme pour les illustrations : la page
                   # affiche le schéma à la taille qu'elle veut.
                   "zones": [{"libelle": z["libelle"], "cible_idoc": z["cible_idoc"],
                              "x_pct": round(z["x"] / max(largeur, 1) * 100, 3),
                              "y_pct": round(z["y"] / max(hauteur, 1) * 100, 3)}
                             for z in zones]} if carte else None),
        "voir_aussi": [{"idoc": v["cible_idoc"], "titre": v["titre"]} for v in voisins],
    }


@app.get(f"{PREFIXE}/suggestions")
def suggestions() -> dict:
    """Questions d'amorce — éditables par l'équipe IFPC sans toucher au code."""
    if SUGGESTIONS.exists():
        contenu = json.loads(SUGGESTIONS.read_text(encoding="utf-8"))
        if questions := contenu.get("questions"):
            return {"questions": questions[:6]}
    return {"questions": SUGGESTIONS_DEFAUT}


@app.post(f"{PREFIXE}/feedback")
def feedback(retour: Retour, _: dict | None = Depends(utilisateur)) -> dict:
    """Retour utilisateur, en append-only : il alimente le jeu d'or (spec 08)."""
    RETOURS.parent.mkdir(parents=True, exist_ok=True)
    with RETOURS.open("a", encoding="utf-8") as f:
        f.write(json.dumps({"horodatage": time.strftime("%Y-%m-%dT%H:%M:%S"),
                            **retour.model_dump()}, ensure_ascii=False) + "\n")
    return {"enregistre": True}


@app.get(f"{PREFIXE}/sante")
def sante() -> dict:
    """État du service — la page s'en sert pour ne pas proposer un chat mort."""
    with MagasinSqlite(BASE_DONNEES) as magasin:
        comptes = magasin.compter()
    return {
        "corpus": comptes,
        "chunks": moteur.index.taille if moteur.index else 0,
        "recherche": "hybride" if moteur.index else "lexicale",
        "generation": moteur.generation_disponible,
        "modeles": {"analyse": moteur.modele_analyse,
                    "redaction": moteur.modele_redaction},
        "chargement_s": round(moteur.charge_le, 2),
    }
