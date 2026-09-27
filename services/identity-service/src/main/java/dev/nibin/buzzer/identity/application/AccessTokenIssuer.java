package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.config.JwtProperties;
import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds and signs every access token this service issues, so all tokens share one claim layout. */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder jwtEncoder, JwtProperties jwtProperties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    /** For a registered user: their id and roles, short-lived (refresh tokens extend the session). */
    public AccessToken issueFor(User user) {
        return issue(user.id(), user.roles(), jwtProperties.accessTokenTtl(), Map.of());
    }

    /** For a guest: no account behind it, so it can't be refreshed and lives for one game night. */
    public AccessToken issueForGuest(UUID guestId, String nickname) {
        return issue(guestId, Set.of(Role.PLAYER), jwtProperties.guestTokenTtl(), Map.of("nickname", nickname));
    }

    private AccessToken issue(UUID subject, Set<Role> roles, Duration ttl, Map<String, Object> extraClaims) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(subject.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("roles", roles.stream().map(Enum::name).sorted().toList())
                .claims(existing -> existing.putAll(extraClaims))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, ttl);
    }

    public record AccessToken(String value, Duration expiresIn) {

        @Override
        public String toString() {
            return "AccessToken[value=***, expiresIn=" + expiresIn + "]";
        }
    }
}
