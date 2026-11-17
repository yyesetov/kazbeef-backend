package kz.kazbeef.kb.convert;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** TXT и MD. У Markdown удаляются картинки (в том числе встроенные base64). Кодировка: UTF-8, иначе Windows-1251. */
@Component
public class TextConverter implements MarkdownConverter {

    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
    private static final Pattern HTML_IMAGE = Pattern.compile("<img[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REF_IMAGE = Pattern.compile("(?m)^\\[[^]]+]:\\s*data:image/\\S+.*$");

    @Override
    public Set<String> extensions() {
        return Set.of("txt", "md", "markdown");
    }

    @Override
    public String toMarkdown(InputStream in) throws IOException {
        String text = decode(in.readAllBytes());
        text = MD_IMAGE.matcher(text).replaceAll("");
        text = HTML_IMAGE.matcher(text).replaceAll("");
        text = REF_IMAGE.matcher(text).replaceAll("");
        return Md.tidy(text);
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                    .replace("﻿", "");
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1251"));
        }
    }
}
