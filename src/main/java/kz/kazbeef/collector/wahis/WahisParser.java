package kz.kazbeef.collector.wahis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.kazbeef.collector.RawEvent;
import kz.kazbeef.domain.EventStatus;
import kz.kazbeef.normalize.NameNormalizer;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Преобразует ответы WAHIS в {@link RawEvent}. Без ввода-вывода.
 * Одно событие WAHIS (eventId) = одна запись; данные берутся из последнего отчёта по событию.
 * Числа и даты переносятся как есть, без пересчёта.
 */
@Component
public class WahisParser {

    private static final int MAX_REGIONS = 10;

    private final ObjectMapper mapper;
    private final String baseUrl;

    public WahisParser(ObjectMapper mapper, WahisProperties props) {
        this.mapper = mapper;
        this.baseUrl = props.baseUrl().replaceAll("/+$", "");
    }

    /**
     * @param listItem элемент из filtered-list
     * @param detail   ответ all-information по тому же отчёту или {@code null}, если его не удалось получить
     */
    public RawEvent parse(JsonNode listItem, JsonNode detail) {
        boolean hasDetail = detail != null && detail.hasNonNull("event");
        JsonNode event = hasDetail ? detail.path("event") : mapper.missingNode();
        JsonNode report = hasDetail ? detail.path("report") : mapper.missingNode();

        long eventId = listItem.path("eventId").asLong();
        long reportId = listItem.path("reportId").asLong();

        String disease = firstText(event.path("disease").path("name"), listItem.path("disease"));
        String subType = firstText(event.path("subType"), listItem.path("subType"));
        String countryName = firstText(event.path("country").path("name"), listItem.path("country"));
        String countryIso3 = text(event.path("country").path("isoCode"));

        LocalDate startDate = date(firstText(event.path("startedOn"), listItem.path("eventStartDate")));
        LocalDate reportDate = date(firstText(report.path("reportedOn"), listItem.path("submissionDate")));

        Outbreaks ob = outbreaks(detail);
        Quantities q = quantities(detail);

        String diseaseClean = NameNormalizer.clean(disease);
        String title = diseaseClean + (subType != null ? " [" + subType.trim() + "]" : "")
                + " — " + NameNormalizer.clean(countryName);

        ObjectNode raw = mapper.createObjectNode();
        raw.set("listItem", listItem);
        if (hasDetail) {
            raw.set("detail", detail);
        }

        return new RawEvent(
                String.valueOf(eventId),
                baseUrl + "/#/in-review/" + eventId + "?reportId=" + reportId + "&fromPage=event-dashboard-url",
                diseaseClean,
                NameNormalizer.clean(countryName),
                countryIso3,
                ob.regions,
                ob.latitude,
                ob.longitude,
                startDate,
                reportDate,
                q.species,
                hasDetail ? ob.count : null,
                q.susceptible,
                q.cases,
                q.deaths,
                status(firstText(event.path("eventStatus").path("keyValue"), listItem.path("eventStatus")),
                        text(listItem.path("reportType"))),
                title,
                description(event, listItem),
                raw.toString(),
                null);
    }

    static EventStatus status(String wahisStatus, String reportType) {
        String s = wahisStatus == null ? "" : wahisStatus.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (s.equals("resolved") || s.equals("closed")) {
            return EventStatus.CLOSED;
        }
        // Первый отчёт (immediate notification) по ещё не закрытому событию
        if ("IN".equalsIgnoreCase(reportType)) {
            return EventStatus.NEW;
        }
        return EventStatus.ONGOING;
    }

    private String description(JsonNode event, JsonNode listItem) {
        List<String> parts = new ArrayList<>();
        String reason = firstText(event.path("reason").path("translation"), listItem.path("reason"));
        if (reason != null) parts.add("Reason: " + reason);
        String agent = text(event.path("causalAgent").path("name"));
        if (agent != null) parts.add("Causal agent: " + agent);
        String comment = text(event.path("eventComment"));
        if (comment != null) parts.add(comment);
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private Outbreaks outbreaks(JsonNode detail) {
        Outbreaks r = new Outbreaks();
        if (detail == null) return r;
        JsonNode list = detail.path("outbreaks");
        Set<String> regions = new LinkedHashSet<>();
        for (JsonNode o : list) {
            r.count++;
            String region = firstText(o.path("adminDivision"), o.path("location"));
            if (region != null) regions.add(region.trim());
            if (r.latitude == null && o.hasNonNull("latitude") && o.hasNonNull("longitude")) {
                r.latitude = o.path("latitude").asDouble();
                r.longitude = o.path("longitude").asDouble();
            }
        }
        if (!regions.isEmpty()) {
            List<String> l = new ArrayList<>(regions);
            r.regions = l.size() <= MAX_REGIONS
                    ? String.join(", ", l)
                    : String.join(", ", l.subList(0, MAX_REGIONS)) + " и ещё " + (l.size() - MAX_REGIONS);
        }
        return r;
    }

    private Quantities quantities(JsonNode detail) {
        Quantities q = new Quantities();
        if (detail == null) return q;
        Set<String> species = new LinkedHashSet<>();
        for (JsonNode t : detail.path("quantitativeData").path("totals")) {
            String name = text(t.path("speciesName"));
            if (name != null) species.add(name.trim() + (t.path("isWild").asBoolean(false) ? " (wild)" : ""));
            q.susceptible = add(q.susceptible, t.path("susceptible"));
            q.cases = add(q.cases, t.path("cases"));
            q.deaths = add(q.deaths, t.path("deaths"));
        }
        q.species = species.isEmpty() ? null : String.join("; ", species);
        return q;
    }

    private static Integer add(Integer acc, JsonNode value) {
        if (value == null || !value.isNumber()) return acc;
        return (acc == null ? 0 : acc) + value.asInt();
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstText(JsonNode a, JsonNode b) {
        String s = text(a);
        return s != null ? s : text(b);
    }

    /** "2025-10-20T00:00:00.000+00:00" → 2025-10-20 (дата в UTC, как в источнике). */
    static LocalDate date(String s) {
        if (s == null) return null;
        try {
            return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static final class Outbreaks {
        int count;
        String regions;
        Double latitude;
        Double longitude;
    }

    private static final class Quantities {
        String species;
        Integer susceptible;
        Integer cases;
        Integer deaths;
    }
}
