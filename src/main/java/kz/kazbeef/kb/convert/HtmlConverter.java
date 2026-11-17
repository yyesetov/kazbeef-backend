package kz.kazbeef.kb.convert;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** HTML → Markdown: заголовки, абзацы, списки, таблицы. Картинки, скрипты, стили и навигация выбрасываются. */
@Component
public class HtmlConverter implements MarkdownConverter {

    @Override
    public Set<String> extensions() {
        return Set.of("html", "htm");
    }

    @Override
    public String toMarkdown(InputStream in) throws IOException {
        Document doc = Jsoup.parse(in, StandardCharsets.UTF_8.name(), "");
        doc.select("script, style, noscript, img, picture, svg, video, audio, iframe, nav, header, footer, form").remove();
        StringBuilder sb = new StringBuilder();
        Element root = doc.body() == null ? doc : doc.body();
        block(root, sb, 0);
        return Md.tidy(sb.toString());
    }

    private void block(Element el, StringBuilder sb, int listDepth) {
        for (Element child : el.children()) {
            String tag = child.tagName();
            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    String text = child.text().strip();
                    if (!text.isEmpty()) {
                        sb.append('\n').append("#".repeat(Math.min(4, tag.charAt(1) - '0'))).append(' ').append(text).append("\n\n");
                    }
                }
                case "p", "blockquote", "pre" -> {
                    String text = inline(child).strip();
                    if (!text.isEmpty()) sb.append(text).append("\n\n");
                }
                case "ul", "ol" -> {
                    list(child, sb, listDepth, tag.equals("ol"));
                    if (listDepth == 0) sb.append('\n');
                }
                case "table" -> sb.append('\n').append(table(child)).append('\n');
                case "br", "hr" -> sb.append('\n');
                default -> {
                    if (child.children().isEmpty() || child.children().stream().allMatch(c -> isInline(c.tagName()))) {
                        String text = inline(child).strip();
                        if (!text.isEmpty()) sb.append(text).append("\n\n");
                    } else {
                        block(child, sb, listDepth);
                    }
                }
            }
        }
    }

    private void list(Element listEl, StringBuilder sb, int depth, boolean ordered) {
        int n = 1;
        for (Element li : listEl.children()) {
            if (!li.tagName().equals("li")) continue;
            Element copy = li.clone();
            copy.select("ul, ol").remove();
            String text = inline(copy).strip();
            if (!text.isEmpty()) {
                sb.append("  ".repeat(Math.min(depth, 4))).append(ordered ? (n++) + ". " : "- ").append(text).append('\n');
            }
            for (Element nested : li.children()) {
                if (nested.tagName().equals("ul") || nested.tagName().equals("ol")) {
                    list(nested, sb, depth + 1, nested.tagName().equals("ol"));
                }
            }
        }
    }

    private static boolean isInline(String tag) {
        return Set.of("a", "b", "strong", "i", "em", "span", "u", "sub", "sup", "code", "small", "mark", "br").contains(tag);
    }

    /** Текст с жирным и курсивом; ссылки — только текст. */
    private static String inline(Node node) {
        StringBuilder sb = new StringBuilder();
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode t) {
                sb.append(t.text());
            } else if (child instanceof Element e) {
                String inner = inline(e);
                switch (e.tagName()) {
                    case "b", "strong" -> sb.append(emphasis(inner, "**"));
                    case "i", "em" -> sb.append(emphasis(inner, "*"));
                    case "br" -> sb.append(' ');
                    default -> sb.append(inner);
                }
            }
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    /** Выделение с сохранением пробелов снаружи: «**текст** ,» не появится. */
    private static String emphasis(String inner, String mark) {
        if (inner.isBlank()) return inner;
        String lead = Character.isWhitespace(inner.charAt(0)) ? " " : "";
        String trail = Character.isWhitespace(inner.charAt(inner.length() - 1)) ? " " : "";
        return lead + mark + inner.strip() + mark + trail;
    }

    private static String table(Element table) {
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : table.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.select("> th, > td")) {
                cells.add(Md.cell(cell.text()));
            }
            rows.add(cells);
        }
        return Md.table(rows);
    }
}
