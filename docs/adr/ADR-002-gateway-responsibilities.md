# ADR-002: Gateway responsibilities — a thin edge that checks tokens, services still decide

- **Status:** Accepted
- **Date:** 2026-09-28
- **Deciders:** nibin
- **Related:** ADR-001 (RS256 + JWKS), ADR-003 (WebSocket auth on STOMP CONNECT)

## Context

Clients talk to four services: identity, quiz, session and scoring. Without a common entry point:

1. **Every client would need four base URLs**, and every service would need its own CORS setup and public
   exposure (in AWS: one ALB route and one public security-group rule per service).
2. **Cross-cutting concerns would be repeated four times**: rejecting bad tokens early, rate limiting, CORS, and
   one error format.
3. **Some endpoints must never be public.** session-service reads quiz snapshots from
   `/internal/quizzes/{id}/snapshot`, which includes the correct answers.
4. **Identity headers are tempting and dangerous.** Passing `X-User-Id` downstream is convenient for logs, but if a
   service trusted it, any client could send `X-User-Id: <someone else>` (header spoofing).
5. **WebSocket handshakes can't carry an `Authorization` header** from a browser (ADR-003).
6. **Anonymous endpoints attract abuse**: login, register and guest login are open to anyone, so brute force and
   sign-up floods must be throttled before they reach a service.

The question is what belongs in the gateway, and just as important, what doesn't.

## Decision

The gateway (`services/gateway`, Spring Cloud Gateway on WebFlux, port 8080) is the **only public entry point**.
It does six things:

1. **Routing by path prefix.** Paths are forwarded unchanged; each service already serves its own prefix.

   | Path | Service |
   |---|---|
   | `/api/auth/**`, `/.well-known/**` | identity-service |
   | `/api/quizzes/**` | quiz-service |
   | `/api/sessions/**`, `/ws/**` (WebSocket upgrade) | session-service |
   | `/api/results/**` | scoring-service |

2. **Block `/internal/**` with a 404.** A `WebFilter` at the highest precedence answers 404 before security and
   routing run. It answers 404 rather than 401, so the endpoint's existence isn't revealed. It also keeps blocking
   if someone later adds a route that would match.
