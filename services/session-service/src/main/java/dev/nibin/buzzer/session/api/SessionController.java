package dev.nibin.buzzer.session.api;

import dev.nibin.buzzer.session.application.CreateSession;
import dev.nibin.buzzer.session.application.GetSessionState;
import dev.nibin.buzzer.session.application.JoinSession;
import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

/** Roles per endpoint are in SecurityConfig. The caller is always the JWT's sub. */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final CreateSession createSession;
    private final JoinSession joinSession;
    private final GetSessionState getSessionState;

    public SessionController(CreateSession createSession, JoinSession joinSession, GetSessionState getSessionState) {
        this.createSession = createSession;
        this.joinSession = joinSession;
        this.getSessionState = getSessionState;
    }

    /** HOST. 201 with the room code the host shows to players. */
    @PostMapping
    public ResponseEntity<CreatedSession> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateSessionRequest request) {
        Session session = createSession.create(userId(jwt), request.quizId());
        return ResponseEntity.created(URI.create("/api/sessions/" + session.id()))
                .body(new CreatedSession(session.id(), session.roomCode().value()));
    }

    /**
     * PLAYER (guest token). 201 the first time, 200 when the caller is already in: joining is idempotent.
     * The display name is the token's nickname, which identity-service already validated.
     */
    @PostMapping("/join")
    public ResponseEntity<JoinedSession> join(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody JoinRequest request) {
        JoinSession.Joined joined = joinSession.join(request.roomCode(), userId(jwt), nickname(jwt));
        Player player = joined.player();
        JoinedSession body = new JoinedSession(player.sessionId(), player.playerId(), player.displayName());
        return joined.newPlayer()
                ? ResponseEntity.created(URI.create("/api/sessions/" + player.sessionId() + "/state")).body(body)
                : ResponseEntity.ok(body);
    }

    /** HOST of the session or one of its PLAYERs; 404 for anyone else. What a reconnecting client redraws from. */
    @GetMapping("/{sessionId}/state")
    public SessionStateResponse state(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return SessionStateResponse.from(getSessionState.get(sessionId, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static String nickname(Jwt jwt) {
        String nickname = jwt.getClaimAsString("nickname");
        if (nickname == null || nickname.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Join with a guest token, which carries a nickname");
        }
        return nickname;
    }

    public record CreateSessionRequest(@NotNull UUID quizId) {
    }

    public record CreatedSession(UUID sessionId, String roomCode) {
    }

    public record JoinRequest(@NotBlank String roomCode) {
    }

    public record JoinedSession(UUID sessionId, UUID playerId, String displayName) {
    }
}
