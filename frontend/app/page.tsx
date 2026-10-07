"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/lib/store";
import { ChevronRight } from "lucide-react";
import { AnimatePresence, motion } from "framer-motion";
import {
  GlypheeColorimetrie,
  GlypheeCuve,
  GlypheePasteurisation,
} from "@/components/icones";
import { getHistory, type HistoryEntry } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { modulesVisibles } from "@/lib/modules";

// ── Types ────────────────────────────────────────────────────────────────────

interface RecentActivity {
  id: string;
  date: string;
  type: "controle" | "bareme" | "assemblage";
  label: string;
  lotIdentifier?: string;
  produit?: string;
  procede?: string;
  statut?: string;
  vp?: number;
  vpCible?: number;
  fromDb?: boolean;
  resultJson?: string;
  parametres?: string;
}

const PUCES: Record<string, string> = {
  controle: "bg-brand-primary",
  bareme: "bg-brand-accent",
  assemblage: "bg-brand-link",
};

const CIBLES: Record<string, string> = {
  controle: "/controle",
  bareme: "/bareme",
  assemblage: "/colorimetrie/assemblage",
};

interface SubModule {
  href: string;
  label: string;
}

interface Module {
  key: string;
  label: string;
  /** Seule trace de couleur : le nom du domaine. Ni pastille, ni dégradé. */
  couleur: string;
  /** Glyphe métier, dessiné pour PADOC (components/icones). */
  glyphe?: (p: { className?: string }) => JSX.Element;
  subModules: SubModule[];
  adminOnly?: boolean;
}

// ── Couronne de domaines ─────────────────────────────────────────────────────

/**
 * Les domaines disposés en couronne autour d'un noyau, à la manière de la
 * carte d'accueil d'AsCoCid : un centre identitaire, des branches colorées,
 * les accès en périphérie.
 *
 * La géométrie est calculée, pas dessinée à la main : n domaines se répartissent
 * sur 360°, la première branche au nord. Ajouter un domaine ne demande donc
 * aucun ajustement — l'écran se réorganise seul, y compris quand le bloc
 * Administration apparaît pour un compte ADMIN.
 *
 * En dessous de `lg`, la couronne cède la place à une liste : un cercle de
 * 560 px ne tient pas sur un téléphone, et le réduire le rendrait illisible.
 */

const RAYON_NOYAU = 82;      // rayon du cercle central, en px
const RAYON_COURONNE = 196;  // distance du centre à chaque pastille
const LARGEUR = 640;
const HAUTEUR = 520;

function positionPolaire(angleDeg: number, rayon: number) {
  const a = (angleDeg * Math.PI) / 180;
  return { x: LARGEUR / 2 + rayon * Math.cos(a), y: HAUTEUR / 2 + rayon * Math.sin(a) };
}

