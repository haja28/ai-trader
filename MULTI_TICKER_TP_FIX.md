# Multi-Ticker TP Fix — Complete

## Problem
When analyzing 2+ tickers (e.g., NBIS + JPM), the PDF report showed:
- ✅ NBIS: complete TP1-TP5 table
- ❌ JPM: missing TP table entirely

## Root Cause
1. LLM was combining multiple tickers into one table in the Trading Signals section
2. The system prompt didn't explicitly enforce "one subsection per ticker"
3. The user prompt's critical data section wasn't formatted as ready-to-use tables

## Solution Applied

### 1. Enhanced System Prompt
**Before:**
```
### TICKER sub-sections. TWO-COLUMN table (Parameter | Value)...
Then a SEPARATE two-column table (Take-Profit Level | Price) for TP1-TP5.
```

**After:**
```
CRITICAL: Create ONE SUBSECTION PER TICKER. Each ticker MUST have:
### TICKER
Two-column table (Parameter | Value): Position, Entry Point, Stop Loss...

Immediately after, a SEPARATE two-column table (Take-Profit Level | Price):
| **Take-Profit Level** | **Price** |
|-----------------------|-----------|
| TP1                   | X.XX      |
| TP2                   | X.XX      |
| TP3                   | X.XX      |
| TP4                   | X.XX      |
| TP5                   | X.XX      |

Then use --- before next ticker's ### subsection. NEVER combine tickers into one table.
```

Added explicit table template and separation rules.

### 2. Restructured User Prompt Header
**Before:**
```
=== CRITICAL TRADING LEVELS ===
--- TICKER ---
Position: LONG
Entry: 15.23
TP1 (1R): 15.59
...
```

**After:**
```
=== CRITICAL TRADING LEVELS (Priority Data - Use These Exact Values in Report) ===

IMPORTANT: Each ticker below MUST have its OWN ### subsection in the Trading Signals section.
Extract Position, Entry, Stop Loss, R:R, Confidence, Algorithm from the first table below.
Extract TP1-TP5 from the Take-Profit table below. DO NOT merge tickers.

━━━ NBIS ━━━
| **Parameter**            | **Value**                     |
|--------------------------|-------------------------------|
| Position                 | LONG                          |
| Entry Point              | 112.95                        |
| Stop Loss                | 96.42                         |
| Risk-to-Reward Ratio     | 3.0                           |
| Confidence               | 72%                           |
| Algorithm Used           | Sniper Confluence V2...       |

| **Take-Profit Level** | **Price** |
|-----------------------|-----------|
| TP1                   | 129.48    |
| TP2                   | 146.01    |
| TP3                   | 162.54    |
| TP4                   | 179.07    |
| TP5                   | 195.60    |

━━━ JPM ━━━
| **Parameter**            | **Value**                     |
|--------------------------|-------------------------------|
| Position                 | SHORT                         |
...
```

Now the LLM receives **ready-to-use Markdown tables** for each ticker at the top of the prompt.

### 3. Rewrote `appendTradingSignalDetails()`
**Old approach:**
- Plain key-value text: `Position: LONG`, `TP1 (1R): 15.59`
- LLM had to parse and reformat

**New approach:**
- Outputs complete Markdown tables directly
- Two tables per ticker (main parameters + TP levels)
- LLM can copy verbatim into the report

**Code structure:**
```java
String position = "", entry = "", stopLoss = "", rrr = "", confidence = "", algorithm = "";
String tp1 = "", tp2 = "", tp3 = "", tp4 = "", tp5 = "";

// Extract from TradingSignalResult or Map
if (tradingSig instanceof TradingSignalResult ts) { ... }
else if (tradingSig instanceof Map<?, ?> tsMap) { ... }

// Output pre-formatted tables
sb.append("| **Parameter**            | **Value**     |\n");
sb.append("|--------------------------|---------------|\n");
sb.append("| Position                 | ").append(position).append(" |\n");
...

sb.append("| **Take-Profit Level** | **Price** |\n");
sb.append("|-----------------------|-----------|\n");
sb.append("| TP1                   | ").append(tp1).append(" |\n");
sb.append("| TP2                   | ").append(tp2).append(" |\n");
sb.append("| TP3                   | ").append(tp3).append(" |\n");
sb.append("| TP4                   | ").append(tp4).append(" |\n");
sb.append("| TP5                   | ").append(tp5).append(" |\n");
```

## Expected Behavior After Fix

