package kz.kazbeef.api;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Запросы чтения для интерфейса и экспорта. Агрегаты считаются в SQL,
 * строки отдаются как есть (ключи — псевдонимы колонок в camelCase).
 */
@Component
public class EventQueries {

    /** Дата события: начало вспышки, а если его нет — дата отчёта. */
    public static final String EVENT_DATE = "coalesce(e.start_date, e.report_date)";

    private static final String FROM = """
             FROM event e
             LEFT JOIN disease d ON d.id = e.disease_id
             LEFT JOIN country c ON c.id = e.country_id
            """;

    private static final String DISEASE_NAME = "coalesce(d.name_ru, d.name_en, e.raw_disease_name, 'Не указана')";
    private static final String COUNTRY_NAME = "coalesce(c.name_ru, c.name_en, e.raw_country_name, 'Не указана')";

    private static final String EVENT_COLUMNS = """
            e.id, e.title, e.status, e.trust_level AS "trustLevel",
            e.disease_id AS "diseaseId", d.code AS "diseaseCode", %s AS disease,
            c.iso3, %s AS country, coalesce(c.is_priority, false) AS "priorityCountry",
            e.region, e.latitude, e.longitude,
            e.start_date::text AS "startDate", e.report_date::text AS "reportDate", %s::text AS "eventDate",
            e.outbreaks, e.susceptible, e.cases, e.deaths,
            e.ruminant_relevant AS "ruminantRelevant", e.risk_score AS "riskScore",
            round(e.distance_to_kz_km) AS "distanceKm", e.raw_species AS "rawSpecies",
            (SELECT string_agg(s.name_ru, ', ' ORDER BY s.group_code, s.name_ru)
               FROM event_species es JOIN animal_species s ON s.id = es.species_id
              WHERE es.event_id = e.id) AS species,
            (SELECT string_agg(DISTINCT s.group_code, ',')
               FROM event_species es JOIN animal_species s ON s.id = es.species_id
              WHERE es.event_id = e.id) AS "speciesGroups",
            (SELECT string_agg(so.code, ',' ORDER BY so.code)
               FROM event_source x JOIN source so ON so.id = x.source_id
              WHERE x.event_id = e.id) AS sources,
            e.ai_summary AS "aiSummary"
            """.formatted(DISEASE_NAME, COUNTRY_NAME, EVENT_DATE);

    private static final Map<String, String> SORT = Map.ofEntries(
            Map.entry("eventDate", EVENT_DATE),
            Map.entry("reportDate", "e.report_date"),
            Map.entry("startDate", "e.start_date"),
            Map.entry("country", COUNTRY_NAME),
            Map.entry("disease", DISEASE_NAME),
            Map.entry("status", "e.status"),
            Map.entry("outbreaks", "e.outbreaks"),
            Map.entry("cases", "e.cases"),
            Map.entry("deaths", "e.deaths"),
            Map.entry("susceptible", "e.susceptible"),
            Map.entry("riskScore", "e.risk_score"),
            Map.entry("distanceKm", "e.distance_to_kz_km"));

    private final NamedParameterJdbcTemplate jdbc;

    public EventQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- события ----------

