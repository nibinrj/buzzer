package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.application.SessionBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * The SessionBroadcaster: PUBLISHes each event on one Redis channel instead of delivering it itself. Every instance,
 * this one included, is subscribed (RedisRelayListener) and delivers the event to the clients connected to IT. So a
 * player receives the question whichever instance their WebSocket landed on.
 * <p>
 * It deliberately does NOT also deliver locally: its own listener receives the message too, and doing both would
 * show this instance's players everything twice.
 * <p>
 * Fire-and-forget (at-most-once): Redis pub/sub stores nothing. An instance that isn't subscribed at that moment
 * misses the message, and a failed PUBLISH is logged and dropped. The change itself is already committed (callers
 * broadcast after the commit), and a client that suspects a gap reloads GET /api/sessions/{id}/state.
 */
@Component
class RedisRelayBroadcaster implements SessionBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RedisRelayBroadcaster.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    RedisRelayBroadcaster(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void questionShown(QuestionShown question) {
        publish(RelayMessage.of(question), question.sessionId());
    }

    @Override
    public void statusChanged(StatusChanged change) {
        publish(RelayMessage.of(change), change.sessionId());
    }

    private void publish(RelayMessage message, UUID sessionId) {
        try {
            redis.convertAndSend(RedisRelayListener.CHANNEL, json.writeValueAsString(message));
        } catch (DataAccessException e) {
            // The command already succeeded; failing it now would only make the host retry a committed change.
            log.warn("Broadcast for session {} not published; clients catch up via /state: {}", sessionId,
                    e.getMessage());
        }
    }
}
