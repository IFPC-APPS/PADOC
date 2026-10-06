"""ascocid eval — exécute le jeu de questions et enregistre ce que le système répond.

    eval lancer   eval/questions.json → eval/resultats-<horodatage>.json
    eval rapport  resultats → document de relecture pour le client

La recherche et la génération sont enregistrées séparément : si la génération
échoue (clé absente, quota, refus), la recherche reste exploitable et le jeu
n'est pas perdu.
"""

from __future__ import annotations

import json
import pathlib
import re
import time
from datetime import datetime

import typer
from dotenv import load_dotenv
from rich.console import Console

from ascocid.application.requete import prompt as gabarit
from ascocid.application.requete.recherche import RechercheHybride
from ascocid.application.requete.verification import verifier
from ascocid.infrastructure.graphe.sqlite import MagasinSqlite

app = typer.Typer(add_completion=False, help=__doc__)
console = Console()
EVAL = pathlib.Path("eval")


@app.command()
def lancer(
    questions: str = typer.Option("eval/questions.json"),
    base_donnees: str = typer.Option("data/ascocid.sqlite"),
    k: int = typer.Option(8),
    sans_generation: bool = typer.Option(False, help="Recherche seule, aucun appel API."),
    env: str = typer.Option(".env"),
) -> None:
    load_dotenv(env)
    jeu = json.loads(pathlib.Path(questions).read_text(encoding="utf-8"))

    from ascocid.infrastructure.embeddings.e5 import EmbedderE5
    from ascocid.infrastructure.index.memoire import IndexMemoire

    console.print("chargement du modèle d'embedding…")
    idx = IndexMemoire(); idx.charger()
    magasin = MagasinSqlite(base_donnees)
    recherche = RechercheHybride(magasin, EmbedderE5(), idx)

    generateur = None
    if not sans_generation:
        from ascocid.infrastructure.llm import fabrique

        if (fournisseur := fabrique.fournisseur_actif()):
            generateur = fabrique.generateur(fournisseur)
            console.print(f"génération : [bold]{generateur.identifiant_modele}[/bold] "
                          f"({fournisseur})")
        else:
            console.print("[yellow]Pas de clé — recherche seule.[/yellow]")

    carte = gabarit.carte_du_corpus(magasin) if generateur else ""
    resultats = []

    for i, item in enumerate(jeu, 1):
        t0 = time.perf_counter()
        passages = recherche.chercher(item["q"], k=k)
        t_rech = (time.perf_counter() - t0) * 1000

        ligne = {
            **item,
            "recherche_ms": round(t_rech),
            "passages": [
                {"fiche_idoc": p.fiche_idoc, "titre_fiche": p.titre_fiche,
                 "titre_section": p.titre_section, "terme": p.terme,
                 "type": p.type, "origine": p.origine, "cle": p.cle,
                 "texte": p.texte}
                for p in passages
            ],
        }

        if generateur:
            try:
                contexte = recherche.contexte_graphe([p.fiche_idoc for p in passages])
                t1 = time.perf_counter()
                ttft, rep = None, None
                for ev in generateur.repondre(item["q"], passages, contexte, carte):
                    if ev["type"] == "delta" and ttft is None:
                        ttft = (time.perf_counter() - t1) * 1000
                    elif ev["type"] == "fin":
                        rep = ev["reponse"]
                rapport = verifier(rep.texte, passages)
                ligne |= {
                    "modele": rep.modele,
                    "reponse": rep.texte,
                    "ttft_ms": round(ttft or 0),
                    "total_ms": round((time.perf_counter() - t0) * 1000),
                    "sources_citees": [
                        {"index": c.source_index,
                         "fiche": passages[c.source_index - 1].titre_fiche
                         if 1 <= c.source_index <= len(passages) else "INEXISTANTE",
                         "section": passages[c.source_index - 1].titre_section
                         if 1 <= c.source_index <= len(passages) else ""}
                        for c in rep.citations
                    ],
                    "verification": {
                        "bloquee": rapport.bloquee,
                        "alertes": [{"gravite": a.gravite.value, "code": a.code,
                                     "detail": a.detail} for a in rapport.alertes],
                    },
                    "usage": rep.usage.model_dump(),
                }
            except Exception as exc:  # noqa: BLE001 — on enregistre l'échec, on continue
                ligne |= {"erreur": f"{type(exc).__name__}: {exc}"[:300]}
                console.print(f"[red]{item['id']} : {type(exc).__name__}[/red]")

        resultats.append(ligne)
        v = ligne.get("verification", {})
        etat = ("!" if ligne.get("erreur") else
                "✗" if v.get("bloquee") else
                "~" if v.get("alertes") else
                "✓" if ligne.get("reponse") else "·")
        console.print(f"  {etat} {item['id']} [{item['forme']:13}] "
                      f"{item['q'][:56]:58} {ligne['recherche_ms']:>4} ms")

    horodatage = datetime.now().strftime("%Y%m%d-%H%M")
    sortie = EVAL / f"resultats-{horodatage}.json"
    EVAL.mkdir(exist_ok=True)
    sortie.write_text(json.dumps(resultats, ensure_ascii=False, indent=1), encoding="utf-8")

    avec = sum(1 for r in resultats if r.get("reponse"))
    err = sum(1 for r in resultats if r.get("erreur"))
    cout = sum(r["usage"]["cout_usd"] for r in resultats if r.get("usage"))
    bloquees = sum(1 for r in resultats if r.get("verification", {}).get("bloquee"))
    console.print(f"\n{len(resultats)} questions · {avec} réponses · {err} erreur(s) "
                  f"· [bold]{bloquees} bloquée(s) par la vérification[/bold]")
    if avec:
        ttft = sorted(r["ttft_ms"] for r in resultats if r.get("ttft_ms"))
        tot = sorted(r["total_ms"] for r in resultats if r.get("total_ms"))
        console.print(f"TTFT médian {ttft[len(ttft)//2]} ms · "
                      f"réponse complète médiane {tot[len(tot)//2]} ms · "
                      f"coût total {cout:.3f} $ ({cout/avec:.4f} $/question)")
    console.print(f"écrit : [bold]{sortie}[/bold]")