    public Map<String, Object> page(EventFilter f, int page, int size, String sort, String dir) {
        Where w = where(f);
        Long total = jdbc.queryForObject("SELECT count(*)" + FROM + w.sql, w.params, Long.class);
        String order = SORT.getOrDefault(sort, EVENT_DATE) + ("asc".equalsIgnoreCase(dir) ? " ASC" : " DESC") + " NULLS LAST, e.id DESC";
        w.params.addValue("limit", Math.clamp(size, 1, 500)).addValue("offset", Math.max(0, page) * (long) Math.clamp(size, 1, 500));
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT " + EVENT_COLUMNS + FROM + w.sql + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", w.params);
        return Map.of("items", items, "total", total == null ? 0 : total);
    }

    /** Все события по фильтру без пагинации (экспорт, сводки), не больше {@code limit}. */
    public List<Map<String, Object>> list(EventFilter f, String sort, String dir, int limit) {
        Where w = where(f);
        String order = SORT.getOrDefault(sort, EVENT_DATE) + ("asc".equalsIgnoreCase(dir) ? " ASC" : " DESC") + " NULLS LAST, e.id DESC";
        w.params.addValue("limit", limit);
        return jdbc.queryForList("SELECT " + EVENT_COLUMNS + FROM + w.sql + " ORDER BY " + order + " LIMIT :limit", w.params);
    }

    public Map<String, Object> detail(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + EVENT_COLUMNS + """
                , e.description, e.translation, e.ai_generated AS "aiGenerated",
                e.raw_disease_name AS "rawDiseaseName", e.raw_country_name AS "rawCountryName",
                d.name_en AS "diseaseEn", d.severity AS "diseaseSeverity",
                e.created_at::text AS "createdAt", e.updated_at::text AS "updatedAt"
                """ + FROM + " WHERE e.id = :id", p);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> event = new java.util.LinkedHashMap<>(rows.getFirst());
        event.put("sourceRecords", jdbc.queryForList("""
                SELECT so.code, so.name, so.trust_level AS "trustLevel", x.external_id AS "externalId", x.url,
                       x.fetched_at::text AS "fetchedAt", x.raw_payload::text AS "rawPayload"
                  FROM event_source x JOIN source so ON so.id = x.source_id
                 WHERE x.event_id = :id ORDER BY so.code
                """, p));
        return event;
    }

    /** Точки для карты: только события с координатами. */
    public List<Map<String, Object>> mapPoints(EventFilter f) {
        Where w = where(f);
        return jdbc.queryForList("""
                SELECT e.id, e.latitude AS lat, e.longitude AS lon, e.title, e.status, e.region,
                       e.disease_id AS "diseaseId", %s AS disease, %s AS country, c.iso3,
                       %s::text AS "eventDate", e.outbreaks, e.cases, e.deaths,
                       e.risk_score AS "riskScore", round(e.distance_to_kz_km) AS "distanceKm",
                       e.trust_level AS "trustLevel"
                """.formatted(DISEASE_NAME, COUNTRY_NAME, EVENT_DATE)
                + FROM + w.sql + " AND e.latitude IS NOT NULL AND e.longitude IS NOT NULL ORDER BY "
                + EVENT_DATE + " DESC NULLS LAST LIMIT 5000", w.params);
    }

    // ---------- статистика ----------

    public Map<String, Object> summary(EventFilter f) {
        Where w = where(f);
        return jdbc.queryForMap("""
                SELECT count(*) AS events,
                       count(*) FILTER (WHERE e.report_date >= current_date - 7) AS "newLast7d",
                       count(*) FILTER (WHERE e.status <> 'closed' AND e.trust_level <> 'analytics') AS active,
                       coalesce(sum(e.outbreaks), 0) AS outbreaks,
                       coalesce(sum(e.cases), 0) AS cases,
                       coalesce(sum(e.deaths), 0) AS deaths,
                       count(DISTINCT e.country_id) AS countries,
                       count(DISTINCT e.disease_id) AS diseases,
                       max(e.risk_score) AS "maxRisk",
                       count(*) FILTER (WHERE c.iso3 = 'KAZ') AS "inKazakhstan",
                       count(*) FILTER (WHERE e.distance_to_kz_km <= 300 AND c.iso3 <> 'KAZ') AS "nearBorder",
                       max(e.report_date)::text AS "lastReportDate"
                """ + FROM + w.sql, w.params);
    }

    /**
     * @param interval week / month
     * @param by       disease / country / none
     */
    public List<Map<String, Object>> timeseries(EventFilter f, String interval, String by) {
        Where w = where(f);
        String unit = "month".equalsIgnoreCase(interval) ? "month" : "week";
        String key;
        String label;
        switch (by == null ? "" : by.toLowerCase(Locale.ROOT)) {
            case "disease" -> { key = "coalesce(e.disease_id::text, '')"; label = DISEASE_NAME; }
            case "country" -> { key = "coalesce(c.iso3, '')"; label = COUNTRY_NAME; }
            default -> { key = "''"; label = "'Все события'"; }
        }
        return jdbc.queryForList("""
                SELECT date_trunc('%s', %s)::date::text AS period, %s AS key, %s AS label,
                       count(*) AS events, coalesce(sum(e.outbreaks), 0) AS outbreaks,
                       coalesce(sum(e.cases), 0) AS cases, coalesce(sum(e.deaths), 0) AS deaths
                """.formatted(unit, EVENT_DATE, key, label)
                + FROM + w.sql + " AND " + EVENT_DATE + " IS NOT NULL GROUP BY 1, 2, 3 ORDER BY 1, 3", w.params);
    }

    public List<Map<String, Object>> byCountry(EventFilter f) {
        Where w = where(f);
        return jdbc.queryForList("SELECT c.iso3 AS key, " + COUNTRY_NAME + " AS label," + AGGREGATES
                + FROM + w.sql + " GROUP BY 1, 2 ORDER BY events DESC, 2", w.params);
    }

    public List<Map<String, Object>> byDisease(EventFilter f) {
        Where w = where(f);
        return jdbc.queryForList("SELECT e.disease_id::text AS key, " + DISEASE_NAME + " AS label," + AGGREGATES
                + FROM + w.sql + " GROUP BY 1, 2 ORDER BY events DESC, 2", w.params);
    }

    /** Событие с несколькими видами учитывается в каждом из них. */
    public List<Map<String, Object>> bySpecies(EventFilter f) {
        Where w = where(f);
        return jdbc.queryForList("SELECT s.name_en AS key, s.name_ru AS label, s.group_code AS \"group\"," + AGGREGATES
                + FROM + " JOIN event_species es ON es.event_id = e.id JOIN animal_species s ON s.id = es.species_id "
                + w.sql + " GROUP BY 1, 2, 3 ORDER BY events DESC, 2", w.params);
    }

    public List<Map<String, Object>> bySource(EventFilter f) {
        Where w = where(f);
        return jdbc.queryForList("SELECT so.code AS key, so.name AS label, so.trust_level AS \"trustLevel\"," + AGGREGATES
                + FROM + " JOIN event_source x ON x.event_id = e.id JOIN source so ON so.id = x.source_id "
                + w.sql + " GROUP BY 1, 2, 3 ORDER BY events DESC, 2", w.params);
    }

    /** Матрица «болезнь × страна». */
    public List<Map<String, Object>> matrix(EventFilter f) {
        Where w = where(f);
        return jdbc.queryForList("SELECT " + DISEASE_NAME + " AS disease, " + COUNTRY_NAME + " AS country,"
                + " count(*) AS events, coalesce(sum(e.outbreaks), 0) AS outbreaks, coalesce(sum(e.cases), 0) AS cases"
                + FROM + w.sql + " GROUP BY 1, 2 ORDER BY 1, 2", w.params);
    }

    private static final String AGGREGATES = """
             count(*) AS events,
             count(*) FILTER (WHERE e.status <> 'closed' AND e.trust_level <> 'analytics') AS active,
             coalesce(sum(e.outbreaks), 0) AS outbreaks,
             coalesce(sum(e.cases), 0) AS cases,
             coalesce(sum(e.deaths), 0) AS deaths,
             max(e.risk_score) AS "maxRisk"
            """;

    // ---------- справочники ----------

    public Map<String, Object> reference() {
        Map<String, Object> none = Map.of();
        return Map.of(
                "countries", jdbc.queryForList("""
                        SELECT c.iso3, coalesce(c.name_ru, c.name_en) AS name, c.is_priority AS priority, c.watch_group AS "group",
                               (SELECT count(*) FROM event e WHERE e.country_id = c.id) AS events
                          FROM country c
                         ORDER BY c.is_priority DESC, array_position(ARRAY['neighbor', 'cis', 'importer'], c.watch_group::text), 2
                        """, none),
                "diseases", jdbc.queryForList("""
                        SELECT d.id, d.code, coalesce(d.name_ru, d.name_en) AS name, d.name_en AS "nameEn",
                               d.severity, d.needs_review AS "needsReview",
                               (SELECT count(*) FROM event e WHERE e.disease_id = d.id) AS events,
                               EXISTS (SELECT 1 FROM disease_species ds JOIN animal_species s ON s.id = ds.species_id
                                        WHERE ds.disease_id = d.id AND s.group_code <> 'OTHER') AS ruminant
                          FROM disease d ORDER BY 3
                        """, none),
                "species", jdbc.queryForList("""
                        SELECT s.id, s.name_ru AS name, s.name_en AS "nameEn", s.group_code AS "group"
                          FROM animal_species s ORDER BY s.group_code, s.name_ru
                        """, none),
                "sources", jdbc.queryForList("""
                        SELECT s.code, s.name, s.url, s.trust_level AS "trustLevel", s.active, s.note
                          FROM source s ORDER BY s.id
                        """, none));
    }

    // ---------- фильтр ----------

    public record Where(String sql, MapSqlParameterSource params) {
    }

    public Where where(EventFilter f) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (f.ruminantOnlyOrDefault()) {
            sql.append(" AND e.ruminant_relevant");
        }
        if (notEmpty(f.countries())) {
            sql.append(" AND c.iso3 IN (:countries)");
            p.addValue("countries", f.countries().stream().map(s -> s.toUpperCase(Locale.ROOT)).toList());
        }
        if (notEmpty(f.diseases())) {
            sql.append(" AND e.disease_id IN (:diseases)");
            p.addValue("diseases", f.diseases());
        }
        if (notEmpty(f.groups())) {
            sql.append(" ").append("""
                     AND EXISTS (SELECT 1 FROM event_species fes JOIN animal_species fs ON fs.id = fes.species_id
                                  WHERE fes.event_id = e.id AND fs.group_code IN (:groups))""");
            p.addValue("groups", f.groups().stream().map(s -> s.toUpperCase(Locale.ROOT)).toList());
        }
        if (notEmpty(f.sources())) {
            sql.append(" ").append("""
                     AND EXISTS (SELECT 1 FROM event_source fx JOIN source fso ON fso.id = fx.source_id
                                  WHERE fx.event_id = e.id AND fso.code IN (:sources))""");
            p.addValue("sources", f.sources().stream().map(s -> s.toUpperCase(Locale.ROOT)).toList());
        }
        if (notEmpty(f.trust())) {
            sql.append(" AND e.trust_level IN (:trust)");
            p.addValue("trust", f.trust());
        }
        if (notEmpty(f.status())) {
            sql.append(" AND e.status IN (:status)");
            p.addValue("status", f.status());
        }
        if (f.from() != null) {
            sql.append(" AND ").append(EVENT_DATE).append(" >= :from");
            p.addValue("from", f.from());
        }
        if (f.to() != null) {
            sql.append(" AND ").append(EVENT_DATE).append(" <= :to");
            p.addValue("to", f.to());
        }
        if (f.minRisk() != null) {
            sql.append(" AND e.risk_score >= :minRisk");
            p.addValue("minRisk", f.minRisk());
        }
        if (f.q() != null && !f.q().isBlank()) {
            sql.append(" ").append("""
                     AND (e.title ILIKE :q OR e.region ILIKE :q OR e.raw_disease_name ILIKE :q OR e.raw_species ILIKE :q
                          OR d.name_ru ILIKE :q OR d.name_en ILIKE :q OR c.name_ru ILIKE :q OR c.name_en ILIKE :q
                          OR e.description ILIKE :q OR e.translation ILIKE :q OR e.ai_summary ILIKE :q)""");
            p.addValue("q", "%" + f.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return new Where(sql.toString(), p);
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
