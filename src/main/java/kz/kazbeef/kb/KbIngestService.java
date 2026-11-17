package kz.kazbeef.kb;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.kazbeef.ai.AiProperties;
import kz.kazbeef.kb.convert.MarkdownConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Приём документов в базу знаний: конвертация в Markdown, запись файла, нарезка на фрагменты
 * для поиска и (если задан ключ) название, описание и теги от ИИ. Обработка идёт в фоне.
 */
@Service
public class KbIngestService {

    private static final Logger log = LoggerFactory.getLogger(KbIngestService.class);
    private static final int META_SAMPLE = 20_000;
    private static final int MIN_TEXT = 40;

    private static final String META_SYSTEM = """
            Ты каталогизируешь документы базы знаний ветеринарной службы: инструкции, правила техники \
            безопасности, методички. По тексту документа верни:
            - title: короткое понятное название документа на русском языке (до 120 символов). Если в тексте \
            есть официальное название, используй его;
            - summary: о чём документ, 2–3 предложения на русском языке;
            - tags: 3–8 ключевых слов на русском языке в нижнем регистре.
            Опирайся только на текст документа.
            """;

    public record Meta(
            @JsonPropertyDescription("Название документа на русском языке") String title,
            @JsonPropertyDescription("О чём документ, 2-3 предложения") String summary,
            @JsonPropertyDescription("Ключевые слова в нижнем регистре") List<String> tags) {
    }

    private final Map<String, MarkdownConverter> converters = new HashMap<>();
    private final KbStorage storage;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final AiProperties ai;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile AnthropicClient client;

    public KbIngestService(List<MarkdownConverter> converters, KbStorage storage, JdbcTemplate jdbc,
                           TransactionTemplate tx, ObjectMapper mapper, AiProperties ai) {
        converters.forEach(c -> c.extensions().forEach(ext -> this.converters.put(ext, c)));
        this.storage = storage;
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.ai = ai;
    }

    public Set<String> supportedExtensions() {
        return converters.keySet();
    }

    /** Документы, оставшиеся «в обработке» после перезапуска, обрабатываются заново или помечаются ошибкой. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeUnfinished() {
        jdbc.update("""
                UPDATE kb_document SET status = 'failed', error_text = 'Обработка прервана перезапуском сервиса, запустите её заново'
                 WHERE status = 'processing'
                """);
    }

    /** Принимает файл и ставит его в обработку. Возвращает id документа. */
    public long accept(String originalName, byte[] bytes) {
        String ext = extension(originalName);
        if (!converters.containsKey(ext)) {
            throw new IllegalArgumentException("Формат «." + ext + "» не поддерживается. Поддерживаются: " + String.join(", ", supportedExtensions()));
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO kb_document (original_name, content_type, original_size, title, status)
                VALUES (?, ?, ?, ?, 'processing') RETURNING id
                """, Long.class, originalName, ext, (long) bytes.length, titleFromFileName(originalName));
        executor.submit(() -> process(id, originalName, bytes));
        return id;
    }

    /** Повторная обработка по сохранённому Markdown: заново нарезка и описание от ИИ. */
    public void reprocess(long id) {
        jdbc.update("UPDATE kb_document SET status = 'processing', error_text = NULL, updated_at = now() WHERE id = ?", id);
        executor.submit(() -> {
            try {
                String md = storage.read(id);
                index(id, md, true);
            } catch (Exception e) {
                fail(id, e);
            }
        });
    }

    /** Замена Markdown, исправленного вручную. */
    public void replaceMarkdown(long id, String markdown) {
        jdbc.update("UPDATE kb_document SET status = 'processing', error_text = NULL, updated_at = now() WHERE id = ?", id);
        try {
            index(id, markdown, false);
        } catch (Exception e) {
            fail(id, e);
            throw e;
        }
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM kb_document WHERE id = ?", id);
        storage.delete(id);
    }

    private void process(long id, String originalName, byte[] bytes) {
        try {
            String body = converters.get(extension(originalName)).toMarkdown(new ByteArrayInputStream(bytes));
            if (body.replaceAll("[#*|\\-\\s]", "").length() < MIN_TEXT) {
                throw new IllegalStateException("В документе нет текста: возможно, это скан или изображения. Нужен файл с текстовым слоем.");
            }
            String md = "<!-- Источник: " + originalName.replace("--", "—") + " · загружен "
                    + LocalDate.now(ZoneId.of("Asia/Almaty")).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " -->\n\n" + body;
            index(id, md, true);
            log.info("База знаний: документ {} «{}» обработан ({} → {} символов Markdown)", id, originalName, bytes.length, md.length());
        } catch (Exception e) {
            fail(id, e);
        }
    }

    /** Записывает файл, пересобирает фрагменты; при withMeta — описание от ИИ. */
    private void index(long id, String markdown, boolean withMeta) {
        long size = storage.write(id, markdown);
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown);
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM kb_chunk WHERE document_id = ?", id);
            int position = 0;
            for (MarkdownChunker.Chunk c : chunks) {
                jdbc.update("INSERT INTO kb_chunk (document_id, position, heading, content) VALUES (?, ?, ?, ?)",
                        id, position++, c.heading(), c.content());
            }
            jdbc.update("""
                    UPDATE kb_document SET md_path = ?, md_size = ?, chars = ?, status = 'ready', error_text = NULL, updated_at = now()
                     WHERE id = ?
                    """, storage.fileName(id), size, markdown.length(), id);
        });
        if (withMeta && ai.enabled()) {
            try {
                Meta meta = describe(markdown);
                jdbc.update("UPDATE kb_document SET title = ?, summary = ?, tags = ?::jsonb, updated_at = now() WHERE id = ?",
                        blankToNull(meta.title()), blankToNull(meta.summary()), json(meta.tags()), id);
            } catch (Exception e) {
                // документ уже доступен для поиска; без описания он просто показывается под именем файла
                log.warn("База знаний: не удалось получить описание документа {}: {}", id, e.toString());
            }
        }
    }

    private Meta describe(String markdown) {
        String sample = markdown.length() > META_SAMPLE ? markdown.substring(0, META_SAMPLE) : markdown;
        StructuredMessageCreateParams<Meta> params = MessageCreateParams.builder()
                .model(ai.model())
                .maxTokens(2000L)
                .system(META_SYSTEM)
                .outputConfig(Meta.class)
                .addUserMessage("Текст документа (Markdown):\n\n" + sample)
                .build();
        return client().messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("модель не вернула описание"));
    }

    private void fail(long id, Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        log.warn("База знаний: документ {} не обработан: {}", id, message);
        jdbc.update("UPDATE kb_document SET status = 'failed', error_text = ?, updated_at = now() WHERE id = ?",
                message.length() > 2000 ? message.substring(0, 2000) : message, id);
    }

    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) client = AnthropicOkHttpClient.builder().apiKey(ai.apiKey()).build();
            }
        }
        return client;
    }

    private String json(List<String> tags) {
        try {
            return mapper.writeValueAsString(tags == null ? List.of() : tags.stream().map(String::strip).filter(t -> !t.isEmpty()).limit(10).toList());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String titleFromFileName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.replace('_', ' ').strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
