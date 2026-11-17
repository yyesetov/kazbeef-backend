package kz.kazbeef.normalize;

import kz.kazbeef.domain.AnimalSpecies;
import kz.kazbeef.domain.AnimalSpeciesRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Сопоставляет виды животных из источника со справочником.
 * Исходная строка всегда хранится в event.raw_species, поэтому нераспознанное не теряется.
 */
@Component
public class SpeciesResolver {

    /** Имя справочной записи для диких животных без точного вида. */
    private static final String WILDLIFE = "Wildlife";
    private static final Pattern WILD_MARK = Pattern.compile("\\(\\s*wild\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARENS = Pattern.compile("\\([^)]*\\)");

    private final AnimalSpeciesRepository species;

    public SpeciesResolver(AnimalSpeciesRepository species) {
        this.species = species;
    }

    /**
     * Все распознанные виды записи. Строка источника: виды через ";",
     * например {@code "Camelidae; Cattle; Sheep / goats (mixed herd); Red Fox (wild)"}.
     * Дикие животные без совпадения в справочнике относятся к общей записи «Дикие животные».
     */
    public Set<AnimalSpecies> resolveAll(String rawSpecies) {
        Set<AnimalSpecies> result = new LinkedHashSet<>();
        if (rawSpecies == null || rawSpecies.isBlank()) {
            return result;
        }
        List<AnimalSpecies> all = species.findAll();
        for (String item : rawSpecies.split(";")) {
            String name = item.trim();
            if (name.isEmpty()) continue;
            boolean wild = WILD_MARK.matcher(name).find();
            String withoutMark = WILD_MARK.matcher(name).replaceAll(" ").trim();

            // сначала целиком, затем без скобок и по частям ("Sheep / goats (mixed herd)")
            Optional<AnimalSpecies> whole = find(all, withoutMark);
            if (whole.isPresent()) {
                result.add(whole.get());
                continue;
            }
            boolean matched = false;
            for (String part : PARENS.matcher(withoutMark).replaceAll(" ").split("[/,]")) {
                Optional<AnimalSpecies> found = find(all, part);
                if (found.isPresent()) {
                    result.add(found.get());
                    matched = true;
                }
            }
            if (!matched && wild) {
                find(all, WILDLIFE).ifPresent(result::add);
            }
        }
        return result;
    }

    private static Optional<AnimalSpecies> find(List<AnimalSpecies> all, String name) {
        String key = NameNormalizer.key(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return all.stream()
                .filter(s -> Stream.concat(Stream.of(s.getNameEn(), s.getNameRu()), s.getSynonyms().stream())
                        .anyMatch(n -> n != null && NameNormalizer.key(n).equals(key)))
                .findFirst();
    }
}
