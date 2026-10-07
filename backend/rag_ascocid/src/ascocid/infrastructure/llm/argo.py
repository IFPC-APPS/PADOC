"""Adaptateur ARGO (INRAE), compatible OpenAI.

ARGO héberge des modèles ouverts sur l'infrastructure de l'INRAE. Pour AsCoCid,
l'intérêt n'est pas la qualité de rédaction mais la trajectoire des données :
un RAG envoie au modèle les *passages du corpus*, pas seulement la question.
Avec Gemini, le Livre de Connaissances transite donc chez Google à chaque
requête. Avec ARGO, il ne quitte pas l'établissement.

L'API suit la forme OpenAI, servie par vLLM derrière Open WebUI. Deux pièges
découverts à l'essai, qui dictent tout ce fichier :

1. **gpt-oss est un modèle à raisonnement.** Il émet son monologue interne dans
   un champ séparé — `reasoning_content` en flux comme en réponse unique. Un
   adaptateur qui concatène les deltas sans distinguer les deux diffuserait sa
   délibération dans l'interface de l'utilisateur. On ne retient que `content`.

2. **Le raisonnement s'impute sur `max_tokens`.** Mesuré : 43 jetons de
   raisonnement pour répondre « OK ». À budget serré, tout part dans la
   délibération et `content` revient à `null` — une réponse vide, sans erreur
   ni signal. D'où des budgets larges, et `tronquee` quand le modèle est coupé.

Le reste est volontairement calqué sur l'adaptateur Gemini : mêmes règles de
prompt, mêmes marqueurs [Sn] vérifiés après coup, même protocole d'événements.
Changer de fournisseur ne doit rien changer au noyau.
"""

from __future__ import annotations

import json
import os
import random
import re
import time
from collections.abc import Iterator

import httpx

from ascocid.application.requete import prompt as p
from ascocid.domain.ports.llm import Citation, Reponse, Usage
from ascocid.domain.ports.recherche import Passage

BASE_DEFAUT = "https://chatbot.argo.inrae.fr/api"
MODELE_DEFAUT = "chat-gpt-oss-120b"

# Le raisonnement du modèle se prélève sur ce budget avant la réponse. 4 096
# suffisait à Gemini, dont les jetons de réflexion étaient plus sobres ; ici la
# marge est volontairement plus large, une réponse coupée au milieu d'une
# consigne technique étant plus nuisible qu'une réponse un peu coûteuse.
JETONS_SORTIE = 6144

# ARGO est une ressource interne de l'établissement, sans facturation à l'usage.
# On conserve le décompte des jetons — utile pour dimensionner — mais le coût
# affiché reste nul, et ce n'est pas un oubli.
COUT_USD = 0.0


class ErreurArgo(RuntimeError):
    """Le service ARGO n'a pas répondu de manière exploitable."""


def base_url() -> str:
    return (os.environ.get("ARGO_BASE_URL") or BASE_DEFAUT).rstrip("/")


def cle_api(explicite: str | None = None) -> str:
    cle = explicite or os.environ.get("ARGO_API_KEY", "").strip()
    if not cle:
        raise ErreurArgo("ARGO_API_KEY absente")
    return cle


def client_http(timeout: float) -> httpx.Client:
    return httpx.Client(
        base_url=base_url(),
        headers={"Authorization": f"Bearer {cle_api()}",
                 "Content-Type": "application/json"},
        timeout=timeout,
    )


def avec_reprise(appel, *, tentatives: int = 4):  # noqa: ANN001, ANN202
    """Rejoue les pannes passagères du service, pas les erreurs de requête.

    Un 400 vient de nous et se reproduira à l'identique : le rejouer ne fait que
    retarder le diagnostic. Un 429 ou un 5xx vient de la charge du service.
    """
    for essai in range(tentatives):
        try:
            return appel()
        except httpx.HTTPStatusError as exc:
            code = exc.response.status_code
            if code not in (429, 500, 502, 503, 504) or essai == tentatives - 1:
                raise
            # exponentiel + gigue, pour ne pas resynchroniser les reprises
            time.sleep((2 ** essai) + random.random())
        except (httpx.TimeoutException, httpx.TransportError):
            if essai == tentatives - 1:
                raise
            time.sleep((2 ** essai) + random.random())
    raise ErreurArgo("reprises épuisées")


