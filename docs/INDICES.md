# Index Methodology — Fed Dashboard

Extended documentation for all five composite z-score indices.

---

## Overview

All five indices share a common computation pipeline:

```
FRED REST API
  → monthly avg observations (7–8 years)
  → forward-fill gaps (≤ 2 consecutive)
  → YoY transform (selected series)
  → rolling 60-month z-score (min 24 periods)
  → sign-flip inverted series
  → renormalized weighted mean
```

Each z-score measures how far a series sits from its own 5-year average in standard-deviation units, making the composite directly comparable across time and across structurally different series.

---

## 1. ISI — Inflation Strength Index

**Purpose:** Measures the current (coincident) state of inflation across all dimensions the Fed watches.
**Series count:** 10 (equal weight, 10% each)
**Lead/lag:** Coincident — reflects current conditions

| Series ID | Description | Transform | Inverted |
|-----------|-------------|-----------|----------|
| CPILFESL | Core CPI (ex food & energy) | YoY | — |
| PCEPILFE | Core PCE | YoY | — |
| PCETRIM12M159SFRBDAL | Trimmed Mean PCE (Dallas Fed) | Level | — |
| CORESTICKM159SFRBATL | Sticky CPI (Atlanta Fed) | Level | — |
| PPIFID | PPI Final Demand | YoY | — |
| PPIACO | PPI All Commodities | YoY | — |
| T5YIE | 5Y TIPS Breakeven | Level | — |
| T10YIE | 10Y TIPS Breakeven | Level | — |
| CUSR0000SAH1 | Shelter CPI | YoY | — |
| MICH | UMich Inflation Expectations | Level | — |

### Regime Guide

| Regime | Score | Fed Posture |
|--------|-------|-------------|
| ELEVATED | ≥ +0.5 | Inflation running hot — actively hawkish |
| RISING | ≥ 0.0 | Inflation picking up — cautious |
| ANCHORED | ≥ −0.5 | Near 2% target — neutral to slightly dovish |
| COOLING | ≥ −1.0 | Inflation fading — cuts likely on table |
| DEFLATIONARY | < −1.0 | Deflation risk — emergency easing possible |

---

## 2. LIIMSI — Leading Inflation Indicators Strength Index

**Purpose:** Leads CPI/PCE by approximately 2–6 months, giving early warning of inflation inflection.
**Series count:** 7 (weighted)
**Lead:** ~2–6 months ahead of headline inflation

| Series ID | Label | Weight | Transform | Inverted |
|-----------|-------|--------|-----------|----------|
| T5YIE | 5Y TIPS Breakeven | 20% | Level | — |
| PPIFIS | PPI Final Demand Services | 20% | YoY | — |
| FLEXCPIM159SFRBATL | Atlanta Fed Flexible CPI | 15% | Level | — |
| MICH | UMich Inflation Expectations | 15% | Level | — |
| CES0500000003 | Avg Hourly Earnings (Total Private) | 10% | YoY | — |
| PPIACO | PPI All Commodities | 10% | YoY | — |
| T5YIFR | 5Y/5Y Forward Inflation Breakeven | 10% | Level | — |

### Why These Series Lead

- **TIPS Breakevens (T5YIE, T5YIFR):** Market-implied inflation expectations reflect real-money positioning — tend to move before survey data
- **PPI Final Demand (PPIFIS):** Producer prices flow through to consumer prices with a 1–4 month lag
- **Flexible CPI (FLEXCPIM159SFRBATL):** Prices that reprice frequently — first to respond to demand shifts
- **UMich Expectations (MICH):** Consumer expectations feed into wage negotiations and actual spending behavior
- **Avg Hourly Earnings (CES0500000003):** Wage growth is the most persistent driver of core services inflation
- **PPI Commodities (PPIACO):** Commodity prices propagate upstream pressure throughout supply chains

### Regime Guide

Same thresholds as ISI: ELEVATED / RISING / ANCHORED / COOLING / DEFLATIONARY

---

## 3. LSI — Labor Strength Index

**Purpose:** Coincident composite of labor market health across all dimensions the Fed monitors for its employment mandate.
**Series count:** 9 (equal weight, ~11.1% each)
**Lead/lag:** Coincident

