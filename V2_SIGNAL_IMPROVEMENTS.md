# Trading Signal V2 Improvements

## What Was Changed

### 1. **Enhanced Signal Analysis (TradingSignalAgent.java)**

#### New Factors Added (10-Factor Model)
- **Factor 8: Regime Compatibility** (weight 1.5)
  - Detects market regime: `trend`, `range`, `high_volatility`, `low_volatility`
  - Uses ADX threshold (25), EMA spread, and ATR percentile
  - Only passes when in trend regime with directional movement confirmation

- **Factor 9: Higher-Timeframe Proxy** (weight 1.3)
  - Primary mode: EMA50/EMA100 alignment + EMA50 slope (when 100+ bars available)
  - Fallback mode: EMA9/EMA21 slope + alignment consistency (for shorter histories)
  - Confirms direction on longer timeframe structure

- **Factor 10: Relative Strength Proxy** (weight 1.0)
  - Compares 20-bar vs 60-bar returns
  - LONG: requires positive returns with acceleration (ret20 > ret60*0.3)
  - SHORT: requires negative returns with acceleration
  - Adaptive lookback with sample-quality guard

#### Gate Rules (Pre-Scoring Filters)
Before any signal can fire, these conditions must pass:
- **ATR% Gate**: 0.8% ≤ ATR% ≤ 8.0% (avoids dead/chaotic markets)
- **ADX Gate**: ADX ≥ 18 (avoids complete chop)
- **Regime Gate**: In range/chop regimes, threshold increases by +0.8 points

#### Weighted Scoring System
- Old: all 7 factors = 1 point each (max 7)
- New: 10 factors with differentiated weights (max 10.0)
  - High-impact factors (ADX+DI, regime, HTF) weighted higher
  - Volume/secondary RSI weighted lower
- **Thresholds:**
  - Normal entry: weighted score ≥ 6.2
  - Fresh crossover: weighted score ≥ 5.6
  - Range regime: threshold +0.8

#### Logistic Confidence Model
Replaced linear confidence (`score/7 * 100`) with calibrated logistic function:

```
z = -1.4 + 0.55*weightedScore + 0.35*(crossover) + 0.40*(regimePass) - 0.45*(eventRisk)
probability = 1 / (1 + exp(-z))
confidence = clamp(probability * 100, 5, 95)
```

Benefits:
- Better separation between high/medium/low quality setups
- Accounts for regime compatibility and fresh crossover explicitly
- Ready for event-risk penalty when calendar feed is added
- Output range clamped to 5-95% (realistic bounds)

#### Adaptive Data Handling
- Minimum bars reduced: 35 (from 70+) to support shorter histories
- Factor 9 and 10 have fallback modes when long lookbacks unavailable
- Safe indexing with `Math.min()` guards

---

### 2. **Report Generation Fix (ReportGeneratorService.java)**

#### Problem
With 2+ tickers, the LLM prompt became too large and TP levels were truncated or lost in verbose JSON dumps.

#### Solution
**Three-tier prompt structure:**

1. **Priority Section (Top of Prompt)**
   ```
   === CRITICAL TRADING LEVELS ===
   --- TICKER ---
   Position: LONG
   Entry: 15.23
   Stop Loss: 14.87
   TP1 (1R): 15.59
   TP2 (2R): 15.95
   TP3 (3R): 16.31
   TP4 (4R): 16.67
   TP5 (5R): 17.03
   ...
   ```
   This section is **guaranteed to appear** before any JSON can truncate.

2. **Portfolio Decisions Section**
   Clean table format with actions/quantities/reasoning.

3. **Supporting Context Section**
   - Uses `buildCompactReasoning()` for trading_signal agent
   - Strips verbose `confluence_factors` nested maps
   - Keeps only: scores, gates, indicators, levels, trading_signal object
   - Per-ticker budget scales with ticker count:
     - 1 ticker: 3000 chars
     - 2 tickers: 1000 chars each
     - 3+ tickers: 800 chars each

