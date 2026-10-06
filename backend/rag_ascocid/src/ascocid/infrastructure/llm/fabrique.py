"""Choix du fournisseur de modèle, en un seul endroit.

Trois interfaces construisaient jusqu'ici leur générateur à la main, chacune en
testant `GEMINI_API_KEY`. Ajouter un fournisseur imposait de modifier les trois
et d'espérer n'en oublier aucune — l'API HTTP, la commande `ask` et la campagne
d'évaluation auraient pu diverger sans que rien ne le signale.

Sélection, par ordre de priorité :

1. `LDC_FOURNISSEUR` s'il est renseigné (`argo` ou `gemini`) — un réglage
   explicite doit toujours l'emporter sur une déduction ;
2. sinon ARGO si `ARGO_API_KEY` est présente. C'est le défaut voulu : le RAG
   transmet au modèle les passages du corpus, pas seulement la question, et
   ARGO les garde dans l'établissement ;
3. sinon Gemini si `GEMINI_API_KEY` est présente ;
4. sinon rien — le service démarre quand même, en annonçant que la rédaction
   est indisponible et en ne renvoyant que les sources.

Les imports sont tardifs : `google-genai` n'a pas à être installé pour que
l'adaptateur ARGO fonctionne, et réciproquement.
"""

from __future__ import annotations

import os

FOURNISSEURS = ("argo", "gemini")


def _renseignee(cle: str) -> bool:
    return bool(os.environ.get(cle, "").strip())


def fournisseur_actif() -> str | None:
    """Nom du fournisseur retenu, ou None si aucun n'est utilisable."""
    choix = os.environ.get("LDC_FOURNISSEUR", "").strip().lower()
    if choix:
        if choix not in FOURNISSEURS:
            raise ValueError(
                f"LDC_FOURNISSEUR={choix!r} inconnu — attendu : "
                + " ou ".join(FOURNISSEURS)
            )
        return choix
    if _renseignee("ARGO_API_KEY"):
        return "argo"
    if _renseignee("GEMINI_API_KEY"):
        return "gemini"
    return None


def generateur(nom: str | None = None):  # noqa: ANN201
    """Adaptateur de rédaction du fournisseur retenu."""
    nom = nom or fournisseur_actif()
    if nom == "argo":
        from ascocid.infrastructure.llm.argo import GenerateurArgo

        return GenerateurArgo()
    if nom == "gemini":
        from ascocid.infrastructure.llm.gemini import GenerateurGemini

        return GenerateurGemini()
    raise RuntimeError("aucun fournisseur de modèle configuré")


def analyseur(registre, nom: str | None = None):  # noqa: ANN001, ANN201
    """Adaptateur d'analyse du fournisseur retenu."""
    nom = nom or fournisseur_actif()
    if nom == "argo":
        from ascocid.infrastructure.llm.argo_analyse import AnalyseurArgo

        return AnalyseurArgo(registre)
    if nom == "gemini":
        from ascocid.infrastructure.llm.gemini_analyse import AnalyseurGemini

        return AnalyseurGemini(registre)
    raise RuntimeError("aucun fournisseur de modèle configuré")
