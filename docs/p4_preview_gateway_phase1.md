# P4 Preview Gateway Phase1

## Scope

- Add in-memory route registry for task preview upstream mapping.
- Add preview gateway service for register/resolve/unregister/recycle.
- Add unified gateway path proxy endpoints: `/p/{taskId}/**` and `/t/{taskId}/**`.
- Keep legacy proxy path `/api/preview/{taskId}/**` unchanged for compatibility.

## Core Components

- `PreviewRouteRegistry`
  - register route by `taskId -> upstreamBaseUrl`
  - resolve active route
  - unregister route
  - recycle expired routes by `expireAt`

- `PreviewGatewayService`
  - register route and return preview URL
  - resolve route with repository fallback restore
  - unregister route
  - recycle expired routes

- `PreviewGatewayController`
  - proxy requests from `/p/{taskId}/**` and `/t/{taskId}/**`
  - validate preview status is `ready`
  - resolve route from gateway service and forward upstream response

## Publish Flow Changes

- In `PreviewDeployService`:
  - unregister old route before each new deploy attempt
  - when `smartark.preview.gateway.enabled=true`, publish URL via gateway service (`/p/{taskId}/`)
  - on deploy failure, unregister route for rollback safety

- In `PreviewLifecycleService`:
  - recycle expired gateway routes in scheduled job
  - unregister route when preview expires or project preview cleanup runs

## Rollback

- Set `smartark.preview.gateway.enabled=false`
- New preview URL falls back to legacy `/api/preview/{taskId}/`
- Existing `/api/preview` path remains available during and after rollback
