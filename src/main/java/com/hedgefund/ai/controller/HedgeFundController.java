package com.hedgefund.ai.controller;

import com.hedgefund.ai.model.HedgeFundRequest;
import com.hedgefund.ai.model.HedgeFundResponse;
import com.hedgefund.ai.service.HedgeFundOrchestrator;
import com.hedgefund.ai.service.PdfReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST API for the AI Hedge Fund.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class HedgeFundController {

    private static final Logger log = Logger.getLogger(HedgeFundController.class.getName());
    private final HedgeFundOrchestrator orchestrator;
    private final PdfReportService pdfReportService;

    public HedgeFundController(HedgeFundOrchestrator orchestrator,
                               PdfReportService pdfReportService) {
        this.orchestrator = orchestrator;
        this.pdfReportService = pdfReportService;
    }

    /**
     * Run the hedge fund analysis pipeline.
     * Returns JSON with decisions, analyst signals, and an LLM-generated Markdown report.
     *
     * POST /api/hedge-fund/run
     * Body: HedgeFundRequest JSON
     */
    @PostMapping("/hedge-fund/run")
    public ResponseEntity<HedgeFundResponse> runHedgeFund(@Valid @RequestBody HedgeFundRequest request) {
        log.info("Received hedge fund request for tickers: " + request.getTickers());
        HedgeFundResponse response = orchestrator.run(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Run the analysis and return ONLY the Markdown report as plain text.
     * Ideal for direct consumption / rendering.
     *
     * POST /api/hedge-fund/report
     * Body: HedgeFundRequest JSON
     * Returns: text/markdown
     */
    @PostMapping(value = "/hedge-fund/report", produces = "text/markdown; charset=UTF-8")
    public ResponseEntity<String> runReport(@Valid @RequestBody HedgeFundRequest request) {
        log.info("Received report request for tickers: " + request.getTickers());
        HedgeFundResponse response = orchestrator.run(request);
        String report = response.getReport() != null ? response.getReport() : "Report generation failed.";
        return ResponseEntity.ok(report);
    }

    /**
     * Run the analysis and return a downloadable PDF report.
     *
     * POST /api/hedge-fund/report/pdf
     * Body: HedgeFundRequest JSON
     * Returns: application/pdf (attachment download)
     */
    @PostMapping(value = "/hedge-fund/report/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> runPdfReport(@Valid @RequestBody HedgeFundRequest request) {
        log.info("Received PDF report request for tickers: " + request.getTickers());
        HedgeFundResponse response = orchestrator.run(request);

        String markdownReport = response.getReport();
        if (markdownReport == null || markdownReport.isBlank()) {
            return ResponseEntity.internalServerError().build();
        }

        try {
            byte[] pdfBytes = pdfReportService.generatePdf(markdownReport, request.getTickers());

            String filename = "hedge_fund_report_"
                    + String.join("_", request.getTickers()).toLowerCase()
                    + "_" + LocalDate.now() + ".pdf";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                    .body(pdfBytes);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to generate PDF report", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * List available analyst agents.
     *
     * GET /api/agents
     */
    @GetMapping("/agents")
    public ResponseEntity<List<Map<String, String>>> listAgents() {
        return ResponseEntity.ok(orchestrator.listAgents());
    }

    /**
     * Health check.
     *
     * GET /api/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
