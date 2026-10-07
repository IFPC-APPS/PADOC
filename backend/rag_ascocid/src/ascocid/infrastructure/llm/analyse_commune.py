"""Ce que l'analyse de la demande a de commun à tous les fournisseurs.

La consigne, le schéma de sortie et la lecture du JSON décrivent *la tâche*, pas
le modèle qui l'exécute. Les laisser dans l'adaptateur Gemini aurait condamné
tout nouvel adaptateur à les recopier — et à diverger au premier ajustement de
la consigne, silencieusement, puisque rien ne compare les deux copies.
"""

from __future__ import annotations

from ascocid.domain.ports.analyse import Analyse, Intention

CONSIGNE = """\
Tu analyses la demande d'un producteur ou technicien cidricole adressée à un \
assistant adossé au Livre de Connaissances AsCoCid (IFPC / INRAE).

Tu ne réponds pas à la question. Tu la classes et tu la reformules.

INTENTIONS possibles :
- corpus : question de fond sur le cidre, ses procédés, ses phénomènes. Cas par défaut.
- lookup : l'utilisateur demande une fiche précise par son nom.
- navigation : il demande la structure d'un processus, l'enchaînement des étapes, \
ce qui vient avant ou après.
- outil : il veut un CALCUL SUR SES PROPRES DONNÉES (son lot, sa cuve, sa mesure), \
que seul un outil de la plateforme peut faire.
- conversation : salutation, remerciement, question sur l'assistant lui-même.
- clarification : demande trop vague ou ambiguë pour lancer une recherche.

DISTINCTION DÉCISIVE entre corpus et outil :
- « Pourquoi pasteuriser un cidre ? » → corpus (c'est une explication)
- « Quel barème pour mon lot à 65 °C ? » → outil (c'est un calcul sur son cas)
Le sujet ne tranche pas ; c'est la présence d'un cas particulier à calculer qui \
tranche. En cas d'hésitation, choisis corpus : mieux vaut répondre et proposer \
l'outil que de retenir une information que le Livre contenait.

REQUETE_RECHERCHE : reformule la demande en une requête autonome et explicite, \
en vocabulaire du référentiel. Si la demande est une relance qui s'appuie sur \
l'échange précédent, intègre le contexte manquant. Développe les abréviations \
d'atelier (brett → Brettanomyces, ferm → fermentation, MV → masse volumique, \
TAV → titre alcoométrique volumique). Garde-la courte.

OUTILS DISPONIBLES SUR LA PLATEFORME :
{outils}

CONFIANCE : entre 0 et 1, ta certitude sur l'intention.\
"""

SCHEMA = {
    "type": "object",
    "properties": {
        "intention": {"type": "string", "enum": [i.value for i in Intention]},
        "requete_recherche": {"type": "string"},
        "outil_suggere": {"type": "string"},
        "entites": {
            "type": "object",
            "properties": {
                "processus": {"type": "string"},
                "variete": {"type": "string"},
                "produit": {"type": "string"},
            },
        },
        "confiance": {"type": "number"},
    },
    "required": ["intention", "requete_recherche", "confiance"],
}


def contenu_analyse(question: str, historique: list[str] | None) -> str:
    """Message utilisateur, enrichi du strict nécessaire pour le multi-tours.

    Deux tours suffisent : au-delà, le contexte dérive plus qu'il n'aide.
    """
    if not historique:
        return question
    echange = "\n".join(historique[-2:])
    return f"Échange précédent :\n{echange}\n\nNouvelle demande : {question}"


def analyse_depuis_json(brut: dict, question: str) -> Analyse:
    outil = (brut.get("outil_suggere") or "").strip().lower()
    # Le modèle écrit volontiers « aucun » là où le schéma attend l'absence.
    outil = None if outil in ("", "aucun", "none", "null", "n/a") else outil
    return Analyse(
        intention=Intention(brut.get("intention", "corpus")),
        requete_recherche=brut.get("requete_recherche", question),
        outil_suggere=outil,
        entites={k: v for k, v in (brut.get("entites") or {}).items() if v},
        confiance=float(brut.get("confiance", 0.5)),
        origine="modele",
    )