@app.command()
def reverifier(fichier: str = typer.Argument(..., help="eval/resultats-*.json")) -> None:
    """Rejoue la vérification sur des résultats enregistrés, sans appel API.

    La vérification est une fonction pure des passages et de la réponse : la
    corriger ne doit jamais coûter une campagne de génération.
    """
    from ascocid.domain.ports.recherche import Passage

    chemin = pathlib.Path(fichier)
    resultats = json.loads(chemin.read_text(encoding="utf-8"))
    change = 0
    for ligne in resultats:
        if not ligne.get("reponse"):
            continue
        passages = [
            Passage(cle=p.get("cle", ""), fiche_idoc=p["fiche_idoc"],
                    titre_fiche=p["titre_fiche"], titre_section=p.get("titre_section", ""),
                    terme=p.get("terme", ""), type=p["type"],
                    texte=p.get("texte") or p.get("extrait", ""))
            for p in ligne["passages"]
        ]
        rapport = verifier(ligne["reponse"], passages)
        neuf = {"bloquee": rapport.bloquee,
                "alertes": [{"gravite": a.gravite.value, "code": a.code, "detail": a.detail}
                            for a in rapport.alertes]}
        change += neuf != ligne.get("verification")
        ligne["verification"] = neuf
    chemin.write_text(json.dumps(resultats, ensure_ascii=False, indent=1), encoding="utf-8")
    bloquees = sum(1 for r in resultats if r.get("verification", {}).get("bloquee"))
    propres = sum(1 for r in resultats
                  if r.get("reponse") and not r["verification"]["alertes"])
    console.print(f"{change} verdict(s) modifié(s) · {bloquees} bloquée(s) · "
                  f"{propres}/{sum(1 for r in resultats if r.get('reponse'))} sans alerte")


