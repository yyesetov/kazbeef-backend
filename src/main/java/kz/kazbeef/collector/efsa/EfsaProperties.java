package kz.kazbeef.collector.efsa;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "kazbeef.efsa")
public record EfsaProperties(
        /* RSS-ленты EFSA */
        @DefaultValue({"https://www.efsa.europa.eu/en/publications/rss", "https://www.efsa.europa.eu/en/press/rss"}) List<String> feeds,
        @DefaultValue("2s") Duration requestDelay
) {
}
