package dev.nibin.buzzer.session.infrastructure.kafka;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.ScoreUpdated;
import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.TestTokens;
import dev.nibin.buzzer.session.application.JoinSession;
import dev.nibin.buzzer.session.application.LogContext;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
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
import static org.awaitility.Awaitility.await;

/**
 * scoring.score-updated in, /topic/sessions/{id}/leaderboard out: ScoreUpdated records are produced to real Redpanda
 * the way scoring-service does (key = sessionId, eventType header, JSON), and a real STOMP client subscribed as a
 * player receives what ScoreUpdatedListener pushed through the Redis relay.
 */
@ApiIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LeaderboardPushTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration WAIT = Duration.ofSeconds(15);
    /** How long "nothing else arrives" is watched after the last expected message. */
    private static final Duration QUIET = Duration.ofSeconds(1);

    @LocalServerPort
    private int port;

    @Autowired
    private WireMockServer identityJwks;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private LiveStateRepository liveState;

    @Autowired
    private JoinSession joinSession;

    @Autowired
    private SimpUserRegistry userRegistry;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private JsonMapper json;

    private final WebSocketStompClient stompClient = stompClient();
    private final List<StompSession> connections = new ArrayList<>();

    private Session session;
    private UUID ada;

    @BeforeEach
    void setUp() {
        identityJwks.stubFor(get(urlEqualTo(TestTokens.JWKS_PATH)).willReturn(okJson(TestTokens.jwks())));
        session = newSession();
        ada = UUID.randomUUID();
        joinSession.join(session.roomCode().value(), ada, "Ada");
        // auto-offset-reset is latest: a record sent before the consumer has its partitions would be skipped.
        MessageListenerContainer container = listeners.getListenerContainer(ScoreUpdatedListener.ID);
        await().atMost(WAIT).until(() -> container.getAssignedPartitions() != null
                && !container.getAssignedPartitions().isEmpty());
    }

    @AfterEach
    void disconnect() {
        connections.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
    }

    @Test
    void aLeaderboardOlderThanTheLastPushedOneIsNotPushed() throws Exception {
        BlockingQueue<Map<String, Object>> leaderboards = subscribeAsAda();
        UUID bob = UUID.randomUUID();

        send(update(session.id(), 2, List.of(new ScoreUpdated.Entry(1, bob, 1000))));
        send(update(session.id(), 1, List.of(new ScoreUpdated.Entry(1, bob, 900)))); // late: older than 2
        send(update(session.id(), 3, List.of(new ScoreUpdated.Entry(1, bob, 1000),
                new ScoreUpdated.Entry(2, UUID.randomUUID(), 900))));

        Map<String, Object> first = next(leaderboards);
        assertThat(first).containsEntry("sessionId", session.id().toString());
        assertThat(version(first)).isEqualTo(2);
        assertThat(first.get("top")).isEqualTo(List.of(Map.of("rank", 1, "playerId", bob.toString(), "points", 1000)));
        assertThat(version(next(leaderboards))).isEqualTo(3); // not 1
        assertThat(leaderboards.poll(QUIET.toMillis(), TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void anUnreadableRecordIsSkippedAndTheNextLeaderboardStillArrives() throws Exception {
        BlockingQueue<Map<String, Object>> leaderboards = subscribeAsAda();

        send(session.id(), "ScoreUpdated", "{\"sessionId\": ");
        send(session.id(), "SomethingElse", json.writeValueAsString(update(session.id(), 5, List.of())));
        send(update(session.id(), 1, List.of()));

        assertThat(version(next(leaderboards))).isEqualTo(1);
        assertThat(leaderboards.poll(QUIET.toMillis(), TimeUnit.MILLISECONDS)).isNull();
    }

    /**
     * The listener labels its lines with the session from the record's KEY, so even the line about a record whose
     * JSON can't be read says which session it belonged to.
     */
    @Test
    void theLineAboutAnUnreadableRecordCarriesTheSessionFromItsKey(CapturedOutput output) throws Exception {
        BlockingQueue<Map<String, Object>> leaderboards = subscribeAsAda();

        send(session.id(), "ScoreUpdated", "{\"sessionId\": ");
        send(update(session.id(), 1, List.of()));
        // Same key, same partition: once this one arrived, the unreadable one before it has been handled.
        assertThat(version(next(leaderboards))).isEqualTo(1);

        JsonNode line = output.getOut().lines().filter(l -> l.startsWith("{")).map(json::readTree)
                .filter(l -> l.path("message").asString().startsWith("Skipped an unreadable ScoreUpdated"))
                .filter(l -> l.path(LogContext.SESSION_ID).asString().equals(session.id().toString()))
                .findFirst().orElseThrow();
        assertThat(line.path("log").path("logger").asString()).isEqualTo(ScoreUpdatedListener.class.getName());
    }

    @Test
    void anotherSessionsLeaderboardDoesNotReachThisSessionsPlayers() throws Exception {
        BlockingQueue<Map<String, Object>> leaderboards = subscribeAsAda();

        send(update(UUID.randomUUID(), 1, List.of(new ScoreUpdated.Entry(1, UUID.randomUUID(), 1000))));
        send(update(session.id(), 4, List.of()));

        Map<String, Object> received = next(leaderboards);
        assertThat(received).containsEntry("sessionId", session.id().toString());
        assertThat(version(received)).isEqualTo(4);
    }

    // --- helpers ---

    private BlockingQueue<Map<String, Object>> subscribeAsAda() throws Exception {
        String topic = "/topic/sessions/" + session.id() + "/leaderboard";
        BlockingQueue<Map<String, Object>> received = subscribe(connect(TestTokens.guest(ada, "Ada")), topic);
        awaitSubscribers(topic);
        return received;
    }

    private static ScoreUpdated update(UUID sessionId, long version, List<ScoreUpdated.Entry> top10) {
        return new ScoreUpdated(UUID.randomUUID(), sessionId, top10, version, ScoreUpdated.SCHEMA_VERSION);
    }

    private void send(ScoreUpdated update) throws Exception {
        send(update.sessionId(), "ScoreUpdated", json.writeValueAsString(update));
    }

    /** Keyed by session like scoring-service does, so one session's records keep their order on one partition. */
    private void send(UUID sessionId, String type, String value) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(ScoreUpdated.TOPIC, sessionId.toString(), value);
        record.headers().add(EventHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    private static long version(Map<String, Object> leaderboard) {
        return ((Number) leaderboard.get("version")).longValue();
    }

    private StompSession connect(String token) throws Exception {
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

    /** SUBSCRIBE is asynchronous: wait until the server has registered it before anything is sent. */
    private void awaitSubscribers(String destination) {
        await().atMost(WAIT).until(() ->
                !userRegistry.findSubscriptions(s -> s.getDestination().equals(destination)).isEmpty());
    }

    private static Map<String, Object> next(BlockingQueue<Map<String, Object>> queue) throws InterruptedException {
        Map<String, Object> message = queue.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(message).as("a leaderboard within " + WAIT).isNotNull();
        return message;
    }

    private Session newSession() {
        Session created = Session.create(RoomCode.random(RANDOM), UUID.randomUUID(), UUID.randomUUID(), "Capitals",
                List.of(new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0)),
                Instant.now());
        sessions.add(created);
        liveState.initialize(created.id(), created.status());
        return created;
    }

    private static WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
        return client;
    }
}
