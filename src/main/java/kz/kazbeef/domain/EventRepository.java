package kz.kazbeef.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    /** Кандидаты в дубли с координатами: та же болезнь и страна, начало в окне дат. */
    @Query("""
            select e from Event e
             where e.disease = :disease and e.country = :country
               and e.latitude is not null and e.longitude is not null
               and e.startDate between :from and :to
            """)
    List<Event> findLocatedCandidates(Disease disease, Country country, LocalDate from, LocalDate to);

    /** Кандидаты в дубли по дате отчёта: та же болезнь и страна. */
    @Query("""
            select e from Event e
             where e.disease = :disease and e.country = :country
               and e.reportDate between :from and :to
             order by e.reportDate desc
            """)
    List<Event> findReportedCandidates(Disease disease, Country country, LocalDate from, LocalDate to);

    /** Очередь обработки ИИ: есть текст, результата ещё нет, попытки не исчерпаны. */
    @Query("""
            select e from Event e
             where e.aiGenerated = false and e.aiAttempts < :maxAttempts
               and (e.title is not null or e.description is not null)
             order by e.ruminantRelevant desc, e.reportDate desc nulls last
             limit :limit
            """)
    List<Event> findAiQueue(short maxAttempts, int limit);
}
