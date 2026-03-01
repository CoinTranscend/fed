# Fed Dashboard

**Macro Intelligence · Real-Time**

A standalone Android app and Python desktop toolkit for tracking the US macro environment through composite z-score indices built entirely from [FRED (Federal Reserve Economic Data)](https://fred.stlouisfed.org/) series.

---

## Contents

| Path | Description |
|------|-------------|
| `android/` | Android app source (Kotlin, minSdk 26) |
| `releases/` | Pre-built APK — ready to sideload |
| `fed_dashboard.py` | Python desktop script — LIIMSI + LLMSI leading indices |
| `fed_compare.py` | Python desktop script — leading vs coincident overlay charts |
| `docs/` | Detailed index methodology and architecture notes |

---

## Android App

### Features

- **4 tabs:** Inflation · Labor · Recession · Settings
- **Inflation tab** — ISI (coincident, 10 series) overlaid with LIIMSI (leading, 7 series)
- **Labor tab** — LSI (coincident, 9 series) overlaid with LLMSI (leading, 7 series)
- **Recession tab** — RRI (6 series, leads 6–18 months) + optional Gemini AI commentary
- **HD Export** — saves a crisp 2000 × 900 px PNG to your Downloads folder
- **Splash screen** with app cover art and version
- Light theme, minimalist UI

### Install (Sideload)

> **Android 8.0+ (API 26) required.**

#### Option A — Direct transfer

1. Download `releases/FedDashboard-v1.0-debug.apk` to your phone.
2. Open the APK file from Files / Downloads.
3. When prompted, allow installation from unknown sources for your file manager.
4. Tap **Install**.

#### Option B — ADB (USB or Wi-Fi)

```bash
# USB
adb install releases/FedDashboard-v1.0-debug.apk

# Wi-Fi (replace with your phone's IP)
adb connect 192.168.x.x:5555
adb install releases/FedDashboard-v1.0-debug.apk
```

#### First Launch

1. Open **Fed Dashboard** from your home screen.
2. Go to **Settings** tab.
3. Paste your **FRED API key** (free — see below) and tap **Save FRED Key**.
4. Optionally add a **Gemini API key** for AI recession commentary.
5. Return to any index tab — data fetches automatically.

---

## Getting API Keys

### FRED API Key (required)
- Register free at [https://fred.stlouisfed.org/docs/api/api_key.html](https://fred.stlouisfed.org/docs/api/api_key.html)
- Takes ~1 minute, no credit card

### Gemini API Key (optional — AI commentary on Recession tab)
- Get a free key at [https://aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)
- Uses Gemini 2.5 Flash with Google Search grounding

---

## The Five Indices

### ISI — Inflation Strength Index
*What the Fed actually reacts to.* 10 equal-weight coincident series: Core CPI, Core PCE, Trimmed PCE, Sticky CPI, PPI Final Demand, PPI Commodities, 5Y/10Y Breakevens, Shelter CPI, UMich Inflation Expectations.

| Regime | Score | Implication |
|--------|-------|-------------|
| ELEVATED | ≥ +0.5 | Inflation running hot — Fed hawkish |
| RISING | ≥ 0.0 | Inflation picking up — caution |
| ANCHORED | ≥ −0.5 | Near target — ideal backdrop |
| COOLING | ≥ −1.0 | Inflation fading — rate cuts possible |
| DEFLATIONARY | < −1.0 | Deflation risk — recession concern |

---

### LIIMSI — Leading Inflation Indicators Strength Index
*Leads CPI/PCE by 2–6 months.* 7 weighted FRED series.

| Series | Label | Weight | Transform |
|--------|-------|--------|-----------|
| T5YIE | 5Y TIPS Breakeven | 20% | Level |
| PPIFIS | PPI Final Demand | 20% | YoY |
| FLEXCPIM159SFRBATL | Atlanta Fed Flexible CPI | 15% | Level |
| MICH | UMich Inflation Expectations | 15% | Level |
| CES0500000003 | Avg Hourly Earnings | 10% | YoY |
| PPIACO | PPI All Commodities | 10% | YoY |
| T5YIFR | 5Y/5Y Forward Breakeven | 10% | Level |

---

### LSI — Labor Strength Index
*Coincident picture of the US labor market.* 9 equal-weight series: Nonfarm Payrolls (YoY), Unemployment Rate (↓inv), LFPR, Initial Claims (↓inv), Continuing Claims (↓inv), JOLTS Openings (YoY), Quits Rate, Avg Hourly Earnings (YoY), Mfg Employment (YoY).

| Regime | Score | Implication |
|--------|-------|-------------|
| STRONG | ≥ +0.5 | Robust expansion |
| MODERATE | ≥ 0.0 | Healthy, moderating |
| SOFTENING | ≥ −0.5 | Early warning signs |
| WEAK | ≥ −1.0 | Significant deterioration |
| CRITICAL | < −1.0 | Recession-level distress |

---

### LLMSI — Leading Labor Market Strength Index
*Leads payrolls by 2–6 months.* 7 weighted FRED series.

| Series | Label | Weight | Transform | Inverted |
|--------|-------|--------|-----------|----------|
| ICSA | Initial Claims | 20% | YoY | ↓ |
| AWHMAN | Mfg Avg Weekly Hours | 15% | Level | — |
| TEMPHELPS | Temp Help Services | 15% | YoY | — |
| JTSJOL | JOLTS Job Openings | 15% | YoY | — |
| JTSLDL | JOLTS Layoffs | 15% | YoY | ↓ |
| JTSQUR | Quits Rate | 10% | Level | — |
| CCSA | Continuing Claims | 10% | YoY | ↓ |

---

### RRI — Recession Risk Index
*Composite leading indicator for recession prediction 6–18 months ahead.*

| Series | Label | Weight | Transform | Inverted |
|--------|-------|--------|-----------|----------|
| T10Y2Y | 10Y−2Y Yield Spread | 25% | Level | ↓ |
| T10Y3M | 10Y−3M Yield Spread | 20% | Level | ↓ |
| BAMLH0A0HYM2 | HY Credit Spread (OAS) | 20% | Level | ↓ |
| PERMIT | Building Permits | 15% | YoY | — |
| ICSA | Initial Claims | 10% | YoY | ↓ |
| UMCSENT | UMich Consumer Sentiment | 10% | Level | — |

| Regime | Score | Historical Recession Probability |
|--------|-------|----------------------------------|
| LOW RISK | ≥ +0.5 | < 10% — expansion intact |
| STABLE | ≥ 0.0 | ~20% — normal business cycle |
| CAUTION | ≥ −0.5 | ~40% — warning signs building |
| WARNING | ≥ −1.0 | ~70% — elevated, act defensively |
| CRITICAL | < −1.0 | > 85% — recession likely in 6–12m |

---

## Computation Pipeline

All five indices use the same algorithm (mirroring the Android implementation exactly):

```
1. Fetch 7–8 years of monthly data from FRED REST API
   GET /fred/series/observations?series_id=X&frequency=m&aggregation_method=avg

2. Forward-fill up to 2 consecutive missing months
   (handles JOLTS / building permits release lag)

3. YoY transform where flagged:
   v[i] = (raw[i] / raw[i-12]) - 1.0

4. Rolling 60-month z-score (min 24 periods, sample std):
   z[i] = (v[i] - mean(window)) / std(window)

5. Sign-flip inverted series:
   z_signed = z * (-1 if inverted else +1)

6. Renormalized weighted mean:
   composite = Σ(z_signed_i × w_i) / Σ(w_i for available series)
```

---

## Python Scripts

Both scripts are standalone — they pull the FRED key from `fed_key.txt` (same directory), a `FRED_API_KEY` environment variable, or prompt interactively.

```bash
# Install dependencies
pip install requests pandas numpy matplotlib

# Leading indices with full z-score derivation log
python3 fed_dashboard.py

# Coincident vs leading overlay comparison
python3 fed_compare.py
```

Charts save to `charts/` and open automatically with the system image viewer.

---

## Building from Source

### Requirements
- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34 + Build Tools 34.0.0

### Steps

```bash
git clone git@github.com:CoinTranscend/fed.git
cd fed/android

# Download gradle wrapper jar
curl -L -o gradle/wrapper/gradle-wrapper.jar \
  https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar

# Set your SDK path
echo "sdk.dir=/path/to/your/android-sdk" > local.properties

# Build debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

Or simply open `android/` in Android Studio — it handles the wrapper and SDK automatically.

---

## Project Structure

```
fed/
├── android/
│   ├── app/src/main/
│   │   ├── java/com/sun/feddashboard/
│   │   │   ├── MainActivity.kt
│   │   │   ├── MainViewModel.kt
│   │   │   ├── SplashActivity.kt
│   │   │   ├── domain/
│   │   │   │   ├── EngineBase.kt          # Shared math (forwardFill, YoY, z-score)
│   │   │   │   ├── InflationLeadingEngine.kt  # LIIMSI
│   │   │   │   ├── LaborLeadingEngine.kt      # LLMSI
│   │   │   │   ├── FedEngine.kt               # ISI + LSI
│   │   │   │   └── RecessionEngine.kt         # RRI
│   │   │   ├── network/
│   │   │   │   ├── FredClient.kt          # FRED REST API client
│   │   │   │   └── GeminiClient.kt        # Gemini 2.5 Flash + Search grounding
│   │   │   ├── model/
│   │   │   │   └── FedModels.kt           # Data classes
│   │   │   └── ui/
│   │   │       ├── InflationFragment.kt
│   │   │       ├── LaborFragment.kt
│   │   │       ├── RecessionFragment.kt
│   │   │       ├── SettingsFragment.kt
│   │   │       ├── OverlayChartView.kt    # Custom Canvas 2-line chart
│   │   │       └── ChartExporter.kt       # 2000×900 HD bitmap export
│   │   └── res/
│   │       ├── layout/                    # Fragment + activity layouts
│   │       ├── navigation/nav_graph.xml
│   │       ├── menu/bottom_nav_menu.xml
│   │       └── values/                    # Colors, themes, strings
├── releases/
│   └── FedDashboard-v1.0-debug.apk       # Ready-to-install APK
├── fed_dashboard.py                       # Python: LIIMSI + LLMSI
├── fed_compare.py                         # Python: leading vs coincident
└── docs/
    └── INDICES.md                         # Extended index methodology
```

---

## License

Private repository — © CoinTranscend. All rights reserved.
