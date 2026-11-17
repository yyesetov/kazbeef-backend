package kz.kazbeef.admin;

import kz.kazbeef.ai.AiEnrichmentService;
import kz.kazbeef.ingest.EventMaintenanceService;
import kz.kazbeef.notify.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Служебные действия администратора: состояние ИИ и оповещений, ручные запуски. */
@RestController
@RequestMapping("/admin")
public class SystemAdminController {

    private final AiEnrichmentService ai;
    private final NotificationService notifications;
    private final EventMaintenanceService maintenance;
    private final JdbcTemplate jdbc;

    public SystemAdminController(AiEnrichmentService ai, NotificationService notifications,
                                 EventMaintenanceService maintenance, JdbcTemplate jdbc) {
        this.ai = ai;
        this.notifications = notifications;
        this.maintenance = maintenance;
        this.jdbc = jdbc;
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        Map<String, Object> aiState = new LinkedHashMap<>();
        aiState.put("enabled", ai.enabled());
        aiState.put("model", ai.model());
        aiState.put("processed", jdbc.queryForObject("SELECT count(*) FROM event WHERE ai_generated", Long.class));
        aiState.put("total", jdbc.queryForObject("SELECT count(*) FROM event", Long.class));

        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("channels", notifications.channels());
        notify.put("alertsSent", jdbc.queryForObject("SELECT count(*) FROM notification_log WHERE kind = 'alert' AND ok", Long.class));
        notify.put("lastError", jdbc.queryForList(
                "SELECT error_text FROM notification_log WHERE NOT ok ORDER BY sent_at DESC LIMIT 1", String.class)
                .stream().findFirst().orElse(null));
        return Map.of("ai", aiState, "notify", notify);
    }

    /** Обработать одну пачку очереди ИИ сейчас. */
    @PostMapping("/ai/run")
    public Map<String, Object> runAi() {
        return Map.of("processed", ai.processBatch());
    }

    /** Пробное сообщение во все настроенные каналы. */
    @PostMapping("/notify/test")
    public Map<String, String> testNotify() {
        return notifications.sendTest();
    }

    /** Отправить дайджест сейчас, даже если сегодня он уже уходил. */
    @PostMapping("/notify/digest")
    public Map<String, Object> digest() {
        return Map.of("sent", notifications.sendDigest(true));
    }

    /** Пересчитать виды, значимость и риск всех событий. */
    @PostMapping("/maintenance/recompute")
    public Map<String, Object> recompute() {
        return Map.of("changed", maintenance.recomputeAll());
    }
}
