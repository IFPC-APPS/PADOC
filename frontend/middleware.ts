import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'

// Pages accessibles sans être connecté.
//
// /federation/documentation est le guide d'intégration destiné aux plateformes
// partenaires : son lecteur n'a précisément pas de compte IFPC. Sans cette
// entrée, le middleware le renvoie vers /login — et comme le chemin ne
// commence pas par /api et ne contient pas de point, il n'atteint jamais la
// rewrite qui le proxifie vers le Core API.
const PUBLIC_PATHS = ['/', '/login', '/reset-password', '/federation/documentation']

export function middleware(request: NextRequest) {
  const token = request.cookies.get('token')?.value
  const { pathname } = request.nextUrl

  // Laisser passer les routes publiques et next internals
  if (
    PUBLIC_PATHS.includes(pathname) ||
    pathname.startsWith('/_next') ||
    pathname.startsWith('/api') ||
    pathname.includes('.')
  ) {
    // Sur la page login: si déjà connecté, rediriger vers /controle
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
