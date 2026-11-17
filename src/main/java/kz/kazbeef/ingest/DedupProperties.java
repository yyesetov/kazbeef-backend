package kz.kazbeef.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Пороги межисточниковой дедупликации.
 *
 * @param startWindowDays  допустимая разница дат начала у записей с координатами
 * @param distanceKm       допустимое расстояние между координатами
 * @param reportBeforeDays запись без координат ищет событие с датой отчёта не раньше, чем за столько дней до своей
 * @param reportAfterDays  ...и не позже, чем через столько дней после
 */
@ConfigurationProperties(prefix = "kazbeef.dedup")
public record DedupProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("7") int startWindowDays,
        @DefaultValue("25") double distanceKm,
        @DefaultValue("5") int reportBeforeDays,
        @DefaultValue("1") int reportAfterDays
) {
}
