package kz.kazbeef.normalize;

import java.util.Locale;
import java.util.regex.Pattern;

/** Ключ для сравнения названий: без регистра, пунктуации, лишних пробелов и суффикса "(Inf. with)". */
public final class NameNormalizer {

    private static final Pattern INF_WITH = Pattern.compile("\\(\\s*inf\\.?\\s*with[^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    private NameNormalizer() {
    }

    public static String key(String name) {
        if (name == null) {
            return "";
        }
        String s = INF_WITH.matcher(name).replaceAll(" ");
        s = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /** Чистит название для отображения: обрезает пробелы и схлопывает повторы. */
    public static String clean(String name) {
        return name == null ? null : name.trim().replaceAll("\\s+", " ");
    }
}
