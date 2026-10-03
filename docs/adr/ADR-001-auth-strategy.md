# ADR-001: Authentication — RS256 access tokens verified via JWKS, rotating refresh tokens

- **Status:** Accepted
- **Date:** 2026-09-27
- **Deciders:** nibin
- **Related:** ADR-002 (gateway responsibilities), ADR-003 (auth on STOMP CONNECT)

## Context

Several services must know **who the caller is** and **what they may do**:

1. **Many verifiers, one issuer.** The gateway, quiz-service and session-service all check tokens on every request.
   Only identity-service creates them.
2. **No call to identity-service per request.** A game sends hundreds of answers within a few hundred milliseconds
   (ADR-004). An extra network hop to "ask identity" on each one would add latency and make identity a single
   point of failure for every request.
3. **Two kinds of users.** Hosts have an account (email + password). Players usually join a game as **guests** with
   only a nickname, for one evening.
4. **Stolen tokens must do limited damage.** Tokens live in browser memory and travel on every request. A leaked
   one must expire soon, and a stolen long-lived credential must be detectable.
5. **Keys must be rotatable** without redeploying every service.
6. **Secrets must not leak.** No secret may live in the repo, and the service must refuse to start
   rather than silently generate a key.

## Decision

1. **Access tokens are JWTs signed with RS256** (RSA private key, SHA-256).
   - identity-service alone holds the **private** key and signs.
   - Every other service holds nothing secret. It verifies the signature with the **public** key.
   - Claims: `iss` = `buzzer-identity`, `sub` = user or guest UUID, `roles` (`HOST` / `PLAYER`), `iat`, `exp`.
     Guest tokens also carry `nickname`.
   - Lifetime: **15 minutes** for accounts, **3 hours** for guests.
2. **The public key is published as a JWK Set** at `GET /.well-known/jwks.json`.
   - The key id (`kid`) is the key's RFC 7638 thumbprint, so a new key automatically gets a new `kid`.
   - The response may be cached for 5 minutes (`Cache-Control: public, max-age=300`).
   - Verifiers (the gateway, quiz-service, session-service) are OAuth2 resource servers configured with only the
     JWKS URI and the issuer. They fetch the keys on first use and cache them. A token with an unknown `kid`
     triggers a refetch. They check signature, expiry and issuer, and then map `roles` to Spring authorities.
