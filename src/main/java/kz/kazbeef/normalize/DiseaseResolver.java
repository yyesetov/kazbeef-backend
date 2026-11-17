package kz.kazbeef.normalize;

import kz.kazbeef.domain.Disease;
import kz.kazbeef.domain.DiseaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Сопоставляет название болезни из источника со справочником (name_en, name_ru, синонимы).
 * Неизвестная болезнь заводится с одним английским названием и признаком needs_review,
 * чтобы данные не терялись (очередь ручной проверки появится в админке).
 */
@Component
public class DiseaseResolver {

    private static final Logger log = LoggerFactory.getLogger(DiseaseResolver.class);

    private final DiseaseRepository diseases;

    public DiseaseResolver(DiseaseRepository diseases) {
        this.diseases = diseases;
    }

    /** Вызывается внутри транзакции записи события. */
    public Optional<Disease> resolve(String rawName) {
        String key = NameNormalizer.key(rawName);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        Optional<Disease> found = diseases.findAll().stream()
                .filter(d -> matches(d, key))
                .findFirst();
        if (found.isPresent()) {
            return found;
        }
        Disease created = new Disease();
        created.setNameEn(NameNormalizer.clean(rawName));
        created.setNeedsReview(true);
        log.info("Новая болезнь в справочнике (требует проверки): {}", created.getNameEn());
        return Optional.of(diseases.save(created));
    }

    private static boolean matches(Disease d, String key) {
        return Stream.concat(Stream.of(d.getNameEn(), d.getNameRu(), d.getCode()), d.getSynonyms().stream())
                .anyMatch(name -> name != null && NameNormalizer.key(name).equals(key));
    }
}
