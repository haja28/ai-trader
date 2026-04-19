# Available Analyst Agents — Complete List

## Currently Registered Analysts (10 Total)

Your AI Hedge Fund system has **10 active analyst agents** registered and ready to use:

### 1. **Trading Signal Agent** ⭐ (V2 Enhanced)
- **Agent ID:** `trading_signal`
- **Display Name:** Sniper Confluence Agent
- **Description:** 7-Factor Confluence Entry/Exit — LONG/SHORT with TP1-TP5, ATR Stop-Loss & Breakeven Trailing
- **Type:** Technical/Tactical
- **Key Features:**
  - 10-factor weighted scoring model
  - Regime detection (trend/range/volatility)
  - Higher-timeframe alignment proxy
  - Relative strength proxy
  - Gate rules (ATR%, ADX minimums)
  - Logistic confidence calibration
  - 5 take-profit levels (1R-5R)
  - ATR-based stop-loss
  - Breakeven trailing after TP1

---

### 2. **Technical Analyst Agent**
- **Agent ID:** `technical_analyst`
- **Display Name:** Technical Analyst
- **Description:** Chart Pattern Specialist
- **Type:** Technical
- **Key Features:**
  - Trend-following (EMA 8/21 crossover)
  - Mean reversion (Bollinger Band z-score)
  - Momentum (RSI 14)
  - Weighted ensemble (35% trend, 30% mean-reversion, 35% momentum)

---

### 3. **Fundamentals Analyst Agent**
- **Agent ID:** `fundamentals_analyst`
- **Display Name:** Fundamentals Analyst
- **Description:** Financial Statement Specialist
- **Type:** Fundamental
- **Key Features:**
  - P/E ratio analysis
  - Revenue growth trends
  - Profitability metrics
  - Financial statement deep dive

---

### 4. **Valuation Analyst Agent**
- **Agent ID:** `valuation_analyst`
- **Display Name:** Valuation Analyst
- **Description:** Company Valuation Specialist
- **Type:** Fundamental
- **Key Features:**
  - Intrinsic value calculation
  - Relative valuation (P/E, P/B, P/S)
  - DCF modeling approach
  - Fair value estimates

---

### 5. **Growth Analyst Agent**
- **Agent ID:** `growth_analyst`
- **Display Name:** Growth Analyst
- **Description:** Growth Specialist
- **Type:** Fundamental
- **Key Features:**
  - Revenue growth rate analysis
  - Earnings growth trajectory
  - Market expansion potential
  - Growth sustainability metrics

---

### 6. **Sentiment Analyst Agent**
- **Agent ID:** `sentiment_analyst`
- **Display Name:** Sentiment Analyst
- **Description:** Market Sentiment Specialist
- **Type:** Alternative Data
- **Key Features:**
  - Market mood assessment
  - Social media sentiment
  - Investor psychology indicators
  - Fear & greed index correlation

---

### 7. **News Sentiment Agent**
- **Agent ID:** `news_sentiment_analyst`
- **Display Name:** News Sentiment Analyst
- **Description:** News Sentiment Specialist
- **Type:** Alternative Data
- **Key Features:**
  - News article sentiment scoring
  - Media coverage analysis
  - Event impact assessment
  - Real-time news monitoring

---

### 8. **Warren Buffett Agent** 🎩
- **Agent ID:** `warren_buffett`
- **Display Name:** Warren Buffett
- **Description:** The Oracle of Omaha
- **Type:** Value Investing / LLM-based
- **Key Features:**
  - Value investing principles
  - Long-term perspective
  - Quality business assessment
  - "Moat" evaluation
  - Mimics Buffett's investment philosophy

---

### 9. **Risk Manager Agent** 🛡️
- **Agent ID:** `risk_management_agent`
- **Display Name:** Risk Manager
- **Description:** Portfolio Risk Controller
- **Type:** Risk Management
- **Key Features:**
  - Position limit enforcement (20% default)
  - Portfolio exposure tracking
  - Volatility calculation (annualized)
  - Risk-adjusted position sizing
  - Margin requirement compliance

---

