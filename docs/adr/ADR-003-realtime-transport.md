# ADR-003: Real-time transport — WebSocket + STOMP, fanned out through Redis

- **Status:** Accepted
- **Date:** 2026-09-28
- **Deciders:** nibin
- **Related:** ADR-001 (auth: RS256 + JWKS), ADR-002 (gateway responsibilities), plan §3.5

## Context

A host runs a live quiz for up to **500 players** per session. The requirements that shape the transport:

1. **Server → all players, fast.** When the host advances, every player must see the question within **~200 ms** (plan, core flow step 4).
2. **Players → server, fast.** Answers and buzzes are the product. Their arrival order decides who wins (ADR-004), so the client-to-server path matters as much as the push.
3. **Several server instances.** session-service runs on ECS Fargate behind an ALB and may scale out. Players in one room can land on different tasks.
4. **Unreliable clients.** Phones switch networks and laptops sleep. A dropped connection must recover without the player losing the game.
5. **Browsers can't set headers on a WebSocket handshake**, so the usual `Authorization: Bearer` check can't happen at connect time.
6. **One developer, small budget.** Every extra piece of infrastructure has to justify its cost and operational load.

## Decision

1. **Transport: WebSocket**, one connection per browser tab, carrying both directions.
2. **Protocol on top: STOMP** (Spring's `@EnableWebSocketMessageBroker`), not raw frames.
   - Clients SEND commands to `/app/sessions/{id}/…`, handled by `@MessageMapping`.
   - The server pushes to `/topic/sessions/{id}/question|status`, one-to-many.
   - Errors go to `/user/queue/errors`, reaching only the sender.
3. **Broker: Spring's in-memory simple broker on each instance, joined by a Redis pub/sub relay.** Each event is PUBLISHed once to Redis. Every instance, including the publisher, hands it to its own local broker, which delivers to its own clients. No RabbitMQ or ActiveMQ.
4. **Authentication on the STOMP `CONNECT` frame**, not the handshake.
   - The gateway lets the `/ws` handshake through without a token.
   - session-service checks the JWT carried in the CONNECT frame, with the same JWKS as REST.
   - A deny-by-default interceptor then authorizes every SUBSCRIBE and SEND. Only the host may send commands; only the host and joined players may subscribe.
5. **Liveness:**
   - STOMP heartbeats every **10 s** in both directions.
   - ALB idle timeout **≥ 120 s**.
6. **Reconnect = resync, not replay.** The client reconnects on its own after 5 s and subscribes again. It then calls `GET /api/sessions/{id}/state`, which rebuilds the screen from the source of truth: Postgres for sessions and players, Redis for the live question and deadline.

## Alternatives considered

| Option | Why not |
|---|---|
| **Short polling** (`GET /state` every *n* seconds) | 500 players × 1 request/s = 500 req/s per session just to wait. Latency is up to *n* seconds, which misses the 200 ms target unless polling is aggressive, and that multiplies cost. |
| **Long polling** | Meets latency, but each push costs a new HTTP request per player, and the upstream direction still needs separate requests. It's more complex than WebSocket for no gain in our browsers. |
| **Server-Sent Events + REST POST for answers** | **The strongest alternative.** SSE is plain HTTP, reconnects automatically, and works through strict proxies. We rejected it because: (a) it is one-way, so every answer becomes a separate HTTP request with its own TLS, auth and gateway cost; that is the hot path in ADR-004; (b) `EventSource` can't set headers either, so it doesn't solve the auth problem; (c) browsers cap HTTP/1.1 connections per origin, which hurts multi-tab testing. |
| **Raw WebSocket (no STOMP)** | We'd have to invent message framing, routing by destination, subscriptions, per-user addressing, heartbeats and error frames. STOMP provides all of these, and Spring and `stompjs` implement them. |
| **STOMP broker relay (RabbitMQ / ActiveMQ)** | Gives durable, clustered fan-out, but it is one more stateful service to run, secure and pay for. Our pushes are notifications, not records (see Consequences), so durability buys little. Redis is already in the stack for live state and rate limiting. |
| **Sticky sessions by room code** | Pins a room to one task, so no fan-out is needed. But the ALB can't route by a value inside a STOMP frame, a hot room can't spread across tasks, and one task restart drops the whole room. |
| **Managed push** (API Gateway WebSocket, AppSync, Pusher) | Removes the connection-handling work, but moves the core of the product out of the codebase this portfolio is meant to show. Also adds per-message cost and a vendor-specific programming model. |

## Consequences

**Positive**
- One connection per tab carries both directions. Both the push and the answer path stay under the latency target.
- Any number of session-service tasks can serve one room. No sticky sessions are needed.
- Authorization is enforced **per frame** in the service that owns the data. The gateway only lets the handshake through, consistent with ADR-002 ("services keep validating").
- A player who drops recovers to a correct screen with one REST call. There is no replay log to build or trim.

**Negative, accepted on purpose**
- **Pushes are at-most-once.** Redis pub/sub stores nothing, so an instance or client that is disconnected at that moment misses the message. This is acceptable because a push is a *notification*: the truth lives in Postgres and Redis live state, and `/state` restores it. Anything that must not be lost (answers, scores) does **not** travel on this path. Answers are ordered by a Lua script (ADR-004) and published through the outbox to Kafka (ADR-005).
- **The token is checked once, at CONNECT.** A connection outlives its token's expiry. Guest tokens last 3 h, about one game night. Closing connections when the token expires would need an extra timer per connection; deferred.
- **No SockJS fallback.** Networks that block WebSocket upgrades (some corporate proxies) can't play. Accepted for a consumer party game.
- **Every instance receives every session's events.** Fan-out costs O(instances × events), not O(players in the room). This is fine at our scale of a few tasks.
- **Two settings must stay coordinated:** heartbeat 10 s, well below the ALB idle timeout of ≥ 120 s. If someone lowers the ALB timeout or removes heartbeats, idle lobbies disconnect silently.

## Verification

| Claim | Evidence |
|---|---|
| Host advances, players on the same instance receive the question without the answer | `SessionWebSocketTest` (real STOMP client, real socket) |
| Players on **another** instance receive it, exactly once | `MultiInstanceBroadcastTest` (two running instances, shared Postgres and Redis). It fails when the relay is replaced by local-only delivery. |
| Handshake passes the gateway without a token; forged identity headers are stripped; foreign origins are refused | `WebSocketProxyTest` (gateway) |
| Bad/missing token, outsiders, players sending host commands, anyone sending straight to a topic are all refused | `JwtConnectInterceptorTest`, `DestinationAuthorizationInterceptorTest`, `SessionWebSocketTest` |
| End to end, 1 host + 3 players through the gateway, including a session-service restart mid-game | Manual run with `tools/test-client.html`, 2026-09-28: every tab received every question; all tabs reconnected and resynced from `/state` |

## Revisit when

- A message **must** reach clients that were briefly offline (for example, a live leaderboard players can't afford to miss). Then move to a STOMP broker relay, or Redis Streams with per-client offsets.
- session-service runs **more than ~10 tasks**, or rooms grow past ~2,000 players. Then measure the fan-out cost first (Phase 9 load test), and consider per-session channels or a real broker.
- Players report connection failures on restrictive networks. Then add SockJS or an SSE fallback for the server-to-client direction.