| Series ID | Description | Transform | Inverted |
|-----------|-------------|-----------|----------|
| PAYEMS | Nonfarm Payrolls | YoY | — |
| UNRATE | Unemployment Rate | Level | ↓ |
| CIVPART | Labor Force Participation Rate | Level | — |
| ICSA | Initial Jobless Claims | YoY | ↓ |
| CCSA | Continuing Claims | YoY | ↓ |
| JTSJOL | JOLTS Job Openings | YoY | — |
| JTSQUR | JOLTS Quits Rate | Level | — |
| CES0500000003 | Avg Hourly Earnings | YoY | — |
| MANEMP | Manufacturing Employment | YoY | — |

### Regime Guide

| Regime | Score | Implication |
|--------|-------|-------------|
| STRONG | ≥ +0.5 | Robust expansion, tight labor market |
| MODERATE | ≥ 0.0 | Healthy and moderating — goldilocks |
| SOFTENING | ≥ −0.5 | Early warning signs — rising claims, slowing adds |
| WEAK | ≥ −1.0 | Significant deterioration — Fed likely pivoting |
| CRITICAL | < −1.0 | Recession-level labor distress |

---

## 4. LLMSI — Leading Labor Market Strength Index

**Purpose:** Leads nonfarm payrolls and unemployment rate by approximately 2–6 months.
**Series count:** 7 (weighted)
**Lead:** ~2–6 months ahead of coincident labor data

| Series ID | Label | Weight | Transform | Inverted |
|-----------|-------|--------|-----------|----------|
| ICSA | Initial Jobless Claims | 20% | YoY | ↓ |
| AWHMAN | Mfg Avg Weekly Hours | 15% | Level | — |
| TEMPHELPS | Temp Help Services Employment | 15% | YoY | — |
| JTSJOL | JOLTS Job Openings | 15% | YoY | — |
| JTSLDL | JOLTS Layoffs & Discharges | 15% | YoY | ↓ |
| JTSQUR | JOLTS Quits Rate | 10% | Level | — |
| CCSA | Continuing Claims | 10% | YoY | ↓ |

### Why These Series Lead

- **Initial Claims (ICSA):** Fastest-moving labor signal — employers cut hours and temps before payroll filings
- **Mfg Weekly Hours (AWHMAN):** Hours are adjusted before headcount — falling hours predict future layoffs
- **Temp Help (TEMPHELPS):** Temporary staffing is the first hired and first fired — leads permanent employment by 1–3 months
- **JOLTS Openings (JTSJOL):** Demand for labor precedes actual hiring; openings compress before payrolls slow
- **JOLTS Layoffs (JTSLDL):** Rising layoff rate is a direct precursor to claims and unemployment
- **Quits Rate (JTSQUR):** Workers quit when confident in job alternatives — quits collapsing signals labor market anxiety
- **Continuing Claims (CCSA):** Persistence of joblessness — leads unemployment rate by 4–8 weeks

### Regime Guide

Same thresholds as LSI: STRONG / MODERATE / SOFTENING / WEAK / CRITICAL

---

## 5. RRI — Recession Risk Index

**Purpose:** Composite leading indicator for US recession probability with a 6–18 month forward horizon.
**Series count:** 6 (weighted)
**Lead:** 6–18 months ahead of NBER recession dating

| Series ID | Label | Weight | Transform | Inverted |
|-----------|-------|--------|-----------|----------|
| T10Y2Y | 10Y−2Y Treasury Yield Spread | 25% | Level | ↓ |
| T10Y3M | 10Y−3M Treasury Yield Spread | 20% | Level | ↓ |
| BAMLH0A0HYM2 | HY Corporate Credit Spread (OAS) | 20% | Level | ↓ |
| PERMIT | New Private Housing Permits | 15% | YoY | — |
| ICSA | Initial Jobless Claims | 10% | YoY | ↓ |
| UMCSENT | UMich Consumer Sentiment | 10% | Level | — |

### Why These Series

