package dev.nibin.buzzer.session.infrastructure.redis;

import java.util.UUID;

/**
 * Every Redis key of a session, in one place. The session id sits in literal braces: a Redis Cluster "hash tag".
 * In a cluster only the part inside {...} is hashed to pick the slot (the node), so all of a session's keys land
 * on one node. That is required: a Lua script may only touch keys of one slot (else Redis answers CROSSSLOT).
 * A single-node Redis ignores the braces.
 * <pre>
 * session:{S}:state          hash, see RedisLiveStateRepository
 * session:{S}:players        hash, see RedisLiveStateRepository
 * buzz:{S}:Q                 sorted set, see submit_answer.lua   (Q = question id, no braces)
 * buzz:{S}:Q:seq             counter
 * buzz:{S}:Q:correct         counter
 * </pre>
 */
final class SessionKeys {

    private SessionKeys() {
    }

    static String state(UUID sessionId) {
        return "session:" + tag(sessionId) + ":state";
    }

    static String players(UUID sessionId) {
        return "session:" + tag(sessionId) + ":players";
    }

    static String answered(UUID sessionId, UUID questionId) {
        return "buzz:" + tag(sessionId) + ":" + questionId;
    }

    static String answerSeq(UUID sessionId, UUID questionId) {
        return answered(sessionId, questionId) + ":seq";
    }

    static String correctCount(UUID sessionId, UUID questionId) {
        return answered(sessionId, questionId) + ":correct";
    }

    private static String tag(UUID sessionId) {
        return "{" + sessionId + "}";
    }
}
