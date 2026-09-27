package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.application.AccessTokenIssuer.AccessToken;

/** What login and refresh hand out: a short-lived access token plus the one-time refresh token for the next one. */
public record SessionTokens(AccessToken accessToken, String refreshToken) {

    @Override
    public String toString() {
        return "SessionTokens[accessToken=" + accessToken + ", refreshToken=***]";
    }
}
