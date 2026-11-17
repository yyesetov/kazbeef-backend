package kz.kazbeef.admin;

import jakarta.servlet.http.HttpServletRequest;
import kz.kazbeef.collector.CollectorRunner;
import kz.kazbeef.domain.CollectRun;
import kz.kazbeef.domain.CollectRunRepository;
import kz.kazbeef.domain.SourceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Этап 1: JSON-эндпоинты администратора. Страницы админки появятся на этапе 2. */
@RestController
@RequestMapping("/admin")
public class CollectorAdminController {

    private final CollectorRunner runner;
    private final SourceRepository sources;
    private final CollectRunRepository runs;

    public CollectorAdminController(CollectorRunner runner, SourceRepository sources, CollectRunRepository runs) {
        this.runner = runner;
        this.sources = sources;
        this.runs = runs;
    }

    /** CSRF-токен для вызовов из curl/скриптов (вместе с cookie сессии). */
    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    /**
     * Ручной запуск сборщика (ФТ-20). Выполняется в фоне, результат в журнале запусков.
     *
     * @param days догрузить историю за столько дней; без параметра — только новое с прошлого запуска
     */
    @PostMapping("/collectors/{code}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String code,
                                                   @RequestParam(required = false) Integer days) {
        Instant since = days == null ? null : Instant.now().minus(Duration.ofDays(Math.clamp(days, 1, 3650)));
        boolean started = runner.runAsync(code.toUpperCase(), since);
        return started
                ? ResponseEntity.accepted().body(Map.of("source", code, "status", "started"))
                : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("source", code, "status", "already running"));
    }

    /** Состояние источников и последние запуски (ФТ-19). */
    @GetMapping("/collectors")
    @Transactional(readOnly = true)
    public List<SourceView> collectors() {
        return sources.findAll().stream()
                .map(s -> new SourceView(s.getCode(), s.getName(), s.getTrustLevel().dbValue(), s.isActive(),
                        runner.sourceCodes().contains(s.getCode()), runner.isRunning(s.getCode()),
                        s.getLastSuccessAt(), s.getNote(),
                        runs.findTop20BySourceOrderByStartedAtDesc(s).stream().map(RunView::of).toList()))
                .toList();
    }

    @ExceptionHandler(CollectorRunner.UnknownCollectorException.class)
    public ResponseEntity<Map<String, String>> unknown(CollectorRunner.UnknownCollectorException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    public record SourceView(String code, String name, String trustLevel, boolean active, boolean hasCollector,
                             boolean running, Instant lastSuccessAt, String note, List<RunView> recentRuns) {
    }

    public record RunView(long id, Instant startedAt, Instant finishedAt, String status, int fetched,
                          int created, int updated, int failed, String error) {
        static RunView of(CollectRun r) {
            return new RunView(r.getId(), r.getStartedAt(), r.getFinishedAt(), r.getStatus().name(),
                    r.getFetchedCount(), r.getNewCount(), r.getUpdatedCount(), r.getFailedCount(), r.getErrorText());
        }
    }
}
