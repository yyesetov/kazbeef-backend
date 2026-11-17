package kz.kazbeef.api;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/**
 * Общий фильтр событий: один и тот же набор параметров у списка, карты, статистики и экспорта.
 *
 * @param countries    ISO3
 * @param diseases     id болезней
 * @param groups       группы поражённых видов: CATTLE, SMALL_RUMINANT, OTHER
 * @param sources      коды источников
 * @param trust        confirmed / signal / analytics
 * @param status       new / ongoing / closed
 * @param from         дата события с (включительно)
 * @param to           дата события по (включительно)
 * @param ruminantOnly только значимые для КРС/МРС; по умолчанию true
 * @param q            поиск по тексту
 * @param minRisk      минимальный балл риска
 */
public record EventFilter(
        List<String> countries,
        List<Long> diseases,
        List<String> groups,
        List<String> sources,
        List<String> trust,
        List<String> status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        Boolean ruminantOnly,
        String q,
        Integer minRisk
) {
    public boolean ruminantOnlyOrDefault() {
        return ruminantOnly == null || ruminantOnly;
    }
}
