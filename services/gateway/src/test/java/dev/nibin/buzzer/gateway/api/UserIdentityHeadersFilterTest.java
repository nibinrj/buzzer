package dev.nibin.buzzer.gateway.api;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.nibin.buzzer.gateway.GatewayTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static dev.nibin.buzzer.gateway.api.UserIdentityHeadersFilter.USER_ID;
import static dev.nibin.buzzer.gateway.api.UserIdentityHeadersFilter.USER_ROLES;
import static org.assertj.core.api.Assertions.assertThat;

/** Header spoofing: whatever X-User-* the client sends, the service sees only what the verified token says. */
class UserIdentityHeadersFilterTest extends GatewayTestSupport {

    private static final String ATTACKER_CHOICE = UUID.randomUUID().toString();

    @ParameterizedTest(name = "spoofed as {0}")
    @ValueSource(strings = {"X-User-Id", "x-user-id"})
    void spoofedHeadersAreReplacedByTheVerifiedToken(String spoofedIdHeader) {
        UUID caller = UUID.randomUUID();

        client.get().uri("/api/quizzes")
                .headers(h -> {
                    h.setBearerAuth(validToken(caller, List.of("HOST", "PLAYER")));
                    h.add(spoofedIdHeader, ATTACKER_CHOICE);
                    h.add(USER_ROLES, "ADMIN");
                })
                .exchange()
                .expectStatus().isOk();

        LoggedRequest forwarded = onlyRequestTo(QUIZ, "/api/quizzes");
        assertThat(forwarded.getHeaders().getHeader(USER_ID).values()).containsExactly(caller.toString());
        assertThat(forwarded.getHeaders().getHeader(USER_ROLES).values()).containsExactly("HOST,PLAYER");
    }

    @Test
    void onAPublicPathSpoofedHeadersAreRemovedAndNotReplaced() {
        client.post().uri("/api/sessions/join")
                .headers(h -> {
                    h.add(USER_ID, ATTACKER_CHOICE);
                    h.add(USER_ROLES, "HOST");
                })
                .exchange()
                .expectStatus().isOk();

        LoggedRequest forwarded = onlyRequestTo(SESSION, "/api/sessions/join");
        assertThat(forwarded.containsHeader(USER_ID)).isFalse();
        assertThat(forwarded.containsHeader(USER_ROLES)).isFalse();
    }

    @Test
    void onAPublicPathEvenAValidTokenSetsNoIdentityHeaders() {
        // Public paths don't check tokens, so there is no verified identity to vouch for.
        client.post().uri("/api/sessions/join")
                .headers(h -> h.setBearerAuth(validToken(UUID.randomUUID(), List.of("PLAYER"))))
                .exchange()
                .expectStatus().isOk();

        LoggedRequest forwarded = onlyRequestTo(SESSION, "/api/sessions/join");
        assertThat(forwarded.containsHeader(USER_ID)).isFalse();
        assertThat(forwarded.containsHeader(USER_ROLES)).isFalse();
    }
}
