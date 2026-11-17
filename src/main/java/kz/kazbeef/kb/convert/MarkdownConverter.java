package kz.kazbeef.kb.convert;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** Превращает документ одного формата в Markdown без изображений. */
public interface MarkdownConverter {

    /** Расширения файлов в нижнем регистре, без точки. */
    Set<String> extensions();

    String toMarkdown(InputStream in) throws IOException;
}
