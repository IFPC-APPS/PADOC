# Stage 1: Dépendances
FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# Stage 2: Build
FROM node:24-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# Les rewrites de next.config.mjs sont figées dans
# au moment du build : les URLs des backends sont donc des ARG, pas des ENV runtime.
ARG SPRING_URL=http://localhost:8080
ARG FASTAPI_URL=http://localhost:8000
# Assistant AsCoCid. Le défaut ne vaut que pour un build local : dans un
# conteneur, « localhost » désigne le conteneur lui-même et la page d'assistant
# affichera « service injoignable ». À renseigner au build comme les deux autres.
ARG LDC_URL=http://localhost:8100
ENV SPRING_URL=$SPRING_URL
ENV FASTAPI_URL=$FASTAPI_URL
ENV LDC_URL=$LDC_URL
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# Stage 3: Runtime
FROM node:24-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
RUN addgroup -g 1001 -S nodejs && adduser -S -u 1001 -G nodejs nextjs
COPY --from=build /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
