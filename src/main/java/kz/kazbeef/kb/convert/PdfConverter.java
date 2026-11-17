package kz.kazbeef.kb.convert;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PDF → Markdown. В PDF нет структуры, поэтому она восстанавливается:
 * строки крупнее основного текста становятся заголовками, маркеры — пунктами списка,
 * строки, повторяющиеся на большинстве страниц (колонтитулы), и номера страниц удаляются.
 * Изображения не извлекаются. Таблицы переносятся как текст строк.
 */
@Component
public class PdfConverter implements MarkdownConverter {

    private static final Pattern PAGE_NUMBER = Pattern.compile("^(стр\\.?|страница|page)?\\s*\\d{1,4}(\\s*(из|/|of)\\s*\\d{1,4})?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BULLET = Pattern.compile("^[•●▪■◦○·\\-–—]\\s+");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d{1,3}[.)]|[а-яa-z][.)])\\s+");
    /** Буквица другим шрифтом даёт «С ообщения»: склеиваем заглавную с остатком слова. */
    private static final Pattern SPLIT_CAPITAL = Pattern.compile("^(\\p{Lu}) (\\p{Ll}{2,})");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?:;]$");

    private record Line(String text, float size, int page) {
    }

    @Override
    public Set<String> extensions() {
        return Set.of("pdf");
    }

    @Override
    public String toMarkdown(InputStream in) throws IOException {
        try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
            List<Line> lines = new ArrayList<>();
            Map<Float, Integer> sizeChars = new HashMap<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    float size = 0;
                    for (TextPosition tp : positions) {
                        float s = Math.round(tp.getFontSizeInPt() * 2) / 2f;
                        size = Math.max(size, s);
                        sizeChars.merge(s, 1, Integer::sum);
                    }
                    lines.add(new Line(text, size, getCurrentPageNo()));
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
            return build(lines, bodySize(sizeChars), doc.getNumberOfPages());
        }
    }

    /** Размер шрифта, которым набрано больше всего символов. */
    private static float bodySize(Map<Float, Integer> sizeChars) {
        return sizeChars.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(10f);
    }

    private static String build(List<Line> raw, float body, int pages) {
        // колонтитулы: одинаковая короткая строка на большинстве страниц
        Map<String, Set<Integer>> pagesByText = new HashMap<>();
        for (Line l : raw) {
            String key = l.text().strip().replaceAll("\\d+", "#");
            if (!key.isEmpty() && key.length() < 120) {
                pagesByText.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(l.page());
            }
        }
        int threshold = Math.max(3, (int) Math.ceil(pages * 0.6));

        // номер страницы — только первая или последняя строка страницы; числа внутри таблиц не трогаем
        Map<Integer, Integer> firstOnPage = new HashMap<>();
        Map<Integer, Integer> lastOnPage = new HashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            firstOnPage.putIfAbsent(raw.get(i).page(), i);
            lastOnPage.put(raw.get(i).page(), i);
        }

        StringBuilder sb = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < raw.size(); i++) {
            Line l = raw.get(i);
            String text = SPLIT_CAPITAL.matcher(l.text().strip()).replaceFirst("$1$2");
            if (text.isEmpty()) continue;
            boolean edge = firstOnPage.get(l.page()) == i || lastOnPage.get(l.page()) == i;
            if (edge && PAGE_NUMBER.matcher(text).matches()) continue;
            if (pages >= 3 && pagesByText.getOrDefault(text.replaceAll("\\d+", "#"), Set.of()).size() >= threshold) continue;

            boolean heading = l.size() >= body + 1.5f && text.length() <= 150 && !SENTENCE_END.matcher(text).find();
            if (heading) {
                flush(sb, paragraph);
                int level = l.size() >= body + 6 ? 1 : l.size() >= body + 3 ? 2 : 3;
                sb.append("#".repeat(level)).append(' ').append(text).append("\n\n");
                continue;
            }
            if (BULLET.matcher(text).find()) {
                flush(sb, paragraph);
                sb.append("- ").append(BULLET.matcher(text).replaceFirst("")).append('\n');
                continue;
            }
            if (NUMBERED.matcher(text).find() && paragraph.isEmpty()) {
                flush(sb, paragraph);
                sb.append(text).append('\n');
                continue;
            }
            // склейка строк абзаца; перенос слова через дефис
            if (!paragraph.isEmpty()) {
                if (paragraph.charAt(paragraph.length() - 1) == '-' && Character.isLowerCase(text.charAt(0))) {
                    paragraph.setLength(paragraph.length() - 1);
                } else {
                    paragraph.append(' ');
                }
            }
            paragraph.append(text);
            if (SENTENCE_END.matcher(text).find() && text.length() < 60) {
                flush(sb, paragraph);
            }
        }
        flush(sb, paragraph);
        return Md.tidy(sb.toString());
    }

    private static void flush(StringBuilder sb, StringBuilder paragraph) {
        if (!paragraph.isEmpty()) {
            sb.append(paragraph).append("\n\n");
            paragraph.setLength(0);
        }
    }
}
