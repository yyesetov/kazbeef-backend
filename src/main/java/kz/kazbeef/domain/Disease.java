package kz.kazbeef.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "disease")
public class Disease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_ru")
    private String nameRu;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    private String code;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> synonyms = new ArrayList<>();

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    /** Вес болезни для КРС/МРС, 1..5. */
    @Column(nullable = false)
    private short severity = 2;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Восприимчивые виды животных. */
    @ManyToMany
    @JoinTable(name = "disease_species",
            joinColumns = @JoinColumn(name = "disease_id"),
            inverseJoinColumns = @JoinColumn(name = "species_id"))
    private Set<AnimalSpecies> susceptibleSpecies = new HashSet<>();

    public Long getId() { return id; }
    public String getNameRu() { return nameRu; }
    public void setNameRu(String nameRu) { this.nameRu = nameRu; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public List<String> getSynonyms() { return synonyms; }
    public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }
    public boolean isNeedsReview() { return needsReview; }
    public void setNeedsReview(boolean needsReview) { this.needsReview = needsReview; }
    public short getSeverity() { return severity; }
    public void setSeverity(short severity) { this.severity = severity; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<AnimalSpecies> getSusceptibleSpecies() { return susceptibleSpecies; }
}
