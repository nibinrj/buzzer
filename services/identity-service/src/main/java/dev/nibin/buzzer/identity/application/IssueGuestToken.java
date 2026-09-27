package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.application.AccessTokenIssuer.AccessToken;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Use case: let someone join a game as a PLAYER without an account.
 * Nothing is stored: the guest exists only inside the token, and a new request means a new guest.
 */
@Service
public class IssueGuestToken {

    private final AccessTokenIssuer accessTokenIssuer;

    public IssueGuestToken(AccessTokenIssuer accessTokenIssuer) {
        this.accessTokenIssuer = accessTokenIssuer;
    }

    public AccessToken issue(String nickname) {
        // Random UUID v4, like registered users' ids: 122 random bits, so it won't collide with them.
        return accessTokenIssuer.issueForGuest(UUID.randomUUID(), nickname.strip());
    }
}
