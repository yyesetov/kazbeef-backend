package kz.kazbeef.collector.iac;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "kazbeef.iac")
public record IacProperties(
        @DefaultValue("https://fsvps.gov.ru") String baseUrl,
        @DefaultValue("/jepizooticheskaja-situacija/iac/") String listPath,
        @DefaultValue("3s") Duration requestDelay,
        @DefaultValue("90d") Duration initialLookback,
        @DefaultValue("3d") Duration overlap,
        /* защита от бесконечной пагинации */
        @DefaultValue("20") int maxPages,
        /* брать только страны с признаком is_priority (Казахстан и соседи) */
        @DefaultValue("true") boolean priorityOnly
) {
}
