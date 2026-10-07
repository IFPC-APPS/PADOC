/** @type {import('next').NextConfig} */
const SPRING_URL = process.env.SPRING_URL || 'http://localhost:8080';
const FASTAPI_URL = process.env.FASTAPI_URL || 'http://localhost:8000';
// Chatbot du Livre de Connaissances (backend/rag_ascocid) : service séparé,
// parce qu'il embarque un modèle d'embeddings et un index en mémoire dont le
// moteur de calcul n'a aucun besoin.
// Le chemin /api/ldc est ajouté par la rewrite ci-dessous : si LDC_URL le porte
// déjà, on le retire plutôt que de produire une URL en double — la panne se
// manifesterait par un 404 opaque, impossible à rattacher à sa cause.
const LDC_URL = (process.env.LDC_URL || 'http://localhost:8100')
  .replace(/\/+$/, '')
  .replace(/\/api\/ldc$/, '');

const nextConfig = {
  // Sortie autonome : embarque un serveur Node minimal + les seules dépendances
  // réellement utilisées, pour une image Docker sans node_modules complet.
  output: 'standalone',

  async rewrites() {
    return [
      {
        source: '/api/auth/:path*',
        destination: `${SPRING_URL}/api/auth/:path*`,
      },
      {
        source: '/api/admin/:path*',
        destination: `${SPRING_URL}/api/admin/:path*`,
      },
      {
        source: '/api/config/:path*',
        destination: `${SPRING_URL}/api/config/:path*`,
      },
      {
        source: '/api/deploy/:path*',
        destination: `${SPRING_URL}/api/deploy/:path*`,
      },
      {
        source: '/api/history',
        destination: `${SPRING_URL}/api/history`,
      },
      {
        source: '/api/history/:path*',
        destination: `${SPRING_URL}/api/history/:path*`,
      },
      {
        source: '/api/cuves',
        destination: `${SPRING_URL}/api/cuves`,
      },
      {
        source: '/api/cuves/:path*',
        destination: `${SPRING_URL}/api/cuves/:path*`,
      },
      {
        source: '/api/lots',
        destination: `${SPRING_URL}/api/lots`,
      },
      {
        source: '/api/lots/:path*',
        destination: `${SPRING_URL}/api/lots/:path*`,
      },
      {
        source: '/api/stockages',
        destination: `${SPRING_URL}/api/stockages`,
      },
      {
        source: '/api/stockages/:path*',
        destination: `${SPRING_URL}/api/stockages/:path*`,
      },
      {
        source: '/api/operations',
        destination: `${SPRING_URL}/api/operations`,
      },
      {
        source: '/api/operations/:path*',
        destination: `${SPRING_URL}/api/operations/:path*`,
      },
      {
        source: '/api/ldc/:path*',
        destination: `${LDC_URL}/api/ldc/:path*`,
      },
      // ── Fédération d'identité ────────────────────────────────────────────
      //
      // Tout le serveur d'autorisation est relayé sous le domaine public de
      // l'application, et non la seule page de documentation.
      //
      // Une version antérieure ne relayait QUE /federation/documentation, en
      // redoutant ceci : le formulaire de connexion dépose un cookie de
      // session, et si le parcours se poursuivait sur l'origine du Core API,
      // le point d'autorisation ne retrouverait pas cette session. La crainte
      // était fondée — pour un relais PARTIEL. Elle disparaît avec un relais
      // complet : cookie, page de connexion, autorisation et jeton se trouvent
      // alors sur une seule et même origine, celle du front.
      //
      // C'est ce qui rend la fédération utilisable en production. OpenID
      // Connect fait voyager le navigateur : il doit pouvoir joindre lui-même
      // l'émetteur, lequel doit donc répondre à une adresse publique. Sans ces
      // règles, l'émetteur ne serait atteignable que sur l'URL interne du Core
      // API — et le parcours s'interromprait au premier saut.
      //
      // FEDERATION_ISSUER doit valoir exactement cette origine publique.
      {
        source: '/oauth2/:path*',
        destination: `${SPRING_URL}/oauth2/:path*`,
      },
      {
        source: '/.well-known/:path*',
        destination: `${SPRING_URL}/.well-known/:path*`,
      },
      {
        source: '/userinfo',
        destination: `${SPRING_URL}/userinfo`,
      },
      {
        source: '/connect/:path*',
        destination: `${SPRING_URL}/connect/:path*`,
      },
      {
        source: '/federation/:path*',
        destination: `${SPRING_URL}/federation/:path*`,
      },
      {
        source: '/api/referentiels/:path*',
        destination: `${FASTAPI_URL}/api/referentiels/:path*`,
      },
      {
        source: '/api/:path*',
        destination: `${FASTAPI_URL}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
