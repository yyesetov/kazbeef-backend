package kz.kazbeef.collector.wahis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "kazbeef.wahis")
public record WahisProperties(
        @DefaultValue("https://wahis.woah.org") String baseUrl,
        /* ISO3 стран, по которым собираем все болезни */
        @DefaultValue({"KAZ", "RUS", "CHN", "KGZ", "UZB", "TKM", "MNG", "BLR", "ARM", "AZE", "TJK", "MDA", "UKR", "BRA", "PRY"}) List<String> countries,
        @DefaultValue("100") int pageSize,
        @DefaultValue("1500ms") Duration requestDelay,
        @DefaultValue("365d") Duration initialLookback,
        @DefaultValue("3d") Duration overlap,
        /* защита от бесконечной пагинации */
        @DefaultValue("200") int maxPages
) {
}
