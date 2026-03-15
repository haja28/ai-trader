# AI Hedge Fund — Spring Boot + Spring AI

A Java/Spring Boot conversion of the [AI Hedge Fund](../ai-hedge-fund/) Python project.  
Uses **Spring AI** for LLM integration (OpenAI & Ollama) and a REST API for the hedge fund analysis pipeline.

## Architecture

```
┌─────────────────────────────────────────────────┐
│               REST Controller                    │
│           POST /api/hedge-fund/run              │
└──────────────────┬──────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────┐
│           HedgeFundOrchestrator                  │
│                                                  │
│  1. Run analyst agents in parallel               │
│  2. Run risk management                          │
│  3. Run portfolio manager (LLM decisions)        │
└──────────────────┬──────────────────────────────┘
                   │
    ┌──────────────┼──────────────┐
    │              │              │
┌───▼───┐    ┌────▼────┐   ┌────▼────┐
│Analyst │    │  Risk   │   │Portfolio│
│ Agents │    │ Manager │   │ Manager │
│(parallel)   │         │   │  (LLM)  │
└───┬───┘    └────┬────┘   └────┬────┘
    │              │              │
┌───▼──────────────▼──────────────▼───┐
│       FinancialDataClient            │
│         (interface)                  │
│                                      │
│  ┌──────────────┐ ┌──────────────┐  │
│  │ Financial    │ │   EODHD      │  │
│  │ Datasets     │ │   Client     │  │
│  │ (.ai API)    │ │ (eodhd.com)  │  │
│  └──────────────┘ └──────────────┘  │
└─────────────────┬───────────────────┘
                  │
┌─────────────────▼───────────────────┐
│         LlmService                   │
│   (Spring AI: OpenAI / Ollama)       │
└─────────────────────────────────────┘
```

## Analyst Agents

| Agent | Key | Description |
|-------|-----|-------------|
| Fundamentals Analyst | `fundamentals_analyst` | Financial statement analysis |
| Technical Analyst | `technical_analyst` | Chart patterns & indicators |
| Sentiment Analyst | `sentiment_analyst` | Insider trades + news |
| News Sentiment | `news_sentiment_analyst` | LLM-classified news headlines |
| Growth Analyst | `growth_analyst` | Revenue/earnings growth |
| Valuation Analyst | `valuation_analyst` | DCF, P/E, P/B, FCF yield |
| Warren Buffett | `warren_buffett` | Buffett-style LLM analysis |

## Prerequisites