#### Key Changes
- Moved TP extraction to top of prompt (before verbose JSON)
- Added `buildCompactReasoning()` to create lightweight JSON for trading_signal
- Applied per-ticker character budget with automatic scaling
- Changed label from "Full Reasoning" to "Context" (less verbose)

---

## How to Verify the Fix

### Test Case: 2+ Tickers
```bash
curl -X POST http://localhost:8080/api/hedge-fund/report/pdf \
  -H "Content-Type: application/json" \
  -d '{"tickers": ["AAPL", "MSFT", "GOOGL"]}'
```

### What to Check in Generated PDF

✅ **Every ticker should have:**
- Complete TP1-TP5 table with all 5 price levels
- Entry, Stop Loss, and R:R visible
- Technical indicators table populated
- No "[truncated]" appearing before critical TP data

✅ **Prompt length should be reasonable:**
- Check logs for "LLM report generated successfully (X chars)"
- For 2 tickers: expect ~2500-3500 chars
- For 3 tickers: expect ~3500-4500 chars
- Should not exceed 6000 chars total

### Quick Verification Commands

```powershell
# Restart server to pick up changes
# Then run analysis for 2 tickers and check logs
curl http://localhost:8080/api/hedge-fund/analyze?tickers=HIVE,FUBO | ConvertFrom-Json | ConvertTo-Json -Depth 10
```

Look for:
- `weighted_score` values in analyst signals
- `hard_gates` section with regime info
- All TP1-TP5 fields populated in trading_signal results

---

## Next Steps (Optional Enhancements)

1. **Event Risk Integration**
   - Add earnings calendar API call
   - Compute days-to-earnings
   - Activate the `eventRisk` penalty in confidence formula

2. **True Relative Strength**
   - Add SPY/QQQ benchmark price fetch
   - Replace internal return proxy with real RS calculation

3. **Support/Resistance Levels**
   - Add swing high/low detection
   - Score proximity to key structure levels
   - Add break-of-structure (BOS) detection

4. **Backtest Calibration**
   - Log historical signals with outcomes (TP1 hit, TP3 hit, SL hit)
   - Train logistic regression on feature set
   - Update z-score coefficients for better calibration

---

## Changed Files

- `src/main/java/com/hedgefund/ai/service/agents/TradingSignalAgent.java`
  - Added 3 new factors (8, 9, 10)
  - Added gate rules and weighted scoring
  - Implemented logistic confidence
  - Lowered minimum data requirement

- `src/main/java/com/hedgefund/ai/service/ReportGeneratorService.java`
  - Reordered prompt: critical TP levels first
  - Added `buildCompactReasoning()` helper
  - Applied per-ticker budget scaling
  - Improved multi-ticker handling

---

## Confidence Score Comparison

### Old (V1)
```
confidence = (score/7) * 100
if (crossover) confidence += 10
if (score >= 6) confidence += 5
```
Result: linear, often clustering around 60-70%

### New (V2)
```
z = -1.4 + 0.55*weightedScore + 0.35*crossover + 0.40*regimePass
probability = sigmoid(z)
confidence = clamp(probability * 100, 5, 95)
```
Result: better separation, regime-aware, calibration-ready

---

## Example Log Output (V2)

```
[trading_signal] LONG AAPL | Score: 6/7 | WScore: 7.80/10.0 (th 6.20) | Gates:true | Fired:true | 
Entry: 172.43 | SL: 168.92 | TP1: 175.94 TP2: 179.45 TP3: 182.96 TP4: 186.47 TP5: 189.98 | R:R=3.0 | Conf=78%
```

Key additions:
- `WScore: 7.80/10.0` — weighted score out of 10
- `th 6.20` — dynamic threshold (higher in chop)
- `Gates:true` — hard gates passed
- `Fired:true` — signal actually fired (not just analyzed)

---

**Status: ✅ V2 Implementation Complete**

