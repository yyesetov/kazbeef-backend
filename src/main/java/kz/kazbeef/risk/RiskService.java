package kz.kazbeef.risk;

import kz.kazbeef.domain.Event;
import kz.kazbeef.domain.EventStatus;
import kz.kazbeef.domain.TrustLevel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Балл риска события для Казахстана, 0..100. Складывается из шести факторов:
 * вес болезни, близость к границе, давность, статус, масштаб и динамика
 * (другие события по той же болезни в той же стране за 30 дней).
 * Событие, не значимое для КРС/МРС, и неподтверждённые данные получают понижающий коэффициент.
 */
@Service
public class RiskService {

    private final KazakhstanGeometry geometry;
    private final RiskProperties props;
    private final JdbcTemplate jdbc;

    public RiskService(KazakhstanGeometry geometry, RiskProperties props, JdbcTemplate jdbc) {
        this.geometry = geometry;
        this.props = props;
        this.jdbc = jdbc;
    }

    /** Пересчитывает расстояние и балл. Вызывается внутри транзакции. Возвращает true, если что-то изменилось. */
    public boolean apply(Event e) {
        Double distance = e.getLatitude() != null && e.getLongitude() != null
                ? (double) Math.round(geometry.distanceKm(e.getLatitude(), e.getLongitude()))
                : null;
        Integer score = score(e, distance);
        boolean changed = !Objects.equals(e.getDistanceToKzKm(), distance) || !Objects.equals(e.getRiskScore(), score);
        e.setDistanceToKzKm(distance);
        e.setRiskScore(score);
        return changed;
    }

    private int score(Event e, Double distanceKm) {
        double points = severity(e) + proximity(e, distanceKm) + recency(e) + status(e) + magnitude(e) + trend(e);
        if (!e.isRuminantRelevant()) {
            points *= 0.4;
        }
        if (e.getTrustLevel() == TrustLevel.SIGNAL) {
            points *= 0.8;
        } else if (e.getTrustLevel() == TrustLevel.ANALYTICS) {
            points *= 0.5;
        }
        return (int) Math.clamp(Math.round(points), 0, 100);
    }

    private double severity(Event e) {
        int severity = e.getDisease() == null ? 2 : e.getDisease().getSeverity();
        return props.severityPoints() * (severity - 1) / 4.0;
    }

    private double proximity(Event e, Double distanceKm) {
        double share;
        if (distanceKm != null) {
            double zone = props.borderZoneKm();
            if (distanceKm <= 0) share = 1.0;
            else if (distanceKm <= zone / 3) share = 0.85;
            else if (distanceKm <= zone) share = 0.7;
            else if (distanceKm <= zone * 2) share = 0.45;
            else if (distanceKm <= zone * 4) share = 0.28;
            else if (distanceKm <= zone * 8) share = 0.14;
            else share = 0.05;
        } else if (e.getCountry() == null) {
            share = 0.1;
        } else if ("KAZ".equals(e.getCountry().getIso3())) {
            share = 1.0;
        } else {
            // координат нет: близость оценивается по группе страны
            share = switch (String.valueOf(e.getCountry().getWatchGroup())) {
                case "neighbor" -> 0.35;
                case "importer" -> 0.2;  // путь заноса — ввоз мяса
                case "cis" -> 0.15;
                default -> 0.08;
            };
        }
        return props.proximityPoints() * share;
    }

    private double recency(Event e) {
        LocalDate date = e.getStatus() == EventStatus.CLOSED
                ? first(e.getStartDate(), e.getReportDate())
                : first(e.getReportDate(), e.getStartDate());
        if (date == null) {
            return props.recencyPoints() * 0.3;
        }
        long days = ChronoUnit.DAYS.between(date, LocalDate.now());
        double share = days <= 14 ? 1.0 : days <= 30 ? 0.8 : days <= 90 ? 0.55 : days <= 180 ? 0.3 : days <= 365 ? 0.12 : 0;
        return props.recencyPoints() * share;
    }

    private double status(Event e) {
        return props.statusPoints() * (e.getStatus() == EventStatus.CLOSED ? 0.2 : 1.0);
    }

    private double magnitude(Event e) {
        int outbreaks = e.getOutbreaks() == null ? 0 : e.getOutbreaks();
        int cases = e.getCases() == null ? 0 : e.getCases();
        double share = outbreaks >= 10 || cases >= 1000 ? 1.0 : outbreaks >= 3 || cases >= 100 ? 0.6 : 0.3;
        return props.magnitudePoints() * share;
    }

    private double trend(Event e) {
        if (e.getDisease() == null || e.getCountry() == null) {
            return 0;
        }
        Integer others = jdbc.queryForObject("""
                SELECT count(*) FROM event
                 WHERE disease_id = ? AND country_id = ? AND id <> ?
                   AND coalesce(start_date, report_date) >= current_date - 30
                """, Integer.class, e.getDisease().getId(), e.getCountry().getId(), e.getId() == null ? -1L : e.getId());
        int n = others == null ? 0 : others;
        return props.trendPoints() * (n >= 3 ? 1.0 : n >= 1 ? 0.6 : 0);
    }

    private static LocalDate first(LocalDate a, LocalDate b) {
        return a != null ? a : b;
    }
}
