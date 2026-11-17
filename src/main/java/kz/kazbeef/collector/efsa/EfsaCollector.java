package kz.kazbeef.collector.efsa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.kazbeef.collector.CollectResult;
import kz.kazbeef.collector.Collector;
import kz.kazbeef.collector.RawEvent;
import kz.kazbeef.config.KazbeefProperties;
import kz.kazbeef.config.SourceHttpClient;
import kz.kazbeef.domain.Disease;
import kz.kazbeef.domain.EventStatus;
import kz.kazbeef.normalize.NameNormalizer;
import kz.kazbeef.normalize.TextMatcher;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сборщик публикаций EFSA из RSS-лент. EFSA не сообщает о конкретных вспышках:
 * это научные отчёты и новости, поэтому записи получают уровень «аналитика» и не имеют страны и чисел.
 * Берутся только публикации, где упомянута болезнь из справочника.
 */
@Component
public class EfsaCollector implements Collector {

    public static final String CODE = "EFSA";
    private static final Logger log = LoggerFactory.getLogger(EfsaCollector.class);
    private static final int MAX_DESCRIPTION = 4000;

    private final SourceHttpClient http;
    private final EfsaProperties props;
    private final TextMatcher textMatcher;
    private final ObjectMapper mapper;

    public EfsaCollector(EfsaProperties props, KazbeefProperties kazbeef, TextMatcher textMatcher, ObjectMapper mapper) {
        this.http = new SourceHttpClient("https://www.efsa.europa.eu", kazbeef.http(), props.requestDelay());
        this.props = props;
        this.textMatcher = textMatcher;
        this.mapper = mapper;
    }

    @Override
    public String sourceCode() {
        return CODE;
    }

    @Override
    public CollectResult collect(Instant since) {
        List<String> warnings = new ArrayList<>();
        // одна публикация может быть в нескольких лентах: ключ — ссылка
        Map<String, RawEvent> byLink = new LinkedHashMap<>();
        int fetched = 0;
        for (String feed : props.feeds()) {
            try {
                String xml = http.execute(rc -> rc.get().uri(URI.create(feed)).retrieve().body(String.class));
                Document doc = Jsoup.parse(xml == null ? "" : xml, "", Parser.xmlParser());
                var items = doc.select("item");
                if (items.isEmpty()) {
                    warnings.add("Лента " + feed + " пуста или изменила формат");
                }
                for (Element item : items) {
                    fetched++;
                    RawEvent event = toEvent(item);
                    if (event != null) byLink.putIfAbsent(event.externalId(), event);
                }
            } catch (Exception e) {
                warnings.add("Лента " + feed + ": " + e);
            }
        }
        if (fetched == 0 && !warnings.isEmpty()) {
            throw new IllegalStateException(String.join("; ", warnings));
        }
        log.info("EFSA: прочитано {} публикаций, о болезнях из справочника {}", fetched, byLink.size());
        return new CollectResult(new ArrayList<>(byLink.values()), fetched, warnings);
    }

    private RawEvent toEvent(Element item) {
        String title = NameNormalizer.clean(text(item, "title"));
        String link = text(item, "link");
        if (title == null || link == null || link.length() > 255) {
            return null;
        }
        String description = NameNormalizer.clean(Jsoup.parse(Optional.ofNullable(text(item, "description")).orElse("")).text());
        Optional<Disease> disease = textMatcher.disease(title + ". " + description);
        if (disease.isEmpty()) {
            return null;
        }
        if (description != null && description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION) + "…";
        }
        ObjectNode raw = mapper.createObjectNode();
        raw.put("title", title);
        raw.put("link", link);
        raw.put("pubDate", text(item, "pubDate"));
        raw.put("description", description);

        return new RawEvent(
                link,
                link,
                disease.get().getCode() != null ? disease.get().getCode() : disease.get().getNameEn(),
                null, null,
                null, null, null,
                null,
                date(text(item, "pubDate")),
                textMatcher.species(title + ". " + description),
                null, null, null, null,
                EventStatus.NEW,
                title.length() <= 1000 ? title : title.substring(0, 1000),
                description == null || description.isBlank() ? null : description,
                raw.toString(),
                null);
    }

    private static String text(Element item, String tag) {
        Element el = item.selectFirst(tag);
        if (el == null) return null;
        String s = el.text().trim();
        return s.isEmpty() ? null : s;
    }

    /** "Fri, 13 Nov 2026 14:47:12 +0100" → 2026-11-13. */
    private static LocalDate date(String pubDate) {
        if (pubDate == null) return null;
        try {
            return ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }
}
