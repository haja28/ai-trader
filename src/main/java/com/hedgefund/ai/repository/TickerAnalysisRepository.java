package com.hedgefund.ai.repository;

import com.hedgefund.ai.model.persistence.TickerAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TickerAnalysisRepository extends JpaRepository<TickerAnalysis, Long> {

    List<TickerAnalysis> findByTickerOrderByAnalysisRun_CreatedAtDesc(String ticker);

    @Query("SELECT ta FROM TickerAnalysis ta WHERE ta.ticker = :ticker " +
           "AND ta.analysisRun.analysisDate BETWEEN :from AND :to " +
           "ORDER BY ta.analysisRun.createdAt DESC")
    List<TickerAnalysis> findByTickerAndDateRange(
            @Param("ticker") String ticker,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Find analyses that need outcome review (outcome is null or 'pending') */
    @Query("SELECT ta FROM TickerAnalysis ta WHERE ta.outcome IS NULL OR ta.outcome = 'pending'")
    List<TickerAnalysis> findPendingOutcomes();

    /** Accuracy stats: count by outcome for a ticker */
    @Query("SELECT ta.outcome, COUNT(ta) FROM TickerAnalysis ta " +
           "WHERE ta.ticker = :ticker AND ta.outcome IS NOT NULL AND ta.outcome <> 'pending' " +
           "GROUP BY ta.outcome")
    List<Object[]> countByOutcomeForTicker(@Param("ticker") String ticker);

    /** Overall accuracy stats */
    @Query("SELECT ta.outcome, COUNT(ta) FROM TickerAnalysis ta " +
           "WHERE ta.outcome IS NOT NULL AND ta.outcome <> 'pending' " +
           "GROUP BY ta.outcome")
    List<Object[]> countByOutcome();
}

