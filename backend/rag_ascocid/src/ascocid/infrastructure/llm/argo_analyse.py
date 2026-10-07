"""Analyse de la demande par ARGO, en une requête à sortie contrainte.

vLLM applique réellement le `json_schema` fourni — vérifié à l'essai : la
réponse respecte le schéma, y compris l'énumération des intentions. On conserve
donc la garantie structurelle qu'offrait Gemini, sans retomber sur un « réponds
en JSON s'il te plaît » que rien ne fait respecter.

Le modèle raisonne avant de répondre, et ce raisonnement se prélève sur
`max_tokens`. Pour un étiquetage dont la sortie fait moins de cent jetons, le
budget est donc dicté par la délibération, pas par la réponse : d'où une valeur
sans rapport apparent avec la taille du résultat attendu.
"""

from __future__ import annotations

import json
import os

import httpx

from ascocid.domain.ports.analyse import Analyse
from ascocid.infrastructure.llm import analyse_commune as commun

# Importé comme module, et non par ses noms : un test qui remplace
# `argo.client_http` doit atteindre cet adaptateur aussi. Lier les noms à
# l'import les figerait ici, et la couture ne tiendrait que pour le générateur.
from ascocid.infrastructure.llm import argo

# Le petit modèle suffit pour un étiquetage et répond nettement plus vite que le
# large : la tâche est une classification, pas une synthèse.
MODELE_DEFAUT = "chat-gpt-oss-20b"

# La sortie utile tient en ~80 jetons ; le reste est la marge de raisonnement.
JETONS_SORTIE = 1024


class AnalyseurArgo:
    def __init__(self, registre, modele: str | None = None,  # noqa: ANN001
                 cle: str | None = None, timeout: float = 60.0) -> None:
        self._nom = modele or os.environ.get("ARGO_MODELE_ANALYSE") or MODELE_DEFAUT
        self._cle = argo.cle_api(cle)
        self._timeout = timeout
        self._consigne = commun.CONSIGNE.format(outils=registre.resume_pour_prompt())

    @property
    def identifiant_modele(self) -> str:
        return self._nom

    def analyser(self, question: str, historique: list[str] | None = None) -> Analyse:
        corps = {
            "model": self._nom,
            "messages": [
                {"role": "system", "content": self._consigne},
                {"role": "user", "content": commun.contenu_analyse(question, historique)},
            ],
            "max_tokens": JETONS_SORTIE,
            "temperature": 0.0,   # un étiquetage n'a pas à varier
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "analyse", "schema": commun.SCHEMA},
            },
        }

        with argo.client_http(self._timeout) as client:
            reponse = argo.avec_reprise(lambda: _poster(client, corps))

        choix = (reponse.get("choices") or [{}])[0]
        texte = ((choix.get("message") or {}).get("content") or "").strip()
        # Budget épuisé par le raisonnement : `content` revient vide, sans
        # erreur. On lève — l'orchestrateur retombe alors sur le chemin corpus,
        # ce qui vaut mieux qu'une analyse inventée à partir de rien.
        if not texte:
            raise argo.ErreurArgo("réponse d'analyse vide")
        return commun.analyse_depuis_json(json.loads(texte), question)


def _poster(client: httpx.Client, corps: dict) -> dict:
    reponse = client.post("/chat/completions", json=corps)
    reponse.raise_for_status()
    return reponse.json()
