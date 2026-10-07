"use client";

import { useEffect, useState } from "react";
import { Loader2, ToggleLeft, ToggleRight } from "lucide-react";
import { basculerFonctionnalite, getFonctionnalites } from "@/lib/api";

interface Fonctionnalite {
  cle: string;
  libelle: string;
  description: string | null;
  activee: boolean;
  modifieLe: string | null;
  modifiePar: string | null;
}

/**
 * Ouvrir ou fermer un pan de l'application, sans redéploiement.
 *
 * Fermer une fonctionnalité la retire de la navigation de tous les comptes non
 * administrateurs, et son adresse directe cesse de répondre. Les
 * administrateurs continuent de tout voir : il faut bien pouvoir préparer et
 * vérifier ce que le public ne voit pas encore.
 */
export default function PanneauFonctionnalites() {
  const [liste, setListe] = useState<Fonctionnalite[]>([]);
  const [chargement, setChargement] = useState(true);
  const [enCours, setEnCours] = useState<string | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);

  useEffect(() => {
    getFonctionnalites()
      .then((d: Fonctionnalite[]) => setListe(d))
      .catch(() => setErreur("Chargement impossible."))
      .finally(() => setChargement(false));
  }, []);

  const basculer = async (f: Fonctionnalite) => {
    setEnCours(f.cle);
    setErreur(null);
    // État remplacé par la réponse du serveur, et non deviné localement : on
    // affiche ce qui est réellement enregistré, pas ce qu'on espérait.
    try {
      const maj: Fonctionnalite = await basculerFonctionnalite(f.cle, !f.activee);
      setListe((prev) => prev.map((x) => (x.cle === maj.cle ? maj : x)));
    } catch {
      setErreur(`Changement refusé pour « ${f.libelle} ».`);
    } finally {
      setEnCours(null);
    }
  };

  if (chargement) {
    return (
      <div className="py-20 text-center">
        <Loader2 className="w-6 h-6 text-gray-300 mx-auto animate-spin" />
      </div>
    );
  }

  return (
    <div className="animate-in fade-in duration-300">
      <p className="text-sm text-gray-500 mb-6 max-w-2xl">
        Une fonctionnalité fermée disparaît de la navigation des producteurs et
        son adresse cesse de répondre. Les administrateurs y gardent accès.
      </p>

      {erreur && (
        <div className="mb-4 rounded-lg border border-red-100 bg-red-50 px-4 py-3 text-sm text-red-700">
          {erreur}
        </div>
      )}

      <ul className="flex flex-col gap-3">
        {liste.map((f) => (
          <li
            key={f.cle}
            className="flex items-start justify-between gap-6 rounded-xl border border-gray-200 bg-white px-5 py-4"
          >
            <div className="min-w-0">
              <p className="font-semibold text-gray-900">{f.libelle}</p>
              {f.description && (
                <p className="text-sm text-gray-500 mt-0.5">{f.description}</p>
              )}
              {f.modifieLe && (
                <p className="text-xs text-gray-400 mt-1.5">
                  Dernier changement le{" "}
                  {new Date(f.modifieLe).toLocaleDateString(undefined, {
                    day: "2-digit",
                    month: "short",
                    year: "numeric",
                  })}
                  {f.modifiePar ? ` par ${f.modifiePar}` : ""}
                </p>
              )}
            </div>

            <button
              onClick={() => basculer(f)}
              disabled={enCours === f.cle}
              aria-pressed={f.activee}
              className="flex items-center gap-2 shrink-0 text-sm font-semibold disabled:opacity-50"
            >
              {enCours === f.cle ? (
                <Loader2 className="w-5 h-5 animate-spin text-gray-400" />
              ) : f.activee ? (
                <ToggleRight className="w-7 h-7 text-emerald-600" />
              ) : (
                <ToggleLeft className="w-7 h-7 text-gray-300" />
              )}
              {/* Le mot, et pas seulement la couleur ni la position de
                  l'interrupteur : l'état doit se lire sans distinguer le vert
                  du gris. */}
              <span className={f.activee ? "text-emerald-700" : "text-gray-400"}>
                {f.activee ? "Ouverte" : "Fermée"}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
