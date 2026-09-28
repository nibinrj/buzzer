package dev.nibin.buzzer.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gateway really proxies a WebSocket: a browser-style upgrade to /ws, with no token (browsers can't add one to
 * the handshake), reaches session-service, and frames flow both ways. WireMock can't speak WebSocket, so
 * session-service is stood in for by a tiny Reactor Netty echo server that also records each handshake's headers.
 * Authentication of the connection is session-service's job (STOMP CONNECT), not tested here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RedisTestcontainer.class)
class WebSocketProxyTest {

    private static final String BROWSER_ORIGIN = "http://localhost:5173";

    /** What session-service saw in each handshake: the identity headers (null if absent). */
    private record Handshake(String userId, String userRoles) {
    }

    private static final BlockingQueue<Handshake> HANDSHAKES = new LinkedBlockingQueue<>();

    /** Echoes every text frame back, after recording the handshake request's headers. */
    private static final DisposableServer SESSION = HttpServer.create()
            .port(0)
            .route(routes -> routes.ws("/ws", (in, out) -> {
                HANDSHAKES.add(new Handshake(in.headers().get("X-User-Id"), in.headers().get("X-User-Roles")));
                return out.sendString(in.receive().asString());
            }))
            .bindNow();

    private final WebSocketClient client = new ReactorNettyWebSocketClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void pointAtEchoServer(DynamicPropertyRegistry registry) {
        registry.add("SESSION_URI", () -> "http://localhost:" + SESSION.port());
    }

    @AfterAll
    static void stopEchoServer() {
        SESSION.disposeNow();
    }

    @BeforeEach
    void forgetHandshakes() {
        HANDSHAKES.clear();
    }

    @Test
    void aHandshakeWithoutATokenIsForwardedAndFramesFlowBothWays() throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(BROWSER_ORIGIN);

        assertThat(echo("hello", headers)).isEqualTo("hello");
        assertThat(HANDSHAKES.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void clientSuppliedIdentityHeadersNeverReachSessionService() throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(BROWSER_ORIGIN);
        headers.set("X-User-Id", "00000000-0000-0000-0000-000000000001");
        headers.set("X-User-Roles", "HOST");

        echo("hi", headers);

        Handshake handshake = HANDSHAKES.poll(5, TimeUnit.SECONDS);
        assertThat(handshake).isNotNull();
        assertThat(handshake.userId()).isNull();
        assertThat(handshake.userRoles()).isNull();
    }

    @Test
    void aHandshakeFromAForeignOriginIsRefusedAtTheGateway() throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://evil.example");

        assertThatThrownBy(() -> echo("hello", headers)).isNotNull();
        assertThat(HANDSHAKES.poll(500, TimeUnit.MILLISECONDS)).as("reached session-service").isNull();
    }

    /** Opens ws://gateway/ws, sends one text frame, returns the first frame that comes back. */
    private String echo(String text, HttpHeaders headers) {
        AtomicReference<String> received = new AtomicReference<>();
        client.execute(URI.create("ws://localhost:" + port + "/ws"), headers, session ->
                        session.send(Mono.just(session.textMessage(text)))
                                .thenMany(session.receive().take(1).map(WebSocketMessage::getPayloadAsText))
                                .doOnNext(received::set)
                                .then())
                .block(Duration.ofSeconds(10));
        return received.get();
    }
}
