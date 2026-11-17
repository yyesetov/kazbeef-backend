package kz.kazbeef.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import kz.kazbeef.domain.Event;
import kz.kazbeef.domain.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Перевод события на русский и краткое резюме (Claude). Работает очередью: берёт события
 * без результата, обрабатывает пачку и сохраняет каждое в своей транзакции.
 * Числа, даты, болезнь, страна и виды в событии ИИ не меняет — только текстовые поля
 * translation и ai_summary, которые в интерфейсе помечены как сгенерированные.
 */
@Service
public class AiEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(AiEnrichmentService.class);
    /** Короткий текст, который уже на русском, в переводе и резюме не нуждается. */
    private static final int SHORT_RUSSIAN_TEXT = 400;

    private static final String SYSTEM = """
            Ты помогаешь ветеринарному аналитику в Казахстане, который следит за болезнями крупного \
            и мелкого рогатого скота. На вход приходит одно сообщение о болезни животных: заголовок, \
            описание и поля, уже извлечённые из источника.

            Верни три поля:
            - titleRu: заголовок на русском языке;
            - textRu: полный перевод описания на русский язык. Если описания нет, верни пустую строку;
            - summaryRu: резюме в 2–3 предложениях: какая болезнь, где, у каких животных, масштаб и статус.

            Правила. Используй только сведения из сообщения: не добавляй фактов, оценок и рекомендаций, \
            которых в нём нет. Числа, даты, названия мест и серотипы переноси точно. Названия болезней \
            пиши так, как принято в русскоязычной ветеринарии (ящур, нодулярный дерматит, чума мелких \
            жвачных). Если текст уже на русском, перенеси его в titleRu и textRu без изменений.
            """;

    /** Ответ модели. Схема строится по этому классу. */
    public record Enrichment(
            @JsonPropertyDescription("Заголовок на русском языке") String titleRu,
            @JsonPropertyDescription("Перевод описания на русский язык; пустая строка, если описания нет") String textRu,
            @JsonPropertyDescription("Резюме на русском языке, 2-3 предложения") String summaryRu) {
    }

    private record Job(long id, String prompt, boolean skip) {
    }

    private final AiProperties props;
    private final EventRepository events;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile AnthropicClient client;

    public AiEnrichmentService(AiProperties props, EventRepository events, TransactionTemplate tx) {
        this.props = props;
        this.events = events;
        this.tx = tx;
        if (!props.enabled()) {
            log.info("ИИ отключён: не задан ANTHROPIC_API_KEY");
        }
    }

    public boolean enabled() {
        return props.enabled();
    }

    public String model() {
        return props.model();
    }

    @Scheduled(cron = "${kazbeef.ai.cron:0 */10 * * * *}", zone = "Asia/Almaty")
    public void scheduled() {
        if (props.enabled()) {
            processBatch();
        }
    }

    /** Обрабатывает одну пачку. Возвращает число событий, получивших перевод и резюме. */
    public int processBatch() {
        if (!props.enabled() || !running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<Job> jobs = tx.execute(s -> {
                List<Job> list = new ArrayList<>();
                for (Event e : events.findAiQueue((short) props.maxAttempts(), props.batchSize())) {
                    list.add(new Job(e.getId(), prompt(e), isShortRussian(e)));
                }
                return list;
            });
            int done = 0;
            for (Job job : jobs == null ? List.<Job>of() : jobs) {
                if (job.skip()) {
                    save(job.id(), null, false);
                    continue;
                }
                try {
                    save(job.id(), call(job.prompt()), false);
                    done++;
                } catch (RateLimitException e) {
                    log.warn("ИИ: превышен лимит запросов, пачка прервана до следующего прохода");
                    break;
                } catch (AnthropicServiceException e) {
                    log.warn("ИИ: событие {} не обработано: HTTP {} {}", job.id(), e.statusCode(), e.getMessage());
                    save(job.id(), null, true);
                } catch (Exception e) {
                    log.warn("ИИ: событие {} не обработано: {}", job.id(), e.toString());
                    save(job.id(), null, true);
                }
            }
            if (done > 0) {
                log.info("ИИ: обработано событий: {}", done);
            }
            return done;
        } finally {
            running.set(false);
        }
    }

    private Enrichment call(String prompt) {
        StructuredMessageCreateParams<Enrichment> params = MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(8000L)
                .system(SYSTEM)
                .outputConfig(Enrichment.class)
                .addUserMessage(prompt)
                .build();
        return client().messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                // отказ модели или пустой ответ: текста нет
                .orElseThrow(() -> new IllegalStateException("модель не вернула результат"));
    }

    /**
     * @param result  результат модели; {@code null} — пропуск или ошибка
     * @param failure ошибка: увеличить счётчик попыток; иначе при {@code null} событие снимается с очереди
     */
    private void save(long id, Enrichment result, boolean failure) {
        tx.executeWithoutResult(s -> events.findById(id).ifPresent(e -> {
            if (result != null) {
                String text = result.textRu() == null || result.textRu().isBlank() ? "" : "\n\n" + result.textRu().trim();
                e.setTranslation(result.titleRu() == null ? null : result.titleRu().trim() + text);
                e.setAiSummary(result.summaryRu() == null || result.summaryRu().isBlank() ? null : result.summaryRu().trim());
                e.setAiGenerated(true);
            } else if (failure) {
                e.setAiAttempts((short) (e.getAiAttempts() + 1));
            } else {
                e.setAiAttempts((short) props.maxAttempts());
            }
        }));
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder().apiKey(props.apiKey()).build();
                }
                c = client;
            }
        }
        return c;
    }

    private static boolean isShortRussian(Event e) {
        String text = (e.getTitle() == null ? "" : e.getTitle()) + (e.getDescription() == null ? "" : e.getDescription());
        long cyrillic = text.chars().filter(ch -> Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC).count();
        long letters = text.chars().filter(Character::isLetter).count();
        return letters > 0 && cyrillic * 2 > letters && text.length() < SHORT_RUSSIAN_TEXT;
    }

    /** Сообщение целиком, без усечения. Вызывается внутри транзакции. */
    private static String prompt(Event e) {
        StringBuilder sb = new StringBuilder();
        line(sb, "Заголовок", e.getTitle());
        line(sb, "Описание", e.getDescription());
        sb.append("\nПоля из источника:\n");
        line(sb, "Болезнь", e.getDisease() != null ? e.getDisease().getNameEn() : e.getRawDiseaseName());
        line(sb, "Страна", e.getCountry() != null ? e.getCountry().getNameEn() : e.getRawCountryName());
        line(sb, "Регион", e.getRegion());
        line(sb, "Виды животных", e.getRawSpecies());
        line(sb, "Начало", e.getStartDate());
        line(sb, "Дата отчёта", e.getReportDate());
        line(sb, "Статус", e.getStatus() == null ? null : e.getStatus().dbValue());
        line(sb, "Очагов", e.getOutbreaks());
        line(sb, "Восприимчивых", e.getSusceptible());
        line(sb, "Случаев", e.getCases());
        line(sb, "Пало", e.getDeaths());
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, Object value) {
        if (value != null && !value.toString().isBlank()) {
            sb.append(label).append(": ").append(value).append('\n');
        }
    }
}