3. **Edge authentication.** The gateway is a reactive OAuth2 resource server. It verifies the JWT's signature
   (identity's JWKS, ADR-001), expiry and issuer.
   - **Public, no token:** `/api/auth/**`, `/.well-known/**`, `/api/sessions/join`, and the exact `/ws` handshake.
     On these paths a token is not even checked, so an expired access token sent to `/api/auth/refresh` doesn't lock
     the user out.
   - **Everything else needs a valid token**, otherwise `401` with `WWW-Authenticate: Bearer`. The response never
     says *why* the token was rejected.
   - The gateway checks **"valid token or not" only**. Roles and ownership are not its business.
4. **Identity headers: strip, then set.** On every route, any `X-User-Id` / `X-User-Roles` the client sent is
   removed. If the request carries a verified token, they are set again from its `sub` and `roles`. The original
   `Authorization` header is forwarded unchanged.
5. **Rate limiting** with Redis token buckets (`RequestRateLimiter`), over the limit → `429`:
   - **Per client IP** on identity routes (anonymous): 2 requests/s, burst 20. Generous on purpose, because a whole
     pub on one Wi-Fi shares one IP.
   - **Per user** (token `sub`) everywhere else: 10 requests/s, burst 20. Falls back to per IP on public paths like
     `/api/sessions/join`.
   - The client IP comes from the TCP peer unless proxies are configured (`gateway.client-ip.trusted-proxies`: 0
     locally, 1 behind the ALB). Then the entry the outermost trusted proxy wrote is used, so a client can't pick its
     own IP by sending `X-Forwarded-For`.
   - Only the WebSocket **handshake** is rate limited. STOMP frames on an open socket aren't HTTP requests.
6. **CORS and errors in one place.**
   - CORS allows configured origins only (`http://localhost:[*]` locally), headers `Authorization` and
     `Content-Type`, and caches preflights for 1 hour. Preflights are answered before authentication.
   - Every gateway error (401, 404, 429, firewall 400, a service dropping the connection) comes back as
     `application/problem+json`.

**What the gateway deliberately does NOT do:**
- **Authorization.** "Is this user a HOST?" and "does this user own this quiz?" are answered by the service that
  owns the data.
- **Replace service-side token checks.** Every service verifies the forwarded JWT itself, against the same JWKS.
  It **never authorizes from `X-User-*`**; those headers are for logs and tracing only.
- **Business logic, aggregation or response transformation.** No calls that combine several services.
- **Expose Actuator.** Health and metrics live on a separate management port (9080) that no route or load balancer
  exposes.

## Alternatives considered

| Option | Why not |
|---|---|
| **No gateway: every service public** | Four public surfaces, four CORS configs, rate limiting four times, and `/internal/**` protected only by each service remembering to. |
| **Gateway as the only token check ("trusted network")** | Simpler services, but one misconfigured route, or any attacker inside the network, can call a service directly with forged `X-User-*` headers and become anyone. Defense in depth costs one signature check per request: microseconds of CPU, no network call (ADR-001). |
| **Services trust `X-User-*` from the gateway** | Same weakness as above. It also couples every service's security to the gateway's correctness. Stripping the headers prevents spoofing *through* the gateway, but not around it. |
| **Authorization (roles, ownership) in the gateway** | The gateway doesn't have the data. "Owns this quiz" lives in quiz_db. Pulling that knowledge into the edge would mean a central component that changes with every service's rules. |
| **AWS API Gateway / ALB authentication** | Managed and scalable, but Lambda authorizers, vendor config and per-request pricing replace code this project is meant to show. Also doesn't run locally or on kind. The ALB stays in front as a load balancer (ADR-009). |
| **Spring Cloud Gateway on Web MVC** | Spring Cloud 2025.1 offers a servlet variant. The reactive one handles many idle WebSocket connections and slow clients on a few event-loop threads, which suits a proxy that mostly waits on I/O. |
| **Kong / Envoy / NGINX** | Excellent proxies, but JWT validation, header rules and rate-limit keys would live in another configuration language. Keeping the gateway in Java and Spring keeps it testable with the same tools as the services. |

## Consequences

**Positive**
- **One public surface.** Clients know one URL; AWS needs one ALB target. `/internal/**` is unreachable from outside
  even if a route is added by mistake.
- **Bad tokens die at the edge.** Forged, expired or missing tokens never reach a service, which saves their
  resources and keeps junk out of their logs.
- **Header spoofing is impossible through the gateway**, and harmless around it, because services ignore `X-User-*`
  for decisions.
- **Abuse of anonymous endpoints is throttled** before it costs a BCrypt hash or a database write.
- **Consistent errors and CORS** for every client.

**Negative, accepted on purpose**
- **Each token is verified twice** (gateway and service). That's cheap CPU work, and the price of defense in depth.
- **Another hop and another thing to run.** Every request passes through one more process. It adds a few
  milliseconds locally, and the gateway must be scaled with traffic.
- **Redis becomes part of the request path** for rate limiting. If Redis is slow, every request is slow. Spring Cloud
  Gateway's Redis rate limiter is designed to let requests through when Redis errors rather than fail them, but that
  failure mode isn't tested here.
- **Rate limits are approximate.** Per-IP limits punish shared networks and can be dodged with many IPs; per-user
  limits only apply after login. Good enough against floods, not a WAF.
- **Route config and service paths must stay in sync.** A new service endpoint under a new prefix needs a new route.
- **Long-lived WebSocket connections pass through the gateway**, so a gateway restart drops every player's socket.
  Clients reconnect and resync (ADR-003).

## Verification

| Claim | Evidence |
|---|---|
| Each prefix reaches its service; unrouted paths 404; path traversal is rejected before routing | `GatewayRoutingTest` (WireMock stand-ins for the services) |
| No token, expired, forged or wrong-issuer token → 401, never forwarded; the reason isn't echoed | `GatewayRoutingTest`, `ProblemDetailExceptionHandlerTest` |
| An expired token doesn't block a public path such as refresh | `GatewayRoutingTest.anExpiredTokenDoesNotBlockAPublicPath` |
| `/internal/**` → 404, token or not | `ProblemDetailExceptionHandlerTest.anInternalPathIs404` |
| Spoofed `X-User-*` are removed, then set only from a verified token; never on public paths | `UserIdentityHeadersFilterTest`, `WebSocketProxyTest.clientSuppliedIdentityHeadersNeverReachSessionService` |
| 429 after a burst; one bucket per user; a spoofed `X-Forwarded-For` doesn't buy a fresh bucket | `RateLimitTest`, `RateLimitConfigTest` (Testcontainers Redis) |
| Preflight from localhost answered without a token; foreign origins refused | `CorsTest`, `WebSocketProxyTest.aHandshakeFromAForeignOriginIsRefusedAtTheGateway` |
| A service ignores `X-User-*`: valid-looking header, no token → 401; another user's id in the header changes nothing | `QuizApiTest` (quiz-service) |
| Actuator isn't served on the public port | `HealthProbeTest`, `MetricsEndpointTest` |
| End to end through `:8080`, forged and expired tokens rejected, direct call to quiz-service without a token fails | Manual curl run through the gateway |

## Revisit when

- **Public launch.** Put a WAF or CDN in front for bot protection and per-country limits; IP-based limits alone
  won't hold.
- **Many more services or teams.** Route config in one gateway becomes a bottleneck. Then consider per-service route
  ownership, or a service mesh for service-to-service auth (mTLS) so `/internal/**` relies on more than network
  isolation.
- **Redis rate limiting shows up in latency** (the k6 load tests record the gateway's route timings). Then
  consider local in-memory buckets with approximate global limits.
- **Gateway restarts disrupt games.** Then run at least two gateway instances behind the load balancer, with graceful
  shutdown that drains WebSockets.
