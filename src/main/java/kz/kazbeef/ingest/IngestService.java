package kz.kazbeef.ingest;

import kz.kazbeef.collector.RawEvent;
import kz.kazbeef.domain.Country;
import kz.kazbeef.domain.Disease;
import kz.kazbeef.domain.Event;
import kz.kazbeef.domain.EventRepository;
import kz.kazbeef.domain.EventSource;
import kz.kazbeef.domain.EventSourceRepository;
import kz.kazbeef.domain.EventStatus;
import kz.kazbeef.domain.Source;
import kz.kazbeef.domain.SourceRepository;
import kz.kazbeef.domain.TrustLevel;
import kz.kazbeef.normalize.CountryResolver;
import kz.kazbeef.normalize.DiseaseResolver;
import kz.kazbeef.risk.KazakhstanGeometry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Идемпотентная запись RawEvent: ключ (источник, внешний id). Повторный запуск не создаёт дублей (ФТ-2).
 * <p>
 * Межисточниковая дедупликация: если то же событие уже пришло из другого источника, новая запись
 * прикрепляется к нему как подтверждение (неосновная связь) и его поля не перезаписывает.
 * Совпадением считается та же болезнь и страна плюс одно из двух:
 * <ul>
 *   <li>у обеих записей есть координаты: начало в пределах окна дат и точки рядом;</li>
 *   <li>у подтверждённой записи нет координат (сводка): дата отчёта события в окне вокруг даты записи.</li>
 * </ul>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final SourceRepository sources;
    private final EventRepository events;
    private final EventSourceRepository eventSources;
    private final DiseaseResolver diseaseResolver;
    private final CountryResolver countryResolver;
    private final EventClassifier classifier;
    private final DedupProperties dedup;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public IngestService(SourceRepository sources, EventRepository events, EventSourceRepository eventSources,
                         DiseaseResolver diseaseResolver, CountryResolver countryResolver,
                         EventClassifier classifier, DedupProperties dedup,
                         JdbcTemplate jdbc, EntityManager entityManager) {
        this.sources = sources;
        this.events = events;
        this.eventSources = eventSources;
        this.diseaseResolver = diseaseResolver;
        this.countryResolver = countryResolver;
        this.classifier = classifier;
        this.dedup = dedup;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IngestOutcome ingest(String sourceCode, RawEvent raw) {
        Source source = sources.findByCode(sourceCode).orElseThrow();
        var existing = eventSources.findBySourceAndExternalId(source, raw.externalId());

        if (existing.isPresent()) {
            EventSource link = existing.get();
            // неосновная запись только подтверждает событие: его поля ведёт основной источник
            boolean changed = link.isPrimary() && apply(link.getEvent(), raw);
            changed |= set(link::getUrl, link::setUrl, raw.url());
            link.setRawPayload(raw.rawPayload());
            link.setFetchedAt(Instant.now());
            return changed ? IngestOutcome.UPDATED : IngestOutcome.UNCHANGED;
        }

        // доверие задаётся источником; запись может его только уточнить (сводка со ссылкой на СМИ)
        TrustLevel trust = raw.trustLevel() != null ? raw.trustLevel() : source.getTrustLevel();
        Disease disease = diseaseResolver.resolve(raw.diseaseName()).orElse(null);
        Country country = countryResolver.resolve(raw.countryCode(), raw.countryName()).orElse(null);

        Event duplicate = findDuplicate(source, raw, trust, disease, country);
        if (duplicate != null) {
            duplicate.addSource(link(source, raw, false));
            if (trust.ordinal() < duplicate.getTrustLevel().ordinal()) {
                duplicate.setTrustLevel(trust);
            }
            log.info("Запись {}/{} прикреплена к событию {} как подтверждение", sourceCode, raw.externalId(), duplicate.getId());
            return IngestOutcome.UPDATED;
        }

        Event event = new Event();
        event.setTrustLevel(trust);
        apply(event, raw);
        event.addSource(link(source, raw, true));
        events.save(event);
        absorbSummaries(event, source, raw, trust);
        return IngestOutcome.CREATED;
    }

    private Event findDuplicate(Source source, RawEvent raw, TrustLevel trust, Disease disease, Country country) {
        if (!dedup.enabled() || disease == null || country == null) {
            return null;
        }
        boolean located = raw.latitude() != null && raw.longitude() != null;
        if (located && raw.startDate() != null) {
            return events.findLocatedCandidates(disease, country,
                            raw.startDate().minusDays(dedup.startWindowDays()), raw.startDate().plusDays(dedup.startWindowDays()))
                    .stream()
                    .filter(e -> fromOtherSources(e, source))
                    .filter(e -> KazakhstanGeometry.haversineKm(raw.latitude(), raw.longitude(), e.getLatitude(), e.getLongitude())
                            <= dedup.distanceKm())
                    .findFirst().orElse(null);
        }
        if (!located && trust == TrustLevel.CONFIRMED && raw.reportDate() != null) {
            List<Event> candidates = events.findReportedCandidates(disease, country,
                            raw.reportDate().minusDays(dedup.reportBeforeDays()), raw.reportDate().plusDays(dedup.reportAfterDays()))
                    .stream()
                    .filter(e -> fromOtherSources(e, source) && e.getTrustLevel() == TrustLevel.CONFIRMED)
                    .toList();
            // предпочитаем структурированное событие с координатами
            return candidates.stream().filter(e -> e.getLatitude() != null).findFirst()
                    .orElse(candidates.isEmpty() ? null : candidates.getFirst());
        }
        return null;
    }

    /**
     * Обратный случай: сводка без координат пришла раньше структурированного события.
     * Её записи переносятся к новому событию, а пустое событие-сводка удаляется.
     */
    private void absorbSummaries(Event event, Source source, RawEvent raw, TrustLevel trust) {
        if (!dedup.enabled() || trust != TrustLevel.CONFIRMED || event.getLatitude() == null
                || event.getDisease() == null || event.getCountry() == null || raw.reportDate() == null) {
            return;
        }
        List<Event> summaries = events.findReportedCandidates(event.getDisease(), event.getCountry(),
                        raw.reportDate().minusDays(dedup.reportAfterDays()), raw.reportDate().plusDays(dedup.reportBeforeDays()))
                .stream()
                .filter(e -> !e.getId().equals(event.getId()) && e.getLatitude() == null
                        && e.getTrustLevel() == TrustLevel.CONFIRMED && fromOtherSources(e, source))
                .toList();
        if (summaries.isEmpty()) {
            return;
        }
        // перенос связей напрямую в БД: у коллекции источников включено удаление «сирот»
        events.flush();
        for (Event summary : summaries) {
            jdbc.update("UPDATE event_source SET event_id = ?, is_primary = FALSE WHERE event_id = ?", event.getId(), summary.getId());
            jdbc.update("DELETE FROM event WHERE id = ?", summary.getId());
            entityManager.detach(summary);
            log.info("Сводка {} объединена с событием {}", summary.getId(), event.getId());
        }
    }

    private static boolean fromOtherSources(Event e, Source source) {
        return e.getSources().stream().noneMatch(s -> s.getSource().getId().equals(source.getId()));
    }

    private static EventSource link(Source source, RawEvent raw, boolean primary) {
        EventSource link = new EventSource();
        link.setSource(source);
        link.setExternalId(raw.externalId());
        link.setUrl(raw.url());
        link.setRawPayload(raw.rawPayload());
        link.setFetchedAt(Instant.now());
        link.setPrimary(primary);
        return link;
    }

    /** Переносит поля записи в событие. Возвращает true, если что-то изменилось. */
    private boolean apply(Event e, RawEvent r) {
        boolean changed = false;
        if (set(e::getRawDiseaseName, e::setRawDiseaseName, r.diseaseName())) {
            e.setDisease(diseaseResolver.resolve(r.diseaseName()).orElse(null));
            changed = true;
        }
        if (set(e::getRawCountryName, e::setRawCountryName, r.countryName()) || e.getCountry() == null) {
            var country = countryResolver.resolve(r.countryCode(), r.countryName()).orElse(null);
            changed |= e.getCountry() != country;
            e.setCountry(country);
        }
        changed |= set(e::getRawSpecies, e::setRawSpecies, r.species());
        changed |= set(e::getRegion, e::setRegion, r.region());
        changed |= set(e::getLatitude, e::setLatitude, r.latitude());
        changed |= set(e::getLongitude, e::setLongitude, r.longitude());
        changed |= set(e::getStartDate, e::setStartDate, r.startDate());
        changed |= set(e::getReportDate, e::setReportDate, r.reportDate());
        changed |= set(e::getOutbreaks, e::setOutbreaks, r.outbreaks());
        changed |= set(e::getSusceptible, e::setSusceptible, r.susceptible());
        changed |= set(e::getCases, e::setCases, r.cases());
        changed |= set(e::getDeaths, e::setDeaths, r.deaths());
        changed |= set(e::getStatus, e::setStatus, r.status() == null ? EventStatus.NEW : r.status());
        boolean textChanged = set(e::getTitle, e::setTitle, r.title());
        textChanged |= set(e::getDescription, e::setDescription, r.description());
        if (textChanged) {
            // Оригинал изменился: перевод и резюме ИИ устарели, событие снова попадёт в очередь
            e.setTranslation(null);
            e.setAiSummary(null);
            e.setAiGenerated(false);
            e.setAiAttempts((short) 0);
            changed = true;
        }
        // после болезни и видов: набор видов, значимость для КРС/МРС и риск
        changed |= classifier.classify(e);
        return changed;
    }

    private static <T> boolean set(Supplier<T> getter, Consumer<T> setter, T value) {
        if (Objects.equals(getter.get(), value)) {
            return false;
        }
        setter.accept(value);
        return true;
    }
}
