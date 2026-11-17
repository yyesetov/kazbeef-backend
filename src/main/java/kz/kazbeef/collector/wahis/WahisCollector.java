package kz.kazbeef.collector.wahis;

import com.fasterxml.jackson.databind.JsonNode;
import kz.kazbeef.collector.CollectResult;
import kz.kazbeef.collector.Collector;
import kz.kazbeef.collector.RawEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Сборщик WAHIS: все болезни по приоритетным странам.
 * <ol>
 *   <li>Постранично читает список отчётов (новые первыми) до границы окна {@code since - overlap}.</li>
 *   <li>Оставляет последний отчёт по каждому событию (eventId).</li>
 *   <li>Для каждого берёт полную информацию отчёта (вспышки, количества, координаты).</li>
 * </ol>
 */
@Component
public class WahisCollector implements Collector {

    public static final String CODE = "WAHIS";
    private static final Logger log = LoggerFactory.getLogger(WahisCollector.class);

    private final WahisClient client;
    private final WahisParser parser;
    private final WahisProperties props;

    public WahisCollector(WahisClient client, WahisParser parser, WahisProperties props) {
        this.client = client;
        this.parser = parser;
        this.props = props;
    }

    @Override
    public String sourceCode() {
        return CODE;
    }

    @Override
    public CollectResult collect(Instant since) {
        Instant cutoff = since == null
                ? Instant.now().minus(props.initialLookback())
                : since.minus(props.overlap());
        List<String> warnings = new ArrayList<>();

        List<Integer> areaIds = resolveAreaIds(warnings);
        if (areaIds.isEmpty()) {
            throw new IllegalStateException("Ни одна страна из kazbeef.wahis.countries не найдена в справочнике WAHIS");
        }

        // eventId → последний (самый свежий) отчёт
        Map<Long, JsonNode> latestByEvent = new LinkedHashMap<>();
        int fetched = 0;
        boolean complete = false;
        for (int page = 0; page < props.maxPages() && !complete; page++) {
            JsonNode resp = client.filteredList(areaIds, page, props.pageSize());
            JsonNode list = resp.path("list");
            if (!list.isArray() || list.isEmpty()) {
                complete = true;
                break;
            }
            for (JsonNode item : list) {
                Instant submitted = parseInstant(item.path("submissionDate").asText(null));
                if (submitted != null && submitted.isBefore(cutoff)) {
                    complete = true;
                    break;
                }
                fetched++;
                if (item.path("isAquatic").asBoolean(false)) {
                    continue; // водные животные вне охвата системы
                }
                latestByEvent.putIfAbsent(item.path("eventId").asLong(), item);
            }
            int total = resp.path("totalSize").asInt(0);
            if ((long) (page + 1) * props.pageSize() >= total) {
                complete = true;
            }
        }
        if (!complete) {
            warnings.add("Достигнут предел страниц (" + props.maxPages() + "), часть отчётов не прочитана");
        }
        log.info("WAHIS: прочитано {} отчётов с {}, событий {}", fetched, cutoff, latestByEvent.size());

        List<RawEvent> events = new ArrayList<>(latestByEvent.size());
        for (JsonNode item : latestByEvent.values()) {
            long reportId = item.path("reportId").asLong();
            JsonNode detail = null;
            try {
                detail = client.reportDetail(reportId);
            } catch (Exception e) {
                warnings.add("Отчёт " + reportId + " (событие " + item.path("eventId").asLong()
                        + "): детали недоступны, записан по данным списка: " + e.getMessage());
            }
            try {
                events.add(parser.parse(item, detail));
            } catch (Exception e) {
                warnings.add("Отчёт " + reportId + ": ошибка разбора: " + e);
            }
        }
        return new CollectResult(events, fetched, warnings);
    }

    private List<Integer> resolveAreaIds(List<String> warnings) {
        Map<String, Integer> byIso3 = new HashMap<>();
        for (JsonNode c : client.countries()) {
            String iso = c.path("isoCode").asText("").toUpperCase(Locale.ROOT);
            if (!iso.isEmpty() && c.hasNonNull("areaId")) {
                byIso3.put(iso, c.path("areaId").asInt());
            }
        }
        List<Integer> ids = new ArrayList<>();
        for (String iso : props.countries()) {
            Integer id = byIso3.get(iso.toUpperCase(Locale.ROOT));
            if (id == null) {
                warnings.add("Страна " + iso + " не найдена в справочнике WAHIS");
            } else {
                ids.add(id);
            }
        }
        return ids;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
