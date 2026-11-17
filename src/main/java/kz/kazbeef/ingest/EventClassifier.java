package kz.kazbeef.ingest;

import kz.kazbeef.domain.AnimalSpecies;
import kz.kazbeef.domain.Event;
import kz.kazbeef.normalize.SpeciesResolver;
import kz.kazbeef.risk.RiskService;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Производные поля события, которые зависят от справочников: набор видов, признак
 * «значимо для КРС/МРС», расстояние до границы Казахстана и балл риска. Вызывается при записи и при пересчёте после правки справочников.
 */
@Component
public class EventClassifier {

    private final SpeciesResolver speciesResolver;
    private final RiskService riskService;

    public EventClassifier(SpeciesResolver speciesResolver, RiskService riskService) {
        this.speciesResolver = speciesResolver;
        this.riskService = riskService;
    }

    /** Вызывается внутри транзакции. Возвращает true, если что-то изменилось. */
    public boolean classify(Event e) {
        boolean changed = false;

        Set<AnimalSpecies> resolved = speciesResolver.resolveAll(e.getRawSpecies());
        if (!e.getSpeciesSet().equals(resolved)) {
            e.getSpeciesSet().clear();
            e.getSpeciesSet().addAll(resolved);
            changed = true;
        }
        AnimalSpecies main = resolved.size() == 1 ? resolved.iterator().next() : null;
        if (e.getSpecies() != main) {
            e.setSpecies(main);
            changed = true;
        }

        boolean relevant = isRuminantRelevant(e, resolved);
        if (e.isRuminantRelevant() != relevant) {
            e.setRuminantRelevant(relevant);
            changed = true;
        }
        // риск зависит от значимости для КРС/МРС, поэтому считается последним
        changed |= riskService.apply(e);
        return changed;
    }

    /** Поражены КРС/МРС либо болезнь опасна для них, даже если вспышка у другого вида. */
    private static boolean isRuminantRelevant(Event e, Set<AnimalSpecies> affected) {
        if (affected.stream().anyMatch(s -> s.getGroup().isRuminant())) {
            return true;
        }
        return e.getDisease() != null
                && e.getDisease().getSusceptibleSpecies().stream().anyMatch(s -> s.getGroup().isRuminant());
    }
}