@app.command()
def rapport(
    fichier: str = typer.Argument(..., help="eval/resultats-*.json"),
    sortie: str = typer.Option("eval/relecture.html"),
) -> None:
    """Produit le document de relecture destiné au client."""
    import html as H

    resultats = json.loads(pathlib.Path(fichier).read_text(encoding="utf-8"))
    avec = [r for r in resultats if r.get("reponse")]
    modele = next((r.get("modele", "") for r in avec), "")
    ttft = sorted(r["ttft_ms"] for r in avec if r.get("ttft_ms"))
    cout = sum(r["usage"]["cout_usd"] for r in avec if r.get("usage"))
    bloquees = [r for r in resultats if r.get("verification", {}).get("bloquee")]

    LIB_FORME = {"fait": "Fait", "procedure": "Procédure", "arbitrage": "Arbitrage",
                 "lookup": "Recherche par nom", "sans_reponse": "Aucune réponse attendue",
                 "mal_formulee": "Formulation abrégée"}
    CLS_FORME = {"fait": "f-fait", "procedure": "f-proc", "arbitrage": "f-arb",
                 "lookup": "f-look", "sans_reponse": "f-non", "mal_formulee": "f-abr"}

    def marqueurs_en_liens(texte: str, qid: str, n_max: int) -> str:
        """Chaque [Sn] devient un lien vers l'entrée de source correspondante."""
        def remplacer(m: "re.Match[str]") -> str:
            bloc = m.group(0)
            def lien(d: "re.Match[str]") -> str:
                n = int(d.group(0))
                if not 1 <= n <= n_max:
                    return f'<span class="src-tag absente">S{n}</span>'
                return f'<a class="src-tag" href="#{qid}-S{n}">S{n}</a>'
            return "[" + re.sub(r"\d+", lien, bloc[1:-1]).replace("S<a", "<a") + "]"
        return re.sub(r"\[\s*[Ss][^\]]*\]", remplacer,
                      H.escape(texte)).replace("\n", "<br>")

    def sources_citees(r: dict) -> str:
        """Sources réellement citées : texte du passage + lien vers la fiche."""
        blocs = []
        passages = r["passages"]
        for s in r.get("sources_citees", []):
            i = s["index"]
            if not 1 <= i <= len(passages):
                blocs.append(
                    f'<div class="source absente" id="{r["id"]}-S{i}">'
                    f'<div class="s-tete"><code>S{i}</code>'
                    f'<span>source inexistante — le système a cité un passage '
                    f'qui ne lui a pas été fourni</span></div></div>')
                continue
            p = passages[i - 1]
            url = f"https://ascocid.fr/ldc/view.php?id_document={p['fiche_idoc']}"
            lib = p.get("titre_section") or (
                f"définition de « {p['terme']} »" if p.get("terme") else "")
            blocs.append(
                f'<div class="source" id="{r["id"]}-S{i}">'
                f'<div class="s-tete"><code>S{i}</code>'
                f'<a href="{url}" target="_blank" rel="noopener">'
                f'{H.escape(p["titre_fiche"])}</a>'
                + (f'<em>{H.escape(lib)}</em>' if lib else "")
                + f'<span class="idoc">fiche n°{p["fiche_idoc"]}</span></div>'
                # Passage entier, jamais tronqué : le relecteur doit pouvoir
                # vérifier l'affirmation sans rouvrir la fiche — et une coupure
                # tombe justement sur la phrase qui porte le chiffre contesté.
                f'<blockquote>{H.escape(p["texte"])}</blockquote></div>')
        return "".join(blocs) or '<p class="vide">aucune source citée</p>'

    fiches = []
    for r in resultats:
        v = r.get("verification", {})
        alertes = "".join(
            f'<li class="al {"bloq" if a["gravite"]=="bloquante" else "avert"}">'
            f'<b>{H.escape(a["code"])}</b> — {H.escape(a["detail"])}</li>'
            for a in v.get("alertes", [])
        )
        fiches.append(f'''
<article class="q" id="{r['id']}">
  <div class="q-tete">
    <span class="id">{r['id']}</span>
    <span class="forme {CLS_FORME.get(r['forme'],'')}">{LIB_FORME.get(r['forme'], r['forme'])}</span>
    <span class="theme">{H.escape(r['theme'])}</span>
  </div>
  <p class="question">{H.escape(r['q'])}</p>
  <div class="reponse">{marqueurs_en_liens(r.get('reponse') or '— aucune réponse —',
                                            r['id'], len(r['passages']))}</div>
  {f'<ul class="alertes">{alertes}</ul>' if alertes else ''}
  <div class="sources">
    <p class="s-titre">Ce sur quoi le système s\'est appuyé — le titre ouvre la fiche dans AsCoCid</p>
    {sources_citees(r)}
  </div>
  <div class="verdict">
    <span>Votre avis :</span>
    <label><input type="checkbox"> correcte</label>
    <label><input type="checkbox"> incomplète</label>
    <label><input type="checkbox"> fausse</label>
    <label><input type="checkbox"> hors sujet</label>
    <span class="commentaire">Commentaire / reformulation : ______________________________________</span>
  </div>
</article>''')

    from ascocid.interfaces.cli.gabarit_rapport import GABARIT

    valeurs = {
        "@@N@@": str(len(resultats)),
        "@@N_REP@@": str(len(avec)),
        "@@MODELE@@": H.escape(modele),
        "@@TTFT@@": f"{(ttft[len(ttft) // 2] / 1000) if ttft else 0:.1f}",
        "@@COUT@@": f"{cout:.3f}",
        "@@COUT_Q@@": f"{cout / max(len(avec), 1):.4f}",
        "@@BLOQUEES@@": str(len(bloquees)),
        "@@PROPRES@@": str(sum(1 for r in avec if not r["verification"]["alertes"])),
        "@@FICHES@@": "".join(fiches),
        "@@DATE@@": datetime.now().strftime("%d/%m/%Y"),
    }
    page = GABARIT
    for jeton, valeur in valeurs.items():
        page = page.replace(jeton, valeur)
    pathlib.Path(sortie).write_text(page, encoding="utf-8")
    console.print(f"écrit : [bold]{sortie}[/bold]")

if __name__ == "__main__":
    app()
