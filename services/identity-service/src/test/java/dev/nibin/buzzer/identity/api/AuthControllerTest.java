package dev.nibin.buzzer.identity.api;

import dev.nibin.buzzer.identity.HttpIntegrationTest;
import dev.nibin.buzzer.identity.TestJwtKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP against the running app on a random port, backed by PostgreSQL 16 in a container.
 * Not transactional: data stays between tests, so every test uses its own email.
 */
@HttpIntegrationTest
class AuthControllerTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void loginReturnsATokenThatVerifiesWithThePublicKey() {
        String email = uniqueEmail();
        String userId = (String) register(email, "correct horse").getBody().get("id");

        ResponseEntity<Map<String, Object>> response = login(email.toUpperCase(), "correct horse");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).containsEntry("expiresIn", 900);
        assertThat((String) response.getBody().get("refreshToken")).matches("[A-Za-z0-9_-]{43}");

        Jwt jwt = NimbusJwtDecoder.withPublicKey(TestJwtKeys.publicKey()).build()
                .decode((String) response.getBody().get("accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(userId);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("HOST");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("buzzer-identity");
    }

    @Test
    void refreshRotatesTheRefreshTokenAndIssuesAnAccessTokenForTheSameUser() {
        Session session = newSession();

        ResponseEntity<Map<String, Object>> response = refresh(session.refreshToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        String rotated = (String) response.getBody().get("refreshToken");
        assertThat(rotated).isNotEqualTo(session.refreshToken());
        Jwt jwt = NimbusJwtDecoder.withPublicKey(TestJwtKeys.publicKey()).build()
                .decode((String) response.getBody().get("accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(session.userId());
        // The new one works (once) as well.
        assertThat(refresh(rotated).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeSession() {
        Session session = newSession();
        String rotated = (String) refresh(session.refreshToken()).getBody().get("refreshToken");

        ResponseEntity<Map<String, Object>> reuse = refresh(session.refreshToken());

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getBody()).containsEntry("title", "Invalid refresh token");
        // The legitimate successor is dead too: the revocation was committed although the request failed
        // (noRollbackFor). Without it, this would still be 200.
        assertThat(refresh(rotated).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutEndsThatSessionOnly() {
        String email = uniqueEmail();
        register(email, "correct horse");
        String phone = (String) login(email, "correct horse").getBody().get("refreshToken");
        String laptop = (String) login(email, "correct horse").getBody().get("refreshToken");

        ResponseEntity<Map<String, Object>> logout = post("/api/auth/logout", Map.of("refreshToken", phone));

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(phone).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(laptop).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void logoutWithAnUnknownOrAlreadyRevokedTokenIsStill204() {
        Session session = newSession();
        post("/api/auth/logout", Map.of("refreshToken", session.refreshToken()));

        assertThat(post("/api/auth/logout", Map.of("refreshToken", session.refreshToken())).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("/api/auth/logout", Map.of("refreshToken", "never-issued")).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void unknownRefreshTokenGets401AndBlankGets400() {
        assertThat(refresh("never-issued").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(" ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSame401ProblemDetail() {
        String email = uniqueEmail();
        register(email, "correct horse");

        ResponseEntity<Map<String, Object>> wrongPassword = login(email, "wrong password");
        ResponseEntity<Map<String, Object>> unknownEmail = login(uniqueEmail(), "correct horse");

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(wrongPassword.getBody())
                .containsEntry("status", 401)
                .containsEntry("title", "Invalid credentials")
                .doesNotContainKey("accessToken");
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getBody()).isEqualTo(wrongPassword.getBody());
    }

    @Test
    void loginWithBlankFieldsReturns400() {
        ResponseEntity<Map<String, Object>> response = login("", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldsInErrors(response)).containsExactlyInAnyOrder("email", "password");
    }

    @Test
    void guestGetsAThreeHourPlayerTokenWithTheStrippedNickname() {
        ResponseEntity<Map<String, Object>> response = guest("  Ünïcode Fan_1 ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).containsEntry("expiresIn", 10800).doesNotContainKey("refreshToken");

        Jwt jwt = NimbusJwtDecoder.withPublicKey(TestJwtKeys.publicKey()).build()
                .decode((String) response.getBody().get("accessToken"));
        assertThat(UUID.fromString(jwt.getSubject())).isNotNull();
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("PLAYER");
        assertThat(jwt.getClaimAsString("nickname")).isEqualTo("Ünïcode Fan_1");
    }

    @Test
    void guestNicknameThatIsBlankTooLongOrContainsMarkupReturns400() {
        for (String nickname : List.of("   ", "x".repeat(21), "<b>bold</b>", "tab\there")) {
            ResponseEntity<Map<String, Object>> response = guest(nickname);

            assertThat(response.getStatusCode()).as(nickname).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(fieldsInErrors(response)).as(nickname).containsOnly("nickname");
        }
    }

    @Test
    void registerReturns201WithTheNewUser() {
        String email = uniqueEmail();

        ResponseEntity<Map<String, Object>> response = register(email, "correct horse");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .containsEntry("email", email)
                .containsEntry("roles", List.of("HOST"))
                .containsKey("id")
                .doesNotContainKeys("password", "passwordHash");
        assertThat(UUID.fromString((String) response.getBody().get("id"))).isNotNull();
    }

    @Test
    void duplicateEmailInDifferentCaseReturns409ProblemDetail() {
        String email = uniqueEmail();
        register(email, "correct horse");

        ResponseEntity<Map<String, Object>> response = register(email.toUpperCase(), "another password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .containsEntry("status", 409)
                .containsEntry("title", "Email already registered")
                .containsEntry("instance", "/api/auth/register");
    }

    @Test
    void invalidInputReturns400WithFieldErrorsButNoRejectedValues() {
        ResponseEntity<Map<String, Object>> response = register("not-an-email", "short");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("status", 400);
        assertThat(fieldsInErrors(response)).containsExactlyInAnyOrder("email", "password");
        assertThat(response.getBody().toString()).doesNotContain("short");
    }

    @Test
    void passwordOver72BytesIsRejectedEvenWhenUnder72Characters() {
        String eightyBytePassword = "é".repeat(40); // 40 chars, but 2 bytes each in UTF-8

        ResponseEntity<Map<String, Object>> response = register(uniqueEmail(), eightyBytePassword);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldsInErrors(response)).containsExactly("passwordWithinBcryptLimit");
    }

    private ResponseEntity<Map<String, Object>> register(String email, String password) {
        return post("/api/auth/register", Map.of("email", email, "password", password));
    }

    private ResponseEntity<Map<String, Object>> login(String email, String password) {
        return post("/api/auth/login", Map.of("email", email, "password", password));
    }

    private ResponseEntity<Map<String, Object>> refresh(String refreshToken) {
        return post("/api/auth/refresh", Map.of("refreshToken", refreshToken));
    }

    private record Session(String userId, String refreshToken) {
    }

    private Session newSession() {
        String email = uniqueEmail();
        String userId = (String) register(email, "correct horse").getBody().get("id");
        return new Session(userId, (String) login(email, "correct horse").getBody().get("refreshToken"));
    }

    private ResponseEntity<Map<String, Object>> guest(String nickname) {
        return post("/api/auth/guest", Map.of("nickname", nickname));
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, String> body) {
        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                    // Don't throw on 4xx: the tests assert on error responses.
                })
                .toEntity(JSON_OBJECT);
    }

    @SuppressWarnings("unchecked")
    private static List<String> fieldsInErrors(ResponseEntity<Map<String, Object>> response) {
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.getBody().get("errors");
        return errors.stream().map(error -> (String) error.get("field")).toList();
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.dev";
    }
}
