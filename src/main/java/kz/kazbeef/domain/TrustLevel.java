package kz.kazbeef.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Уровень доверия к данным. Сигнал нельзя показывать как подтверждённый факт. */
public enum TrustLevel {
    CONFIRMED("confirmed", "подтверждено"),
    SIGNAL("signal", "сигнал"),
    ANALYTICS("analytics", "аналитика");

    private final String dbValue;
    private final String labelRu;

    TrustLevel(String dbValue, String labelRu) {
        this.dbValue = dbValue;
        this.labelRu = labelRu;
    }

    public String dbValue() { return dbValue; }

    public String labelRu() { return labelRu; }

    public static TrustLevel fromDb(String value) {
        for (TrustLevel t : values()) {
            if (t.dbValue.equals(value)) return t;
        }
        throw new IllegalArgumentException("Unknown trust level: " + value);
    }

    @Converter(autoApply = true)
    public static class JpaConverter implements AttributeConverter<TrustLevel, String> {
        @Override
        public String convertToDatabaseColumn(TrustLevel attribute) {
            return attribute == null ? null : attribute.dbValue;
        }

        @Override
        public TrustLevel convertToEntityAttribute(String dbData) {
            return dbData == null ? null : fromDb(dbData);
        }
    }
}
