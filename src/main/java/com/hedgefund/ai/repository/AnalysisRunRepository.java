package com.hedgefund.ai.repository;

import com.hedgefund.ai.model.persistence.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, Long> {

    List<AnalysisRun> findByAnalysisDateBetweenOrderByCreatedAtDesc(LocalDate from, LocalDate to);

    List<AnalysisRun> findByTickersContainingOrderByCreatedAtDesc(String ticker);

    @Query("SELECT ar FROM AnalysisRun ar ORDER BY ar.createdAt DESC")
    List<AnalysisRun> findRecentRuns(org.springframework.data.domain.Pageable pageable);
}

