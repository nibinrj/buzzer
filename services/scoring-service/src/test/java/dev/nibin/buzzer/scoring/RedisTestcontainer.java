package dev.nibin.buzzer.scoring;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Redis 7 for the leaderboards. A plain GenericContainer: Testcontainers has no official Redis module.
 * {@code @ServiceConnection} points spring.data.redis.* at it. The name is required: for a @Bean, Boot must pick
 * the connection type while registering bean definitions, before the container (and its image name) exists.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainer {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
    }
}
