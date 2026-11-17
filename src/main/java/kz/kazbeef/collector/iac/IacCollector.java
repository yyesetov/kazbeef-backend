package kz.kazbeef.collector.iac;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.kazbeef.collector.CollectResult;
import kz.kazbeef.collector.Collector;
import kz.kazbeef.collector.RawEvent;
import kz.kazbeef.config.KazbeefProperties;
import kz.kazbeef.config.SourceHttpClient;
import kz.kazbeef.domain.Country;
import kz.kazbeef.domain.Disease;
import kz.kazbeef.domain.EventStatus;
import kz.kazbeef.domain.TrustLevel;
import kz.kazbeef.normalize.CountryResolver;
import kz.kazbeef.normalize.NameNormalizer;
import kz.kazbeef.normalize.TextMatcher;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сборщик информационных сообщений ИАЦ Россельхознадзора (fsvps.gov.ru).
 * <p>
 * Сообщение — это сводка за день: разделы («Официальная информация ВОЗЖ», «Информация по сообщениям СМИ»)
 * и пункты вида «<u>Страна</u>: текст». Каждый пункт о болезни становится отдельной записью.
 * Пункты раздела ВОЗЖ получают уровень «подтверждено», пункты по сообщениям СМИ — «сигнал».
 * Недельные сводки («с … по …») пропускаются: они повторяют ежедневные.
 */
@Component
public class IacCollector implements Collector {

