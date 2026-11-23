# ADR 0012 — React SPA with Vite, one origin through a proxy

**Status:** Accepted (Phase 5)

## Context

Phase 5 delivers the SPA that walks catalog → waiting room → hold → payment → notification
(`docs/01-arquitectura.md`, `docs/04-frontend-redis.md`). The backend is already three HTTP
services on three ports (Catalog 8081, Booking 8082, Notification 8083) with no gateway, and the
product decisions are already made: Vite instead of Next.js because all server logic is Java and
SSR is not needed; TanStack Query for remote state; React Hook Form + Zod for forms; no Redux.

Two practical questions remain: how the browser reaches three origins, and which versions are
pinned. The repository rule is to verify versions instead of guessing, and this machine's npm
configuration refuses packages published in the last three days (`min-release-age=3`).

## Decision

1. **Vite + React 19 + TypeScript, static build.** `frontend/` is an npm project with the exact
   versions in its `package.json` and a committed `package-lock.json` (`npm ci` reproducible).
   Vite 8.3.8, `@vitejs/plugin-react` 6.3.0, React 19.3.1, TypeScript 5.9.3, Tailwind CSS 4.3.5
   through `@tailwindcss/vite`, TanStack Query 5.108.1, React Router 7.18.6, React Hook Form 7.92.0
   with Zod 4.6.7 and `@hookform/resolvers` 7.0.7, Vitest 5.1.1 and Testing Library on jsdom.
   The matrix was installed and built in a scratch project before being adopted; the versions are
   the newest releases outside the three-day guard. Zod 4 coercion does not type-check with the
   React Hook Form resolver, so numeric fields use `z.number()` plus `valueAsNumber`.
2. **One origin, three proxies.** The SPA always calls its own origin under `/api/catalog`,
   `/api/booking` and `/api/notification`; Vite's `server.proxy` and `preview.proxy` rewrite the
   service prefix to `/api` and forward to 8081/8082/8083. No CORS configuration is added to the
   services, and the production deployment (Phase 6) can implement the same mapping in the ingress
   or reverse proxy. The alternative — exposing three origins and enabling CORS — would leak
   deployment topology into the services and the client.
3. **Remote state stays remote.** TanStack Query owns the cache; the client keeps only the demo
   user id and the reservation ids in `localStorage`, never a duplicated backend state machine.
   Every reservation status, payment outcome and notification is rendered from an API response.
   Loading, empty, error and expiry states are explicit, and a lost Redis or a `403 NOT_ADMITTED`
   sends the user back to the real waiting-room flow instead of simulating an admission.
4. **The demo identity is explicit.** The user id is `web-<uuid>` in `localStorage`, sent as
   `X-User-Id`, and labelled in the UI as a demo identity. It is not authentication and is never
   presented as such; replacing it is a Phase 6+ concern (Keycloak is out of scope).
5. **Testing is component-first with one flow test.** Vitest + Testing Library cover selection
   validation, waiting-room transitions (including the 503 fallback), the countdown, and the
   reservation/payment states. One test renders the real routes with a fake `fetch` backend and
   walks catalog → selection → join → hold → pay → confirmed, so routing, API parsing and polling
   are exercised together. The production build (`tsc -b && vite build`) is a gate, not an
   afterthought.

## Consequences

- The browser never needs CORS, and the services keep their own ports locally; the proxy mapping
  is the single place that knows the topology.
- `npm run preview` serves the built SPA with the same proxy, so the production build can be
  exercised locally before Phase 6 packages it.
- The version set is reproducible and respects the local supply-chain policy, at the cost of not
  using the absolute newest majors (TypeScript 7, React Router 8); the pinned versions are the ones
  the probe proved together.
- The SPA cannot confirm a payment client-side: a confirmed state only appears after the backend
  reports `CONFIRMED`, which keeps the demo honest about the saga.
- The frontend is a separate npm project, not a Gradle module: backend and frontend builds stay
  independent, and Phase 6/8 can wire them into one compose image and CI jobs.

## Alternatives considered

- **Next.js.** Rejected by the product decision: no SSR requirement and all server logic lives in
  Java.
- **Three origins with CORS.** More configuration in every service, and it exposes internal ports
  to the browser; the proxy keeps one origin and one contract.
- **Redux/global store duplicating server state.** The spec explicitly rejects it; TanStack Query
  already provides caching, retries and polling.
- **Jest + Enzyme or no tests.** Slower and does not match Vite; the roadmap requires component and
  critical-flow tests plus a production build.
- **Bleeding-edge majors (TypeScript 7, React Router 8, react-query 5.108.3).** They are outside
  the npm release guard, unpublished combinations, or unnecessary for this phase; the probe proved
  the chosen set.
