package dev.nibin.buzzer.session.config;

import dev.nibin.buzzer.session.api.LogContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Adds to Boot's Spring MVC setup without replacing it (no @EnableWebMvc). */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    private final LogContextInterceptor logContext;

    public WebMvcConfig(LogContextInterceptor logContext) {
        this.logContext = logContext;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(logContext);
    }
}
