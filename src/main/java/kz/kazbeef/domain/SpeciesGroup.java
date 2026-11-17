package kz.kazbeef.domain;

/** Группа вида: по ней работает фильтр «КРС / МРС». */
public enum SpeciesGroup {
    CATTLE("КРС"),
    SMALL_RUMINANT("МРС"),
    OTHER("Прочие");

    private final String labelRu;

    SpeciesGroup(String labelRu) {
        this.labelRu = labelRu;
    }

    public String labelRu() { return labelRu; }

    public boolean isRuminant() {
        return this != OTHER;
    }
}
