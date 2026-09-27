package dev.nibin.buzzer.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** The service's single source of "now". Unit tests pass Clock.fixed(...) instead. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
