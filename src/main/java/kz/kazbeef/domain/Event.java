package kz.kazbeef.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "event")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "disease_id")
    private Disease disease;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id")
    private Country country;

    /** Основной вид: заполняется, если в событии ровно один распознанный вид. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "species_id")
    private AnimalSpecies species;

    @Column(name = "raw_disease_name")
    private String rawDiseaseName;

    @Column(name = "raw_country_name")
    private String rawCountryName;

    @Column(name = "raw_species")
    private String rawSpecies;

    private String region;
    private Double latitude;
    private Double longitude;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "report_date")
    private LocalDate reportDate;

    private Integer outbreaks;
    private Integer susceptible;
    private Integer cases;
    private Integer deaths;

    @Column(nullable = false)
    private EventStatus status = EventStatus.NEW;

    @Column(name = "trust_level", nullable = false)
    private TrustLevel trustLevel;

    private String title;
    private String description;
    private String translation;

    @Column(name = "ai_summary")
    private String aiSummary;

    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated;

    /** Все распознанные виды животных события. */
    @ManyToMany
    @JoinTable(name = "event_species",
            joinColumns = @JoinColumn(name = "event_id"),
            inverseJoinColumns = @JoinColumn(name = "species_id"))
    private Set<AnimalSpecies> speciesSet = new HashSet<>();

    /** Поражены КРС/МРС или болезнь опасна для них. */
    @Column(name = "ruminant_relevant", nullable = false)
    private boolean ruminantRelevant;

    /** Расстояние до границы Казахстана, км (0 внутри страны). */
    @Column(name = "distance_to_kz_km")
    private Double distanceToKzKm;

    /** Балл риска для Казахстана, 0..100. */
    @Column(name = "risk_score")
    private Integer riskScore;

    /** Сколько раз событие отправлялось на обработку ИИ. */
    @Column(name = "ai_attempts", nullable = false)
    private short aiAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EventSource> sources = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void addSource(EventSource source) {
        sources.add(source);
        source.setEvent(this);
    }

    public Long getId() { return id; }
    public Disease getDisease() { return disease; }
    public void setDisease(Disease disease) { this.disease = disease; }
    public Country getCountry() { return country; }
    public void setCountry(Country country) { this.country = country; }
    public AnimalSpecies getSpecies() { return species; }
    public void setSpecies(AnimalSpecies species) { this.species = species; }
    public String getRawDiseaseName() { return rawDiseaseName; }
    public void setRawDiseaseName(String rawDiseaseName) { this.rawDiseaseName = rawDiseaseName; }
    public String getRawCountryName() { return rawCountryName; }
    public void setRawCountryName(String rawCountryName) { this.rawCountryName = rawCountryName; }
    public String getRawSpecies() { return rawSpecies; }
    public void setRawSpecies(String rawSpecies) { this.rawSpecies = rawSpecies; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }
    public Integer getOutbreaks() { return outbreaks; }
    public void setOutbreaks(Integer outbreaks) { this.outbreaks = outbreaks; }
    public Integer getSusceptible() { return susceptible; }
    public void setSusceptible(Integer susceptible) { this.susceptible = susceptible; }
    public Integer getCases() { return cases; }
    public void setCases(Integer cases) { this.cases = cases; }
    public Integer getDeaths() { return deaths; }
    public void setDeaths(Integer deaths) { this.deaths = deaths; }
    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = status; }
    public TrustLevel getTrustLevel() { return trustLevel; }
    public void setTrustLevel(TrustLevel trustLevel) { this.trustLevel = trustLevel; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTranslation() { return translation; }
    public void setTranslation(String translation) { this.translation = translation; }
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
    public boolean isAiGenerated() { return aiGenerated; }
    public void setAiGenerated(boolean aiGenerated) { this.aiGenerated = aiGenerated; }
    public Set<AnimalSpecies> getSpeciesSet() { return speciesSet; }
    public boolean isRuminantRelevant() { return ruminantRelevant; }
    public void setRuminantRelevant(boolean ruminantRelevant) { this.ruminantRelevant = ruminantRelevant; }
    public Double getDistanceToKzKm() { return distanceToKzKm; }
    public void setDistanceToKzKm(Double distanceToKzKm) { this.distanceToKzKm = distanceToKzKm; }
    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }
    public short getAiAttempts() { return aiAttempts; }
    public void setAiAttempts(short aiAttempts) { this.aiAttempts = aiAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<EventSource> getSources() { return sources; }
}