- **Yield Curve (T10Y2Y, T10Y3M):** Inverted yield curve is the most reliable historical recession predictor. T10Y3M has the strongest academic evidence (Estrella & Mishkin 1998); T10Y2Y is the market's preferred measure. Combined weight 45%.
- **HY Credit Spread (BAMLH0A0HYM2):** Financial conditions tighten before the real economy slows. Spread widening signals credit stress and tightening lending standards — historically precedes recession by 6–12 months.
- **Building Permits (PERMIT):** Housing is the most interest-rate sensitive sector. Permits lead starts which lead construction employment. One of the Conference Board's official leading indicators.
- **Initial Claims (ICSA):** Rising claims signal employers are cutting — early labor deterioration before it shows in payrolls.
- **Consumer Sentiment (UMCSENT):** Collapses before spending turns; spending is 70% of GDP.

### Historical Performance

| Recession | RRI Crossed WARNING | Lead Time |
|-----------|-------------------|-----------|
| 2001 Dot-com | Early 2000 | ~8 months |
| 2007–09 GFC | Mid-2006 | ~12–14 months |
| 2020 COVID | Jan 2020 | ~2 months (exogenous shock) |

*Note: COVID was an exogenous external shock — no composite index can consistently lead by more than 1–2 months for pandemic-scale surprises.*

### Regime Guide

| Regime | Score | Historical Recession Probability (12m) |
|--------|-------|----------------------------------------|
| LOW RISK | ≥ +0.5 | < 10% — expansion intact |
| STABLE | ≥ 0.0 | ~20% — normal business cycle variation |
| CAUTION | ≥ −0.5 | ~40% — warning signs building |
| WARNING | ≥ −1.0 | ~70% — elevated, consider defensive positioning |
| CRITICAL | < −1.0 | > 85% — recession likely within 6–12 months |

---

## Computation Pipeline (Detailed)

### Step 1 — FRED Fetch

```
GET https://api.stlouisfed.org/fred/series/observations
  ?series_id=<ID>
  &frequency=m
  &aggregation_method=avg
  &file_type=json
  &api_key=<KEY>
```

- Monthly frequency, averaged from daily/weekly where applicable
- 7–8 years of history requested (`observation_start` = today minus 8 years)
- Observations with value `"."` are treated as null

### Step 2 — Forward-fill

Missing values (release lags are common for JOLTS, PERMIT, PCE components) are filled forward up to **2 consecutive months**. Beyond 2 months a gap is left, and that series is excluded from the weighted mean for those months.

### Step 3 — YoY Transform

For series flagged `useYoY = true`:

```
v[i] = (raw[i] / raw[i-12]) - 1.0
```

This removes secular trends and seasonal effects, making the z-score meaningful across decades. Requires 12 months of history before the first usable value.

### Step 4 — Rolling 60-Month Z-Score

```
z[i] = (v[i] - mean(v[i-59..i])) / std(v[i-59..i], ddof=1)
```

- Window: 60 months (5 years)
- Minimum periods: 24 months (requires at least 2 years of history)
- Sample standard deviation (ddof=1, unbiased)
- Values with fewer than 24 data points are excluded

A 60-month window is long enough to smooth cycles but short enough to adapt to structural regime changes (e.g., the post-2008 low-rate era vs the 2022 hiking cycle).

### Step 5 — Sign Flip

For series where a **higher raw value indicates weakness** (e.g., unemployment rate, jobless claims, credit spreads):

```
z_signed[i] = z[i] * -1
```

This ensures all series point in the same direction: positive z = bullish/strong, negative z = bearish/weak.

### Step 6 — Renormalized Weighted Mean

```
composite[i] = Σ(z_signed[i] × w_j) / Σ(w_j for available series at i)
```

Renormalization means the composite remains valid even when some series have missing data. A series is considered unavailable at time `i` if its z-score could not be computed (insufficient history or >2 consecutive gaps).

---

## Notes on Data Timeliness

| Series | Typical Release Lag |
|--------|---------------------|
| Initial Claims (ICSA) | 1 week |
| Nonfarm Payrolls (PAYEMS) | ~4 weeks |
| JOLTS (JTSJOL, JTSLDL, etc.) | ~6 weeks |
| CPI/PCE components | ~3–5 weeks |
| Building Permits (PERMIT) | ~3 weeks |
| TIPS Breakevens (T5YIE, T10YIE) | Real-time (daily) |
| UMich Sentiment (MICH, UMCSENT) | ~2–4 weeks (preliminary/final) |

The forward-fill (Step 2) handles normal release lags. The composite score will automatically up-weight available series when others are pending release.
