package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.infrastructure.websocket.LocalStompDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * This instance's end of the relay: receives every event any instance published on {@link #CHANNEL} and hands it
 * to the local broker, which delivers it to the clients connected HERE. Subscribed by RedisRelayConfig.
 * <p>
 * A message it can't read is logged (without its content) and dropped: one bad message must not stop the relay.
 */
@Component
public class RedisRelayListener implements MessageListener {

    /** One channel for all sessions: every instance needs every session's events anyway. */
    public static final String CHANNEL = "session-broadcast";

    private static final Logger log = LoggerFactory.getLogger(RedisRelayListener.class);

    private final LocalStompDelivery delivery;
    private final JsonMapper json;

    public RedisRelayListener(LocalStompDelivery delivery, JsonMapper json) {
        this.delivery = delivery;
        this.json = json;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        RelayMessage relayed;
        try {
            relayed = json.readValue(message.getBody(), RelayMessage.class);
        } catch (JacksonException e) {
            log.warn("Dropped an unreadable relay message ({})", e.getClass().getSimpleName());
            return;
        }
        if (relayed.questionShown() != null) {
            delivery.questionShown(relayed.questionShown());
        } else if (relayed.statusChanged() != null) {
            delivery.statusChanged(relayed.statusChanged());
        } else if (relayed.answerRevealed() != null) {
            delivery.answerRevealed(relayed.answerRevealed());
        } else {
            log.warn("Dropped an empty relay message");
        }
    }
}
