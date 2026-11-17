package kz.kazbeef.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "kazbeef")
public record KazbeefProperties(
        Admin admin,
        Http http,
        Map<String, Schedule> collectors
) {

    public KazbeefProperties {
        collectors = collectors == null ? Map.of() : Map.copyOf(collectors);
    }

    public record Admin(@DefaultValue("admin") String username, String password) {
    }

    /** Общие настройки HTTP-доступа к источникам (ФТ-7). */
    public record Http(
            String userAgent,
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("60s") Duration readTimeout,
            @DefaultValue("4") int maxAttempts,
            @DefaultValue("2s") Duration initialBackoff
    ) {
    }

    /** Расписание сборщика. */
    public record Schedule(
            @DefaultValue("true") boolean enabled,
            String cron,
            @DefaultValue("UTC") String zone
    ) {
    }
}
