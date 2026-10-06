"""ascocid ask — poser une question au Livre de Connaissances.

    ./ask "à quelle température fermenter ?"          réponse rédigée et sourcée
    ./ask "…" --sources-seules                        passages retrouvés, sans LLM

Le second mode ne demande aucune clé d'API : il sert à mesurer et régler la
recherche isolément, ce qui est aussi ce que fait le diagnostic de la spec 08 §2.2.
"""

from __future__ import annotations

import os
import time

import typer
from dotenv import load_dotenv
from rich.console import Console
from rich.panel import Panel

from ascocid.application.requete import prompt as gabarit
from ascocid.application.requete.analyse import Analyseur, RegistreOutils
from ascocid.application.requete.pipeline import Pipeline
from ascocid.application.requete.recherche import RechercheHybride
from ascocid.application.requete.verification import verifier
from ascocid.infrastructure.graphe.sqlite import MagasinSqlite

app = typer.Typer(add_completion=False, help=__doc__)
console = Console()


def _construire_recherche(magasin: MagasinSqlite, sans_vecteurs: bool) -> RechercheHybride:
    if sans_vecteurs:
        return RechercheHybride(magasin)
    try:
        from ascocid.infrastructure.embeddings.e5 import EmbedderE5
        from ascocid.infrastructure.index.memoire import IndexMemoire

        idx = IndexMemoire()
        idx.charger()
        return RechercheHybride(magasin, EmbedderE5(), idx)
    except (ImportError, FileNotFoundError) as exc:
        console.print(f"[yellow]Index vectoriel indisponible ({type(exc).__name__}) — "
                      f"repli sur la recherche lexicale seule.[/yellow]")
        return RechercheHybride(magasin)


@app.command()
def poser(
    question: str = typer.Argument(...),
    base_donnees: str = typer.Option("data/ascocid.sqlite"),
    k: int = typer.Option(8, help="Passages transmis au modèle."),
    precedent: str = typer.Option("", help="Tour précédent, pour tester une relance."),
    sources_seules: bool = typer.Option(False, help="N'appelle pas le modèle de rédaction."),
    sans_vecteurs: bool = typer.Option(False, help="Recherche lexicale seule."),
    env: str = typer.Option(".env"),
) -> None:
    """Répond à une question, en passant par l'analyse d'intention."""
    load_dotenv(env)
    t0 = time.perf_counter()

    with MagasinSqlite(base_donnees) as magasin:
        recherche = _construire_recherche(magasin, sans_vecteurs)
        registre = RegistreOutils()
        modele_analyse = None
        if os.environ.get("GEMINI_API_KEY", "").strip():
            from ascocid.infrastructure.llm.gemini_analyse import AnalyseurGemini
            modele_analyse = AnalyseurGemini(registre)
        analyseur = Analyseur(magasin, modele_analyse, registre)

        if sources_seules:
            a = analyseur.analyser(question, [precedent] if precedent else None)
            console.print(Panel(
                f"intention : [bold]{a.intention.value}[/bold]   "
                f"outil : {a.outil_suggere or '—'}   confiance : {a.confiance:.2f}   "
                f"({a.origine}, {a.duree_ms} ms)\n"
                f"requête   : [bold]{a.requete_recherche}[/bold]",
                title="Analyse de la demande", border_style="blue"))
            if not a.passe_par_le_corpus:
                return
            passages = recherche.chercher(a.requete_recherche, k=k)
            console.print(Panel("\n".join(
                f"[bold]{i}.[/bold] [{p.origine:7}] {p.titre_fiche}"
                + (f" — {p.titre_section}" if p.titre_section else "")
                for i, p in enumerate(passages, 1)),
                title=f"Passages ({len(passages)})", border_style="blue"))
            return

        from ascocid.infrastructure.llm import fabrique

        if not fabrique.fournisseur_actif():
            console.print(Panel(
                "Aucune clé. Renseigner [bold]ARGO_API_KEY[/bold] (INRAE) ou "
                "[bold]GEMINI_API_KEY[/bold] dans .env.\n"
                "[bold]--sources-seules[/bold] fonctionne sans clé.",
                title="Génération indisponible", border_style="yellow"))
            raise typer.Exit(code=2)

        generateur = fabrique.generateur()
        generateur.carte = gabarit.carte_du_corpus(magasin)
        chaine = Pipeline(analyseur, recherche, generateur, verifier, magasin, k=k)

        for ev in chaine.repondre(question, [precedent] if precedent else None):
            if ev["type"] == "analyse":
                a = ev["analyse"]
                console.print(f"[dim]intention {a['intention']} · "
                              f"« {a['requete_recherche']} » · {a['duree_ms']} ms[/dim]\n")
            elif ev["type"] == "outil":
                console.print(Panel(
                    f"[bold]{ev['outil']['nom']}[/bold] — {ev['outil']['objet']}\n"
                    f"[dim]{ev['outil']['url']}[/dim]",
                    title="Outil de la plateforme", border_style="green"))
            elif ev["type"] == "sources":
                console.print(Panel("\n".join(
                    f"[bold]{p['index']}.[/bold] {p['titre_fiche']}"
                    + (f" — {p['titre_section']}" if p["titre_section"] else "")
                    for p in ev["passages"]),
                    title=f"Passages ({len(ev['passages'])})", border_style="blue"))
                console.print()
            elif ev["type"] == "illustrations":
                console.print(Panel("\n".join(
                    f"[bold]{i['nature']}[/bold] {i['format']} "
                    f"{i['largeur']}×{i['hauteur']} {i['orientation']} · "
                    f"{i['octets']/1024:.0f} Ko"
                    + (f" · {len(i['zones'])} zones dont "
                       f"{sum(z['active'] for z in i['zones'])} active(s)"
                       if i["zones"] else "")
                    + (" · [bold]essentielle[/bold]" if i["essentielle"] else "")
                    + f"\n     {i['legende']} → {i['url']}"
                    for i in ev["illustrations"]),
                    title=f"Illustrations ({len(ev['illustrations'])})",
                    border_style="green"))
            elif ev["type"] == "delta":
                console.print(ev["texte"], end="")
            elif ev["type"] == "fin":
                console.print("\n")
                v = ev["verification"]
                if v["alertes"]:
                    for al in v["alertes"]:
                        couleur = "red" if al["gravite"] == "bloquante" else "yellow"
                        console.print(f"[{couleur}]  {al['code']} — {al['detail']}[/{couleur}]")
                lat, u = ev["latence"], ev["usage"]
                console.print(f"[dim]analyse {lat['analyse_ms']} ms · "
                              f"TTFT {lat['ttft_ms']} ms · total {lat['total_ms']} ms"
                              + (f" · {u['cout_usd']:.4f} $" if u else "") + "[/dim]")


if __name__ == "__main__":
    app()