- **Java 21+**
- **Maven 3.9+**
- API keys (set as environment variables or in `application.properties`):
  - `OPENAI_API_KEY` — for OpenAI models
  - **Financial data** (choose one):
    - `FINANCIAL_DATASETS_API_KEY` — for [financialdatasets.ai](https://financialdatasets.ai) (default)
    - `EODHD_API_TOKEN` — for [EODHD](https://eodhd.com) (set `FINANCIAL_DATA_PROVIDER=eodhd`)
  - *(Optional)* Ollama running at `http://localhost:11434` for local LLMs

## Quick Start

### 1. Clone & Build

```bash
cd ai-hedge-fund-java
mvn clean package -DskipTests
```

### 2. Choose a Provider & Start the Application

#### Option A — OpenAI (requires API key)

Set your API keys first:

```bash
# Linux/Mac
export OPENAI_API_KEY=sk-...
export FINANCIAL_DATASETS_API_KEY=your-key

# Windows PowerShell
$env:OPENAI_API_KEY = "sk-..."
$env:FINANCIAL_DATASETS_API_KEY = "your-key"
```

To use **EODHD** instead of Financial Datasets:

```bash
# Linux/Mac
export FINANCIAL_DATA_PROVIDER=eodhd
export EODHD_API_TOKEN=your-eodhd-token

# Windows PowerShell
$env:FINANCIAL_DATA_PROVIDER = "eodhd"
$env:EODHD_API_TOKEN = "your-eodhd-token"
```

Then start:

```bash
mvn spring-boot:run
```

Or with the JAR:

```bash
java -jar target/ai-hedge-fund-1.0.0-SNAPSHOT.jar
$env:FINANCIAL_DATA_PROVIDER = "eodhd"
$env:EODHD_API_TOKEN = "your-eodhd-token"
$env:EODHD_DEFAULT_EXCHANGE = "SG"
java -jar target/ai-hedge-fund-1.0.0-SNAPSHOT.jar --spring.profiles.active=ollama
```

#### Option B — Ollama / Local LLM (no OpenAI key needed)

> **Important:** You **must** activate the `ollama` Spring profile to disable OpenAI auto-configuration.
> Without it, Spring Boot will fail to start with:
> `OpenAI API key must be set`.

**Using Maven:**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=ollama
```

**Using the JAR:**

```bash
java -jar target/ai-hedge-fund-1.0.0-SNAPSHOT.jar --spring.profiles.active=ollama
```

**Using an environment variable (PowerShell):**

```powershell
$env:SPRING_PROFILES_ACTIVE = "ollama"
java -jar target/ai-hedge-fund-1.0.0-SNAPSHOT.jar
```

**Using an environment variable (Bash):**

```bash
SPRING_PROFILES_ACTIVE=ollama java -jar target/ai-hedge-fund-1.0.0-SNAPSHOT.jar
```

The `ollama` profile (`application-ollama.properties`) does the following:
- Excludes `OpenAiAutoConfiguration` so no OpenAI API key is required
- Sets `hedgefund.default-model-provider=OLLAMA`
- Defaults to the `qwen3:30b` model (override with `$env:OLLAMA_MODEL` or `--spring.ai.ollama.chat.options.model=<model>`)

You can also override the default model at startup:

```bash
# Maven
mvn spring-boot:run -Dspring-boot.run.profiles=ollama -Dspring-boot.run.arguments="--spring.ai.ollama.chat.options.model=llama3"

# JAR
java -jar target/ai-hedge-fund-1.0.0-SNAPSHOT.jar --spring.profiles.active=ollama --spring.ai.ollama.chat.options.model=llama3
```

### 3. Call the API

**Using OpenAI (default):**

```bash
curl -X POST http://localhost:8080/api/hedge-fund/run \
  -H "Content-Type: application/json" \
  -d '{
    "tickers": ["AAPL", "MSFT", "NVDA"],
    "showReasoning": true
  }'
```

**Using Ollama (local LLM) — just set `modelProvider` to `"OLLAMA"` in the request:**

```bash
curl -X POST http://localhost:8080/api/hedge-fund/run \
  -H "Content-Type: application/json" \
  -d '{
    "tickers": ["AAPL", "MSFT"],
    "modelProvider": "OLLAMA",
    "showReasoning": true
  }'
```

You can also specify a particular Ollama model via `modelName` (it must already be pulled):

```bash
curl -X POST http://localhost:8080/api/hedge-fund/run \
  -H "Content-Type: application/json" \
  -d '{
    "tickers": ["AAPL"],
    "modelProvider": "OLLAMA",
    "modelName": "llama3:70b",
    "showReasoning": true
  }'
```

> **Tip:** You can mix & match — some analysts use OpenAI, but the request-level
> `modelProvider` controls LLM-based agents (Warren Buffett, News Sentiment, Portfolio Manager).
> Non-LLM agents (Fundamentals, Technical, etc.) don't call an LLM and work regardless.

## Using Ollama (Local LLMs)

### 1. Install Ollama

Download from [ollama.com](https://ollama.com/download) and install for your OS.

**Windows:** Download the installer and run it. Ollama will be available at `http://localhost:11434`.

### 2. Pull a Model

```bash
ollama pull llama3
```

Other recommended models:
```bash
ollama pull llama3:70b       # Larger, more capable
ollama pull mistral           # Good alternative
ollama pull deepseek-coder    # Good for structured output
```

### 3. Start Ollama

```bash
ollama serve
```

### 4. Run the Hedge Fund with Ollama

**Option A — Start the server with the `ollama` profile (recommended):**

```bash
# Maven
mvn spring-boot:run -Dspring-boot.run.profiles=ollama

# JAR
java -jar target/ai-hedge-fund-1.0.0-SNAPSHOT.jar --spring.profiles.active=ollama
```

This uses the `application-ollama.properties` profile which disables OpenAI and defaults to Ollama.

**Option B — Per-request override:** If the server was started with the `ollama` profile (or with an OpenAI key), you can override the provider per request by setting `"modelProvider": "OLLAMA"` in the JSON body:

```bash
curl -X POST http://localhost:8080/api/hedge-fund/run \
  -H "Content-Type: application/json" \
  -d '{"tickers": ["AAPL"], "modelProvider": "OLLAMA"}'
```

**Option C — Server-wide default with a specific model:**

```bash
# PowerShell
$env:OLLAMA_MODEL = "llama3"
mvn spring-boot:run -Dspring-boot.run.profiles=ollama

# Bash
OLLAMA_MODEL=llama3 mvn spring-boot:run -Dspring-boot.run.profiles=ollama
```

