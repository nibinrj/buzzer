package dev.nibin.buzzer.session.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.random.RandomGenerator;

@Configuration(proxyBeanMethods = false)
public class ClockAndRandomConfig {

    /** The service's single source of "now". Unit tests pass Clock.fixed(...) instead. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Randomness for room codes. SecureRandom so a code can't be guessed from codes seen before; a room code
     * is what lets someone into a game. Unit tests pass a seeded generator for repeatable codes.
     */
    @Bean
    RandomGenerator roomCodeRandom() {
        return new SecureRandom();
    }
}
