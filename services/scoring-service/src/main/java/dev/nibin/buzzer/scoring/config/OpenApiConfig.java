package dev.nibin.buzzer.scoring.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document's title, and the bearer-token scheme behind Swagger UI's "Authorize" button. Scores are
 * computed from Kafka events (described in docs/asyncapi.yaml); this API only reads them.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("scoring-service API")
                        .version("v1")
                        .description("Results of a session: the live top 10 while it runs and the final ranking after "
                                + "it ends. Scores come from session.answer-submitted events on Kafka."))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
