package kz.kazbeef.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * CORS для интерфейса на другом домене (например, Cloudflare Workers).
 * Адреса интерфейса — в KAZBEEF_CORS_ORIGINS через запятую; пусто — CORS выключен
 * (интерфейс на том же домене через прокси nginx).
 */
@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${kazbeef.cors.origins:}") String origins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> allowed = Arrays.stream(origins.split(","))
                .map(String::strip)
                .map(s -> s.replaceAll("/+$", ""))
                .filter(s -> !s.isEmpty())
                .toList();
        if (allowed.isEmpty()) {
            return source;
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowed);
        // cookie сессии: вход и админка
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // имя файла выгрузки и пауза при превышении лимита чата
        config.setExposedHeaders(List.of("Content-Disposition", "Retry-After"));
        config.setMaxAge(Duration.ofHours(1));
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/admin/**", config);
        source.registerCorsConfiguration("/public/**", config);
        return source;
    }
}
