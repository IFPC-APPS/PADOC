"""Adaptateur ARGO : ce qui casse silencieusement si on ne le tient pas.

Aucun appel réseau — le transport httpx est simulé. L'enjeu de ces tests n'est
pas la couverture, c'est de figer les trois comportements qui, à l'essai contre
le vrai service, se sont révélés non évidents :

* le monologue du modèle (`reasoning_content`) ne doit jamais atteindre
  l'utilisateur ;
* une génération coupée doit se signaler, et non passer pour une réponse ;
* un flux abîmé ne doit pas faire perdre la réponse déjà reçue.
"""

from __future__ import annotations

import json

import httpx
import pytest

from ascocid.domain.ports.recherche import Passage
from ascocid.infrastructure.llm import argo


def sse(*evenements: dict) -> bytes:
    lignes = [f"data: {json.dumps(e)}\n\n" for e in evenements]
    lignes.append("data: [DONE]\n\n")
    return "".join(lignes).encode()


def delta_contenu(texte: str) -> dict:
    return {"choices": [{"index": 0, "delta": {"content": texte}}]}


def delta_raisonnement(texte: str) -> dict:
    return {"choices": [{"index": 0, "delta": {"reasoning_content": texte}}]}


def brancher(monkeypatch, corps: bytes, statut: int = 200) -> list[dict]:
    """Remplace le client ARGO par un transport simulé. Rend les requêtes vues."""
    vues: list[dict] = []

    def transport(requete: httpx.Request) -> httpx.Response:
        vues.append(json.loads(requete.content))
        return httpx.Response(statut, content=corps)

    def faux_client(timeout: float) -> httpx.Client:  # noqa: ARG001
        return httpx.Client(
            base_url="https://exemple.invalid/api",
            transport=httpx.MockTransport(transport),
        )

    monkeypatch.setattr(argo, "client_http", faux_client)
    monkeypatch.setenv("ARGO_API_KEY", "cle-de-test")
    return vues


PASSAGE = Passage(
    cle="p1", fiche_idoc=1, titre_fiche="Pasteurisation",
    type="section", texte="La pasteurisation détruit les levures.",
)


def collecter(generateur) -> tuple[list[str], dict]:  # noqa: ANN001
    deltas, fin = [], {}
    for evenement in generateur:
        if evenement["type"] == "delta":
            deltas.append(evenement["texte"])
        else:
            fin = evenement
    return deltas, fin


def test_le_raisonnement_du_modele_ne_sort_jamais(monkeypatch):
    """gpt-oss délibère dans un champ séparé : il ne doit pas être diffusé."""
    brancher(monkeypatch, sse(
        delta_raisonnement("L'utilisateur demande "),
        delta_raisonnement("pourquoi pasteuriser. Je vais citer [S1]."),
        delta_contenu("La pasteurisation "),
        delta_contenu("détruit les levures [S1]."),
        {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
        {"choices": [{"index": 0, "delta": {}}],
         "usage": {"prompt_tokens": 350, "completion_tokens": 42}},
    ))

    deltas, fin = collecter(
        argo.GenerateurArgo().repondre("pourquoi ?", [PASSAGE], {}, ""))

    assert "".join(deltas) == "La pasteurisation détruit les levures [S1]."
    assert "utilisateur demande" not in fin["reponse"].texte
    # Le [S1] du raisonnement ne doit pas non plus devenir une citation.
    assert [c.source_index for c in fin["reponse"].citations] == [1]


def test_une_reponse_coupee_est_signalee(monkeypatch):
    """Sur un référentiel technique, une consigne tronquée est un danger."""
    brancher(monkeypatch, sse(
        delta_contenu("Chauffer à 65 °C pendant"),
        {"choices": [{"index": 0, "delta": {}, "finish_reason": "length"}]},
    ))

    _, fin = collecter(argo.GenerateurArgo().repondre("barème ?", [PASSAGE], {}, ""))

    assert fin["reponse"].tronquee is True


def test_une_reponse_complete_n_est_pas_dite_tronquee(monkeypatch):
    brancher(monkeypatch, sse(
        delta_contenu("Réponse complète."),
        {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
    ))

    _, fin = collecter(argo.GenerateurArgo().repondre("q", [PASSAGE], {}, ""))

    assert fin["reponse"].tronquee is False


def test_le_decompte_des_jetons_est_repris(monkeypatch):
    brancher(monkeypatch, sse(
        delta_contenu("ok"),
        {"choices": [{"index": 0, "delta": {}}],
         "usage": {"prompt_tokens": 1200, "completion_tokens": 80}},
    ))

    _, fin = collecter(argo.GenerateurArgo().repondre("q", [PASSAGE], {}, ""))

    assert fin["reponse"].usage.entree == 1200
    assert fin["reponse"].usage.sortie == 80
    # ARGO est une ressource interne : pas de facturation à l'usage.
    assert fin["reponse"].usage.cout_usd == 0.0


def test_un_fragment_illisible_ne_perd_pas_la_reponse(monkeypatch):
    """Un flux abîmé au milieu ne doit pas annuler ce qui a déjà été reçu."""
    corps = (b"data: " + json.dumps(delta_contenu("avant ")).encode() + b"\n\n"
             b"data: {ceci n'est pas du json\n\n"
             b"data: " + json.dumps(delta_contenu("apres")).encode() + b"\n\n"
             b"data: [DONE]\n\n")
    brancher(monkeypatch, corps)

    deltas, fin = collecter(argo.GenerateurArgo().repondre("q", [PASSAGE], {}, ""))

    assert "".join(deltas) == "avant apres"
    assert fin["reponse"].texte == "avant apres"


def test_la_carte_du_corpus_part_en_consigne_systeme(monkeypatch):
    """Le préfixe stable doit rester en système : c'est lui qui est mis en cache."""
    vues = brancher(monkeypatch, sse(delta_contenu("ok")))

    collecter(argo.GenerateurArgo().repondre("q", [PASSAGE], {}, "CARTE DU CORPUS"))

    messages = vues[0]["messages"]
    assert messages[0]["role"] == "system"
    assert "CARTE DU CORPUS" in messages[0]["content"]
    assert messages[1]["role"] == "user"
    assert "La pasteurisation détruit les levures." in messages[1]["content"]


def test_le_flux_demande_le_decompte(monkeypatch):
    """Sans stream_options, le dernier événement ne porte aucun usage."""
    vues = brancher(monkeypatch, sse(delta_contenu("ok")))

    collecter(argo.GenerateurArgo().repondre("q", [PASSAGE], {}, ""))

    assert vues[0]["stream"] is True
    assert vues[0]["stream_options"] == {"include_usage": True}


def test_cle_absente_refusee_tot(monkeypatch):
    monkeypatch.delenv("ARGO_API_KEY", raising=False)
    monkeypatch.delenv("LDC_FOURNISSEUR", raising=False)
    with pytest.raises(argo.ErreurArgo):
        argo.GenerateurArgo()


@pytest.mark.parametrize("ligne", [
    "", "data: [DONE]", "data:", ": commentaire", "event: ping",
    "data: {tronqu", "autre chose",
])
def test_lignes_sans_evenement(ligne):
    assert argo._evenement(ligne) is None


def test_erreur_http_remonte_avec_son_corps(monkeypatch):
    """Un 400 vient de nous : il doit sortir tout de suite, lisible."""
    brancher(monkeypatch, b'{"error":"modele inconnu"}', statut=400)

    with pytest.raises(httpx.HTTPStatusError):
        collecter(argo.GenerateurArgo().repondre("q", [PASSAGE], {}, ""))