### 5. Custom Ollama Host

If Ollama runs on a different machine or port:

```bash
# PowerShell
$env:OLLAMA_BASE_URL = "http://192.168.1.100:11434"
mvn spring-boot:run

# Or in application.properties:
# spring.ai.ollama.base-url=http://192.168.1.100:11434
```

**List available agents:**

```bash
curl http://localhost:8080/api/agents
```

## Financial Data Providers

The application supports two financial data providers, selectable via the `financial.data.provider` property (or the `FINANCIAL_DATA_PROVIDER` environment variable).

### Financial Datasets (default)

[financialdatasets.ai](https://financialdatasets.ai) — provides prices, financial metrics, line items, insider trades, company news, and company facts via a dedicated REST API.

```properties
financial.data.provider=financial-datasets
financial.datasets.api-key=your-key
```

### EODHD

[eodhd.com](https://eodhd.com) — comprehensive financial data API covering 70+ exchanges with EOD prices, fundamentals, insider transactions, news with sentiment, and historical market cap. Authenticates via the `api_token` query parameter.

```properties
financial.data.provider=eodhd
eodhd.api-token=your-eodhd-token
eodhd.base-url=https://eodhd.com/api
# Default exchange for bare tickers (US, SG, LSE, TO, etc.)
eodhd.default-exchange=US
```

**Using EODHD for Singapore (SGX) stocks:**

Set the default exchange to `SG` so bare tickers are automatically resolved to the Singapore Exchange:

```bash
# Linux/Mac
export FINANCIAL_DATA_PROVIDER=eodhd
export EODHD_API_TOKEN=your-token
export EODHD_DEFAULT_EXCHANGE=SG

# Windows PowerShell
$env:FINANCIAL_DATA_PROVIDER = "eodhd"
$env:EODHD_API_TOKEN = "your-token"
$env:EODHD_DEFAULT_EXCHANGE = "SG"
```

Then you can pass SGX ticker codes directly:

```bash
curl -X POST http://localhost:8080/api/hedge-fund/run \
  -H "Content-Type: application/json" \
  -d '{
    "tickers": ["D05", "O39", "U11", "Z74"],
    "showReasoning": true
  }'
```

Common SGX ticker codes: `D05` (DBS), `O39` (OCBC), `U11` (UOB), `Z74` (Singtel), `C6L` (SIA), `BS6` (YZJ Shipbuilding), `S68` (SGX), `A17U` (CapitaLand Ascendas REIT).

You can also mix exchanges by using the full `SYMBOL.EXCHANGE` format — tickers with a dot are passed through as-is:

```bash
# Mix Singapore and US stocks
curl -X POST http://localhost:8080/api/hedge-fund/run \
  -H "Content-Type: application/json" \
  -d '{
    "tickers": ["D05.SG", "AAPL.US", "O39.SG", "MSFT.US"],
    "showReasoning": true
  }'
```

**EODHD endpoint mapping:**

| Feature | EODHD Endpoint | Notes |
|---------|----------------|-------|
| Prices | `GET /eod/{TICKER.US}` | Daily OHLCV with adjusted close |
| Financial Metrics | `GET /fundamentals/{TICKER.US}` | Derived from Highlights, Valuation, Financials sections |
| Line Items | `GET /fundamentals/{TICKER.US}` | Extracted from Income_Statement, Balance_Sheet, Cash_Flow |
| Insider Trades | `GET /insider-transactions` | Mapped from SEC Form 4 filings |
| Company News | `GET /news` | Includes sentiment polarity scores |
| Market Cap | `GET /historical-market-cap/{TICKER.US}` | Falls back to fundamentals |

> **Note:** EODHD tickers use the `SYMBOL.EXCHANGE` format (e.g. `AAPL.US`, `D05.SG`). The client automatically appends the configured default exchange (`.US` by default) to bare tickers. Set `eodhd.default-exchange=SG` for Singapore stocks.

## API Reference

### `POST /api/hedge-fund/run`

**Request body:**

```json
{
  "tickers": ["AAPL", "MSFT"],
  "startDate": "2025-01-01",
  "endDate": "2025-06-01",
  "initialCash": 100000.0,
  "marginRequirement": 0.5,
  "selectedAnalysts": ["fundamentals_analyst", "warren_buffett"],
  "showReasoning": true,
  "modelName": "gpt-4.1",
  "modelProvider": "OPENAI"
}
```

**Response:**

```json
{
  "decisions": {
    "AAPL": {
      "action": "buy",
      "quantity": 50,
      "confidence": 85,
      "reasoning": "Strong fundamentals and growth"
    }
  },
  "analystSignals": {
    "fundamentals_analyst": {
      "AAPL": {
        "signal": "bullish",
        "confidence": 75.0,
        "reasoning": { ... }
      }
    }
  }
}
```

### `GET /api/agents`

Returns list of available analyst agents with keys, display names, and descriptions.

### `GET /api/health`

Health check endpoint.

## Configuration

Key properties in `application.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `spring.ai.openai.api-key` | `${OPENAI_API_KEY}` | OpenAI API key |
| `spring.ai.openai.chat.options.model` | `gpt-4.1` | OpenAI model |
| `spring.ai.ollama.base-url` | `http://localhost:11434` | Ollama URL |
| `financial.data.provider` | `financial-datasets` | Data provider: `financial-datasets` or `eodhd` |
| `financial.datasets.api-key` | `${FINANCIAL_DATASETS_API_KEY}` | Financial Datasets API key |
| `eodhd.api-token` | `${EODHD_API_TOKEN}` | EODHD API token |
| `eodhd.base-url` | `https://eodhd.com/api` | EODHD base URL |
| `eodhd.default-exchange` | `US` | Default exchange suffix for bare tickers (`US`, `SG`, `LSE`, etc.) |
| `hedgefund.default-model-provider` | `OPENAI` | Default LLM provider |
| `hedgefund.max-llm-retries` | `3` | Max LLM retry attempts |
| `hedgefund.initial-cash` | `100000.0` | Default starting cash |

## Project Structure

```
src/main/java/com/hedgefund/ai/
├── AiHedgeFundApplication.java       # Spring Boot entry point
├── cache/
│   └── ApiCache.java                 # In-memory Caffeine cache
├── config/
│   └── JacksonConfig.java            # JSON serialization config
├── controller/
│   └── HedgeFundController.java      # REST API endpoints
├── model/
│   ├── HedgeFundRequest.java         # Request DTO
│   ├── HedgeFundResponse.java        # Response DTO
│   ├── agent/
│   │   ├── AgentState.java           # Pipeline state
│   │   ├── AnalystSignal.java        # Agent output signal
│   │   └── PortfolioDecision.java    # Trading decision
│   └── api/
│       ├── ApiResponses.java         # API response wrappers
│       ├── CompanyFacts.java         # Company metadata
│       ├── CompanyNews.java          # News article
│       ├── FinancialMetrics.java     # Financial metrics
│       ├── InsiderTrade.java         # Insider trade
│       ├── LineItem.java             # Financial line item
│       └── Price.java                # Stock price
├── service/
│   ├── HedgeFundOrchestrator.java    # Main workflow orchestrator
│   ├── LlmService.java              # Spring AI LLM wrapper
│   ├── agents/
│   │   ├── AnalystAgent.java         # Agent interface
│   │   ├── FundamentalsAnalystAgent.java
│   │   ├── GrowthAnalystAgent.java
│   │   ├── NewsSentimentAgent.java
│   │   ├── PortfolioManagerAgent.java
│   │   ├── RiskManagerAgent.java
│   │   ├── SentimentAnalystAgent.java
│   │   ├── TechnicalAnalystAgent.java
│   │   ├── ValuationAnalystAgent.java
│   │   └── WarrenBuffettAgent.java
│   └── api/
│       ├── FinancialDataClient.java      # Provider interface
│       ├── FinancialDatasetsClient.java  # financialdatasets.ai impl
│       └── EodhdClient.java             # EODHD (eodhd.com) impl
└── resources/
    └── application.properties
```

## Mapping from Python to Java

| Python Component | Java Equivalent |
|-----------------|-----------------|
| `src/main.py` (LangGraph workflow) | `HedgeFundOrchestrator.java` |
| `src/utils/llm.py` (call_llm) | `LlmService.java` (Spring AI) |
| `src/tools/api.py` | `FinancialDatasetsClient.java` (WebClient) |
| `src/data/cache.py` | `ApiCache.java` (Caffeine) |
| `src/data/models.py` | `model/api/*.java` (POJOs) |
| `src/graph/state.py` | `AgentState.java` |
| `src/agents/*.py` | `service/agents/*.java` |
| `src/agents/risk_manager.py` | `RiskManagerAgent.java` |
| `src/agents/portfolio_manager.py` | `PortfolioManagerAgent.java` |
| Pydantic models | Jackson `@Data` POJOs with Lombok |
| LangChain ChatPromptTemplate | Spring AI `ChatClient` |

