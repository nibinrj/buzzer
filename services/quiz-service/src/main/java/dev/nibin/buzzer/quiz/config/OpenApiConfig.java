package dev.nibin.buzzer.quiz.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document's title, and the bearer-token scheme behind Swagger UI's "Authorize" button: paste a HOST
 * access token from identity-service (POST /api/auth/login) and every call carries it. /internal/** is left out of
 * the document (springdoc.paths-to-exclude): it's service-to-service only.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("quiz-service API")
                        .version("v1")
                        .description("Quiz authoring for hosts: create a quiz, add and replace questions, publish. "
                                + "Requires the HOST role; a quiz is visible to its owner only."))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