### User Prompt Format (excerpt)
```
=== CRITICAL TRADING LEVELS ===

IMPORTANT: Each ticker below MUST have its OWN ### subsection...

━━━ NBIS ━━━
| **Parameter**            | **Value**                     |
|--------------------------|-------------------------------|
| Position                 | LONG                          |
| Entry Point              | 112.95                        |
| Stop Loss                | 96.42                         |
| Risk-to-Reward Ratio     | 3.0                           |
| Confidence               | 72%                           |
| Algorithm Used           | Sniper Confluence V2...       |

| **Take-Profit Level** | **Price** |
|-----------------------|-----------|
| TP1                   | 129.48    |
| TP2                   | 146.01    |
| TP3                   | 162.54    |
| TP4                   | 179.07    |
| TP5                   | 195.60    |

━━━ JPM ━━━
| **Parameter**            | **Value**                     |
|--------------------------|-------------------------------|
| Position                 | SHORT                         |
| Entry Point              | 283.44                        |
| Stop Loss                | 299.27                        |
| Risk-to-Reward Ratio     | 3.0                           |
| Confidence               | 95%                           |
| Algorithm Used           | Sniper Confluence V2...       |

| **Take-Profit Level** | **Price** |
|-----------------------|-----------|
| TP1                   | 267.61    |
| TP2                   | 251.78    |
| TP3                   | 235.95    |
| TP4                   | 220.12    |
| TP5                   | 204.29    |

=== PORTFOLIO DECISIONS ===
...
```

### Expected PDF Output
```markdown
## Trading Signals (per ticker)

### NBIS
| **Parameter**            | **Value**                     |
|--------------------------|-------------------------------|
| Position                 | LONG                          |
| Entry Point              | 112.95                        |
| Stop Loss                | 96.42                         |
| Risk-to-Reward Ratio     | 3.0                           |
| Confidence               | 72%                           |
| Algorithm Used           | Sniper Confluence V2          |

| **Take-Profit Level** | **Price** |
|-----------------------|-----------|
| TP1                   | 129.48    |
| TP2                   | 146.01    |
| TP3                   | 162.54    |
| TP4                   | 179.07    |
| TP5                   | 195.60    |

---

### JPM
| **Parameter**            | **Value**                     |
|--------------------------|-------------------------------|
| Position                 | SHORT                         |
| Entry Point              | 283.44                        |
| Stop Loss                | 299.27                        |
| Risk-to-Reward Ratio     | 3.0                           |
| Confidence               | 95%                           |
| Algorithm Used           | Sniper Confluence V2          |

| **Take-Profit Level** | **Price** |
|-----------------------|-----------|
| TP1                   | 267.61    |
| TP2                   | 251.78    |
| TP3                   | 235.95    |
| TP4                   | 220.12    |
| TP5                   | 204.29    |

---
```

✅ Every ticker now has both tables completely populated.

## Testing Instructions

### 1. Restart Server
```powershell
cd C:\workspace\hedgefund\ai-hedge-fund-java
mvn spring-boot:run
```

### 2. Test Multi-Ticker Report
```powershell
curl -X POST http://localhost:8080/api/hedge-fund/report/pdf `
  -H "Content-Type: application/json" `
  -d '{\"tickers\": [\"AAPL\", \"MSFT\"]}'
```

### 3. Verify in PDF
Open the generated PDF and confirm:
- ✅ Each ticker has a ### subsection under "Trading Signals"
- ✅ Each ticker has TWO tables:
  - Main parameters table (6 rows)
  - TP table (5 rows: TP1-TP5)
- ✅ No merged/combined tables
- ✅ All price values rounded to 2 decimals

### 4. Check Logs
Look for:
```
[trading_signal] LONG AAPL | Score: 6/7 | WScore: 7.80/10.0 (th 6.20) | Gates:true | Fired:true | 
Entry: 172.43 | SL: 168.92 | TP1: 175.94 TP2: 179.45 TP3: 182.96 TP4: 186.47 TP5: 189.98 | R:R=3.0 | Conf=78%
```

All 5 TPs should appear in the agent log for verification.

## Changed Files

- `src/main/java/com/hedgefund/ai/service/ReportGeneratorService.java`
  - Updated system prompt with explicit per-ticker subsection requirement
  - Added table template example in system prompt
  - Added "DO NOT merge tickers" rule
  - Rewrote `appendTradingSignalDetails()` to output pre-formatted Markdown tables
  - Enhanced user prompt header with separation instructions
  - Used `━━━` separators for visual clarity

## Why This Works

1. **Pre-formatted tables in prompt** — LLM doesn't have to parse/reformat, just copy
2. **Explicit subsection rule** — "ONE SUBSECTION PER TICKER" in all caps
3. **Visual separators** — `━━━ TICKER ━━━` makes boundaries obvious
4. **Instruction reinforcement** — "DO NOT merge tickers" + "MUST have its OWN subsection"
5. **Table template** — Shows exact expected output format in system prompt

The LLM now receives a prompt where the hard work is already done — it just needs to:
1. Create ### subsections per ticker
2. Copy the tables from the CRITICAL section
3. Add technical analysis tables per ticker

---

**Status: ✅ Multi-Ticker TP Fix Complete**

Next test should show complete TP1-TP5 tables for every ticker.

