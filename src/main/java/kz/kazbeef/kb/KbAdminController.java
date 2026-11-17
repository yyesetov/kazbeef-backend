package kz.kazbeef.kb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Управление базой знаний (только админ). */
@RestController
@RequestMapping("/admin/kb")
public class KbAdminController {

    private final KbIngestService ingest;
    private final KbStorage storage;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public KbAdminController(KbIngestService ingest, KbStorage storage, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.ingest = ingest;
        this.storage = storage;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/formats")
    public Map<String, Object> formats() {
        return Map.of("extensions", new TreeSet<>(ingest.supportedExtensions()), "maxFileSizeMb", 50);
    }

    /** Загрузка одного или нескольких файлов. Каждый обрабатывается в фоне; статус — в списке документов. */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Map<String, Object>> upload(@RequestParam("files") List<MultipartFile> files) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            try {
                if (file.isEmpty()) throw new IllegalArgumentException("Пустой файл");
                item.put("id", ingest.accept(name, file.getBytes()));
                item.put("status", "processing");
            } catch (IllegalArgumentException e) {
                item.put("status", "rejected");
                item.put("error", e.getMessage());
            }
            result.add(item);
        }
        return result;
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT d.id, d.original_name AS "originalName", d.content_type AS "format", d.original_size AS "originalSize",
                       d.title, d.summary, d.tags::text AS tags, d.status, d.error_text AS error,
                       d.md_size AS "markdownSize", d.chars,
                       (SELECT count(*) FROM kb_chunk c WHERE c.document_id = d.id) AS chunks,
                       d.created_at::text AS "createdAt", d.updated_at::text AS "updatedAt"
                  FROM kb_document d ORDER BY d.created_at DESC
                """).stream().map(this::withTags).toList();
    }

    @GetMapping("/documents/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return list().stream().filter(d -> ((Number) d.get("id")).longValue() == id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Markdown документа; download=true — файлом. */
    @GetMapping("/documents/{id}/markdown")
    public ResponseEntity<String> markdown(@PathVariable long id, @RequestParam(defaultValue = "false") boolean download) {
        if (!storage.exists(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Markdown ещё не готов");
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8));
        if (download) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(downloadName(id), StandardCharsets.UTF_8).build().toString());
        }
        return response.body(storage.read(id));
    }

    /** Замена Markdown, исправленного вручную (тело — текст Markdown). Поиск пересобирается сразу. */
    @PutMapping(value = "/documents/{id}/markdown", consumes = {"text/markdown", MediaType.TEXT_PLAIN_VALUE})
    public Map<String, Object> replaceMarkdown(@PathVariable long id, @RequestBody String markdown) {
        exists(id);
        if (markdown.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пустой документ");
        ingest.replaceMarkdown(id, markdown);
        return get(id);
    }

    public record MetaUpdate(String title, String summary, List<String> tags) {
    }

    @PutMapping("/documents/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody MetaUpdate body) throws JsonProcessingException {
        exists(id);
        jdbc.update("UPDATE kb_document SET title = ?, summary = ?, tags = ?::jsonb, updated_at = now() WHERE id = ?",
                blankToNull(body.title()), blankToNull(body.summary()),
                mapper.writeValueAsString(body.tags() == null ? List.of() : body.tags()), id);
        return get(id);
    }

    @PostMapping("/documents/{id}/reprocess")
    public Map<String, Object> reprocess(@PathVariable long id) {
        exists(id);
        if (!storage.exists(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Markdown не создан: загрузите файл заново");
        }
        ingest.reprocess(id);
        return get(id);
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        exists(id);
        ingest.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Последние вопросы посетителей; unanswered=true — только те, на которые в базе ничего не нашлось. */
    @GetMapping("/questions")
    public List<Map<String, Object>> questions(@RequestParam(defaultValue = "false") boolean unanswered,
                                               @RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("""
                SELECT id, asked_at::text AS "askedAt", question, found, document_ids::text AS "documentIds"
                  FROM kb_chat_log WHERE (NOT ? OR NOT found) ORDER BY asked_at DESC LIMIT ?
                """, unanswered, Math.clamp(limit, 1, 500));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "Файл больше 50 МБ"));
    }

    private Map<String, Object> withTags(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        try {
            copy.put("tags", mapper.readValue(String.valueOf(row.get("tags")), List.class));
        } catch (JsonProcessingException e) {
            copy.put("tags", List.of());
        }
        return copy;
    }

    private void exists(long id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM kb_document WHERE id = ?", Integer.class, id);
        if (n == null || n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private String downloadName(long id) {
        String name = jdbc.queryForObject("SELECT original_name FROM kb_document WHERE id = ?", String.class, id);
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + ".md";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
