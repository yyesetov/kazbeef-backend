package kz.kazbeef.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Максимум баллов по каждому фактору риска; в сумме 100. */
@ConfigurationProperties(prefix = "kazbeef.risk")
public record RiskProperties(
        @DefaultValue("300") double borderZoneKm,
        @DefaultValue("25") int severityPoints,
        @DefaultValue("35") int proximityPoints,
        @DefaultValue("15") int recencyPoints,
        @DefaultValue("10") int statusPoints,
        @DefaultValue("10") int magnitudePoints,
        @DefaultValue("5") int trendPoints,
        @DefaultValue("0 30 4 * * *") String recomputeCron
) {
}
