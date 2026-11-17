package kz.kazbeef.normalize;

import kz.kazbeef.domain.Disease;
import kz.kazbeef.domain.DiseaseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Находит болезнь и виды животных в свободном тексте (сводки ИАЦ, публикации EFSA).
 * Русские названия ищутся по основам слов, чтобы совпадали падежные формы
 * («сибирской язвы», «оспой овец»); английские — по названиям и синонимам справочника.
 */
@Component
public class TextMatcher {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /** Код болезни → основа в русском тексте. Порядок важен: более точные раньше общих. */
    private static final Map<String, Pattern> RU_DISEASES = new LinkedHashMap<>();
    /** Английское название вида справочника → основа в русском тексте. */
    private static final Map<String, Pattern> RU_SPECIES = new LinkedHashMap<>();

    static {
        ru("PPR", "чум\\w+ мелких жвачных|\\bЧМЖ\\b");
        ru("RP", "чум\\w+ (крупного рогатого|КРС)");
        ru("CSF", "классическ\\w+ чум\\w+ свиней|\\bКЧС\\b");
        ru("ASF", "африканск\\w+ чум\\w+ свиней|\\bАЧС\\b");
        ru("FMD", "\\bящур");
        ru("LSD", "узелков\\w+ дерматит|нодулярн\\w+ дерматит|\\bЗУД\\b");
        ru("SGP", "осп\\w+ (овец|коз|мелкого)");
        ru("ANTHRAX", "сибирск\\w+ язв|сибиреязв");
        ru("BRUC", "бруцелл");
        ru("BT", "блютанг|катаральн\\w+ лихорадк");
        ru("RAB", "бешенств");
        ru("CBPP", "плевропневмони\\w+ (крупного рогатого|КРС)");
        ru("CCPP", "плевропневмони\\w+ (коз|мелкого)");
        ru("RVF", "лихорадк\\w+ долины Рифт");
        ru("EHD", "геморрагическ\\w+ болезн\\w+ оленей|\\bЭГБО\\b");
        ru("CCHF", "Конго");
        ru("QF", "лихорадк\\w+ Ку\\b|Ку-лихорадк|коксиелл");
        ru("BSE", "губкообразн\\w+ энцефалопати");
        ru("TB", "туберкул[её]з");
        ru("EBL", "лейкоз");
        ru("SCRAPIE", "\\bскрепи");
        ru("SBV", "Шмалленберг");
        ru("BLACKLEG", "\\bэмкар|эмфизематозн\\w+ карбункул");
        ru("HS", "пастерелл|геморрагическ\\w+ септицеми");
        ru("LEPTO", "лептоспир");
        ru("ND", "ньюкасл");
        ru("HPAI", "грипп\\w* птиц|птич\\w+ грипп|высокопатогенн\\w+ грипп");

        species("Cattle", "\\bКРС\\b|крупн\\w+ рогат\\w+|\\bкоров|\\bтелят|\\bбык|молочн\\w+ скот|мясн\\w+ скот|\\bcattle\\b|\\bbovine|\\bcows?\\b");
        species("Sheep", "\\bовец|\\bовцы|\\bовцам|\\bМРС\\b|мелк\\w+ рогат\\w+|\\bягнят|\\bsheep\\b|\\bovine");
        species("Goats", "\\bкоз\\b|\\bкозы|\\bкозам|\\bкозлят|\\bgoats?\\b|\\bcaprine");
        species("Buffaloes", "буйвол|\\bbuffalo");
        species("Yaks", "\\bяк(ов|и|а)?\\b|\\byaks?\\b");
        species("Camels", "верблюд|\\bcamel");
        species("Swine", "\\bсвин|\\bкабан|\\bpigs?\\b|\\bswine\\b|wild boar");
        species("Birds", "\\bптиц|\\bкур\\b|птицефабрик|птицевод|\\bpoultry\\b|\\bbirds?\\b");
        species("Horses", "\\bлошад|\\bконей\\b|\\bhorses?\\b|\\bequine");
        species("Deer", "\\bолен|\\bdeer\\b|\\bcervid");
        species("Dogs", "\\bсобак|\\bdogs?\\b");
        species("Wildlife", "дик\\w+ животн|\\bwildlife\\b");
    }

    private static void ru(String code, String regex) {
        RU_DISEASES.put(code, Pattern.compile(regex, FLAGS));
    }

    private static void species(String nameEn, String regex) {
        RU_SPECIES.put(nameEn, Pattern.compile(regex, FLAGS));
    }

    private final DiseaseRepository diseases;

    public TextMatcher(DiseaseRepository diseases) {
        this.diseases = diseases;
    }

    /** Код болезни справочника, упомянутой в тексте. */
    @Transactional(readOnly = true)
    public Optional<Disease> disease(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        var all = diseases.findAll();
        for (var entry : RU_DISEASES.entrySet()) {
            if (entry.getValue().matcher(text).find()) {
                Optional<Disease> d = all.stream().filter(x -> entry.getKey().equals(x.getCode())).findFirst();
                if (d.isPresent()) return d;
            }
        }
        // английские названия и синонимы: самое длинное совпадение целыми словами
        String haystack = " " + NameNormalizer.key(text) + " ";
        Disease best = null;
        int bestLength = 0;
        for (Disease d : all) {
            for (String name : Stream.concat(Stream.of(d.getNameEn(), d.getNameRu()), d.getSynonyms().stream()).toList()) {
                String key = NameNormalizer.key(name);
                if (key.length() >= 6 && key.length() > bestLength && haystack.contains(" " + key + " ")) {
                    best = d;
                    bestLength = key.length();
                }
            }
            // короткие коды (FMD, LSD, PPR) — только заглавными и отдельным словом
            if (best == null && d.getCode() != null && d.getCode().length() >= 3 && d.getCode().length() <= 5
                    && Pattern.compile("\\b" + Pattern.quote(d.getCode()) + "\\b").matcher(text).find()) {
                best = d;
                bestLength = d.getCode().length();
            }
        }
        return Optional.ofNullable(best);
    }

    /** Виды животных, упомянутые в тексте, в формате источника: английские названия через "; ". */
    public String species(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Set<String> found = new LinkedHashSet<>();
        RU_SPECIES.forEach((name, pattern) -> {
            if (pattern.matcher(text).find()) found.add(name);
        });
        return found.isEmpty() ? null : String.join("; ", found);
    }
}
