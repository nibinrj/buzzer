package dev.nibin.buzzer.session.api.websocket;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.TestTokens;
import dev.nibin.buzzer.session.application.JoinSession;
import dev.nibin.buzzer.session.domain.LiveState;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
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

import java.lang.reflect.Type;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Real STOMP clients over a real WebSocket against the running app (random port), with real signed tokens.
 * Covers the batch's promise (the host advances, two players receive the question, without its answer) and every
 * refusal of the two interceptors.
 */
@ApiIntegrationTest
class SessionWebSocketTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration WAIT = Duration.ofSeconds(5);

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

    private final WebSocketStompClient stompClient = stompClient();
    private final List<StompSession> connections = new ArrayList<>();

    private Session session;
    private UUID host;
    private UUID ada;
    private UUID bob;

    @BeforeEach
    void setUp() {
        // Other test classes reset WireMock; the JWKS must be there when the first token is verified.
        identityJwks.stubFor(get(urlEqualTo(TestTokens.JWKS_PATH)).willReturn(okJson(TestTokens.jwks())));
        host = UUID.randomUUID();
        session = newSession(host);
        ada = join("Ada");
        bob = join("Bob");
    }

    @AfterEach
    void disconnect() {
        connections.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
    }

    // --- the game ---

    @Test
    void theHostStartsAndBothPlayersReceiveTheFirstQuestionWithoutItsAnswer() throws Exception {
        StompSession hostConnection = connect(TestTokens.host(host));
        BlockingQueue<Map<String, Object>> adaQuestions = subscribe(connect(guestToken(ada)), questionTopic());
        BlockingQueue<Map<String, Object>> bobQuestions = subscribe(connect(guestToken(bob)), questionTopic());
        awaitSubscribers(questionTopic(), 2);

        hostConnection.send(command("start"), "");

        Map<String, Object> toAda = next(adaQuestions);
        Map<String, Object> toBob = next(bobQuestions);
        assertThat(toAda).isEqualTo(toBob);
        assertThat(toAda).containsEntry("index", 0).containsEntry("text", "Capital of France?")
                .containsEntry("options", List.of("Paris", "Lyon")).containsEntry("timeLimitSeconds", 20)
                .containsEntry("questionId", session.questions().get(0).questionId().toString());
        assertThat(toAda.toString()).doesNotContainIgnoringCase("correct");

        LiveState live = liveState.find(session.id()).orElseThrow();
        assertThat(live.status()).isEqualTo(Session.Status.IN_PROGRESS);
        assertThat(live.currentQuestionIndex()).contains(0);
        assertThat(live.questionDeadline()).contains(Instant.parse((String) toAda.get("deadline")));
        assertThat(sessions.findById(session.id()).orElseThrow().status()).isEqualTo(Session.Status.IN_PROGRESS);
    }

    @Test
    void nextBringsTheSecondQuestionAndAfterTheLastOneTheHostIsToldWhy() throws Exception {
        StompSession hostConnection = connect(TestTokens.host(host));
        BlockingQueue<Map<String, Object>> hostErrors = subscribe(hostConnection, "/user/queue/errors");
        BlockingQueue<Map<String, Object>> adaQuestions = subscribe(connect(guestToken(ada)), questionTopic());
        awaitSubscribers(questionTopic(), 1);
        awaitErrorQueueOf(host);

        hostConnection.send(command("start"), "");
        assertThat(next(adaQuestions)).containsEntry("index", 0);
        hostConnection.send(command("next"), "");
        assertThat(next(adaQuestions)).containsEntry("index", 1).containsEntry("text", "Capital of Italy?");

        hostConnection.send(command("next"), "");

        assertThat(next(hostErrors)).containsEntry("title", "Not possible now")
                .containsEntry("detail", "That was the last question. End the session.");
        assertThat(adaQuestions.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void endIsBroadcastOnTheStatusTopic() throws Exception {
        StompSession hostConnection = connect(TestTokens.host(host));
        BlockingQueue<Map<String, Object>> adaStatus = subscribe(connect(guestToken(ada)), statusTopic());
        awaitSubscribers(statusTopic(), 1);

        hostConnection.send(command("start"), "");
        assertThat(next(adaStatus)).containsEntry("status", "IN_PROGRESS");
        hostConnection.send(command("end"), "");

        assertThat(next(adaStatus)).containsEntry("status", "ENDED");
        LiveState live = liveState.find(session.id()).orElseThrow();
        assertThat(live.status()).isEqualTo(Session.Status.ENDED);
        assertThat(live.currentQuestionIndex()).isEmpty();
        assertThat(sessions.findById(session.id()).orElseThrow().status()).isEqualTo(Session.Status.ENDED);
    }

    // --- refusals ---

    @Test
    void connectWithoutATokenIsRefused() throws Exception {
        assertConnectRefused(null);
    }

    @Test
    void connectWithAForgedTokenIsRefused() throws Exception {
        assertConnectRefused("not.a.jwt");
    }

    @Test
    void someoneWhoDidNotJoinCannotSubscribe() throws Exception {
        ErrorCatcher errors = new ErrorCatcher();
        StompSession outsider = connect(guestToken(UUID.randomUUID()), errors);

        subscribe(outsider, questionTopic());

        assertThat(errors.next()).isNotNull();
        assertThat(userRegistry.findSubscriptions(s -> s.getDestination().equals(questionTopic()))).isEmpty();
    }

    @Test
    void aPlayerCannotSendHostCommands() throws Exception {
        ErrorCatcher errors = new ErrorCatcher();
        StompSession player = connect(guestToken(ada), errors);

        player.send(command("start"), "");

        assertThat(errors.next()).isNotNull();
        awaitClosed(player); // Spring answers a refused frame with ERROR, then closes the WebSocket (1002)
        assertThat(sessions.findById(session.id()).orElseThrow().status()).isEqualTo(Session.Status.LOBBY);
    }

    @Test
    void anotherHostCannotStartThisSession() throws Exception {
        ErrorCatcher errors = new ErrorCatcher();
        StompSession otherHost = connect(TestTokens.host(UUID.randomUUID()), errors);

        otherHost.send(command("start"), "");

        assertThat(errors.next()).isNotNull();
        assertThat(sessions.findById(session.id()).orElseThrow().status()).isEqualTo(Session.Status.LOBBY);
    }

    @Test
    void nobodyMaySendStraightToATopicNotEvenTheHost() throws Exception {
        BlockingQueue<Map<String, Object>> adaQuestions = subscribe(connect(guestToken(ada)), questionTopic());
        awaitSubscribers(questionTopic(), 1);
        ErrorCatcher errors = new ErrorCatcher();
        StompSession hostConnection = connect(TestTokens.host(host), errors);

        hostConnection.send(questionTopic(), Map.of("index", 0, "text", "Fake question"));

        assertThat(errors.next()).isNotNull();
        assertThat(adaQuestions.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    // --- helpers ---

    private void assertConnectRefused(String token) throws Exception {
        ErrorCatcher errors = new ErrorCatcher();
        CompletableFuture<StompSession> connecting = stompClient.connectAsync(url(), new WebSocketHttpHeaders(),
                connectHeaders(token), errors);

        assertThat(errors.next()).isNotNull();
        assertThat(connecting.isDone() && !connecting.isCompletedExceptionally()).isFalse();
    }

    private StompSession connect(String token) throws Exception {
        return connect(token, new ErrorCatcher());
    }

    private StompSession connect(String token, ErrorCatcher handler) throws Exception {
        StompSession connection = stompClient.connectAsync(url(), new WebSocketHttpHeaders(), connectHeaders(token),
                handler).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        connections.add(connection);
        return connection;
    }

    private static StompHeaders connectHeaders(String token) {
        StompHeaders headers = new StompHeaders();
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }
        return headers;
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

    /**
     * SUBSCRIBE is asynchronous: wait until the server has registered it, or a message sent right after could
     * arrive before the subscription exists. SimpUserRegistry is the server's own list of subscriptions.
     */
    private void awaitSubscribers(String destination, int count) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (userRegistry.findSubscriptions(s -> s.getDestination().equals(destination)).size() < count) {
            if (System.nanoTime() > deadline) {
                fail("Expected " + count + " subscriptions to " + destination);
            }
            Thread.sleep(20);
        }
    }

    private static void awaitClosed(StompSession connection) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (connection.isConnected()) {
            if (System.nanoTime() > deadline) {
                fail("Expected the server to close the connection");
            }
            Thread.sleep(20);
        }
    }

    /** The host's own error queue. Filtered by user: disconnects of earlier tests' hosts are asynchronous. */
    private void awaitErrorQueueOf(UUID user) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (userRegistry.findSubscriptions(s -> s.getDestination().equals("/user/queue/errors")
                && s.getSession().getUser().getName().equals(user.toString())).isEmpty()) {
            if (System.nanoTime() > deadline) {
                fail("Expected " + user + " to subscribe to /user/queue/errors");
            }
            Thread.sleep(20);
        }
    }

    private static Map<String, Object> next(BlockingQueue<Map<String, Object>> queue) throws InterruptedException {
        Map<String, Object> message = queue.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(message).as("a message within " + WAIT).isNotNull();
        return message;
    }

    private String url() {
        return "ws://localhost:" + port + "/ws";
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

    private String guestToken(UUID user) {
        return TestTokens.guest(user, "Player");
    }

    private Session newSession(UUID hostId) {
        Session created = Session.create(RoomCode.random(RANDOM), UUID.randomUUID(), hostId, "Capitals",
                List.of(new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0),
                        new SessionQuestion(UUID.randomUUID(), "Capital of Italy?", 15, List.of("Milan", "Rome"), 1)),
                Instant.now());
        sessions.add(created);
        liveState.initialize(created.id(), created.status());
        return created;
    }

    /** JSON for messages; plain text for the (empty) command payloads and for ERROR frames. */
    private static WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
        return client;
    }

    /** The connection-level handler. Spring's client hands it ERROR frames: those are what refusals look like. */
    private static final class ErrorCatcher extends StompSessionHandlerAdapter {

        private final BlockingQueue<String> errors = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add(String.valueOf(headers.getFirst("message")));
        }

        String next() throws InterruptedException {
            return errors.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
