"""Analyse de la demande par un modèle rapide, en une requête.

Sortie structurée imposée par schéma : intention, requête de recherche réécrite,
outil concerné, entités. La réécriture est ce qui rend le multi-tours possible
sans appel supplémentaire — « et pour les pommes douces ? » devient une requête
autonome.

Modèle volontairement plus petit que celui de rédaction : la tâche est un
étiquetage, pas une synthèse. Elle doit coûter quelques centaines de
millisecondes, pas quelques secondes.
"""

from __future__ import annotations

import json
import os

from ascocid.domain.ports.analyse import Analyse
from ascocid.infrastructure.llm import analyse_commune as commun

# Un modèle « lite » suffit largement pour un étiquetage, et il est trois fois
# plus rapide : 1,0 s de médiane contre 3,2 s pour le flash complet, à qualité
# de classification égale sur les cas de test.
MODELE_DEFAUT = "gemini-3.1-flash-lite"


class AnalyseurGemini:
    def __init__(self, registre, modele: str | None = None,  # noqa: ANN001
                 cle: str | None = None) -> None:
        from google import genai

        self._nom = modele or os.environ.get("GEMINI_MODELE_ANALYSE") or MODELE_DEFAUT
        self._client = genai.Client(api_key=cle or os.environ["GEMINI_API_KEY"])
        self._consigne = commun.CONSIGNE.format(outils=registre.resume_pour_prompt())

    @property
    def identifiant_modele(self) -> str:
        return self._nom

    def analyser(self, question: str, historique: list[str] | None = None) -> Analyse:
        from google.genai import types

        r = self._client.models.generate_content(
            model=self._nom,
            contents=commun.contenu_analyse(question, historique),
            config=types.GenerateContentConfig(
                system_instruction=self._consigne,
                response_mime_type="application/json",
                response_schema=commun.SCHEMA,
                temperature=0.0,      # un étiquetage n'a pas à varier
                max_output_tokens=300,
                # Sans cela le modèle délibère plusieurs secondes sur une tâche
                # d'étiquetage : mesuré jusqu'à 16 s, pour un gain nul.
                thinking_config=types.ThinkingConfig(thinking_budget=0),
            ),
        )
        # Une réponse vide ou tronquée arrive (modèle saturé, sortie coupée) :
        # on lève, l'orchestrateur retombe alors sur le chemin corpus.
        texte = (r.text or "").strip()
        if not texte:
            raise ValueError("réponse d'analyse vide")
        return commun.analyse_depuis_json(json.loads(texte), question)
