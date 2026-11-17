package kz.kazbeef.collector;

import kz.kazbeef.domain.CollectRun;
import kz.kazbeef.domain.RunStatus;
import kz.kazbeef.ingest.IngestOutcome;
import kz.kazbeef.ingest.IngestService;
import kz.kazbeef.notify.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Единый конвейер запуска сборщиков: журнал запуска → collect → запись каждой записи
 * в отдельной транзакции. Любая ошибка сборщика перехватывается и пишется в collect_run,
 * поэтому сбой одного источника не влияет на остальные (ФТ-6).
 */
@Service
public class CollectorRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectorRunner.class);
    private static final int MAX_ERROR_LENGTH = 4000;

    private final Map<String, Collector> collectors;
    private final CollectRunLog runLog;
    private final IngestService ingestService;
    private final NotificationService notifications;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService manualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CollectorRunner(List<Collector> collectors, CollectRunLog runLog, IngestService ingestService,
                           NotificationService notifications) {
        this.collectors = collectors.stream()
                .collect(Collectors.toMap(Collector::sourceCode, Function.identity()));
        this.runLog = runLog;
        this.ingestService = ingestService;
        this.notifications = notifications;
    }

    public Set<String> sourceCodes() {
        return collectors.keySet();
    }

    public boolean isRunning(String sourceCode) {
        return running.contains(sourceCode);
    }

    /** Запуск в фоне (ручной запуск из админки). false, если сборщик уже работает. */
    public boolean runAsync(String sourceCode) {
        return runAsync(sourceCode, null);
    }

    /**
     * @param sinceOverride начало окна сбора вместо времени последнего успешного запуска
     *                      (догрузка истории); {@code null} — обычный запуск
     */
    public boolean runAsync(String sourceCode, Instant sinceOverride) {
        requireCollector(sourceCode);
        if (isRunning(sourceCode)) {
            return false;
        }
        manualExecutor.submit(() -> run(sourceCode, sinceOverride));
        return true;
    }

    /** Синхронный запуск. Не бросает исключений: результат пишется в журнал. */
    public Optional<CollectRun> run(String sourceCode) {
        return run(sourceCode, null);
    }

    public Optional<CollectRun> run(String sourceCode, Instant sinceOverride) {
        Collector collector = requireCollector(sourceCode);
        if (!running.add(sourceCode)) {
            log.warn("Сборщик {} уже выполняется, запуск пропущен", sourceCode);
            return Optional.empty();
        }
        CollectRun run = null;
        try {
            run = runLog.start(sourceCode);
            Instant since = sinceOverride != null ? sinceOverride : runLog.lastSuccessAt(sourceCode);
            log.info("Старт сборщика {} (since={})", sourceCode, since);

            CollectResult result = collector.collect(since);
            Counters c = ingestAll(sourceCode, result.events());

            RunStatus status = (c.failed > 0 || !result.warnings().isEmpty()) ? RunStatus.PARTIAL : RunStatus.SUCCESS;
            String warnings = result.warnings().isEmpty() ? null : truncate(String.join("\n", result.warnings()));
            CollectRun finished = runLog.finish(run.getId(), status, result.fetched(), c.created, c.updated, c.failed, warnings);
            log.info("Сборщик {} завершён: {} (прочитано {}, новых {}, обновлено {}, ошибок {}, предупреждений {})",
                    sourceCode, status, result.fetched(), c.created, c.updated, c.failed, result.warnings().size());
            if (c.created > 0 || c.updated > 0) {
                notifications.sendAlerts(); // сама перехватывает ошибки каналов
            }
            return Optional.of(finished);
        } catch (Exception e) {
            log.error("Сбой сборщика {}", sourceCode, e);
            if (run != null) {
                return Optional.of(runLog.finish(run.getId(), RunStatus.FAILED, 0, 0, 0, 0, truncate(describe(e))));
            }
            return Optional.empty();
        } finally {
            running.remove(sourceCode);
        }
    }

    private Counters ingestAll(String sourceCode, List<RawEvent> events) {
        Counters c = new Counters();
        for (RawEvent raw : events) {
            try {
                IngestOutcome outcome = ingestService.ingest(sourceCode, raw);
                switch (outcome) {
                    case CREATED -> c.created++;
                    case UPDATED -> c.updated++;
                    case UNCHANGED -> { }
                }
            } catch (Exception e) {
                c.failed++;
                log.warn("Не удалось записать {}/{}: {}", sourceCode, raw.externalId(), e.toString());
            }
        }
        return c;
    }

    private Collector requireCollector(String sourceCode) {
        Collector collector = collectors.get(sourceCode);
        if (collector == null) {
            throw new UnknownCollectorException(sourceCode);
        }
        return collector;
    }

    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder(e.toString());
        Throwable cause = e.getCause();
        while (cause != null && cause != e) {
            sb.append("\nCaused by: ").append(cause);
            e = cause;
            cause = cause.getCause();
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    private static final class Counters {
        int created;
        int updated;
        int failed;
    }

    public static class UnknownCollectorException extends RuntimeException {
        public UnknownCollectorException(String code) {
            super("Неизвестный сборщик: " + code);
        }
    }
}
