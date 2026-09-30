package dev.nibin.buzzer.session.api.websocket;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.TestTokens;
import dev.nibin.buzzer.session.application.JoinSession;
import dev.nibin.buzzer.session.application.LogContext;
import dev.nibin.buzzer.session.application.SubmitAnswer;
import dev.nibin.buzzer.session.domain.Answer;
import dev.nibin.buzzer.session.domain.AnswerRepository;
import dev.nibin.buzzer.session.domain.LiveState;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Real STOMP clients over a real WebSocket against the running app (random port), with real signed tokens.
 * Covers the game (the host advances, players receive each question without its answer, answer and get acked,
 * the host reveals) and every refusal of the two interceptors.
 */
@ApiIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SessionWebSocketTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String ACKS = "/user/queue/answer-ack";

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
    private AnswerRepository answers;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private LoggingSystem loggingSystem;

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
        awaitUserQueue(host, "/user/queue/errors", 1);

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

    // --- answers and reveal (batch 4.3) ---

    @Test
    void anAnswerIsAckedOnlyOnTheConnectionThatSentItAndRecorded() throws Exception {
        StompSession adaPhone = connect(guestToken(ada));
        StompSession adaLaptop = connect(guestToken(ada));
        BlockingQueue<Map<String, Object>> phoneAcks = subscribe(adaPhone, ACKS);
        BlockingQueue<Map<String, Object>> laptopAcks = subscribe(adaLaptop, ACKS);
        startAndAwaitFirstQuestion(adaPhone);
        awaitUserQueue(ada, ACKS, 2);

        adaPhone.send(command("answer"), answer(0, 0)); // Paris: correct

        assertThat(next(phoneAcks)).containsEntry("accepted", true).containsEntry("seq", 1)
                .containsEntry("reason", null).containsEntry("questionId", questionId(0).toString());
        assertThat(laptopAcks.poll(300, TimeUnit.MILLISECONDS)).isNull();
        Answer recorded = answers.find(session.id(), questionId(0), playerIdOf(ada)).orElseThrow();
        assertThat(recorded.correct()).isTrue();
        assertThat(recorded.correctRank()).isEqualTo(1);
        assertThat(recorded.seq()).isEqualTo(1);
    }

    @Test
    void aSecondAnswerIsADuplicateAndTheNextPlayerIsSecondInLine() throws Exception {
        StompSession adaConnection = connect(guestToken(ada));
        StompSession bobConnection = connect(guestToken(bob));
        BlockingQueue<Map<String, Object>> adaAcks = subscribe(adaConnection, ACKS);
        BlockingQueue<Map<String, Object>> bobAcks = subscribe(bobConnection, ACKS);
        startAndAwaitFirstQuestion(adaConnection);
        awaitUserQueue(ada, ACKS, 1);
        awaitUserQueue(bob, ACKS, 1);

        adaConnection.send(command("answer"), answer(0, 1));
        assertThat(next(adaAcks)).containsEntry("accepted", true).containsEntry("seq", 1);
        bobConnection.send(command("answer"), answer(0, 0));
        assertThat(next(bobAcks)).containsEntry("accepted", true).containsEntry("seq", 2);
        adaConnection.send(command("answer"), answer(0, 0));

        assertThat(next(adaAcks)).containsEntry("accepted", false).containsEntry("reason", "DUPLICATE")
                .containsEntry("seq", 1);
        assertThat(answers.find(session.id(), questionId(0), playerIdOf(ada)).orElseThrow().optionIndex())
                .isEqualTo(1); // the first answer stands
    }

    /**
     * The line SubmitAnswer writes (DEBUG, switched on for this test only) is written on the inbound channel's
     * executor thread, not the thread the frame arrived on, and still carries the session and user
     * (StompLogContextInterceptor) and the player (SubmitAnswer). The token sent on CONNECT appears nowhere.
     */
    @Test
    void anAnswersLogLineCarriesSessionUserAndPlayerAndTheTokenIsNeverLogged(CapturedOutput output)
            throws Exception {
        String token = guestToken(ada);
        StompSession adaConnection = connect(token);
        BlockingQueue<Map<String, Object>> acks = subscribe(adaConnection, ACKS);
        startAndAwaitFirstQuestion(adaConnection);
        awaitUserQueue(ada, ACKS, 1);

        loggingSystem.setLogLevel(SubmitAnswer.class.getName(), LogLevel.DEBUG);
        try {
            adaConnection.send(command("answer"), answer(0, 0));
            assertThat(next(acks)).containsEntry("accepted", true);
        } finally {
            loggingSystem.setLogLevel(SubmitAnswer.class.getName(), null);
        }

        JsonNode line = logLines(output)
                .filter(json -> json.path("message").asString().startsWith("Answer ACCEPTED"))
                .filter(json -> json.path(LogContext.SESSION_ID).asString().equals(session.id().toString()))
                .findFirst().orElseThrow();
        assertThat(line.path(LogContext.USER_ID).asString()).isEqualTo(ada.toString());
        assertThat(line.path(LogContext.PLAYER_ID).asString()).isEqualTo(playerIdOf(ada).toString());
        // Not the Tomcat thread the frame arrived on (where preSend runs), yet the MDC is there.
        assertThat(line.path("process").path("thread").path("name").asString()).doesNotStartWith("http-nio");
        assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void revealTellsEveryoneTheCorrectOptionAndClosesTheQuestion() throws Exception {
        StompSession hostConnection = connect(TestTokens.host(host));
        StompSession adaConnection = connect(guestToken(ada));
        BlockingQueue<Map<String, Object>> adaReveals = subscribe(adaConnection, revealTopic());
        BlockingQueue<Map<String, Object>> adaAcks = subscribe(adaConnection, ACKS);
        BlockingQueue<Map<String, Object>> adaQuestions = subscribe(adaConnection, questionTopic());
        awaitSubscribers(revealTopic(), 1);
        awaitSubscribers(questionTopic(), 1);
        awaitUserQueue(ada, ACKS, 1);
        hostConnection.send(command("start"), "");
        next(adaQuestions);

        hostConnection.send(command("reveal"), "");

        assertThat(next(adaReveals)).containsEntry("index", 0).containsEntry("correctOption", 0)
                .containsEntry("questionId", questionId(0).toString());
        adaConnection.send(command("answer"), answer(0, 0));
        assertThat(next(adaAcks)).containsEntry("accepted", false).containsEntry("reason", "CLOSED")
                .containsEntry("seq", null);
        assertThat(liveState.find(session.id()).orElseThrow().questionOpen()).isFalse();
    }

    @Test
    void anUnreadableAnswerIsReportedOnTheErrorQueue() throws Exception {
        StompSession adaConnection = connect(guestToken(ada));
        BlockingQueue<Map<String, Object>> adaErrors = subscribe(adaConnection, "/user/queue/errors");
        awaitUserQueue(ada, "/user/queue/errors", 1);

        adaConnection.send(command("answer"), Map.of("questionId", "not-a-uuid", "optionId", 0));

        assertThat(next(adaErrors)).containsEntry("title", "Unreadable answer");
    }

    // --- refusals ---

    @Test
    void theHostCannotAnswer() throws Exception {
        ErrorCatcher errors = new ErrorCatcher();
        StompSession hostConnection = connect(TestTokens.host(host), errors);

        hostConnection.send(command("answer"), answer(0, 0));

        assertThat(errors.next()).isNotNull();
        awaitClosed(hostConnection);
    }

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

    /**
     * {@code count} subscriptions of this user to one of their queues (one per connection). Filtered by user:
     * disconnects of earlier tests' users are asynchronous.
     */
    private void awaitUserQueue(UUID user, String queue, int count) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (userRegistry.findSubscriptions(s -> s.getDestination().equals(queue)
                && s.getSession().getUser().getName().equals(user.toString())).size() < count) {
            if (System.nanoTime() > deadline) {
                fail("Expected " + user + " to subscribe to " + queue + " " + count + " time(s)");
            }
            Thread.sleep(20);
        }
    }

    /** The host starts the game; returns once this player connection has received question 0. */
    private void startAndAwaitFirstQuestion(StompSession player) throws Exception {
        BlockingQueue<Map<String, Object>> questions = subscribe(player, questionTopic());
        awaitSubscribers(questionTopic(), 1);
        connect(TestTokens.host(host)).send(command("start"), "");
        assertThat(next(questions)).containsEntry("index", 0);
    }

    private Map<String, Object> answer(int questionIndex, int option) {
        return Map.of("questionId", questionId(questionIndex).toString(), "optionId", option);
    }

    private UUID questionId(int index) {
        return session.questions().get(index).questionId();
    }

    /** The captured JSON log lines (application.yml: ECS on the console); anything else is skipped. */
    private static Stream<JsonNode> logLines(CapturedOutput output) {
        JsonMapper json = JsonMapper.builder().build();
        return output.getOut().lines().filter(line -> line.startsWith("{")).map(json::readTree);
    }

    private UUID playerIdOf(UUID user) {
        return players.find(session.id(), user).orElseThrow().playerId();
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

    private String revealTopic() {
        return "/topic/sessions/" + session.id() + "/reveal";
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
