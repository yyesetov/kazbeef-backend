package kz.kazbeef.kb;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Режет Markdown на фрагменты для поиска: по разделам (заголовки #…####),
 * длинные разделы — по абзацам до ~{@value #TARGET} символов с перекрытием.
 * У каждого фрагмента — путь заголовков «Раздел › Подраздел», чтобы найденный кусок был понятен без контекста.
 */
public final class MarkdownChunker {

    public record Chunk(String heading, String content) {
    }

    private static final int TARGET = 1500;
    private static final int OVERLAP = 200;
    private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.+)$");

    private MarkdownChunker() {
    }

    public static List<Chunk> chunk(String markdown) {
        List<Chunk> result = new ArrayList<>();
        String[] path = new String[4];
        StringBuilder section = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                emit(result, heading(path), section.toString());
                section.setLength(0);
                int level = m.group(1).length() - 1;
                path[level] = m.group(2).strip();
                for (int i = level + 1; i < path.length; i++) path[i] = null;
                continue;
            }
            section.append(line).append('\n');
        }
        emit(result, heading(path), section.toString());
        return result;
    }

    private static String heading(String[] path) {
        List<String> parts = new ArrayList<>();
        for (String p : path) if (p != null) parts.add(p);
        return parts.isEmpty() ? null : String.join(" › ", parts);
    }

    private static void emit(List<Chunk> result, String heading, String text) {
        String body = text.strip();
        if (body.isEmpty()) return;
        if (body.length() <= TARGET * 1.3) {
            result.add(new Chunk(heading, body));
            return;
        }
        // длинный раздел: набираем абзацы до целевого размера, хвост предыдущего куска повторяем
        StringBuilder current = new StringBuilder();
        for (String paragraph : body.split("\n\\s*\n")) {
            if (current.length() > 0 && current.length() + paragraph.length() > TARGET) {
                result.add(new Chunk(heading, current.toString().strip()));
                String tail = current.length() > OVERLAP ? current.substring(current.length() - OVERLAP) : current.toString();
                int cut = tail.indexOf(' ');
                current.setLength(0);
                current.append(cut > 0 ? tail.substring(cut + 1) : tail).append("\n\n");
            }
            // абзац длиннее целевого размера (таблица, сплошной текст) — режем по строкам/предложениям
            if (paragraph.length() > TARGET * 1.5) {
                for (String piece : splitLong(paragraph)) {
                    if (current.length() + piece.length() > TARGET && current.length() > 0) {
                        result.add(new Chunk(heading, current.toString().strip()));
                        current.setLength(0);
                    }
                    current.append(piece).append('\n');
                }
            } else {
                current.append(paragraph).append("\n\n");
            }
        }
        if (!current.toString().isBlank()) result.add(new Chunk(heading, current.toString().strip()));
    }

    private static List<String> splitLong(String paragraph) {
        String[] parts = paragraph.contains("\n") ? paragraph.split("\n") : paragraph.split("(?<=[.!?])\\s+");
        return List.of(parts);
    }
}
