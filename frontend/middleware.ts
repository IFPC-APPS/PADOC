import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'
import { estCheminFederation, fonctionnaliteDe } from '@/lib/fonctionnalites-chemins'

// Pages accessibles sans être connecté.
const PUBLIC_PATHS = ['/', '/login', '/reset-password']

type Etat = Record<string, boolean>

// Le Core API répond selon l'utilisateur : un administrateur reçoit tout
// ouvert. On met donc l'état en cache PAR JETON, et brièvement — la bascule
// d'un administrateur doit se voir sans attendre, mais pas au prix d'un
// aller-retour vers l'API à chaque navigation.
const CACHE = new Map<string, { etat: Etat; expire: number }>()
const DUREE_CACHE_MS = 30_000
const TAILLE_MAX_CACHE = 500

async function etatFonctionnalites(request: NextRequest, jeton: string | undefined): Promise<Etat | null> {
  const cle = jeton ?? 'anonyme'
  const connu = CACHE.get(cle)
  if (connu && connu.expire > Date.now()) return connu.etat

  try {
    // En-tête Authorization, et non le cookie : le filtre JWT du Core API ne
    // lit que celui-là. Transmis en cookie, le jeton serait ignoré, l'API
    // répondrait l'état du public — et un administrateur se verrait refuser
    // une fonctionnalité qu'il est censé pouvoir préparer.
    const reponse = await fetch(new URL('/api/config/fonctionnalites', request.url), {
      headers: jeton ? { Authorization: `Bearer ${jeton}` } : {},
      cache: 'no-store',
    })
    if (!reponse.ok) return null
    const etat = (await reponse.json()) as Etat
    // Un cache sans borne grandit indéfiniment sur un processus de longue
    // durée ; on le vide entièrement plutôt que de trier, les entrées étant
    // de toute façon périmées en trente secondes.
    if (CACHE.size >= TAILLE_MAX_CACHE) CACHE.clear()
    CACHE.set(cle, { etat, expire: Date.now() + DUREE_CACHE_MS })
    return etat
  } catch {
    // API injoignable : on ne ferme rien. Fermer l'application entière parce
    // qu'une requête de configuration a échoué serait une panne bien pire que
    // celle qu'on cherche à éviter.
    return null
  }
}

export async function middleware(request: NextRequest) {
  const token = request.cookies.get('token')?.value
  const { pathname } = request.nextUrl

  // Le serveur d'autorisation et le guide d'intégration passent tels quels,
  // avant toute autre règle.
  if (estCheminFederation(pathname)) {
    return NextResponse.next()
  }

  const cleFonctionnalite = fonctionnaliteDe(pathname)
  if (cleFonctionnalite) {
    const etat = await etatFonctionnalites(request, token)
    if (etat && etat[cleFonctionnalite] === false) {
      if (pathname.startsWith('/api/')) {
        return NextResponse.json(
          { message: 'Fonctionnalité désactivée par un administrateur.', fonctionnalite: cleFonctionnalite },
          { status: 403 },
        )
      }
      // Vers l'accueil, et non vers une page d'erreur : du point de vue de
      // l'utilisateur cette fonctionnalité n'existe pas, puisqu'elle a aussi
      // disparu de la navigation.
      return NextResponse.redirect(new URL('/', request.url))
    }
  }

  // Laisser passer les routes publiques et les internes de Next.
  if (
    PUBLIC_PATHS.includes(pathname) ||
    pathname.startsWith('/_next') ||
    pathname.startsWith('/api') ||
    pathname.includes('.')
  ) {
    // Sur la page login : si déjà connecté, rediriger vers /controle
    if (pathname === '/login' && token) {
      return NextResponse.redirect(new URL('/controle', request.url))
    }
    return NextResponse.next()
  }

  // Pour toutes les autres pages (protégées) : rediriger si pas de token.
  // Les données métier appartiennent à un utilisateur : sans jeton, l'API
  // répond 401 et la page n'aurait rien à afficher.
  if (!token) {
    const loginUrl = new URL('/login', request.url)
    loginUrl.searchParams.set('redirect', pathname)
    return NextResponse.redirect(loginUrl)
  }

  return NextResponse.next()
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
}
