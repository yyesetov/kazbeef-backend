package kz.kazbeef.kb;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Публичный чат по базе знаний, без входа.
 * Ответ — поток Server-Sent Events: sources → delta… → done (или error).
 */
@RestController
@RequestMapping("/public/chat")
public class PublicChatController {

    private static final Logger log = LoggerFactory.getLogger(PublicChatController.class);
    private static final long TIMEOUT_MS = 120_000;
    private static final int MAX_HISTORY = 6;
    private static final int MAX_TURN_LENGTH = 4000;

    public record ChatRequest(String question, List<ChatService.Turn> history) {
    }

    private final ChatService chat;
    private final RateLimiter rateLimiter;
    private final KbProperties props;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public PublicChatController(ChatService chat, RateLimiter rateLimiter, KbProperties props) {
        this.chat = chat;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    /** Состояние чата для интерфейса: включён ли, есть ли документы. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", chat.enabled(), "maxQuestionLength", props.maxQuestionLength());
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> stream(@RequestBody ChatRequest body, HttpServletRequest request) {
        String question = body.question() == null ? "" : body.question().strip();
        if (question.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "Пустой вопрос");
        }
        if (question.length() > props.maxQuestionLength()) {
            return error(HttpStatus.BAD_REQUEST, "Вопрос длиннее " + props.maxQuestionLength() + " символов");
        }
        String ip = clientIp(request);
        if (!rateLimiter.tryAcquire(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(rateLimiter.retryAfterSeconds(ip)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "Слишком много вопросов подряд. Подождите немного."));
        }
        List<ChatService.Turn> history = sanitize(body.history());

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        executor.submit(() -> {
            try {
                chat.answer(question, history, new ChatService.Listener() {
                    @Override
                    public void sources(List<ChatService.Source> sources) {
                        send(emitter, closed, "sources", sources);
                    }

                    @Override
                    public void delta(String text) {
                        send(emitter, closed, "delta", Map.of("text", text));
                    }
                }, v -> closed.get());
                send(emitter, closed, "done", Map.of());
                emitter.complete();
            } catch (Exception e) {
                log.warn("Чат: ответ не получен: {}", e.toString());
                String message = e instanceof IllegalStateException && e.getMessage() != null
                        ? e.getMessage()
                        : "Не удалось получить ответ. Попробуйте ещё раз через минуту.";
                send(emitter, closed, "error", Map.of("message", message));
                emitter.complete();
            }
        });
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                // nginx: не буферизовать поток, иначе текст придёт одним куском в конце
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    private static void send(SseEmitter emitter, AtomicBoolean closed, String name, Object data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
        }
    }

    private static List<ChatService.Turn> sanitize(List<ChatService.Turn> history) {
        if (history == null) return List.of();
        List<ChatService.Turn> clean = history.stream()
                .filter(t -> t != null && t.text() != null && !t.text().isBlank()
                        && ("user".equals(t.role()) || "assistant".equals(t.role())))
                .map(t -> new ChatService.Turn(t.role(),
                        t.text().length() > MAX_TURN_LENGTH ? t.text().substring(0, MAX_TURN_LENGTH) : t.text()))
                .toList();
        clean = clean.subList(Math.max(0, clean.size() - MAX_HISTORY), clean.size());
        // модель ждёт диалог, начинающийся с пользователя и с чередованием ролей
        while (!clean.isEmpty() && !"user".equals(clean.getFirst().role())) clean = clean.subList(1, clean.size());
        java.util.ArrayList<ChatService.Turn> alternating = new java.util.ArrayList<>();
        for (ChatService.Turn t : clean) {
            if (!alternating.isEmpty() && alternating.getLast().role().equals(t.role())) alternating.removeLast();
            alternating.add(t);
        }
        // последним перед новым вопросом должен быть ответ ассистента
        if (!alternating.isEmpty() && "user".equals(alternating.getLast().role())) alternating.removeLast();
        return alternating;
    }

    /** Адрес посетителя: за nginx фронтенда — из X-Real-IP / X-Forwarded-For. */
    private static String clientIp(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.strip();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].strip();
        return request.getRemoteAddr();
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
    }
}
