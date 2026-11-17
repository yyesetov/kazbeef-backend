package kz.kazbeef.collector.wahis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.kazbeef.config.KazbeefProperties;
import kz.kazbeef.config.SourceHttpClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Доступ к JSON-эндпоинтам публичного интерфейса WAHIS (wahis.woah.org).
 * Официального документированного API нет, это те же запросы, что делает сайт.
 */
@Component
public class WahisClient {

    /** Фильтры списка, которые мы не используем: WAHIS ожидает их пустыми массивами. */
    private static final List<String> UNUSED_LIST_FILTERS = List.of(
            "eventIds", "reportIds", "firstDiseases", "secondDiseases", "typeStatuses", "reasons",
            "eventStatuses", "reportTypes", "reportStatuses", "animalTypes");

    private final SourceHttpClient http;
    private final ObjectMapper mapper;

    public WahisClient(WahisProperties props, KazbeefProperties kazbeef, ObjectMapper mapper) {
        this.http = new SourceHttpClient(props.baseUrl(), kazbeef.http(), props.requestDelay());
        this.mapper = mapper;
    }

    /** Справочник стран WAHIS: [{areaId, name, isoCode}]. */
    public JsonNode countries() {
        return readTree(http.execute(rc -> rc.get()
                .uri("/api/v1/pi/country/list?language=en")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class)));
    }

    /**
     * Страница списка отчётов, отсортированного по дате подачи (новые первыми).
     * Ответ: {list:[...], totalSize, pageSize, pageNumber}.
     */
    public JsonNode filteredList(Collection<Integer> areaIds, int pageNumber, int pageSize) {
        ObjectNode body = mapper.createObjectNode();
        UNUSED_LIST_FILTERS.forEach(body::putArray);
        ArrayNode countries = body.putArray("countries");
        areaIds.forEach(countries::add);
        body.putNull("eventStartDate");
        body.putNull("submissionDate");
        body.put("sortColumn", "submissionDate");
        body.put("sortOrder", "desc");
        body.put("pageNumber", pageNumber);
        body.put("pageSize", pageSize);

        return readTree(http.execute(rc -> rc.post()
                .uri("/api/v1/pi/event/filtered-list?language=en")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class)));
    }

    /** Полная информация по отчёту: событие, вспышки, количественные данные. */
    public JsonNode reportDetail(long reportId) {
        return readTree(http.execute(rc -> rc.get()
                .uri("/api/v1/pi/review/report/{reportId}/all-information?language=en", reportId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class)));
    }

    private JsonNode readTree(String json) {
        try {
            return json == null ? mapper.nullNode() : mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("WAHIS вернул некорректный JSON", e);
        }
    }
}
