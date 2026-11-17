package kz.kazbeef.kb.convert;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCX → Markdown: заголовки по стилям, списки, таблицы, жирный и курсив.
 * Картинки, фигуры и колонтитулы не переносятся.
 */
@Component
public class DocxConverter implements MarkdownConverter {

    private static final Pattern HEADING = Pattern.compile("(?:heading|заголовок|title|название)\\s*(\\d)?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Override
    public Set<String> extensions() {
        return Set.of("docx");
    }

    @Override
    public String toMarkdown(InputStream in) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            Map<BigInteger, Integer> listCounters = new HashMap<>();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    paragraph(doc, p, sb, listCounters);
                } else if (element instanceof XWPFTable t) {
                    listCounters.clear();
                    sb.append('\n').append(table(t)).append('\n');
                }
            }
            return Md.tidy(sb.toString());
        }
    }

    private void paragraph(XWPFDocument doc, XWPFParagraph p, StringBuilder sb, Map<BigInteger, Integer> listCounters) {
        int level = headingLevel(doc, p);
        String text = level > 0 ? plain(p) : formatted(p);
        if (text.isBlank()) return;

        if (level > 0) {
            listCounters.clear();
            sb.append('\n').append("#".repeat(Math.min(level, 4))).append(' ').append(text.trim()).append("\n\n");
            return;
        }
        BigInteger numId = p.getNumID();
        if (numId != null) {
            int depth = p.getNumIlvl() == null ? 0 : p.getNumIlvl().intValue();
            String indent = "  ".repeat(Math.min(depth, 4));
            String format = p.getNumFmt();
            if (format != null && !format.equalsIgnoreCase("bullet")) {
                int n = listCounters.merge(numId, 1, Integer::sum);
                sb.append(indent).append(n).append(". ").append(text.trim()).append('\n');
            } else {
                sb.append(indent).append("- ").append(text.trim()).append('\n');
            }
            return;
        }
        listCounters.clear();
        sb.append(text.trim()).append("\n\n");
    }

    /** Уровень заголовка по стилю абзаца (и его родительским стилям); 0 — обычный текст. */
    private static int headingLevel(XWPFDocument doc, XWPFParagraph p) {
        String styleId = p.getStyleID();
        for (int guard = 0; styleId != null && guard < 5; guard++) {
            XWPFStyle style = doc.getStyles() == null ? null : doc.getStyles().getStyle(styleId);
            String name = style == null ? styleId : style.getName();
            int level = level(name);
            if (level == 0 && style != null) level = level(styleId);
            if (level > 0) return level;
            styleId = style == null ? null : style.getBasisStyleID();
        }
        return 0;
    }

    private static int level(String name) {
        if (name == null) return 0;
        Matcher m = HEADING.matcher(name.toLowerCase(Locale.ROOT));
        if (!m.find()) return 0;
        if (m.group(1) == null) return 1; // Title
        return Math.max(1, Integer.parseInt(m.group(1)));
    }

    private static String plain(XWPFParagraph p) {
        return p.getText() == null ? "" : p.getText();
    }

    /** Текст абзаца с жирным и курсивом. Прогоны без текста (картинки) пропадают сами. */
    private static String formatted(XWPFParagraph p) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : p.getRuns()) {
            String t = run.text();
            if (t == null || t.isEmpty()) continue;
            if (t.isBlank()) {
                sb.append(t);
                continue;
            }
            String core = t.strip();
            String lead = t.substring(0, t.indexOf(core.charAt(0)));
            String trail = t.substring(t.lastIndexOf(core.charAt(core.length() - 1)) + 1);
            String mark = run.isBold() && run.isItalic() ? "***" : run.isBold() ? "**" : run.isItalic() ? "*" : "";
            sb.append(lead).append(mark).append(core).append(mark).append(trail);
        }
        // соседние одинаково выделенные прогоны: «**a****b**» → «**ab**»
        return sb.toString().replace("****", "").replace("**  **", " ");
    }

    private static String table(XWPFTable t) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : t.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(Md.cell(cell.getText()));
            }
            rows.add(cells);
        }
        return Md.table(rows);
    }
}
