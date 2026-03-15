package com.hedgefund.ai.service;

import com.hedgefund.ai.model.HedgeFundRequest;
import com.hedgefund.ai.model.HedgeFundResponse;
import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.agent.PortfolioDecision;
import com.hedgefund.ai.service.agents.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class HedgeFundOrchestrator {

    private static final Logger log = Logger.getLogger(HedgeFundOrchestrator.class.getName());

    private final Map<String, AnalystAgent> agentRegistry;
    private final RiskManagerAgent riskManagerAgent;
    private final PortfolioManagerAgent portfolioManagerAgent;
    private final ReportGeneratorService reportGeneratorService;

    @Value("${hedgefund.default-model-name:gpt-4.1}")
    private String defaultModelName;

    @Value("${hedgefund.default-model-provider:OPENAI}")
    private String defaultModelProvider;

    @Value("${hedgefund.initial-cash:100000}")
    private double defaultInitialCash;

    @Value("${hedgefund.margin-requirement:0.5}")
    private double defaultMarginRequirement;

    public HedgeFundOrchestrator(
            List<AnalystAgent> agents,
            RiskManagerAgent riskManagerAgent,
            PortfolioManagerAgent portfolioManagerAgent,
            ReportGeneratorService reportGeneratorService) {
        this.riskManagerAgent = riskManagerAgent;
        this.portfolioManagerAgent = portfolioManagerAgent;
        this.reportGeneratorService = reportGeneratorService;
        this.agentRegistry = new LinkedHashMap<>();
        for (AnalystAgent agent : agents) {
            this.agentRegistry.put(agent.getAgentId(), agent);
        }
        log.info("Registered " + agentRegistry.size() + " analyst agents: " + agentRegistry.keySet());
    }

    public HedgeFundResponse run(HedgeFundRequest request) {
        log.info("Starting hedge fund analysis for tickers: " + request.getTickers());
        AgentState state = buildState(request);

        List<String> selectedKeys = request.getSelectedAnalysts();
        if (selectedKeys == null || selectedKeys.isEmpty()) {
            selectedKeys = new ArrayList<>(agentRegistry.keySet());
        }

        // Step 1: Run analyst agents in parallel
        log.info("Running " + selectedKeys.size() + " analysts in parallel...");
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(selectedKeys.size(), Runtime.getRuntime().availableProcessors()));

        Map<String, Future<Map<String, AnalystSignal>>> futures = new LinkedHashMap<>();
        for (String key : selectedKeys) {
            AnalystAgent agent = agentRegistry.get(key);
            if (agent == null) { log.warning("Unknown analyst key: " + key); continue; }
            futures.put(agent.getAgentId(), executor.submit(() -> agent.analyze(state)));
        }

        for (var entry : futures.entrySet()) {
            try {
                Map<String, AnalystSignal> signals = entry.getValue().get(120, TimeUnit.SECONDS);
                state.getAnalystSignals().put(entry.getKey(), signals);
                log.info("✓ " + entry.getKey() + " completed");
            } catch (Exception e) {
                log.log(Level.SEVERE, "✗ " + entry.getKey() + " failed: " + e.getMessage(), e);
            }
        }
        executor.shutdown();

        // Step 2: Risk Management
        log.info("Running risk management...");
        Map<String, Map<String, Object>> riskData = riskManagerAgent.analyze(state);
        Map<String, AnalystSignal> riskSignals = new LinkedHashMap<>();
        for (var entry : riskData.entrySet()) {
            riskSignals.put(entry.getKey(), AnalystSignal.builder()
                    .signal("neutral").confidence(100).reasoning(entry.getValue()).build());
        }
        state.getAnalystSignals().put("risk_management_agent", riskSignals);

        // Step 3: Portfolio Manager
        log.info("Running portfolio manager...");
        Map<String, PortfolioDecision> decisions = portfolioManagerAgent.decide(state, riskData);
        state.setDecisions(decisions);

        // Build intermediate response for report generation
        HedgeFundResponse response = HedgeFundResponse.builder()
                .decisions(decisions)
                .analystSignals(state.getAnalystSignals())
                .build();

        // Step 4: Generate LLM-powered report
        log.info("Generating trading report...");
        String report = reportGeneratorService.generateReport(
                response, request.getTickers(), state.getModelProvider(), state.getModelName());
        response.setReport(report);

        log.info("Hedge fund analysis complete.");
        return response;
    }

    public List<Map<String, String>> listAgents() {
        List<Map<String, String>> list = new ArrayList<>();
        for (AnalystAgent agent : agentRegistry.values()) {
            list.add(Map.of("key", agent.getAgentId(), "display_name", agent.getDisplayName(), "description", agent.getDescription()));
        }
        return list;
    }

    private AgentState buildState(HedgeFundRequest request) {
        String endDate = request.getEndDate() != null ? request.getEndDate()
                : LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String startDate = request.getStartDate() != null ? request.getStartDate()
                : LocalDate.now().minusMonths(3).format(DateTimeFormatter.ISO_LOCAL_DATE);
        double initialCash = request.getInitialCash() > 0 ? request.getInitialCash() : defaultInitialCash;
        double marginReq = request.getMarginRequirement() > 0 ? request.getMarginRequirement() : defaultMarginRequirement;

        Map<String, Object> portfolio = new LinkedHashMap<>();
        portfolio.put("cash", initialCash);
        portfolio.put("margin_requirement", marginReq);
        portfolio.put("margin_used", 0.0);
        Map<String, Map<String, Object>> positions = new LinkedHashMap<>();
        for (String ticker : request.getTickers()) {
            positions.put(ticker, Map.of("long", 0, "short", 0, "long_cost_basis", 0.0, "short_cost_basis", 0.0, "short_margin_used", 0.0));
        }
        portfolio.put("positions", positions);

        return AgentState.builder()
                .tickers(request.getTickers()).startDate(startDate).endDate(endDate)
                .portfolio(portfolio).showReasoning(request.isShowReasoning())
                .modelName(request.getModelName() != null ? request.getModelName() : defaultModelName)
                .modelProvider(request.getModelProvider() != null ? request.getModelProvider() : defaultModelProvider)
                .build();
    }
}
