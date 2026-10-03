package dev.nibin.buzzer.session.api.websocket;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.CapturedSpans;
import dev.nibin.buzzer.session.TestTokens;
import dev.nibin.buzzer.session.application.JoinSession;
import dev.nibin.buzzer.session.application.LogContext;
import dev.nibin.buzzer.session.application.SubmitAnswer;
import dev.nibin.buzzer.session.config.OutboxConfig;
import dev.nibin.buzzer.session.domain.Answer;
import dev.nibin.buzzer.session.domain.AnswerRepository;
import dev.nibin.buzzer.session.domain.LiveState;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

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

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private CapturedSpans spans;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaAdmin kafkaAdmin;

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

    // --- tracing ---

    /**
     * One answer, one trace: the STOMP command opens it (no parent: browsers aren't traced), the outbox row stores
     * it, the publisher restores it on its own thread, and the Kafka record carries it on to scoring-service.
     */
    @Test
    void anAnswersTraceRunsFromTheStompCommandThroughTheOutboxIntoTheKafkaRecord() throws Exception {
        StompSession adaConnection = connect(guestToken(ada));
        BlockingQueue<Map<String, Object>> acks = subscribe(adaConnection, ACKS);
        startAndAwaitFirstQuestion(adaConnection);
        awaitUserQueue(ada, ACKS, 1);

        adaConnection.send(command("answer"), answer(0, 0));
        assertThat(next(acks)).containsEntry("accepted", true);

        UUID answerId = answers.find(session.id(), questionId(0), playerIdOf(ada)).orElseThrow().answerId();
        String stored = jdbc.sql("SELECT traceparent FROM outbox WHERE event_id = ?").param(answerId)
                .query(String.class).single();
        // W3C: version-traceId-parentSpanId-flags. Flags 03 here: bit 1 = sampled, bit 2 = "the trace id is random"
        // (Trace Context Level 2, set by OpenTelemetry Java). The publisher must keep the sampled bit.
        assertThat(stored).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        assertThat(Integer.parseInt(stored.split("-")[3], 16) & 1).as("sampled").isEqualTo(1);
        String traceId = stored.split("-")[1];

        assertThat(traceparentOnKafka(answerId).split("-")[1]).isEqualTo(traceId);
        await().atMost(WAIT).untilAsserted(() -> assertThat(spans.ofTrace(traceId)).extracting(SpanData::getName)
                .contains("STOMP /app/sessions/{sessionId}/answer", "outbox publish"));
        SpanData command = spans.ofTrace(traceId).stream()
                .filter(span -> span.getName().startsWith("STOMP")).findFirst().orElseThrow();
        assertThat(command.getParentSpanContext().isValid()).as("the command starts the trace").isFalse();
        assertThat(spans.ofTrace(traceId)).as("KafkaTemplate's send, inside the restored trace")
                .anyMatch(span -> span.getKind() == SpanKind.PRODUCER);
    }

    // --- metrics: each meter moves ---

    @Test
    void answersAreTimedByOutcomeEndToEndAndInTheController() throws Exception {
        StompSession adaConnection = connect(guestToken(ada));
        BlockingQueue<Map<String, Object>> acks = subscribe(adaConnection, ACKS);
        startAndAwaitFirstQuestion(adaConnection);
        awaitUserQueue(ada, ACKS, 1);
        Timer ackAccepted = timer(AckLatencyInterceptor.ACK_LATENCY, "ACCEPTED");
        Timer ackDuplicate = timer(AckLatencyInterceptor.ACK_LATENCY, "DUPLICATE");
        Timer handledAccepted = timer(AnswerController.HANDLING, "ACCEPTED");
        long accepted = ackAccepted.count();
        long duplicates = ackDuplicate.count();
        long handled = handledAccepted.count();

        adaConnection.send(command("answer"), answer(0, 0));
        assertThat(next(acks)).containsEntry("accepted", true);
        adaConnection.send(command("answer"), answer(0, 1));
        assertThat(next(acks)).containsEntry("reason", "DUPLICATE");

        // Recorded after the ack was handed to the socket, so possibly a moment after the client got it.
        await().atMost(WAIT).until(() -> ackDuplicate.count() == duplicates + 1);
        assertThat(ackAccepted.count()).isEqualTo(accepted + 1);
        assertThat(handledAccepted.count()).isEqualTo(handled + 1);
        // The whole round trip includes the controller's part, so it can't be shorter.
        assertThat(ackAccepted.max(TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(
                handledAccepted.max(TimeUnit.NANOSECONDS));
    }

    @Test
    void theConnectionsGaugeCountsThisInstancesOpenStompConnections() throws Exception {
        // Every test disconnects its clients afterwards: wait until those disconnects have been processed.
        await().atMost(WAIT).until(() -> gauge("buzzer.websocket.connections") == 0);

        StompSession phone = connect(guestToken(ada));
        connect(guestToken(ada)); // a second tab: a second connection, same user
        await().atMost(WAIT).until(() -> gauge("buzzer.websocket.connections") == 2);

        phone.disconnect();
        await().atMost(WAIT).until(() -> gauge("buzzer.websocket.connections") == 1);
    }

    @Test
    void theActiveSessionsGaugeFollowsAGameFromStartToEnd() throws Exception {
        double before = gauge("buzzer.sessions.active");
        StompSession hostConnection = connect(TestTokens.host(host));

        hostConnection.send(command("start"), "");
        await().atMost(WAIT).until(() -> gauge("buzzer.sessions.active") == before + 1);
        hostConnection.send(command("end"), "");
        await().atMost(WAIT).until(() -> gauge("buzzer.sessions.active") == before);
    }

    /** The outbox thread is stuck (as while Kafka is down): its backlog and oldest row's age rise, then drain. */
    @Test
    void theOutboxGaugesShowABacklogWhileThePublisherIsStuckAndDrainAfterwards() throws Exception {
        StompSession adaConnection = connect(guestToken(ada));
        BlockingQueue<Map<String, Object>> acks = subscribe(adaConnection, ACKS);
        startAndAwaitFirstQuestion(adaConnection);
        awaitUserQueue(ada, ACKS, 1);
        ThreadPoolTaskScheduler outbox = context.getBean(OutboxConfig.OUTBOX_SCHEDULER, ThreadPoolTaskScheduler.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        outbox.execute(() -> {
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(blocked.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).as("outbox thread blocked").isTrue();
            adaConnection.send(command("answer"), answer(0, 0)); // writes an outbox row nobody can send now
            assertThat(next(acks)).containsEntry("accepted", true);

            assertThat(gauge("buzzer.outbox.backlog")).isGreaterThanOrEqualTo(1);
            await().atMost(WAIT).until(() -> gauge("buzzer.outbox.oldest.unsent.age") > 0);
        } finally {
            release.countDown();
        }
        await().atMost(WAIT).until(() -> gauge("buzzer.outbox.backlog") == 0);
        assertThat(gauge("buzzer.outbox.oldest.unsent.age")).isZero();
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

    // --- answers and reveal ---

    /**
     * The outbox publisher's only thread is stuck, as it is for up to max.block.ms on every run while Kafka is down.
     * The game must not notice: answers are handled and acked on STOMP's own threads.
     * <p>
     * Red run (2026-10-01, before the fix): no ack within 5 s. Boot had given the STOMP channels the outbox's
     * scheduler as their executor, so every frame in and out waited behind the publisher.
     */
    @Test
    void anAnswerIsAckedWhileTheOutboxThreadIsBusy() throws Exception {
        StompSession adaConnection = connect(guestToken(ada));
        BlockingQueue<Map<String, Object>> acks = subscribe(adaConnection, ACKS);
        startAndAwaitFirstQuestion(adaConnection);
        awaitUserQueue(ada, ACKS, 1);
        ThreadPoolTaskScheduler outbox = context.getBean(OutboxConfig.OUTBOX_SCHEDULER, ThreadPoolTaskScheduler.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        outbox.execute(() -> {
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(blocked.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).as("outbox thread blocked").isTrue();

            adaConnection.send(command("answer"), answer(0, 0));

            assertThat(next(acks)).containsEntry("accepted", true).containsEntry("seq", 1);
        } finally {
            release.countDown();
        }
    }

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
        // Tracing puts the STOMP command's trace into MDC too: from a log line straight to its trace.
        assertThat(line.path("traceId").asString()).matches("[0-9a-f]{32}");
        assertThat(line.path("spanId").asString()).matches("[0-9a-f]{16}");
        // Not the Tomcat thread the frame arrived on (where preSend runs), yet the MDC is there.
        assertThat(line.path("process").path("thread").path("name").asString())
                .doesNotStartWith("http-nio").doesNotStartWith("outbox");
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

    /** The traceparent header of the AnswerSubmitted record for this event, read back from the topic. */
    private String traceparentOnKafka(UUID eventId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaAdmin.getConfigurationProperties().get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(AnswerSubmitted.TOPIC).stream()
                    .map(info -> new TopicPartition(AnswerSubmitted.TOPIC, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    Header traceparent = record.headers().lastHeader("traceparent");
                    if (record.value().contains(eventId.toString()) && traceparent != null) {
                        return new String(traceparent.value(), StandardCharsets.UTF_8);
                    }
                }
            }
        }
        return fail("No AnswerSubmitted with a traceparent for " + eventId);
    }

    private Timer timer(String name, String outcome) {
        return meters.get(name).tag("outcome", outcome).timer();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
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