### 10. **Portfolio Manager Agent** 📊
- **Agent ID:** `portfolio_manager`
- **Display Name:** Portfolio Manager
- **Description:** Portfolio Decision Orchestrator
- **Type:** Execution
- **Key Features:**
  - Aggregates all analyst signals
  - Makes final buy/sell/hold decisions
  - Determines position sizes
  - Enforces risk limits
  - Outputs actionable portfolio decisions

---

## How to Use Specific Analysts

### Default Behavior (All Analysts)
If you don't specify analysts, **all 10 agents run in parallel**:

```json
{
  "tickers": ["AAPL", "MSFT"],
  "startDate": "2026-01-01",
  "endDate": "2026-03-19"
}
```

### Select Specific Analysts
Use the `selectedAnalysts` array with agent IDs:

```json
{
  "tickers": ["AAPL", "MSFT"],
  "startDate": "2026-01-01",
  "endDate": "2026-03-19",
  "selectedAnalysts": [
    "trading_signal",
    "technical_analyst",
    "fundamentals_analyst"
  ]
}
```

### Recommended Combinations

#### **Quick Technical Scan**
```json
"selectedAnalysts": [
  "trading_signal",
  "technical_analyst"
]
```
Fast execution, focuses on price action and momentum.

#### **Deep Fundamental Analysis**
```json
"selectedAnalysts": [
  "fundamentals_analyst",
  "valuation_analyst",
  "growth_analyst",
  "warren_buffett"
]
```
Comprehensive fundamental deep dive, value investing focus.

#### **Sentiment + Technicals**
```json
"selectedAnalysts": [
  "trading_signal",
  "technical_analyst",
  "sentiment_analyst",
  "news_sentiment_analyst"
]
```
Combines chart patterns with market mood and news flow.

#### **Full Portfolio Review**
```json
"selectedAnalysts": [
  "trading_signal",
  "technical_analyst",
  "fundamentals_analyst",
  "valuation_analyst",
  "sentiment_analyst",
  "news_sentiment_analyst"
]
```
Comprehensive analysis from all angles (excludes growth/Buffett for speed).

---

## Execution Flow

1. **Analyst Agents** (parallel execution)
   - All selected agents run simultaneously
   - Each produces a `signal` (bullish/bearish/neutral) + `confidence` + `reasoning`

2. **Risk Manager Agent** (sequential, after analysts)
   - Evaluates portfolio exposure
   - Calculates position limits
   - Assesses volatility

3. **Portfolio Manager Agent** (sequential, after risk)
   - Aggregates all analyst signals
   - Applies risk constraints
   - Outputs final decisions: `buy`, `sell`, `hold` with quantities

---

## Agent Output Structure

Each analyst returns:
```json
{
  "AAPL": {
    "signal": "bullish",
    "confidence": 78.0,
    "reasoning": {
      // Agent-specific details
    }
  }
}
```

**Trading Signal Agent** includes additional fields:
```json
{
  "reasoning": {
    "trading_signal": {
      "position": "LONG",
      "entryPoint": 172.43,
      "stopLoss": 168.92,
      "tp1": 175.94,
      "tp2": 179.45,
      "tp3": 182.96,
      "tp4": 186.47,
      "tp5": 189.98,
      "riskRewardRatio": 3.0,
      "algorithm": "Sniper Confluence V2..."
    }
  }
}
```

---

## Current Configuration

From your `HedgeFundOrchestrator.java`:

- **Default Behavior:** All agents run if `selectedAnalysts` is null/empty
- **Parallel Execution:** Uses thread pool (min of agent count or CPU cores)
- **Timeout:** 120 seconds per agent
- **Error Handling:** Failed agents logged but don't block others

---

## Summary

✅ **10 Active Analysts:**
- 1× Trading Signal (V2 enhanced with 10-factor model)
- 1× Technical Analyst (trend/mean-reversion/momentum)
- 4× Fundamental Analysts (fundamentals/valuation/growth/Warren Buffett)
- 2× Sentiment Analysts (market sentiment/news sentiment)
- 1× Risk Manager (portfolio risk control)
- 1× Portfolio Manager (final decision orchestrator)

All agents are `@Component` registered and auto-discovered by Spring Boot.

**Most Powerful Agent:** `trading_signal` (V2) with 10-factor confluence, regime detection, and complete entry/exit management.

**Date:** March 19, 2026

