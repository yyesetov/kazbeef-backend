package kz.kazbeef.notify;

import kz.kazbeef.api.EventQueries;
import kz.kazbeef.risk.RiskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Оповещения в Telegram и по почте.
 * <ul>
 *   <li>Мгновенные: событие в Казахстане, в приграничной зоне или с риском не ниже порога.
 *       Проверяются после каждого запуска сборщика.</li>
 *   <li>Ежедневный дайджест: что появилось за сутки.</li>
 * </ul>
 * Каждая успешная отправка записывается в notification_log, поэтому повторный запуск
 * сборщика не шлёт то же оповещение второй раз. Канал без настроек отключён.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");
    private static final int MAX_ALERTS_PER_RUN = 20;
    private static final Map<String, String> STATUS = Map.of("new", "новое", "ongoing", "продолжается", "closed", "закрыто");
    private static final Map<String, String> TRUST = Map.of("confirmed", "подтверждено", "signal", "сигнал, не подтверждено", "analytics", "аналитика");

    private final NotifyProperties props;
    private final RiskProperties risk;
    private final JdbcTemplate jdbc;
    private final EventQueries queries;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String smtpHost;
    private final RestClient telegram = RestClient.builder().baseUrl("https://api.telegram.org").build();

    public NotificationService(NotifyProperties props, RiskProperties risk, JdbcTemplate jdbc, EventQueries queries,
                               ObjectProvider<JavaMailSender> mailSender, @Value("${spring.mail.host:}") String smtpHost) {
        this.props = props;
        this.risk = risk;
        this.jdbc = jdbc;
        this.queries = queries;
        this.mailSender = mailSender;
        this.smtpHost = smtpHost;
        log.info("Оповещения: каналы {}", channels().isEmpty() ? "не настроены" : channels());
    }

    public List<String> channels() {
        List<String> list = new ArrayList<>();
        if (props.telegram().enabled()) list.add("telegram");
        if (props.email().enabled() && smtpHost != null && !smtpHost.isBlank()) list.add("email");
        return list;
    }

    // ---------- мгновенные оповещения ----------

    /** Отправляет оповещения о событиях, по которым их ещё не было. Не бросает исключений. */
    public int sendAlerts() {
        int sent = 0;
        for (String channel : channels()) {
            try {
                List<Long> ids = jdbc.queryForList("""
                        SELECT e.id FROM event e LEFT JOIN country c ON c.id = e.country_id
                         WHERE e.trust_level <> 'analytics'
                           AND (NOT ? OR e.ruminant_relevant)
                           AND coalesce(e.report_date, e.start_date) >= current_date - ?
                           AND (c.iso3 = 'KAZ' OR e.distance_to_kz_km <= ? OR e.risk_score >= ?)
                           AND NOT EXISTS (SELECT 1 FROM notification_log n
                                            WHERE n.event_id = e.id AND n.channel = ? AND n.kind = 'alert' AND n.ok)
                         ORDER BY e.risk_score DESC NULLS LAST, e.id
                         LIMIT ?
                        """, Long.class, props.ruminantOnly(), props.maxAgeDays(), risk.borderZoneKm(),
                        props.riskThreshold(), channel, MAX_ALERTS_PER_RUN);
                for (Long id : ids) {
                    Map<String, Object> event = queries.detail(id);
                    if (event == null) continue;
                    String error = deliver(channel, "KazBeef: " + event.get("disease") + " — " + event.get("country"), alertText(event));
                    jdbc.update("INSERT INTO notification_log (event_id, kind, channel, ok, error_text) VALUES (?, 'alert', ?, ?, ?)",
                            id, channel, error == null, error);
                    if (error != null) {
                        log.warn("Оповещение о событии {} в {} не отправлено: {}", id, channel, error);
                        break; // канал не работает: не перебираем остальные события
                    }
                    sent++;
                }
            } catch (Exception e) {
                log.error("Сбой оповещений в канале {}", channel, e);
            }
        }
        if (sent > 0) {
            log.info("Отправлено оповещений: {}", sent);
        }
        return sent;
    }

    private String alertText(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder();
        sb.append(reason(e)).append('\n');
        sb.append(e.get("disease")).append(" — ").append(e.get("country"));
        if (e.get("region") != null) sb.append(", ").append(e.get("region"));
        sb.append('\n');
        sb.append("Риск: ").append(riskLabel(e.get("riskScore")));
        if (e.get("distanceKm") != null) {
            double km = ((Number) e.get("distanceKm")).doubleValue();
            sb.append(km <= 0 ? " · внутри Казахстана" : " · " + Math.round(km) + " км до границы РК");
        }
        sb.append('\n');
        sb.append("Дата: ").append(date(e.get("eventDate"))).append(" · статус: ").append(STATUS.getOrDefault(String.valueOf(e.get("status")), "—")).append('\n');
        if (e.get("outbreaks") != null || e.get("cases") != null || e.get("deaths") != null) {
            sb.append("Очагов: ").append(num(e.get("outbreaks"))).append(" · случаев: ").append(num(e.get("cases")))
                    .append(" · падёж: ").append(num(e.get("deaths"))).append('\n');
        }
        if (e.get("species") != null) sb.append("Виды: ").append(e.get("species")).append('\n');
        sb.append("Источники: ").append(String.valueOf(e.get("sources")).replace(",", ", "))
                .append(" (").append(TRUST.getOrDefault(String.valueOf(e.get("trustLevel")), "—")).append(")\n");
        if (e.get("aiSummary") != null) sb.append('\n').append(e.get("aiSummary")).append(" (резюме ИИ)\n");
        else if (e.get("title") != null) sb.append('\n').append(e.get("title")).append('\n');
        Object records = e.get("sourceRecords");
        if (records instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first && first.get("url") != null) {
            sb.append('\n').append(first.get("url")).append('\n');
        }
        sb.append(props.publicUrl()).append("/events");
        return sb.toString();
    }

    private String reason(Map<String, Object> e) {
        if ("KAZ".equals(e.get("iso3"))) return "Новое событие в Казахстане";
        Object km = e.get("distanceKm");
        if (km instanceof Number n && n.doubleValue() <= risk.borderZoneKm()) {
            return "Новое событие в приграничной зоне (" + Math.round(risk.borderZoneKm()) + " км)";
        }
        return "Новое событие с высоким риском";
    }

    // ---------- дайджест ----------

    @Scheduled(cron = "${kazbeef.notify.digest-cron:0 0 9 * * *}", zone = "Asia/Almaty")
    public void scheduledDigest() {
        sendDigest(false);
    }

    /** @param force отправить, даже если дайджест за сегодня уже уходил (ручная проверка) */
    public int sendDigest(boolean force) {
        LocalDate day = LocalDate.now(ALMATY);
        int sent = 0;
        for (String channel : channels()) {
            try {
                Integer already = jdbc.queryForObject(
                        "SELECT count(*) FROM notification_log WHERE kind = 'digest' AND channel = ? AND digest_day = ? AND ok",
                        Integer.class, channel, day);
                if (!force && already != null && already > 0) continue;
                String error = deliver(channel, "KazBeef: сводка за сутки, " + date(day.toString()), digestText());
                if (error == null && (already == null || already == 0)) {
                    jdbc.update("INSERT INTO notification_log (kind, channel, digest_day, ok) VALUES ('digest', ?, ?, TRUE)", channel, day);
                } else if (error != null) {
                    jdbc.update("INSERT INTO notification_log (kind, channel, digest_day, ok, error_text) VALUES ('digest', ?, ?, FALSE, ?)",
                            channel, day, error);
                    log.warn("Дайджест в {} не отправлен: {}", channel, error);
                    continue;
                }
                sent++;
            } catch (Exception e) {
                log.error("Сбой дайджеста в канале {}", channel, e);
            }
        }
        return sent;
    }

    private String digestText() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT coalesce(d.name_ru, d.name_en, e.raw_disease_name, 'Болезнь не указана') AS disease,
                       coalesce(c.name_ru, c.name_en, e.raw_country_name, 'Страна не указана') AS country,
                       e.region, e.risk_score AS risk, e.trust_level AS trust, e.outbreaks, e.cases
                  FROM event e LEFT JOIN disease d ON d.id = e.disease_id LEFT JOIN country c ON c.id = e.country_id
                 WHERE e.created_at >= now() - interval '24 hours' AND e.trust_level <> 'analytics'
                   AND (NOT ? OR e.ruminant_relevant)
                 ORDER BY e.risk_score DESC NULLS LAST, e.id
                """, props.ruminantOnly());
        StringBuilder sb = new StringBuilder("Сводка за сутки");
        sb.append(props.ruminantOnly() ? " (КРС и МРС)\n" : "\n");
        if (rows.isEmpty()) {
            return sb.append("Новых событий нет.\n").append(props.publicUrl()).toString();
        }
        Map<String, Integer> byCountry = new LinkedHashMap<>();
        rows.forEach(r -> byCountry.merge(String.valueOf(r.get("country")), 1, Integer::sum));
        sb.append("Новых событий: ").append(rows.size()).append(" — ");
        sb.append(String.join(", ", byCountry.entrySet().stream().map(en -> en.getKey() + " " + en.getValue()).toList())).append("\n\n");
        int shown = 0;
        for (Map<String, Object> r : rows) {
            if (shown++ == 10) {
                sb.append("…и ещё ").append(rows.size() - 10).append('\n');
                break;
            }
            sb.append("• ").append(r.get("disease")).append(" — ").append(r.get("country"));
            if (r.get("region") != null) sb.append(", ").append(r.get("region"));
            sb.append(" · риск ").append(riskLabel(r.get("risk")));
            if ("signal".equals(r.get("trust"))) sb.append(" · сигнал");
            sb.append('\n');
        }
        return sb.append('\n').append(props.publicUrl()).toString();
    }

    // ---------- доставка ----------

    /** Пробное сообщение во все настроенные каналы. Возвращает канал → ошибка или "ok". */
    public Map<String, String> sendTest() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String channel : channels()) {
            String error = deliver(channel, "KazBeef: проверка оповещений", "Проверка оповещений KazBeef. Канал настроен.\n" + props.publicUrl());
            result.put(channel, error == null ? "ok" : error);
        }
        return result;
    }

    /** Возвращает {@code null} при успехе или текст ошибки. */
    private String deliver(String channel, String subject, String text) {
        try {
            if ("telegram".equals(channel)) {
                telegram.post()
                        .uri("/bot{token}/sendMessage", props.telegram().botToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("chat_id", props.telegram().chatId(), "text", text, "disable_web_page_preview", true))
                        .retrieve().toBodilessEntity();
            } else {
                JavaMailSender sender = mailSender.getIfAvailable();
                if (sender == null) return "почтовый клиент не настроен";
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(props.email().to().split("\\s*,\\s*"));
                if (props.email().from() != null && !props.email().from().isBlank()) {
                    message.setFrom(props.email().from());
                }
                message.setSubject(subject);
                message.setText(text);
                sender.send(message);
            }
            return null;
        } catch (Exception e) {
            // в тексте ошибки может оказаться адрес с токеном бота
            String message = String.valueOf(e.getMessage());
            if (props.telegram().enabled()) message = message.replace(props.telegram().botToken(), "***");
            return e.getClass().getSimpleName() + ": " + (message.length() > 500 ? message.substring(0, 500) : message);
        }
    }

    private static String riskLabel(Object score) {
        if (!(score instanceof Number n)) return "не оценён";
        int s = n.intValue();
        return s + " (" + (s >= 75 ? "критический" : s >= 50 ? "высокий" : s >= 25 ? "средний" : "низкий") + ")";
    }

    private static String num(Object o) {
        return o == null ? "—" : String.valueOf(o);
    }

    private static String date(Object iso) {
        String s = iso == null ? "" : iso.toString();
        return s.length() >= 10 ? s.substring(8, 10) + "." + s.substring(5, 7) + "." + s.substring(0, 4) : "—";
    }
}
