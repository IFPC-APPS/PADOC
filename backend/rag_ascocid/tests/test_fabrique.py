"""Choix du fournisseur, et analyse de la demande.

Le choix du fournisseur se fait au démarrage et ne se revoit jamais : une
erreur ici ne se manifeste qu'en production, sous la forme d'un corpus parti
chez le mauvais prestataire. D'où des tests sur la règle de priorité elle-même,
et pas seulement sur le fait qu'un adaptateur se construit.
"""

from __future__ import annotations

import json

import httpx
import pytest

from ascocid.domain.ports.analyse import Intention
from ascocid.infrastructure.llm import analyse_commune as commun
from ascocid.infrastructure.llm import argo, argo_analyse, fabrique


@pytest.fixture(autouse=True)
def environnement_neutre(monkeypatch):
    for cle in ("LDC_FOURNISSEUR", "ARGO_API_KEY", "GEMINI_API_KEY",
                "ARGO_MODELE", "ARGO_MODELE_ANALYSE"):
        monkeypatch.delenv(cle, raising=False)


# ── Sélection du fournisseur ────────────────────────────────────────────────

def test_sans_aucune_cle_aucun_fournisseur():
    """Le service doit démarrer quand même, en sources seules."""
    assert fabrique.fournisseur_actif() is None


def test_argo_est_le_defaut_quand_sa_cle_est_la(monkeypatch):
    """Le corpus reste dans l'établissement sauf décision contraire explicite."""
    monkeypatch.setenv("ARGO_API_KEY", "x")
    monkeypatch.setenv("GEMINI_API_KEY", "y")
    assert fabrique.fournisseur_actif() == "argo"


def test_gemini_sert_de_repli(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "y")
    assert fabrique.fournisseur_actif() == "gemini"


def test_le_reglage_explicite_prime(monkeypatch):
    monkeypatch.setenv("ARGO_API_KEY", "x")
    monkeypatch.setenv("LDC_FOURNISSEUR", "gemini")
    assert fabrique.fournisseur_actif() == "gemini"


def test_une_cle_vide_ne_compte_pas(monkeypatch):
    """Une variable déclarée mais vide est le cas courant d'un .env incomplet."""
    monkeypatch.setenv("ARGO_API_KEY", "   ")
    assert fabrique.fournisseur_actif() is None


def test_fournisseur_inconnu_refuse_sans_ambiguite(monkeypatch):
    monkeypatch.setenv("LDC_FOURNISSEUR", "mistral")
    with pytest.raises(ValueError, match="mistral"):
        fabrique.fournisseur_actif()


def test_sans_fournisseur_la_construction_echoue():
    with pytest.raises(RuntimeError):
        fabrique.generateur()


def test_la_fabrique_rend_bien_un_adaptateur_argo(monkeypatch):
    monkeypatch.setenv("ARGO_API_KEY", "x")
    assert isinstance(fabrique.generateur(), argo.GenerateurArgo)


def test_le_modele_est_surchargeable(monkeypatch):
    monkeypatch.setenv("ARGO_API_KEY", "x")
    monkeypatch.setenv("ARGO_MODELE", "chat-gpt-oss-20b")
    assert fabrique.generateur().identifiant_modele == "chat-gpt-oss-20b"


# ── Lecture de l'analyse ────────────────────────────────────────────────────

def test_aucun_outil_devient_absence():
    """Le modèle écrit « aucun » là où le schéma attend rien."""
    for mot in ("aucun", "none", "null", "n/a", ""):
        analyse = commun.analyse_depuis_json(
            {"intention": "corpus", "requete_recherche": "q",
             "outil_suggere": mot, "confiance": 0.9}, "q")
        assert analyse.outil_suggere is None


def test_les_entites_vides_sont_ecartees():
    analyse = commun.analyse_depuis_json(
        {"intention": "corpus", "requete_recherche": "q", "confiance": 0.5,
         "entites": {"processus": "pasteurisation", "variete": "", "produit": None}},
        "q")
    assert analyse.entites == {"processus": "pasteurisation"}


def test_la_question_sert_de_repli_a_la_reecriture():
    analyse = commun.analyse_depuis_json({"intention": "corpus"}, "question brute")
    assert analyse.requete_recherche == "question brute"
    assert analyse.intention is Intention.CORPUS


def test_le_multi_tours_integre_les_deux_derniers_tours():
    contenu = commun.contenu_analyse(
        "et pour les douces ?", ["tour 1", "tour 2", "tour 3", "tour 4"])
    assert "tour 3" in contenu and "tour 4" in contenu
    assert "tour 1" not in contenu
    assert "et pour les douces ?" in contenu


def test_sans_historique_la_question_passe_telle_quelle():
    assert commun.contenu_analyse("q", None) == "q"
    assert commun.contenu_analyse("q", []) == "q"


# ── Analyseur ARGO ──────────────────────────────────────────────────────────

class RegistreFactice:
    def resume_pour_prompt(self) -> str:
        return "- pasteurisation : barème thermique"


def brancher_analyse(monkeypatch, contenu: str | None) -> list[dict]:
    vues: list[dict] = []

    def transport(requete: httpx.Request) -> httpx.Response:
        vues.append(json.loads(requete.content))
        return httpx.Response(200, json={
            "choices": [{"message": {"role": "assistant", "content": contenu,
                                     "reasoning_content": "je délibère"}}]})

    monkeypatch.setattr(argo, "client_http", lambda timeout: httpx.Client(  # noqa: ARG005
        base_url="https://exemple.invalid/api",
        transport=httpx.MockTransport(transport)))
    monkeypatch.setenv("ARGO_API_KEY", "cle-de-test")
    return vues


def test_l_analyse_impose_son_schema(monkeypatch):
    """vLLM contraint réellement le décodage : on ne s'en remet pas au prompt."""
    vues = brancher_analyse(monkeypatch, json.dumps(
        {"intention": "outil", "requete_recherche": "barème 65 °C",
         "outil_suggere": "pasteurisation", "confiance": 0.88}))

    analyse = argo_analyse.AnalyseurArgo(RegistreFactice()).analyser(
        "quel barème pour mon lot à 65 °C ?")

    assert analyse.intention is Intention.OUTIL
    assert analyse.outil_suggere == "pasteurisation"
    envoi = vues[0]
    assert envoi["response_format"]["type"] == "json_schema"
    assert envoi["response_format"]["json_schema"]["schema"] == commun.SCHEMA
    assert envoi["temperature"] == 0.0


def test_un_budget_epuise_par_le_raisonnement_leve(monkeypatch):
    """`content` vide sans erreur : l'orchestrateur doit retomber sur le corpus."""
    brancher_analyse(monkeypatch, None)

    with pytest.raises(argo.ErreurArgo):
        argo_analyse.AnalyseurArgo(RegistreFactice()).analyser("q")


def test_la_consigne_annonce_les_outils_de_la_plateforme(monkeypatch):
    vues = brancher_analyse(monkeypatch, json.dumps(
        {"intention": "corpus", "requete_recherche": "q", "confiance": 0.5}))

    argo_analyse.AnalyseurArgo(RegistreFactice()).analyser("q")

    systeme = vues[0]["messages"][0]
    assert systeme["role"] == "system"
    assert "barème thermique" in systeme["content"]