def _usage_depuis(brut: dict | None) -> Usage:
    brut = brut or {}
    return Usage(
        entree=brut.get("prompt_tokens") or 0,
        sortie=brut.get("completion_tokens") or 0,
        cout_usd=COUT_USD,
    )


class GenerateurArgo:
    """Rédaction de la réponse, en flux."""

    def __init__(self, modele: str | None = None, cle: str | None = None,
                 timeout: float = 180.0) -> None:
        self._nom = modele or os.environ.get("ARGO_MODELE") or MODELE_DEFAUT
        self._cle = cle_api(cle)
        self._timeout = timeout
        self.carte = ""

    @property
    def identifiant_modele(self) -> str:
        return self._nom

    def repondre(
        self, question: str, passages: list[Passage], contexte: dict, carte: str,
    ) -> Iterator[dict]:
        systeme = p.REGLES + ("\n\n" + carte if carte else "")
        morceaux = [p.passages_en_texte(passages)]
        if (graphe := p.contexte_en_texte(contexte, passages)):
            morceaux.append(graphe)
        morceaux.append(f"Question : {question}")

        corps = {
            "model": self._nom,
            "messages": [
                {"role": "system", "content": systeme},
                {"role": "user", "content": "\n\n".join(morceaux)},
            ],
            "max_tokens": JETONS_SORTIE,
            "temperature": 0.2,   # une réponse sourcée n'a pas à être créative
            "stream": True,
            # Sans cela, le dernier événement ne porte aucun décompte et l'on
            # perd toute mesure de consommation.
            "stream_options": {"include_usage": True},
        }

        texte = ""
        usage = Usage(cout_usd=COUT_USD)
        tronquee = False

        with client_http(self._timeout) as client:
            reponse = avec_reprise(lambda: self._ouvrir(client, corps))
            # `send(stream=True)` rend une réponse qu'il faut refermer soi-même :
            # ce n'est pas un gestionnaire de contexte, contrairement à
            # `client.stream()`. Sans ce `finally`, une interruption en cours de
            # lecture laisserait la connexion ouverte.
            try:
                for ligne in reponse.iter_lines():
                    evenement = _evenement(ligne)
                    if evenement is None:
                        continue
                    for choix in evenement.get("choices") or []:
                        delta = choix.get("delta") or {}
                        # `reasoning_content` est le monologue du modèle : il
                        # est ignoré, pas diffusé. Seul `content` est la réponse.
                        if (fragment := delta.get("content")):
                            texte += fragment
                            yield {"type": "delta", "texte": fragment}
                        motif = choix.get("finish_reason")
                        if motif and motif != "stop":
                            tronquee = True
                    if (u := evenement.get("usage")):
                        usage = _usage_depuis(u)
            finally:
                reponse.close()

        yield {
            "type": "fin",
            "reponse": Reponse(
                texte=texte.strip(),
                citations=[
                    Citation(source_index=int(n))
                    for n in dict.fromkeys(re.findall(r"\[S(\d+)\]", texte))
                ],
                usage=usage,
                modele=self._nom,
                tronquee=tronquee,
            ),
        }

    def _ouvrir(self, client: httpx.Client, corps: dict):  # noqa: ANN202
        """Ouvre le flux et laisse remonter un statut d'erreur.

        `send(stream=True)` ne lit pas le corps : il faut le consommer avant de
        pouvoir lire le message d'erreur, sans quoi l'exception ne dit rien.
        """
        requete = client.build_request("POST", "/chat/completions", json=corps)
        reponse = client.send(requete, stream=True)
        if reponse.status_code >= 400:
            reponse.read()
            reponse.close()
            reponse.raise_for_status()
        return reponse


def _evenement(ligne: str) -> dict | None:
    """Décode une ligne SSE. Rend None pour tout ce qui n'est pas un événement."""
    if not ligne or not ligne.startswith("data:"):
        return None
    charge = ligne[len("data:"):].strip()
    if not charge or charge == "[DONE]":
        return None
    try:
        return json.loads(charge)
    except json.JSONDecodeError:
        # Un fragment illisible ne doit pas interrompre une réponse en cours.
        return None
