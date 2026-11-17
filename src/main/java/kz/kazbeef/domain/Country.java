package kz.kazbeef.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "country")
public class Country {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "bpchar(2)")
    private String iso2;

    @Column(nullable = false, columnDefinition = "bpchar(3)")
    private String iso3;

    @Column(name = "name_ru")
    private String nameRu;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "is_priority", nullable = false)
    private boolean priority;

    /** Другие написания, в том числе в русскоязычных источниках. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> synonyms = new ArrayList<>();

    /** Группа наблюдения: neighbor / cis / importer; null — страна вне наблюдения. */
    @Column(name = "watch_group")
    private String watchGroup;

    public Long getId() { return id; }
    public String getIso2() { return iso2; }
    public void setIso2(String iso2) { this.iso2 = iso2; }
    public List<String> getSynonyms() { return synonyms; }
    public String getIso3() { return iso3; }
    public void setIso3(String iso3) { this.iso3 = iso3; }
    public String getNameRu() { return nameRu; }
    public void setNameRu(String nameRu) { this.nameRu = nameRu; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getWatchGroup() { return watchGroup; }
    public boolean isPriority() { return priority; }
    public void setPriority(boolean priority) { this.priority = priority; }
}
