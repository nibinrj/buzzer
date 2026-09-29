package dev.nibin.buzzer.session.domain;

import java.util.UUID;

/**
 * Port (Redis): the version of the last leaderboard pushed for each session, shared by every session-service
 * instance, so it survives a Kafka rebalance or a restart that hands the partition to another instance.
 */
public interface LeaderboardVersions {

    /**
     * Stores {@code version} as the session's last pushed one, unless a newer one is already stored. One atomic step,
     * so two callers can't both pass the check with versions in the wrong order.
     *
     * @return true if it isn't older than the stored one (an equal version is accepted: pushing it again is
     *         harmless), false if it is older and must not be pushed
     */
    boolean acceptIfNotOlder(UUID sessionId, long version);
}