function Couronne({ modules }: { modules: Module[] }) {
  const [actif, setActif] = useState<string | null>(null);
  // Première branche au nord, les suivantes réparties également.
  const angles = modules.map((_, i) => -90 + (i * 360) / modules.length);

  return (
    <div className="relative mx-auto hidden lg:block"
         style={{ width: LARGEUR, height: HAUTEUR }}>
      {/* Branches : tracées sous les pastilles, du bord du noyau au bord de
          la pastille, dans la couleur du domaine. */}
      <svg className="absolute inset-0" width={LARGEUR} height={HAUTEUR} aria-hidden>
        <circle cx={LARGEUR / 2} cy={HAUTEUR / 2} r={RAYON_COURONNE - 58}
                fill="none" stroke="currentColor" strokeWidth="1"
                strokeDasharray="2 6" className="text-gray-300" />
        {modules.map((mod, i) => {
          const depart = positionPolaire(angles[i], RAYON_NOYAU + 6);
          const arrivee = positionPolaire(angles[i], RAYON_COURONNE - 46);
          return (
            <g key={mod.key} className={mod.couleur}>
              <line x1={depart.x} y1={depart.y} x2={arrivee.x} y2={arrivee.y}
                    stroke="currentColor" strokeWidth={actif === mod.key ? 2 : 1.25}
                    strokeLinecap="round"
                    opacity={actif && actif !== mod.key ? 0.25 : 0.75} />
              <circle cx={depart.x} cy={depart.y} r="3.5" fill="currentColor"
                      opacity={actif && actif !== mod.key ? 0.25 : 0.9} />
            </g>
          );
        })}
      </svg>

      {/* Noyau */}
      <div
        className="absolute flex items-center justify-center rounded-full border
          border-gray-200/70 bg-white/80 backdrop-blur-sm"
        style={{
          width: RAYON_NOYAU * 2, height: RAYON_NOYAU * 2,
          left: LARGEUR / 2 - RAYON_NOYAU, top: HAUTEUR / 2 - RAYON_NOYAU,
        }}
      >
        <Image src="/assets/log.svg" alt="IFPC" width={120} height={120}
               className="h-16 w-16" priority />
      </div>

      {modules.map((mod, i) => {
        const p = positionPolaire(angles[i], RAYON_COURONNE);
        const Glyphe = mod.glyphe;
        const ouvert = actif === mod.key;
        // Le panneau s'ouvre vers l'extérieur : au-dessus pour une pastille
        // haute, en dessous pour les autres. Sinon il reviendrait sur le noyau.
        const versLeHaut = p.y < HAUTEUR / 2;
        return (
          <div
            key={mod.key}
            className="absolute"
            style={{ left: p.x - 78, top: p.y - 42, width: 156 }}
            onMouseEnter={() => setActif(mod.key)}
            onMouseLeave={() => setActif(null)}
          >
            <button
              onClick={() => setActif((v) => (v === mod.key ? null : mod.key))}
              aria-expanded={ouvert}
              className={`flex w-full flex-col items-center gap-2 rounded-xl px-2 py-3
                transition-all duration-200 ${ouvert ? "bg-white shadow-sm" : ""}`}
            >
              {Glyphe ? (
                <Glyphe className={`h-8 w-8 transition-transform duration-200
                  ${mod.couleur} ${ouvert ? "scale-110" : ""}`} />
              ) : null}
              <span className="text-center text-[12.5px] font-semibold leading-tight
                text-gray-700">{mod.label}</span>
            </button>

            <AnimatePresence>
              {ouvert && (
                <motion.div
                  initial={{ opacity: 0, scale: 0.97 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 0.98 }}
                  transition={{ duration: 0.16, ease: "easeOut" }}
                  className={`absolute left-1/2 z-20 w-56 -translate-x-1/2 rounded-xl
                    border border-gray-100 bg-white p-1.5
                    shadow-[0_10px_30px_rgba(0,0,0,0.10)]
                    ${versLeHaut ? "bottom-full mb-2" : "top-full mt-2"}`}
                >
                  {mod.subModules.map((sous, n) => (
                    <motion.div
                      key={sous.href}
                      initial={{ opacity: 0, x: -4 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ delay: 0.03 * n, duration: 0.16 }}
                    >
                      <Link
                        href={sous.href}
                        className="group flex items-center justify-between gap-2 rounded-lg
                          px-3 py-2 text-[13px] text-gray-600 transition-colors
                          hover:bg-brand-gray hover:text-gray-900"
                      >
                        <span className="leading-snug">{sous.label}</span>
                        <ChevronRight className="h-3.5 w-3.5 shrink-0 text-gray-200
                          transition-all group-hover:translate-x-0.5
                          group-hover:text-gray-400" />
                      </Link>
                    </motion.div>
                  ))}
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        );
      })}
    </div>
  );
}

/** Repli sous `lg` : la même matière, en liste. */
function ListeDomaines({ modules }: { modules: Module[] }) {
  return (
    <div className="space-y-7 lg:hidden">
      {modules.map((mod) => {
        const Glyphe = mod.glyphe;
        return (
          <section key={mod.key}>
            <h2 className={`flex items-center gap-2 border-b border-gray-200/70 pb-2
              text-[11px] font-semibold uppercase tracking-[0.16em] ${mod.couleur}`}>
              {Glyphe ? <Glyphe className="h-[18px] w-[18px] shrink-0" /> : null}
              {mod.label}
            </h2>
            <ul className="mt-1">
              {mod.subModules.map((sous) => (
                <li key={sous.href}>
                  <Link
                    href={sous.href}
                    className="group flex items-center justify-between gap-3 rounded-lg
                      px-2 py-2.5 text-[14px] text-gray-600 transition-colors
                      hover:bg-white hover:text-gray-900"
                  >
                    <span className="leading-snug">{sous.label}</span>
                    <ChevronRight className="h-3.5 w-3.5 shrink-0 text-gray-200
                      transition-all group-hover:translate-x-0.5
                      group-hover:text-gray-400" />
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

// ── Page ─────────────────────────────────────────────────────────────────────

export default function Home() {
  const { user } = useAuthStore();
  const router = useRouter();
  const { t, locale } = useI18n();
  const [activities, setActivities] = useState<RecentActivity[]>([]);
  const [openModules, setOpenModules] = useState<Record<string, boolean>>({ pasteurisation: true });

  // Même liste que la barre latérale (lib/modules.ts). Les deux surfaces en
  // tenaient chacune la sienne et avaient fini par se désaccorder.
  const GLYPHES: Record<string, Module["glyphe"]> = {
    pasteurisation: GlypheePasteurisation,
    colorimetrie: GlypheeColorimetrie,
    cuves: GlypheeCuve,
  };
  const COULEURS: Record<string, string> = {
    pasteurisation: "text-brand-primary",
    colorimetrie: "text-brand-accent",
    cuves: "text-brand-link",
    administration: "text-gray-500",
  };

  const modules: Module[] = modulesVisibles(user?.role, "accueil").map((m) => ({
    key: m.key,
    label: t(m.labelKeyAccueil),
    couleur: COULEURS[m.key] ?? "text-gray-500",
    glyphe: GLYPHES[m.key],
    adminOnly: m.adminOnly,
    subModules: m.sousModules.map((s) => ({ href: s.href, label: t(s.labelKey) })),
  }));

  useEffect(() => {
    let cancelled = false;
    async function loadActivities() {
      if (user) {
        try {
          const dbEntries = await getHistory();
          if (!cancelled && Array.isArray(dbEntries) && dbEntries.length > 0) {
            setActivities(dbEntries.map((e: HistoryEntry) => ({
              id: String(e.id),
              date: e.date,
              type: e.type,
              label: e.label,
              lotIdentifier: e.lotIdentifier,
              statut: e.statut,
              vp: e.vp,
              vpCible: e.vpCible,
              parametres: e.parametres,
              fromDb: true,
            })));
            return;
          }
        } catch { }
      }
      try {
        const stored = localStorage.getItem("ifpc_recent_activities");
        if (!cancelled && stored) setActivities(JSON.parse(stored));
      } catch {
        if (!cancelled) setActivities([]);
      }
    }
    loadActivities();
    return () => { cancelled = true; };
  }, [user]);

  /** « il y a 2 h », « hier » — sans dépendance, via l'API du navigateur. */
  const depuis = (date: string) => {
    const t0 = new Date(date).getTime();
    if (Number.isNaN(t0)) return "";
    const minutes = Math.round((t0 - Date.now()) / 60000);
    const fmt = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
    if (Math.abs(minutes) < 60) return fmt.format(minutes, "minute");
    if (Math.abs(minutes) < 60 * 24) return fmt.format(Math.round(minutes / 60), "hour");
    return fmt.format(Math.round(minutes / 1440), "day");
  };

  const greeting = () => {
    const h = new Date().getHours();
    if (h < 12) return t("home.greetingMorning");
    if (h < 18) return t("home.greetingAfternoon");
    return t("home.greetingEvening");
  };

  // Le tri par rôle est déjà fait par modulesVisibles : le refaire ici
  // rouvrirait la porte à deux règles qui se contredisent.
  const visibleModules = modules;

  return (
    <div className="min-h-screen bg-[#fafaf8] text-gray-950 px-4 sm:px-8 py-6 sm:py-10 relative overflow-hidden">
      {/* Soft background gradient & blobs matching the login aesthetic */}
      <div className="absolute inset-0 bg-gradient-to-br from-brand-primary/5 via-transparent to-brand-accent/5 pointer-events-none" />
      <div className="absolute top-20 -left-20 h-72 w-72 rounded-full bg-brand-primary/5 blur-3xl animate-pulse pointer-events-none" style={{ animationDuration: "8s" }} />
      <div className="absolute bottom-20 -right-20 h-96 w-96 rounded-full bg-brand-accent/5 blur-3xl animate-pulse pointer-events-none" style={{ animationDuration: "12s" }} />

      {/* Grid pattern SVG */}
      <div className="absolute inset-0 opacity-[0.02] mix-blend-overlay pointer-events-none">
        <svg className="h-full w-full" xmlns="http://www.w3.org/2000/svg">
          <defs>
            <pattern id="grid-home" width="45" height="45" patternUnits="userSpaceOnUse">
              <path d="M 45 0 L 0 0 0 45" fill="none" stroke="currentColor" strokeWidth="1" />
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#grid-home)" />
        </svg>
      </div>

      {/* Abstract organic design curves */}
      <svg className="absolute right-0 top-0 h-full w-1/3 opacity-[0.03] text-brand-primary pointer-events-none hidden md:block" viewBox="0 0 400 800" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M400 100 C 250 200, 150 400, 400 600" stroke="currentColor" strokeWidth="2" strokeDasharray="8 4" />
        <path d="M400 150 C 280 280, 220 480, 400 650" stroke="currentColor" strokeWidth="1.5" />
      </svg>

      <div className="max-w-4xl mx-auto space-y-10 sm:space-y-12 relative z-10">

        {/* Welcome */}
        <header className="text-center">
          <h1 className="text-xl sm:text-2xl font-bold text-brand-text">
            {greeting()}{user ? `, ${user.firstName}` : ""}
          </h1>
          <p className="text-gray-400 mt-1 text-xs sm:text-sm">{t("home.subtitle")}</p>
        </header>

        <Couronne modules={visibleModules} />
        <ListeDomaines modules={visibleModules} />

        {/* Reprendre — aperçu compact ; le détail, le regroupement et le tri
            vivent sur /historique, qui est fait pour ça. */}
        {activities.length > 0 && (
          <section>
            <div className="mb-2 flex items-baseline justify-between">
              <h2 className="text-[11px] font-medium text-gray-400">{t("home.resume")}</h2>
              <Link
                href="/historique"
                className="text-[11px] text-gray-300 transition-colors hover:text-brand-primary"
              >
                {t("home.viewAll")}
              </Link>
            </div>
            <ul className="divide-y divide-gray-50 overflow-hidden rounded-xl border
              border-gray-100 bg-white">
              {activities.slice(0, 3).map((a) => (
                <li key={a.id}>
                  <Link
                    href={CIBLES[a.type] ?? "/historique"}
                    className="flex items-center gap-2.5 px-3 py-2 transition-colors
                      hover:bg-brand-gray"
                  >
                    <span className={`h-1.5 w-1.5 shrink-0 rounded-full
                      ${PUCES[a.type] ?? "bg-gray-300"}`} />
                    <span className="min-w-0 flex-1 truncate text-[12.5px] text-gray-600">
                      {a.label}
                      {a.lotIdentifier ? (
                        <span className="text-gray-400"> · {a.lotIdentifier}</span>
                      ) : null}
                    </span>
                    <span className="shrink-0 text-[11px] text-gray-300">{depuis(a.date)}</span>
                    <ChevronRight className="h-3 w-3 shrink-0 text-gray-200" />
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </div>
  );
}
