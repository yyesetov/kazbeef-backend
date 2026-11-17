package kz.kazbeef.admin;

import kz.kazbeef.domain.AnimalSpeciesRepository;
import kz.kazbeef.domain.Disease;
import kz.kazbeef.domain.DiseaseRepository;
import kz.kazbeef.ingest.EventMaintenanceService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Справочник болезней: проверка записей, которые сборщики завели автоматически (needs_review).
 * После любой правки производные поля событий пересчитываются.
 */
@RestController
@RequestMapping("/admin/diseases")
public class DiseaseAdminController {

    private final DiseaseRepository diseases;
    private final AnimalSpeciesRepository species;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventMaintenanceService maintenance;

    public DiseaseAdminController(DiseaseRepository diseases, AnimalSpeciesRepository species, JdbcTemplate jdbc,
                                  TransactionTemplate tx, EventMaintenanceService maintenance) {
        this.diseases = diseases;
        this.species = species;
        this.jdbc = jdbc;
        this.tx = tx;
        this.maintenance = maintenance;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT d.id, d.code, d.name_ru AS "nameRu", d.name_en AS "nameEn", d.severity,
                       d.needs_review AS "needsReview", d.synonyms::text AS synonyms,
                       (SELECT count(*) FROM event e WHERE e.disease_id = d.id) AS events,
                       coalesce((SELECT json_agg(ds.species_id) FROM disease_species ds WHERE ds.disease_id = d.id), '[]')::text AS "speciesIds"
                  FROM disease d ORDER BY d.needs_review DESC, coalesce(d.name_ru, d.name_en)
                """);
    }

    public record DiseaseUpdate(String nameRu, String nameEn, String code, Short severity, List<String> synonyms,
                                List<Long> speciesIds) {
    }

    /** Сохранение записи снимает признак needs_review. */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody DiseaseUpdate body) {
        tx.executeWithoutResult(status -> {
            Disease d = diseases.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (body.nameEn() == null || body.nameEn().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Английское название обязательно");
            }
            d.setNameEn(body.nameEn().trim());
            d.setNameRu(blankToNull(body.nameRu()));
            d.setCode(blankToNull(body.code()));
            if (body.severity() != null) {
                d.setSeverity((short) Math.clamp(body.severity(), 1, 5));
            }
            if (body.synonyms() != null) {
                d.setSynonyms(new ArrayList<>(body.synonyms().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList()));
            }
            if (body.speciesIds() != null) {
                d.getSusceptibleSpecies().clear();
                d.getSusceptibleSpecies().addAll(species.findAllById(body.speciesIds()));
            }
            d.setNeedsReview(false);
        });
        return Map.of("recomputed", maintenance.recomputeAll());
    }

    /** Склеивает запись с другой: события переносятся, названия становятся синонимами, запись удаляется. */
    @PostMapping("/{id}/merge")
    public Map<String, Object> merge(@PathVariable long id, @RequestParam long into) {
        if (id == into) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нельзя склеить запись саму с собой");
        }
        tx.executeWithoutResult(status -> {
            Disease from = diseases.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            Disease target = diseases.findById(into).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            List<String> synonyms = new ArrayList<>(target.getSynonyms());
            for (String name : concat(from.getNameEn(), from.getNameRu(), from.getSynonyms())) {
                if (name != null && !name.isBlank() && !synonyms.contains(name)) {
                    synonyms.add(name);
                }
            }
            target.setSynonyms(synonyms);
            diseases.flush();
            jdbc.update("UPDATE event SET disease_id = ? WHERE disease_id = ?", into, id);
            diseases.delete(from);
        });
        return Map.of("recomputed", maintenance.recomputeAll());
    }

    private static List<String> concat(String a, String b, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(a);
        all.add(b);
        all.addAll(rest);
        return all;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
