package dev.nibin.buzzer.identity.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document's title (served at /v3/api-docs, browsable at /swagger-ui.html). Every endpoint here is public:
 * this service is where tokens come from, so there is no "Authorize" step.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("identity-service API")
                .version("v1")
                .description("Accounts and tokens: register, log in, refresh (rotating refresh tokens), log out, guest "
                        + "tokens for players, and the JWKS other services verify access tokens with."));
    }
}
