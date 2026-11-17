package kz.kazbeef.ingest;

import kz.kazbeef.domain.Event;
import kz.kazbeef.domain.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Пересчёт производных полей всех событий. Нужен после изменения справочников
 * (новые виды, синонимы, связи «болезнь — вид»): сами записи источников при этом не меняются.
 */
@Service
public class EventMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(EventMaintenanceService.class);

    private final EventRepository events;
    private final EventClassifier classifier;
    private final TransactionTemplate tx;

    public EventMaintenanceService(EventRepository events, EventClassifier classifier, TransactionTemplate tx) {
        this.events = events;
        this.classifier = classifier;
        this.tx = tx;
    }

    /** После старта: миграция могла расширить справочники. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            recomputeAll();
        } catch (Exception e) {
            log.error("Не удалось пересчитать производные поля событий", e);
        }
    }

    /** Раз в сутки: балл риска зависит от давности события. */
    @Scheduled(cron = "${kazbeef.risk.recompute-cron:0 30 4 * * *}", zone = "Asia/Almaty")
    public void daily() {
        onStartup();
    }

    /** Возвращает число изменённых событий. */
    public int recomputeAll() {
        Integer changed = tx.execute(status -> {
            int n = 0;
            for (Event e : events.findAll()) {
                if (classifier.classify(e)) n++;
            }
            return n;
        });
        log.info("Пересчёт производных полей событий: изменено {}", changed);
        return changed == null ? 0 : changed;
    }
}
