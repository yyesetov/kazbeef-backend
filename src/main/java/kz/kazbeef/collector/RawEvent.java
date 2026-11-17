package kz.kazbeef.collector;

import kz.kazbeef.domain.EventStatus;
import kz.kazbeef.domain.TrustLevel;

import java.time.LocalDate;

/**
 * Запись источника в едином промежуточном формате, до нормализации.
 *
 * @param externalId  идентификатор записи в источнике (ключ идемпотентности вместе с кодом источника)
 * @param countryCode ISO2 или ISO3, если источник его отдаёт
 * @param species     виды животных как в источнике, через "; "
 * @param rawPayload  сырой ответ источника (JSON-строка)
 * @param trustLevel  уровень доверия записи, если он отличается от уровня источника; иначе {@code null}
 */
public record RawEvent(
        String externalId,
        String url,
        String diseaseName,
        String countryName,
        String countryCode,
        String region,
        Double latitude,
        Double longitude,
        LocalDate startDate,
        LocalDate reportDate,
        String species,
        Integer outbreaks,
        Integer susceptible,
        Integer cases,
        Integer deaths,
        EventStatus status,
        String title,
        String description,
        String rawPayload,
        TrustLevel trustLevel
) {
}
