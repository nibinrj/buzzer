package dev.nibin.buzzer.session.api.websocket;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.SessionServiceApplication;
import dev.nibin.buzzer.session.TestTokens;
import dev.nibin.buzzer.session.application.JoinSession;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import java.lang.reflect.Type;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Two session-service instances, as two ECS tasks would be: the usual test context (instance A) and a second copy
 * of the application (instance B) started here against the SAME Postgres, Redis and quiz stub. A host and players
 * connected to different instances must all see every event, each exactly once. This is what the Redis relay is for:
 * with only a local broker, a player on the other instance would receive nothing.
 */
@ApiIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiInstanceBroadcastTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration WAIT = Duration.ofSeconds(5);

    @LocalServerPort
    private int portA;

    @Autowired
    private SimpUserRegistry registryA;

    @Autowired
    private WireMockServer quizServiceStub;

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    @Qualifier("redisContainer")
    private GenericContainer<?> redis;

    @Autowired
    private RedpandaContainer redpanda;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private LiveStateRepository liveState;

    @Autowired
    private JoinSession joinSession;

    private ConfigurableApplicationContext instanceB;

    private final WebSocketStompClient stompClient = stompClient();
    private final List<StompSession> connections = new ArrayList<>();

    private Session session;
    private UUID host;
    private UUID ada;
    private UUID bob;

    /**
     * Command-line arguments, because they outrank application.yml (whose ${SESSION_DB_PASSWORD} and
     * ${REDIS_PASSWORD} have no defaults). Flyway on B finds the schema already migrated by A and does nothing.
     */
    @BeforeAll
    void startInstanceB() {
        instanceB = new SpringApplicationBuilder(SessionServiceApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--spring.data.redis.password=",
                "--spring.kafka.bootstrap-servers=" + redpanda.getBootstrapServers(),
                "--session.quiz-client.base-url=" + quizServiceStub.baseUrl(),
                "--session.security.jwt.jwk-set-uri=" + quizServiceStub.baseUrl() + TestTokens.JWKS_PATH);
    }

    @AfterAll
    void stopInstanceB() {
        instanceB.close();
    }

    @BeforeEach
    void setUp() {
        // Other test classes reset WireMock; the JWKS must be there when the first token is verified.
        quizServiceStub.stubFor(get(urlEqualTo(TestTokens.JWKS_PATH)).willReturn(okJson(TestTokens.jwks())));
        host = UUID.randomUUID();
        session = newSession(host);
        ada = join("Ada");
        bob = join("Bob");
    }

    @AfterEach
    void disconnect() {
        connections.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
    }

    @Test
    void aQuestionStartedOnOneInstanceReachesPlayersOnBothInstancesExactlyOnce() throws Exception {
        StompSession hostOnA = connect(portA, TestTokens.host(host));
        BlockingQueue<Map<String, Object>> adaOnB = subscribe(connect(portB(), guestToken(ada)), questionTopic());
        BlockingQueue<Map<String, Object>> bobOnA = subscribe(connect(portA, guestToken(bob)), questionTopic());
        awaitSubscriber(registryA, questionTopic());
        awaitSubscriber(registryB(), questionTopic());

        hostOnA.send(command("start"), "");

        Map<String, Object> toAda = next(adaOnB);
        Map<String, Object> toBob = next(bobOnA);
        assertThat(toAda).isEqualTo(toBob).containsEntry("index", 0).containsEntry("text", "Capital of France?");
        // The publishing instance doesn't also deliver locally, so nobody gets a second copy.
        assertThat(adaOnB.poll(300, TimeUnit.MILLISECONDS)).as("second copy on B").isNull();
        assertThat(bobOnA.poll(300, TimeUnit.MILLISECONDS)).as("second copy on A").isNull();
    }

    @Test
    void aHostOnTheOtherInstanceReachesPlayersHere() throws Exception {
        StompSession hostOnB = connect(portB(), TestTokens.host(host));
        BlockingQueue<Map<String, Object>> adaOnA = subscribe(connect(portA, guestToken(ada)), statusTopic());
        awaitSubscriber(registryA, statusTopic());

        hostOnB.send(command("end"), "");

        assertThat(next(adaOnA)).containsEntry("status", "ENDED");
    }

    private int portB() {
        return Integer.parseInt(instanceB.getEnvironment().getRequiredProperty("local.server.port"));
    }

    private SimpUserRegistry registryB() {
        return instanceB.getBean(SimpUserRegistry.class);
    }

    private StompSession connect(int port, String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession connection = stompClient.connectAsync("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
                }).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        connections.add(connection);
        return connection;
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<Map<String, Object>> subscribe(StompSession connection, String destination) {
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        connection.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((Map<String, Object>) payload);
            }
        });
        return received;
    }

    /** SUBSCRIBE is asynchronous: wait until that instance has registered it (see SessionWebSocketTest). */
    private static void awaitSubscriber(SimpUserRegistry registry, String destination) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (registry.findSubscriptions(s -> s.getDestination().equals(destination)).isEmpty()) {
            if (System.nanoTime() > deadline) {
                fail("Expected a subscription to " + destination);
            }
            Thread.sleep(20);
        }
    }

    private static Map<String, Object> next(BlockingQueue<Map<String, Object>> queue) throws InterruptedException {
        Map<String, Object> message = queue.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(message).as("a message within " + WAIT).isNotNull();
        return message;
    }

    private String questionTopic() {
        return "/topic/sessions/" + session.id() + "/question";
    }

    private String statusTopic() {
        return "/topic/sessions/" + session.id() + "/status";
    }

    private String command(String name) {
        return "/app/sessions/" + session.id() + "/" + name;
    }

    private UUID join(String nickname) {
        UUID user = UUID.randomUUID();
        joinSession.join(session.roomCode().value(), user, nickname);
        return user;
    }

    private static String guestToken(UUID user) {
        return TestTokens.guest(user, "Player");
    }

    private Session newSession(UUID hostId) {
        Session created = Session.create(RoomCode.random(RANDOM), UUID.randomUUID(), hostId, "Capitals",
                List.of(new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0)),
                Instant.now());
        sessions.add(created);
        liveState.initialize(created.id(), created.status());
        return created;
    }

    /** JSON for messages; plain text for the (empty) command payloads. */
    private static WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
        return client;
    }
}
