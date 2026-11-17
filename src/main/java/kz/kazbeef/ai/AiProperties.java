package kz.kazbeef.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kazbeef.ai")
public record AiProperties(
        String apiKey,
        @DefaultValue("claude-opus-5-5") String model,
        @DefaultValue("10") int batchSize,
        /* после стольких неудач событие больше не отправляется на обработку */
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("0 */10 * * * *") String cron
) {
    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