    public static final String CODE = "IAC";
    private static final Logger log = LoggerFactory.getLogger(IacCollector.class);
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private static final List<String> MONTHS = List.of("января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})\\s+([а-яё]+)\\s+(\\d{4})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUMBER = Pattern.compile("№\\s*(\\d+)");
    /** Подразделы не про болезни. */
    private static final Pattern OFF_TOPIC = Pattern.compile("производств|импорт|экспорт|торговл|рынок|цен[аы]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** «Казахстан (Костанайская область): …» → регион в скобках сразу после страны. */
    private static final Pattern REGION = Pattern.compile("^[^:(]{2,40}\\(([^)]{2,120})\\)\\s*:");
    private static final int MAX_TITLE = 300;

    private final SourceHttpClient http;
    private final IacProperties props;
    private final TextMatcher textMatcher;
    private final CountryResolver countryResolver;
    private final ObjectMapper mapper;

    public IacCollector(IacProperties props, KazbeefProperties kazbeef, TextMatcher textMatcher,
                        CountryResolver countryResolver, ObjectMapper mapper) {
        this.http = new SourceHttpClient(props.baseUrl(), kazbeef.http(), props.requestDelay());
        this.props = props;
        this.textMatcher = textMatcher;
        this.countryResolver = countryResolver;
        this.mapper = mapper;
    }

    @Override
    public String sourceCode() {
        return CODE;
    }

    @Override
    public CollectResult collect(Instant since) {
        LocalDate cutoff = (since == null ? Instant.now().minus(props.initialLookback()) : since.minus(props.overlap()))
                .atZone(MOSCOW).toLocalDate();
        List<String> warnings = new ArrayList<>();

        List<MessageRef> messages = listMessages(cutoff, warnings);
        log.info("ИАЦ: сообщений с {}: {}", cutoff, messages.size());

        List<RawEvent> events = new ArrayList<>();
        int fetched = 0;
        for (MessageRef ref : messages) {
            try {
                Document page = Jsoup.parse(get(ref.url()), ref.url());
                Parsed parsed = parseMessage(ref, page);
                fetched += parsed.items();
                events.addAll(parsed.events());
            } catch (Exception e) {
                warnings.add("Сообщение " + ref.title() + ": " + e);
            }
        }
        return new CollectResult(events, fetched, warnings);
    }

    // ---------- список сообщений ----------

    private record MessageRef(String url, String title, LocalDate date) {
    }

    private List<MessageRef> listMessages(LocalDate cutoff, List<String> warnings) {
        List<MessageRef> result = new ArrayList<>();
        for (int page = 1; page <= props.maxPages(); page++) {
            String path = page == 1 ? props.listPath() : props.listPath() + "page/" + page;
            Document doc = Jsoup.parse(get(props.baseUrl() + path), props.baseUrl());
            var rows = doc.select("li.views-row");
            if (rows.isEmpty()) {
                if (page == 1) {
                    throw new IllegalStateException("На странице сообщений ИАЦ не найден список: разметка сайта изменилась");
                }
                return result;
            }
            boolean olderSeen = false;
            for (Element row : rows) {
                Element link = row.selectFirst(".views-field-title a[href]");
                String dateText = row.select(".views-field-field-iac-date").text();
                if (link == null) continue;
                if (dateText.toLowerCase(Locale.ROOT).contains(" по ")) {
                    continue; // недельная сводка
                }
                LocalDate date = parseDate(dateText);
                if (date == null) {
                    warnings.add("Не разобрана дата «" + dateText + "» у " + link.text());
                    continue;
                }
                if (date.isBefore(cutoff)) {
                    olderSeen = true;
                    continue;
                }
                result.add(new MessageRef(link.absUrl("href"), link.text().trim(), date));
            }
            if (olderSeen) {
                return result;
            }
        }
        warnings.add("Достигнут предел страниц (" + props.maxPages() + "), часть сообщений не прочитана");
        return result;
    }

    // ---------- разбор сообщения ----------

    private record Parsed(List<RawEvent> events, int items) {
    }

    private Parsed parseMessage(MessageRef ref, Document page) {
        Element body = page.selectFirst(".field-name-body");
        if (body == null) {
            throw new IllegalStateException("нет текста сообщения");
        }
        Matcher m = NUMBER.matcher(ref.title());
        String number = m.find() ? m.group(1) : String.valueOf(Math.abs(ref.url().hashCode()));

        List<RawEvent> events = new ArrayList<>();
        String section = "";
        String subsection = "";
        int index = 0;
        for (Element el : body.children()) {
            if (el.tagName().equals("p")) {
                String text = el.text().trim();
                if (text.isEmpty()) continue;
                if (el.selectFirst("strong, b") != null) {
                    section = text;
                    subsection = "";
                } else {
                    subsection = text;
                }
                continue;
            }
            if (!el.tagName().equals("ol") && !el.tagName().equals("ul")) {
                continue;
            }
            for (Element li : el.select("> li")) {
                index++;
                if (OFF_TOPIC.matcher(subsection).find()) continue;
                RawEvent event = toEvent(ref, number, index, section, subsection, li);
                if (event != null) events.add(event);
            }
        }
        return new Parsed(events, index);
    }

    private RawEvent toEvent(MessageRef ref, String number, int index, String section, String subsection, Element li) {
        String text = NameNormalizer.clean(li.text());
        Element marked = li.selectFirst("u");
        String countryRaw = marked == null ? null : marked.text().replace(":", "").trim();
        Optional<Country> country = countryResolver.findByName(countryRaw);
        if (props.priorityOnly() && (country.isEmpty() || !country.get().isPriority())) {
            return null;
        }
        Optional<Disease> disease = textMatcher.disease(text);
        if (disease.isEmpty()) {
            return null; // пункт не о болезни из справочника
        }

        ObjectNode raw = mapper.createObjectNode();
        raw.put("message", ref.title());
        raw.put("date", ref.date().toString());
        raw.put("section", section);
        raw.put("subsection", subsection);
        raw.put("item", index);
        raw.put("country", countryRaw);
        raw.put("text", text);
        raw.put("html", li.html());

        String place = subsection.isEmpty() ? section : section + " — " + subsection;
        return new RawEvent(
                ref.date().getYear() + "-" + number + "-" + index,
                ref.url(),
                disease.get().getCode() != null ? disease.get().getCode() : disease.get().getNameEn(),
                country.map(Country::getNameEn).orElse(countryRaw),
                country.map(Country::getIso3).orElse(null),
                region(text), null, null,
                null,
                ref.date(),
                textMatcher.species(text),
                null, null, null, null,
                EventStatus.NEW,
                text.length() <= MAX_TITLE ? text : text.substring(0, MAX_TITLE) + "…",
                place + "\n" + text,
                raw.toString(),
                trust(section));
    }

    /** Раздел со ссылкой на ВОЗЖ или официальные данные — подтверждённые сведения, остальное — сигнал. */
    static TrustLevel trust(String section) {
        String s = section.toLowerCase(Locale.ROOT);
        return s.contains("возж") || s.contains("мэб") || s.contains("официальн") ? TrustLevel.CONFIRMED : TrustLevel.SIGNAL;
    }

    static String region(String text) {
        Matcher m = REGION.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    static LocalDate parseDate(String text) {
        Matcher m = DATE.matcher(text);
        if (!m.find()) return null;
        int month = MONTHS.indexOf(m.group(2).toLowerCase(Locale.ROOT)) + 1;
        if (month == 0) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1)));
        } catch (Exception e) {
            return null;
        }
    }

    private String get(String url) {
        String html = http.execute(rc -> rc.get().uri(URI.create(url)).retrieve().body(String.class));
        if (html == null || html.isBlank()) {
            throw new IllegalStateException("Пустой ответ: " + url);
        }
        return html;
    }
}
