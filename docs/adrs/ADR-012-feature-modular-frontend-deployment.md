# ADR-012: Feature-Modular Frontend and Independent Deployment

- **Status:** Accepted
- **Date:** 2026-10-27
- **Decision makers:** Project owner

## Context

The React application had screens and reusable components, but source files were grouped mainly by technical type. Its production assets were also written into Quarkus resources, coupling frontend and backend builds and releases.

## Decision

- Organize frontend screens by feature, with application navigation under `frontend/src/app/` and cross-feature API, design, localization, and UI utilities under `frontend/src/shared/`.
- Build static files into `frontend/dist` and package them in a separate Nginx image.
- Run Quarkus as an API-only service and route `/api` and `/q` through Nginx to the API over the deployment network.
- Keep the browser on one origin in the standard deployment; do not enable cross-origin CORS for the frontend/API path.

## Consequences

- Frontend and backend can be built and released independently while preserving a shared-origin browser interface.
- The frontend and backend must keep their API contract synchronized; typed calls remain centralized in `frontend/src/shared/api.ts`.
- Container deployments need both the Quarkus and frontend images. The API container should not be published directly when Nginx is the public entry point.
- The Nginx upload body limit must stay aligned with Quarkus's 500 MB request-body limit.
- Local preview uses separate ports for the UI and API; Vite proxies API and OpenAPI paths.

## Alternatives considered

- **Keep packaging the SPA in Quarkus:** Rejected because it couples frontend output to backend packaging and deployment.
- **Serve the frontend and API on different browser origins:** Rejected as the default because it requires CORS policy and separate browser-visible API configuration.
- **Microfrontends:** Rejected as unnecessary for the current application size and release model.

## References

- [Frontend architecture](../arc42/05-building-block-view.md)
- [Deployment view](../arc42/07-deployment-view.md)
- [ADR-001: Quarkus and React stack](ADR-001-stack-quarkus-react.md)
