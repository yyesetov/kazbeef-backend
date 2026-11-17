package kz.kazbeef.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectRunRepository extends JpaRepository<CollectRun, Long> {
    List<CollectRun> findTop20BySourceOrderByStartedAtDesc(Source source);
}
