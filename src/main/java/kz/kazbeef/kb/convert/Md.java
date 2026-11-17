package kz.kazbeef.kb.convert;

import java.util.List;
import java.util.regex.Pattern;

/** Общие приёмы сборки Markdown. */
final class Md {

    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0\\u2007\\u202F]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private Md() {
    }

    /** Ячейка таблицы: одна строка, без символов, ломающих разметку. */
    static String cell(String s) {
        return s == null ? "" : SPACES.matcher(s.replace('\n', ' ').replace("|", "\\|")).replaceAll(" ").trim();
    }

    static String table(List<List<String>> rows) {
        List<List<String>> nonEmpty = rows.stream().filter(r -> r.stream().anyMatch(c -> !c.isBlank())).toList();
        if (nonEmpty.isEmpty()) return "";
        int cols = nonEmpty.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0) return "";
        // таблица из одной колонки — это просто список строк
        if (cols == 1) {
            StringBuilder sb = new StringBuilder();
            nonEmpty.forEach(r -> sb.append("- ").append(r.getFirst()).append('\n'));
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nonEmpty.size(); i++) {
            List<String> r = nonEmpty.get(i);
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                sb.append(' ').append(c < r.size() ? r.get(c) : "").append(" |");
            }
            sb.append('\n');
            if (i == 0) {
                sb.append('|').append(" --- |".repeat(cols)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Схлопывает пробелы и лишние пустые строки. */
    static String tidy(String markdown) {
        StringBuilder sb = new StringBuilder();
        for (String line : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            sb.append(SPACES.matcher(line).replaceAll(" ").stripTrailing()).append('\n');
        }
        return BLANK_LINES.matcher(sb.toString()).replaceAll("\n\n").strip() + "\n";
    }
}
