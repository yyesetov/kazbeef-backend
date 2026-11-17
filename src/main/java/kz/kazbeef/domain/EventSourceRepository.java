package kz.kazbeef.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EventSourceRepository extends JpaRepository<EventSource, Long> {
    Optional<EventSource> findBySourceAndExternalId(Source source, String externalId);
}
