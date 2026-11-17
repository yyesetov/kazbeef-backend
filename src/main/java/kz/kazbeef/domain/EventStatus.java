package kz.kazbeef.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

public enum EventStatus {
    NEW("new"),
    ONGOING("ongoing"),
    CLOSED("closed");

    private final String dbValue;

    EventStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() { return dbValue; }

    public static EventStatus fromDb(String value) {
        for (EventStatus s : values()) {
            if (s.dbValue.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown event status: " + value);
    }

    @Converter(autoApply = true)
    public static class JpaConverter implements AttributeConverter<EventStatus, String> {
        @Override
        public String convertToDatabaseColumn(EventStatus attribute) {
            return attribute == null ? null : attribute.dbValue;
        }

        @Override
        public EventStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : fromDb(dbData);
        }
    }
}
