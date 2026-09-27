package dev.nibin.buzzer.identity.api;

import dev.nibin.buzzer.identity.application.AccessTokenIssuer.AccessToken;
import dev.nibin.buzzer.identity.application.IssueGuestToken;
import dev.nibin.buzzer.identity.application.LoginUser;
import dev.nibin.buzzer.identity.application.LogoutSession;
import dev.nibin.buzzer.identity.application.RefreshSession;
import dev.nibin.buzzer.identity.application.RegisterUser;
import dev.nibin.buzzer.identity.application.SessionTokens;
import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegisterUser registerUser;
    private final LoginUser loginUser;
    private final IssueGuestToken issueGuestToken;
    private final RefreshSession refreshSession;
    private final LogoutSession logoutSession;

    public AuthController(RegisterUser registerUser, LoginUser loginUser, IssueGuestToken issueGuestToken,
            RefreshSession refreshSession, LogoutSession logoutSession) {
        this.registerUser = registerUser;
        this.loginUser = loginUser;
        this.issueGuestToken = issueGuestToken;
        this.refreshSession = refreshSession;
        this.logoutSession = logoutSession;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return UserResponse.from(registerUser.register(request.email(), request.password()));
    }

    @PostMapping("/login")
    public ResponseEntity<SessionTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return sessionResponse(loginUser.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<SessionTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return sessionResponse(refreshSession.refresh(request.refreshToken()));
    }

    /** Always 204, known token or not: logging out twice is fine, and it reveals nothing. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        logoutSession.logout(request.refreshToken());
    }

    @PostMapping("/guest")
    public ResponseEntity<TokenResponse> guest(@Valid @RequestBody GuestRequest request) {
        AccessToken token = issueGuestToken.issue(request.nickname());
        return noStore(new TokenResponse(token.value(), token.expiresIn().toSeconds()));
    }

    private static ResponseEntity<SessionTokenResponse> sessionResponse(SessionTokens tokens) {
        AccessToken access = tokens.accessToken();
        return noStore(new SessionTokenResponse(access.value(), access.expiresIn().toSeconds(), tokens.refreshToken()));
    }

    // RFC 6749 5.1: responses carrying tokens must not be cached by browsers or proxies.
    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {

        /** BCrypt only uses the first 72 bytes and Spring Security rejects longer input; @Size counts chars. */
        @AssertTrue(message = "must be at most 72 bytes in UTF-8")
        public boolean isPasswordWithinBcryptLimit() {
            return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
        }

        /** The generated record toString would print the password. */
        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", password=***]";
        }
    }

    /** No format rules here: a malformed email simply isn't found, and gets the same 401. */
    public record LoginRequest(@NotBlank String email, @NotBlank String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=***]";
        }
    }

    /**
     * The nickname ends up in a token claim and on other players' screens, so only letters and digits
     * (any language), spaces, and . _ - are allowed: no markup, no control characters.
     */
    public record GuestRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = "[\\p{L}\\p{N} ._-]+") String nickname) {
    }

    /** 43 characters when valid; the limit only stops absurd input before it is hashed. */
    public record RefreshTokenRequest(@NotBlank @Size(max = 100) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshTokenRequest[refreshToken=***]";
        }
    }

    /** Guests: an access token only, no refresh. expiresIn is in seconds, as in OAuth 2 token responses. */
    public record TokenResponse(String accessToken, long expiresIn) {

        @Override
        public String toString() {
            return "TokenResponse[accessToken=***, expiresIn=" + expiresIn + "]";
        }
    }

    /** Login and refresh: the access token plus the refresh token to use next time (once). */
    public record SessionTokenResponse(String accessToken, long expiresIn, String refreshToken) {

        @Override
        public String toString() {
            return "SessionTokenResponse[accessToken=***, expiresIn=" + expiresIn + ", refreshToken=***]";
        }
    }

    /** What the client sees: never the password hash. */
    public record UserResponse(UUID id, String email, Set<Role> roles) {

        static UserResponse from(User user) {
            return new UserResponse(user.id(), user.email(), user.roles());
        }
    }
}
