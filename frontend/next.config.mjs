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
      // Guide d'intégration de la fédération d'identité, servi par le Core API.
      // Proxifié pour être lisible sur le domaine public de l'application : on
      // le transmet à des équipes partenaires, leur donner une URL d'infra
      // serait fragile.
      //
      // Cette page SEULE, et non tout /federation/* : le formulaire de
      // connexion fédéré dépose un cookie de session, et le parcours OAuth se
      // poursuit sur l'origine du Core API. Le proxifier déposerait la session
      // sur le domaine du front, où le point d'autorisation ne la retrouverait
      // pas — la connexion échouerait sans message clair.
      {
        source: '/federation/documentation',
        destination: `${SPRING_URL}/federation/documentation`,
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
