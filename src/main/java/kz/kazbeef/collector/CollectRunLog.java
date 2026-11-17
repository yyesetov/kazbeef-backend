package kz.kazbeef.collector;

import kz.kazbeef.domain.CollectRun;
import kz.kazbeef.domain.CollectRunRepository;
import kz.kazbeef.domain.RunStatus;
import kz.kazbeef.domain.Source;
import kz.kazbeef.domain.SourceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Журнал запусков (collect_run). Каждая операция в своей транзакции. */
@Component
public class CollectRunLog {

    private final SourceRepository sources;
    private final CollectRunRepository runs;

    public CollectRunLog(SourceRepository sources, CollectRunRepository runs) {
        this.sources = sources;
        this.runs = runs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CollectRun start(String sourceCode) {
        CollectRun run = new CollectRun();
        run.setSource(requireSource(sourceCode));
        run.setStartedAt(Instant.now());
        run.setStatus(RunStatus.RUNNING);
        return runs.save(run);
    }

    @Transactional(readOnly = true)
    public Instant lastSuccessAt(String sourceCode) {
        return requireSource(sourceCode).getLastSuccessAt();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CollectRun finish(long runId, RunStatus status, int fetched, int created, int updated, int failed, String error) {
        CollectRun run = runs.findById(runId).orElseThrow();
        Instant now = Instant.now();
        run.setFinishedAt(now);
        run.setStatus(status);
        run.setFetchedCount(fetched);
        run.setNewCount(created);
        run.setUpdatedCount(updated);
        run.setFailedCount(failed);
        run.setErrorText(error);
        if (status == RunStatus.SUCCESS || status == RunStatus.PARTIAL) {
            // Окно следующего запуска отсчитывается от начала текущего: ничего не теряем
            run.getSource().setLastSuccessAt(run.getStartedAt());
        }
        return run;
    }

    private Source requireSource(String sourceCode) {
        return sources.findByCode(sourceCode)
                .orElseThrow(() -> new IllegalStateException("Нет записи в таблице source для " + sourceCode));
    }
}
