package kz.kazbeef.kb;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Поиск фрагментов базы знаний полнотекстовым поиском PostgreSQL с русской морфологией.
 * Сначала все значимые слова вопроса должны встретиться во фрагменте; если так ничего не нашлось —
 * достаточно любого из них (ранжирование всё равно поднимает наверх фрагменты с большим числом совпадений).
 */
@Component
public class KbSearch {

    public record Hit(long chunkId, long documentId, String documentTitle, String heading, String content, double rank) {
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    /** Слова вопроса, которые ничего не говорят о теме. */
    private static final Set<String> STOP = Set.of(
            "как", "что", "где", "когда", "какой", "какая", "какие", "каким", "какое", "почему", "зачем", "можно",
            "нужно", "надо", "ли", "это", "для", "при", "или", "есть", "мне", "нам", "его", "её", "их", "так",
            "the", "and", "what", "how");
    /** Минимальная релевантность, ниже которой фрагмент считается случайным совпадением. */
    private static final double MIN_RANK = 0.01;

    private final JdbcTemplate jdbc;

    public KbSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Hit> search(String text, int limit) {
        List<String> words = WORD.matcher(text.toLowerCase(Locale.ROOT)).results()
                .map(m -> m.group())
                .filter(w -> !STOP.contains(w))
                .distinct()
                .limit(30)
                .toList();
        if (words.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = query(String.join(" & ", quoted(words)), limit);
        if (hits.isEmpty() && words.size() > 1) {
            hits = query(String.join(" | ", quoted(words)), limit);
        }
        return hits;
    }

    private static List<String> quoted(List<String> words) {
        // слова уже без спецсимволов: \p{L}\p{N}
        return words.stream().map(w -> "'" + w + "'").collect(Collectors.toList());
    }

    private List<Hit> query(String tsquery, int limit) {
        return jdbc.query("""
                SELECT c.id, c.document_id, coalesce(d.title, d.original_name) AS title, c.heading, c.content,
                       ts_rank_cd(c.tsv, q, 32) AS rank
                  FROM kb_chunk c
                  JOIN kb_document d ON d.id = c.document_id AND d.status = 'ready',
                       to_tsquery('russian', ?) q
                 WHERE c.tsv @@ q
                 ORDER BY rank DESC, c.document_id, c.position
                 LIMIT ?
                """, (rs, i) -> new Hit(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getDouble(6)),
                tsquery, limit).stream().filter(h -> h.rank() >= MIN_RANK).toList();
    }
}
