package kz.kazbeef.normalize;

import kz.kazbeef.domain.Country;
import kz.kazbeef.domain.CountryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Сопоставляет страну по коду ISO2/ISO3, затем по названию (английскому, русскому, синонимам). */
@Component
public class CountryResolver {

    private static final Logger log = LoggerFactory.getLogger(CountryResolver.class);

    private final CountryRepository countries;

    public CountryResolver(CountryRepository countries) {
        this.countries = countries;
    }

    /** Вызывается внутри транзакции записи события. */
    public Optional<Country> resolve(String code, String name) {
        String iso = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
        if (iso != null && iso.length() == 3) {
            Optional<Country> c = countries.findByIso3(iso);
            if (c.isPresent()) return c;
        } else if (iso != null && iso.length() == 2) {
            Optional<Country> c = countries.findByIso2(iso);
            if (c.isPresent()) return c;
        }
        Optional<Country> byName = findByName(name);
        if (byName.isPresent()) return byName;
        // Страна вне справочника: заводим как неприоритетную, если известен ISO3
        if (iso != null && iso.length() == 3) {
            Country created = new Country();
            created.setIso3(iso);
            created.setNameEn(name == null || name.isBlank() ? iso : name.trim());
            log.info("Новая страна в справочнике: {} ({})", created.getNameEn(), iso);
            return Optional.of(countries.save(created));
        }
        return Optional.empty();
    }
    /** Поиск по названию без создания новой записи. */
    public Optional<Country> findByName(String name) {
        String key = NameNormalizer.key(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return countries.findAll().stream()
                .filter(c -> Stream.concat(Stream.of(c.getNameEn(), c.getNameRu()), c.getSynonyms().stream())
                        .anyMatch(n -> n != null && NameNormalizer.key(n).equals(key)))
                .findFirst();
    }
}
