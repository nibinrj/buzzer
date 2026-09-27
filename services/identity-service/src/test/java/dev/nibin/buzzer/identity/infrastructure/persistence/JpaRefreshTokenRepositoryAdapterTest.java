package dev.nibin.buzzer.identity.infrastructure.persistence;

import dev.nibin.buzzer.identity.domain.RefreshToken;
import dev.nibin.buzzer.identity.domain.RefreshToken.Status;
import dev.nibin.buzzer.identity.domain.RefreshTokenSecret;
import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flyway V2 against a real PostgreSQL 16: constraints, the atomic updates, and a real race. */
@PersistenceTest
class JpaRefreshTokenRepositoryAdapterTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Duration TTL = Duration.ofDays(7);

    @Autowired
    private JpaRefreshTokenRepositoryAdapter adapter;

    @Autowired
    private RefreshTokenJpaRepository jpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void addedTokenIsFoundByItsHash() {
        RefreshToken token = newToken(newUser(), UUID.randomUUID(), NOW);

        adapter.add(token);
        entityManager.clear();

        RefreshToken found = adapter.findByTokenHash(token.tokenHash()).orElseThrow();
        assertThat(found.id()).isEqualTo(token.id());
        assertThat(found.userId()).isEqualTo(token.userId());
        assertThat(found.familyId()).isEqualTo(token.familyId());
        assertThat(found.createdAt()).isEqualTo(NOW);
        assertThat(found.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(found.status(NOW)).isEqualTo(Status.ACTIVE);
        assertThat(adapter.findByTokenHash(RefreshTokenSecret.hash("unknown"))).isEmpty();
    }

    @Test
    void markUsedSucceedsOnceThenFails() {
        RefreshToken token = newToken(newUser(), UUID.randomUUID(), NOW);
        adapter.add(token);

        assertThat(adapter.markUsed(token.id(), NOW.plusSeconds(1))).isTrue();
        assertThat(adapter.markUsed(token.id(), NOW.plusSeconds(2))).isFalse();

        RefreshToken reloaded = adapter.findByTokenHash(token.tokenHash()).orElseThrow();
        assertThat(reloaded.usedAt()).contains(NOW.plusSeconds(1));
        assertThat(reloaded.status(NOW.plusSeconds(3))).isEqualTo(Status.USED);
    }

    @Test
    void markUsedFailsForExpiredAndRevokedTokens() {
        UUID userId = newUser();
        RefreshToken expired = newToken(userId, UUID.randomUUID(), NOW.minus(TTL).minusSeconds(1));
        RefreshToken revoked = newToken(userId, UUID.randomUUID(), NOW);
        adapter.add(expired);
        adapter.add(revoked);
        adapter.revokeFamily(revoked.familyId(), NOW);

        assertThat(adapter.markUsed(expired.id(), NOW)).isFalse();
        assertThat(adapter.markUsed(revoked.id(), NOW)).isFalse();
    }

    @Test
    void revokeFamilyRevokesOnlyThatFamilyAndCountsOnlyNewRevocations() {
        UUID userId = newUser();
        UUID family = UUID.randomUUID();
        UUID otherFamily = UUID.randomUUID();
        RefreshToken first = newToken(userId, family, NOW);
        RefreshToken rotated = newToken(userId, family, NOW);
        RefreshToken otherSession = newToken(userId, otherFamily, NOW);
        adapter.add(first);
        adapter.add(rotated);
        adapter.add(otherSession);

        assertThat(adapter.revokeFamily(family, NOW.plusSeconds(1))).isEqualTo(2);
        assertThat(adapter.revokeFamily(family, NOW.plusSeconds(2))).isZero();

        assertThat(adapter.findByTokenHash(first.tokenHash()).orElseThrow().revokedAt()).contains(NOW.plusSeconds(1));
        assertThat(adapter.findByTokenHash(rotated.tokenHash()).orElseThrow().status(NOW)).isEqualTo(Status.REVOKED);
        assertThat(adapter.findByTokenHash(otherSession.tokenHash()).orElseThrow().status(NOW)).isEqualTo(Status.ACTIVE);
    }

    @Test
    void duplicateHashIsRejected() {
        UUID userId = newUser();
        RefreshToken token = newToken(userId, UUID.randomUUID(), NOW);
        adapter.add(token);
        RefreshToken sameHash = RefreshToken.issue(userId, UUID.randomUUID(), token.tokenHash(), NOW, TTL);

        assertThatThrownBy(() -> adapter.add(sameHash)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsAHashThatIsNotLowercaseHex() {
        // Bypasses the domain's own check: the CHECK constraint must still hold.
        RefreshTokenJpaEntity rawValue = new RefreshTokenJpaEntity(UUID.randomUUID(), newUser(), UUID.randomUUID(),
                "RAW-TOKEN-VALUE-SHOULD-NEVER-BE-STORED-" + "x".repeat(25), NOW, NOW.plus(TTL), null, null);

        assertThatThrownBy(() -> jpaRepository.saveAndFlush(rawValue))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheUserDeletesTheirTokens() {
        UUID userId = newUser();
        RefreshToken token = newToken(userId, UUID.randomUUID(), NOW);
        adapter.add(token);

        userJpaRepository.deleteById(userId);
        userJpaRepository.flush();
        entityManager.clear();

        assertThat(adapter.findByTokenHash(token.tokenHash())).isEmpty();
    }

    /**
     * Two transactions race to mark the same token used. The first UPDATE takes the row lock and holds it
     * (its transaction stays open for HOLD). The second UPDATE must wait for that lock, then Postgres
     * re-checks "used_at IS NULL" against the committed row and updates nothing.
     * <p>
     * No test transaction here: each thread needs its own, and they must see each other's commits.
     * Rows committed by this test stay in the database, so it uses a fresh user and token.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentMarkUsedHasExactlyOneWinnerAndTheLoserWaitsForTheRowLock() throws Exception {
        Duration hold = Duration.ofMillis(500);
        TransactionTemplate inTransaction = new TransactionTemplate(transactionManager);
        RefreshToken token = inTransaction.execute(status -> {
            RefreshToken t = newToken(newUser(), UUID.randomUUID(), NOW);
            adapter.add(t);
            return t;
        });

        CountDownLatch firstHasUpdated = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = threads.submit(() -> inTransaction.execute(status -> {
                boolean won = adapter.markUsed(token.id(), NOW.plusSeconds(1));
                firstHasUpdated.countDown();
                sleep(hold); // commit later: the row stays locked meanwhile
                return won;
            }));
            Future<Attempt> second = threads.submit(() -> {
                firstHasUpdated.await();
                long start = System.nanoTime();
                boolean won = inTransaction.execute(status -> adapter.markUsed(token.id(), NOW.plusSeconds(2)));
                return new Attempt(won, Duration.ofNanos(System.nanoTime() - start));
            });

            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            Attempt loser = second.get(10, TimeUnit.SECONDS);
            assertThat(loser.won()).isFalse();
            // It could only finish after the first transaction committed.
            assertThat(loser.took()).isGreaterThanOrEqualTo(hold.dividedBy(2));
        } finally {
            threads.shutdownNow();
        }

        RefreshToken stored = inTransaction.execute(status -> adapter.findByTokenHash(token.tokenHash()).orElseThrow());
        assertThat(stored.usedAt()).contains(NOW.plusSeconds(1));
    }

    private record Attempt(boolean won, Duration took) {
    }

    private UUID newUser() {
        User user = User.register("user-" + UUID.randomUUID() + "@test.dev", "$2a$10$abcdefghijklmnopqrstuv",
                Set.of(Role.HOST), NOW);
        userJpaRepository.saveAndFlush(UserMapper.toEntity(user));
        return user.id();
    }

    private static RefreshToken newToken(UUID userId, UUID familyId, Instant createdAt) {
        return RefreshToken.issue(userId, familyId, RefreshTokenSecret.hash(RefreshTokenSecret.generate()),
                createdAt, TTL);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