3. **Keys come from PEM files, never from code.** `.\tasks.ps1 keys` writes them to `.secrets\` (gitignored).
   Kubernetes mounts them from a Secret; AWS will use Secrets Manager. A missing file, or a public key that doesn't
   match the private key, stops identity-service at startup.
4. **Refresh tokens are opaque, stored hashed, and rotated on every use.**
   - Value: 32 random bytes → 43 URL-safe characters. Only its **SHA-256** is stored (`refresh_tokens.token_hash`),
     so a database leak doesn't hand out working tokens.
   - Lifetime **7 days**, sliding: every refresh issues a new one valid for another 7 days.
   - A login starts a **family** (one login session). Every rotation adds a row to that family.
   - **A refresh token works exactly once.** Marking it used is an atomic conditional update, so two concurrent
     refreshes with the same token have exactly one winner.
   - **Reuse revokes the whole family.** Presenting a token that was already used means two parties hold it (the
     user and a thief), and we can't tell which is which. So every token in that family is revoked, and both must log
     in again. The revocation commits even though the request fails (`noRollbackFor`).
   - Unknown, expired, revoked and reused tokens all get the same `401`. The response tells an attacker nothing.
   - Logout revokes the family. It always answers `204`, known token or not.
5. **Guests get an access token only.** `POST /api/auth/guest {nickname}` returns a `PLAYER` token with a fresh UUID
   as `sub`. There is no account to refresh against, so the token simply lasts one game night.
6. **Registered users get the `HOST` role.** Passwords are hashed with BCrypt (max 72 bytes, enforced at the API).
   Token responses carry `Cache-Control: no-store`.

## Alternatives considered

| Option | Why not |
|---|---|
| **HS256 (shared secret)** | Every verifier would need the same secret that **creates** tokens. Then a compromise of the gateway, quiz-service or session-service lets the attacker mint tokens for any user with any role. Rotating means updating the secret everywhere at once. With RS256, a verifier only ever holds a public key, and rotation is "publish the new key, then sign with it". |
| **Opaque access tokens + introspection** (ask identity on every request) | Instant revocation, but every request costs a call to identity-service, which breaks requirement 2 and makes identity-service a single point of failure for the whole game. |
| **Server-side sessions with cookies** | Needs shared session storage across services, and brings CSRF into scope. Our clients are a browser test client today and possibly mobile later; bearer tokens fit both. |
| **One long-lived access token, no refresh** | A stolen token works for days, with nothing to detect it. Short access tokens with refresh rotation limit a leak to 15 minutes, and turn a stolen refresh token into a detectable event. |
| **Refresh without rotation** | Simpler, but a stolen refresh token keeps working silently for its whole lifetime. Rotation is what makes reuse visible. |
| **Keycloak / Auth0 / Cognito** | Production-grade and the right call for a company. Here it would hide exactly the mechanisms this portfolio is meant to show (signing, JWKS, rotation, reuse detection), and adds a service to run or a vendor to pay. The token format is standard, so switching later only changes the JWKS URI and issuer in each verifier. |
| **Accounts for players** | Friction kills a party game. A guest nickname takes seconds. |

## Consequences

**Positive**
- Verifying a token is local CPU work in each service. No request depends on identity-service being up at that moment.
- A compromised verifier can't create tokens: no service but identity-service holds a private key.
- Key rotation is non-disruptive: publish the new key in the JWKS, start signing with it, and remove the old one
  after the longest token lifetime has passed. Verifiers pick up a new `kid` by themselves.
- A stolen access token works for at most 15 minutes (3 hours for a guest). A stolen refresh token is caught the
  moment either party uses it after the other.

**Negative, accepted on purpose**
- **Access tokens can't be revoked before they expire.** Logout or a family revocation stops the *refresh* token;
  an already-issued access token keeps working for up to 15 minutes. A deny-list would bring back the per-request
  lookup we avoided.
- **Roles change on the next refresh, not instantly.** A refresh reloads the user, so a role change applies within
  15 minutes.
- **Guest tokens last 3 hours with no way to end them early.** Accepted: a guest can only play in sessions they
  joined, and ADR-003 already notes a WebSocket connection outlives its token.
- **Reuse detection can log out an innocent user.** If a legitimate client retries a refresh after the response was
  lost, the second attempt looks like reuse and ends the session. Safer than the alternative.
- **The key files are a secret to manage** on every machine and environment (`.secrets\` locally, a Secret in
  Kubernetes, Secrets Manager in AWS). A fresh clone needs `.\tasks.ps1 keys` before identity-service starts.

### If identity-service is down

| What | Still works? | Why |
|---|---|---|
| Requests with a valid, unexpired access token | **Yes** | Verifiers check the signature with the cached public key; no call to identity. |
| A game in progress | **Yes** | Answers travel over an already-authenticated STOMP connection (ADR-003). |
| Register, login, guest login | **No** | Only identity-service can create users and sign tokens. |
| Refresh | **No** | Refresh tokens live in identity's database. Users drop out when their 15-minute access token expires. |
| A verifier that **restarts** during the outage | **No**, until identity is back | It has no cached keys yet and can't fetch the JWKS, so every token is rejected with 401. The gateway still *starts*, because keys are fetched on first use, not at startup. |

So an outage stops **new** players from joining and slowly logs out existing ones, but it doesn't stop games that
are already running.

## Verification

| Claim | Evidence |
|---|---|
| Register → 201, duplicate email → 409, wrong password → 401 | `AuthControllerTest`, `RegisterUserTest`, `LoginUserTest`; manual curl run 2026-09-27 |
| Tokens carry `iss`, `sub`, `roles`, `exp` = 15 min (3 h for guests, plus `nickname`) | `AccessTokenIssuerTest`, `IssueGuestTokenTest` |
| Keys load from files only; mismatched pair stops startup; `kid` is the thumbprint | `JwtConfigTest` |
| JWKS exposes one public key with its `kid`, never the private parts | `JwksControllerTest` |
| Only the hash is stored; a login starts a new family; rotation stays in it | `RefreshTokenIssuerTest`, `RefreshTokenSecretTest` |
| A refresh token works once; reuse revokes the whole family; a lost race counts as reuse | `RefreshSessionTest` |
| Two concurrent refreshes with one token have exactly one winner | `JpaRefreshTokenRepositoryAdapterTest.concurrentMarkUsedHasExactlyOneWinnerAndTheLoserWaitsForTheRowLock` (Testcontainers Postgres) |
| Logout revokes the family | `LogoutSessionTest` |
| quiz-service rejects a request with a valid-looking `X-User-Id` but no token | `QuizApiTest` |

## Revisit when

- **Instant revocation is needed** (banning a user mid-game, a compromised account). Then add a short-lived deny-list
  of token ids in Redis, checked at the gateway, or shorten the access token lifetime.
- **Key rotation is automated.** Today a rotation is a manual file swap and restart. Then serve two keys in the JWKS
  during the overlap and move key storage to Secrets Manager or KMS.
- **The JWKS cache during an outage is tested.** How long a running verifier keeps serving from its cached keys
  depends on Spring Security / Nimbus cache settings, which aren't tuned or tested here. Measure it before relying on
  it in AWS.
- **Real users sign up.** Then add email verification, password reset, login rate limits per account (today only per
  IP at the gateway), and probably move to a managed identity provider.
