#!/usr/bin/env python3
"""
Fed Dashboard — LIIMSI & LLMSI
================================
Standalone desktop script that replicates the Android app's:
  • Leading Inflation Indicators Strength Index (LIIMSI)
  • Leading Labor Market Strength Index (LLMSI)

Features:
  • Enhanced charts: every data point labeled with its value
  • Detailed computation log: raw FRED value → YoY transform → rolling
    window stats (count / mean / std) → z-score derivation → composite

Series (from Android InflationEngine.kt / LlmsiEngine.kt):
  LIIMSI: T5YIE  PPIFIS  FLEXM159SFRBATL  NFIBPRCE  CES0500000003  PPIACO  T5YIFR
  LLMSI:  ICSA   AWHMAN  TEMPHELPS        JTSJOL    JTSLDL         JTSQUR  CCSA

Algorithm:
  1. Fetch 7-year history via FRED REST API (monthly avg aggregation)
  2. Forward-fill up to 2 months  (handles release lag)
  3. YoY transform where flagged:  (v / v[i-12]) - 1
  4. Rolling 60-month z-score  (min 24 periods, sample std)
  5. Sign flip for inverted series (LLMSI only)
  6. Renormalized weighted mean composite: Σ(z·w) / Σ(w_available)

FRED API key:
  Save to  fed_key.txt  next to this script  (get a free key at
  https://fred.stlouisfed.org/docs/api/api_key.html)
  Or set env var  FRED_API_KEY=<your_key>
"""

from __future__ import annotations

import os
import sys
import math
import json
import requests
from pathlib import Path
from datetime import date, datetime
from dataclasses import dataclass, field
from typing import Optional

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")   # file-only; chart opened via xdg-open below
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
import subprocess

SCRIPT_DIR = Path(__file__).resolve().parent


# ─────────────────────────────────────────────────────────────────────────────
# API Key
# ─────────────────────────────────────────────────────────────────────────────

def load_fred_key() -> str:
    key = os.getenv("FRED_API_KEY", "").strip()
    if key:
        return key
    key_file = SCRIPT_DIR / "fed_key.txt"
    if key_file.exists():
        key = key_file.read_text().strip()
        if key:
            return key
    print("\nFRED API key not found.")
    print("  Get a free key at: https://fred.stlouisfed.org/docs/api/api_key.html")
    print(f"  Then save it to:   {key_file}")
    key = input("\nEnter FRED API key (or press Enter to cancel): ").strip()
    if not key:
        print("Cancelled.")
        sys.exit(1)
    key_file.write_text(key)
    print(f"  Saved to {key_file}")
    return key


# ─────────────────────────────────────────────────────────────────────────────
# FRED Fetch  (mirrors Android FredClient.fetchSeries)
# ─────────────────────────────────────────────────────────────────────────────

def fetch_fred_series(
    series_id: str,
    api_key: str,
    start_date: str,
) -> list[tuple[str, Optional[float]]]:
    """
    Fetch monthly FRED observations.
    Returns sorted [(YYYY-MM, value | None), ...].
    """
    url = "https://api.stlouisfed.org/fred/series/observations"
    params = {
        "series_id":          series_id,
        "api_key":            api_key,
        "observation_start":  start_date,
        "frequency":          "m",
        "aggregation_method": "avg",
        "file_type":          "json",
    }
    resp = requests.get(url, params=params, timeout=30)
    resp.raise_for_status()
    obs = resp.json().get("observations", [])
    result = []
    for o in obs:
        ym  = o["date"][:7]   # YYYY-MM
        val = None if o["value"] == "." else float(o["value"])
        result.append((ym, val))
    return sorted(result, key=lambda x: x[0])


# ─────────────────────────────────────────────────────────────────────────────
# Series Definitions
# ─────────────────────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class SeriesSpec:
    id:     str
    label:  str
    weight: float
    yoy:    bool
    invert: bool = False  # sign flip (LLMSI inverted series)


# Android InflationEngine.kt  (corrected FRED series IDs)
LIIMSI_SERIES: list[SeriesSpec] = [
    SeriesSpec("T5YIE",             "5Y TIPS Breakeven",      0.20, yoy=False),
    SeriesSpec("PPIFIS",            "PPI Final Demand",        0.20, yoy=True),
    SeriesSpec("FLEXCPIM159SFRBATL","ATL Flexible CPI",        0.15, yoy=False),  # ATL Fed YoY%
    SeriesSpec("MICH",              "UMich Infl. Expectation", 0.15, yoy=False),  # 1Y ahead expectation
    SeriesSpec("CES0500000003",     "Avg Hourly Earnings",     0.10, yoy=True),
    SeriesSpec("PPIACO",            "PPI All Commodities",     0.10, yoy=True),
    SeriesSpec("T5YIFR",            "5Y/5Y Fwd Breakeven",     0.10, yoy=False),
]

# Android LlmsiEngine.kt
LLMSI_SERIES: list[SeriesSpec] = [
    SeriesSpec("ICSA",      "Initial Claims",        0.20, yoy=True,  invert=True),
    SeriesSpec("AWHMAN",    "Mfg Avg Weekly Hours",  0.15, yoy=False, invert=False),
    SeriesSpec("TEMPHELPS", "Temp Help Services",    0.15, yoy=True,  invert=False),
    SeriesSpec("JTSJOL",    "Job Openings (JOLTS)",  0.15, yoy=True,  invert=False),
    SeriesSpec("JTSLDL",    "Layoffs (JOLTS)",       0.15, yoy=True,  invert=True),
    SeriesSpec("JTSQUR",    "Quits Rate",            0.10, yoy=False, invert=False),
    SeriesSpec("CCSA",      "Continuing Claims",     0.10, yoy=True,  invert=True),
]


# ─────────────────────────────────────────────────────────────────────────────
# Computation helpers  (exact mirrors of Android engine)
# ─────────────────────────────────────────────────────────────────────────────

def forward_fill(
    months: list[str],
    data: dict[str, Optional[float]],
    limit: int = 2,
) -> dict[str, Optional[float]]:
    result: dict[str, Optional[float]] = {}
    last_val: Optional[float] = None
    gap_count = 0
    for m in months:
        v = data.get(m)
        if v is not None:
            result[m] = v;  last_val = v;  gap_count = 0
        elif last_val is not None and gap_count < limit:
            result[m] = last_val;  gap_count += 1
        else:
            result[m] = None
            if last_val is not None:
                gap_count += 1
    return result


def apply_yoy(
    months: list[str],
    data: dict[str, Optional[float]],
) -> tuple[dict[str, Optional[float]], dict[str, Optional[float]]]:
    """
    Returns (yoy_values, prior_values_at_lag12).
    yoy[m] = data[m] / data[months[i-12]] - 1.
    """
    yoy: dict[str, Optional[float]]   = {}
    prior: dict[str, Optional[float]] = {}
    for i, m in enumerate(months):
        cur = data.get(m)
        if cur is None or i < 12:
            yoy[m] = None;  prior[m] = None
            continue
        p = data.get(months[i - 12])
        prior[m] = p
        if p is None or p == 0.0:
            yoy[m] = None
        else:
            yoy[m] = (cur / p) - 1.0
    return yoy, prior


def rolling_zscore(
    months: list[str],
    data: dict[str, Optional[float]],
    window: int = 60,
    min_periods: int = 24,
) -> tuple[dict[str, Optional[float]], dict[str, dict]]:
    """
    Returns (z_scores, window_stats).
    window_stats[m] = {count, mean, std}  (or {} if insufficient data).
    """
    z_scores:    dict[str, Optional[float]] = {}
    win_stats:   dict[str, dict]            = {}
    for i, m in enumerate(months):
        cur = data.get(m)
        if cur is None:
            z_scores[m] = None;  win_stats[m] = {};  continue
        start    = max(0, i - window + 1)
        win_vals = [data[months[j]] for j in range(start, i + 1)
                    if data.get(months[j]) is not None]
        if len(win_vals) < min_periods:
            z_scores[m] = None
            win_stats[m] = {"count": len(win_vals), "mean": None, "std": None}
            continue
        mean     = sum(win_vals) / len(win_vals)
        variance = sum((v - mean) ** 2 for v in win_vals) / (len(win_vals) - 1)
        std      = math.sqrt(variance) if variance > 0 else 0.0
        z_scores[m]  = 0.0 if std < 1e-10 else (cur - mean) / std
        win_stats[m] = {"count": len(win_vals), "mean": mean, "std": std}
    return z_scores, win_stats


# ─────────────────────────────────────────────────────────────────────────────
# Per-series result container
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class SeriesResult:
    spec:  SeriesSpec
    raw:   dict[str, Optional[float]]         # FRED values after forward-fill
    trans: dict[str, Optional[float]]         # after YoY  (or same as raw)
    prior: dict[str, Optional[float]]         # prior (lag-12) raw value  (or None if no YoY)
    z_score:    dict[str, Optional[float]]
    win_stats:  dict[str, dict]
    # At latest valid month
    latest_month:        str             = ""
    latest_prior_month:  str             = ""
    latest_raw:          Optional[float] = None
    latest_prior_raw:    Optional[float] = None
    latest_trans:        Optional[float] = None  # value fed into z-score calc
    latest_z:            Optional[float] = None
    wc: int                              = 0      # window count
    wm: Optional[float]                  = None   # window mean
    ws: Optional[float]                  = None   # window std


# ─────────────────────────────────────────────────────────────────────────────
# Main index computation
# ─────────────────────────────────────────────────────────────────────────────

def compute_index(
    series_list: list[SeriesSpec],
    api_key:     str,
    index_name:  str,
) -> tuple[dict[str, float], list[SeriesResult]]:
    """
    Fetches all FRED series, runs the full pipeline, builds the composite.
    Returns (composite_by_month, list[SeriesResult]).
    """
    start_date = date(date.today().year - 7, date.today().month, 1).strftime("%Y-%m-%d")

    # ── Fetch ──────────────────────────────────────────────────────────────
    raw_series: dict[str, list[tuple[str, Optional[float]]]] = {}
    for spec in series_list:
        try:
            print(f"    {spec.id:<20} {spec.label}", flush=True)
            data = fetch_fred_series(spec.id, api_key, start_date)
            if data:
                raw_series[spec.id] = data
        except Exception as e:
            print(f"      (warning: {e})")

    # Union of months
    all_months = sorted({ym for s in raw_series.values() for ym, _ in s})

    # ── Process each series ────────────────────────────────────────────────
    series_results: list[SeriesResult]              = []
    weighted_z:     dict[str, dict[str, Optional[float]]] = {}

    for spec in series_list:
        raw_list = raw_series.get(spec.id)
        if not raw_list:
            continue

        raw_map   = dict(raw_list)
        filled    = forward_fill(all_months, raw_map, limit=2)

        if spec.yoy:
            trans, prior_map = apply_yoy(all_months, filled)
        else:
            trans    = filled
            prior_map = {m: None for m in all_months}

        z_scores, win_stats = rolling_zscore(all_months, trans, window=60, min_periods=24)

        sign = -1.0 if spec.invert else 1.0
        wz   = {m: (z_scores[m] * sign * spec.weight
                    if z_scores[m] is not None else None)
                for m in all_months}
        weighted_z[spec.id] = wz

        # Find latest month with a valid z-score
        latest_month = next((m for m in reversed(all_months)
                             if z_scores.get(m) is not None), "")

        sr = SeriesResult(
            spec=spec, raw=filled, trans=trans,
            prior=prior_map, z_score=z_scores, win_stats=win_stats,
            latest_month=latest_month,
        )
        if latest_month:
            i_last = all_months.index(latest_month)
            sr.latest_raw   = filled.get(latest_month)
            sr.latest_trans = trans.get(latest_month)
            sr.latest_z     = z_scores.get(latest_month)
            stats           = win_stats.get(latest_month, {})
            sr.wc = stats.get("count", 0)
            sr.wm = stats.get("mean")
            sr.ws = stats.get("std")
            if spec.yoy and i_last >= 12:
                prior_ym         = all_months[i_last - 12]
                sr.latest_prior_month = prior_ym
                sr.latest_prior_raw   = filled.get(prior_ym)

        series_results.append(sr)

    # ── Composite ──────────────────────────────────────────────────────────
    weight_map = {spec.id: spec.weight for spec in series_list}
    composite: dict[str, float] = {}
    for m in all_months:
        num = den = 0.0
        for sid, wz_map in weighted_z.items():
            wz = wz_map.get(m)
            if wz is not None and not math.isnan(wz):
                num += wz
                den += weight_map.get(sid, 0.0)
        if den > 0.0:
            composite[m] = num / den

    return composite, series_results


# ─────────────────────────────────────────────────────────────────────────────
# Regime classifiers
# ─────────────────────────────────────────────────────────────────────────────

def liimsi_regime(v: float) -> tuple[str, str]:
    if v >= 0.5:  return ("ELEVATED",     "#E53935")
    if v >= 0.0:  return ("RISING",       "#FB8C00")
    if v >= -0.5: return ("ANCHORED",     "#43A047")
    if v >= -1.0: return ("COOLING",      "#1E88E5")
    return               ("DEFLATIONARY", "#5E35B1")


def llmsi_regime(v: float) -> tuple[str, str]:
    if v >= 0.5:  return ("STRONG",    "#2E7D32")
    if v >= 0.0:  return ("MODERATE",  "#558B2F")
    if v >= -0.5: return ("SOFTENING", "#F9A825")
    if v >= -1.0: return ("WEAK",      "#E65100")
    return               ("CRITICAL",  "#B71C1C")


# ─────────────────────────────────────────────────────────────────────────────
# Computation log  (terminal output)
# ─────────────────────────────────────────────────────────────────────────────

def print_log(
    index_name:  str,
    composite:   dict[str, float],
    results:     list[SeriesResult],
    classify_fn,
) -> None:
    sorted_months = sorted(composite)
    if not sorted_months:
        print(f"\n  {index_name}: no composite data.\n"); return

    last_month = sorted_months[-1]
    last_val   = composite[last_month]
    regime, _  = classify_fn(last_val)

    W = 74
    print(f"\n{'═'*W}")
    print(f"  {index_name}  —  Computation Log")
    print(f"{'═'*W}")
    print(f"  Composite: {last_val:+.4f}   ({last_month})   Regime: {regime}")
    print(f"  Run at:    {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")

    # ── Per-series breakdown ──────────────────────────────────────────────
    print(f"\n  {'── SERIES BREAKDOWN ──':^{W-4}}")
    print()

    log_rows: list[tuple] = []   # (label, z_signed, weight, contrib)

    for sr in results:
        spec = sr.spec
        sign_label = "  [inverted ×-1]" if spec.invert else ""
        yoy_label  = "  [YoY transform]" if spec.yoy else ""
        print(f"  ┌─ {spec.id}  —  {spec.label}")
        print(f"  │  Weight: {spec.weight:.0%}{sign_label}{yoy_label}")

        # Raw FRED value
        rv = sr.raw.get(last_month)
        if rv is None:
            # fall back to sr.latest_month if it differs
            rv = sr.latest_raw
            month_used = sr.latest_month
            suffix = f"  ← from {month_used} (forward-filled)" if month_used != last_month else ""
        else:
            month_used = last_month
            suffix = ""
        if rv is None:
            print(f"  │  FRED value:          N/A  (no data at or near {last_month})")
            print(f"  └─ Skipped.\n")
            continue

        print(f"  │  FRED value:          {rv:>12.4f}  ({month_used}){suffix}")

        # YoY transform detail
        if spec.yoy:
            tv  = sr.trans.get(last_month)
            prm = sr.latest_prior_month
            prv = sr.latest_prior_raw
            if tv is None:
                print(f"  │  YoY transform:       N/A  (insufficient history)")
                print(f"  └─ Skipped.\n"); continue
            if prv is not None:
                print(f"  │  Prior raw (lag 12):  {prv:>12.4f}  ({prm})")
                print(f"  │  YoY fraction:        {tv:>+12.6f}  = {rv:.4f}/{prv:.4f} − 1")
            else:
                print(f"  │  YoY fraction:        {tv:>+12.6f}")
            cur_for_z = tv
        else:
            print(f"  │  YoY:                 not applied  (series already in comparable units)")
            cur_for_z = rv

        # Rolling window stats
        if sr.wm is None:
            print(f"  │  Rolling window:      insufficient data (< 24 months)")
            print(f"  └─ Skipped.\n"); continue

        print(f"  │  Rolling window:      {sr.wc} obs  │  mean={sr.wm:+.6f}  │  std={sr.ws:.6f}")

        # Z-score derivation
        if sr.ws is not None and sr.ws < 1e-10:
            z_val = 0.0
            print(f"  │  Z-score:             0.0  (std ≈ 0, series has no variance)")
        else:
            z_val = (cur_for_z - sr.wm) / sr.ws
            print(f"  │  Z-score:             ({cur_for_z:+.6f} − {sr.wm:+.6f}) / {sr.ws:.6f}")
            print(f"  │                     = {z_val:+.6f}")

        sign    = -1.0 if spec.invert else 1.0
        z_signed = z_val * sign
        if spec.invert:
            print(f"  │  Sign-flipped:        {z_val:+.6f} × (−1) = {z_signed:+.6f}")
        contrib = z_signed * spec.weight
        print(f"  │  Contribution:        {z_signed:+.6f} × {spec.weight:.2f} = {contrib:+.8f}")
        print(f"  └──────────────────────────────────────────────────────────\n")

        log_rows.append((spec.label, z_signed, spec.weight, contrib))

    # ── Composite derivation table ────────────────────────────────────────
    if log_rows:
        total_num = sum(r[3] for r in log_rows)
        total_den = sum(r[2] for r in log_rows)
        final_val = total_num / total_den if total_den > 0 else float("nan")

        print(f"  {'── COMPOSITE DERIVATION ──':^{W-4}}")
        print()
        print(f"  {'Indicator':<30}  {'Z×sign':>9}  {'Weight':>6}  {'Contribution':>14}")
        print(f"  {'─'*30}  {'─'*9}  {'─'*6}  {'─'*14}")
        for label, z_s, wt, contrib in log_rows:
            print(f"  {label:<30}  {z_s:>+9.4f}  {wt:>6.0%}  {contrib:>+14.8f}")
        print(f"  {'─'*30}  {'─'*9}  {'─'*6}  {'─'*14}")
        print(f"  {'Numerator   Σ(z·w)':30}  {'':>9}  {'':>6}  {total_num:>+14.8f}")
        print(f"  {'Denominator Σ(w)':30}  {'':>9}  {'':>6}  {total_den:>14.4f}")
        print(f"  {'COMPOSITE = num / den':30}  {'':>9}  {'':>6}  {final_val:>+14.4f}")

    # ── Monthly history ───────────────────────────────────────────────────
    history = sorted_months[-12:]
    print(f"\n  {'── MONTHLY HISTORY (last 12) ──':^{W-4}}")
    print()
    print(f"  {'Month':<10}  {'Composite':>10}  {'Regime':<13}  {'MoM Chg':>8}")
    print(f"  {'─'*10}  {'─'*10}  {'─'*13}  {'─'*8}")
    prev = None
    for m in history:
        v = composite[m]
        rname, _ = classify_fn(v)
        mom = f"{v - prev:+.3f}" if prev is not None else "—"
        print(f"  {m:<10}  {v:>+10.4f}  {rname:<13}  {mom:>8}")
        prev = v

    print(f"\n{'═'*W}")


# ─────────────────────────────────────────────────────────────────────────────
# Chart helpers
# ─────────────────────────────────────────────────────────────────────────────

LIIMSI_BANDS = [
    ( 0.5,  3.5, "#FFCDD2", "Elevated"),
    ( 0.0,  0.5, "#FFE0B2", "Rising"),
    (-0.5,  0.0, "#C8E6C9", "Anchored"),
    (-1.0, -0.5, "#BBDEFB", "Cooling"),
    (-3.5, -1.0, "#D1C4E9", "Deflationary"),
]

LLMSI_BANDS = [
    ( 0.5,  3.5, "#C8E6C9", "Strong"),
    ( 0.0,  0.5, "#DCEDC8", "Moderate"),
    (-0.5,  0.0, "#FFF9C4", "Softening"),
    (-1.0, -0.5, "#FFE0B2", "Weak"),
    (-3.5, -1.0, "#FFCDD2", "Critical"),
]


def _plot_timeseries(
    ax:            plt.Axes,
    composite:     dict[str, float],
    title:         str,
    line_color:    str,
    bands:         list,
    classify_fn,
    trailing:      int = 24,
) -> None:
    sorted_m = sorted(composite)
    hist     = sorted_m[-trailing:]
    if not hist:
        ax.text(0.5, 0.5, "No data", ha="center", va="center", transform=ax.transAxes)
        return

    xs = pd.to_datetime([f"{m}-01" for m in hist])
    ys = [composite[m] for m in hist]

    y_lo = min(ys)
    y_hi = max(ys)
    pad  = max(abs(y_lo), abs(y_hi)) * 0.35 + 0.45

    # Regime bands
    for lo, hi, color, label in bands:
        ax.axhspan(lo, hi, color=color, alpha=1.0, zorder=0)
        mid = (lo + hi) / 2.0
        if y_lo - pad <= mid <= y_hi + pad:
            ax.text(
                1.002, mid, f" {label}",
                transform=ax.get_yaxis_transform(),
                fontsize=8, va="center", color="#555555", style="italic",
            )

    # Zero line
    ax.axhline(0, color="#888888", linewidth=0.9, linestyle="--", alpha=0.7, zorder=1)

    # Main line
    ax.plot(xs, ys, color=line_color, linewidth=2.4, zorder=4,
            marker="o", markersize=5.5,
            markerfacecolor="white", markeredgecolor=line_color, markeredgewidth=1.8)

    # Label every data point  (alternate above/below)
    for i, (x, y) in enumerate(zip(xs, ys)):
        dy = 13 if i % 2 == 0 else -17
        ax.annotate(
            f"{y:+.2f}",
            xy=(x, y),
            xytext=(0, dy), textcoords="offset points",
            ha="center", fontsize=7.5,
            color=line_color, fontweight="bold", zorder=5,
        )

    # Latest value callout
    lx, ly     = xs[-1], ys[-1]
    rname, _   = classify_fn(ly)
    ax.annotate(
        f" {ly:+.3f}\n {rname}",
        xy=(lx, ly),
        xytext=(32, 18), textcoords="offset points",
        fontsize=9, fontweight="bold", color=line_color,
        bbox=dict(boxstyle="round,pad=0.35", fc="white",
                  ec=line_color, lw=1.3, alpha=0.95),
        arrowprops=dict(arrowstyle="->", color=line_color, lw=1.3),
        zorder=6,
    )

    ax.set_title(title, fontsize=11.5, fontweight="bold", pad=10)
    ax.set_ylabel("Weighted Z-Score", fontsize=10)
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%b '%y"))
    ax.xaxis.set_major_locator(mdates.MonthLocator(interval=2))
    plt.setp(ax.xaxis.get_majorticklabels(), rotation=35, ha="right", fontsize=8.5)
    ax.grid(True, alpha=0.3, linestyle="--", zorder=2)
    ax.set_xlim(xs[0] - pd.Timedelta(days=15), xs[-1] + pd.Timedelta(days=55))
    ax.set_ylim(y_lo - pad, y_hi + pad)
    ax.spines[["top", "right"]].set_visible(False)


def _plot_components(
    ax:          plt.Axes,
    results:     list[SeriesResult],
    title:       str,
    pos_color:   str,
    neg_color:   str,
) -> None:
    labels, z_vals, weights = [], [], []
    for sr in results:
        if sr.latest_z is not None:
            sign = -1.0 if sr.spec.invert else 1.0
            labels.append(sr.spec.label)
            z_vals.append(sr.latest_z * sign)
            weights.append(sr.spec.weight)

    if not labels:
        ax.text(0.5, 0.5, "No data", ha="center", va="center", transform=ax.transAxes)
        return

    y_pos  = list(range(len(labels)))
    colors = [pos_color if z >= 0 else neg_color for z in z_vals]
    bars   = ax.barh(y_pos, z_vals, color=colors, alpha=0.80,
                     edgecolor="#ffffffcc", linewidth=0.6, zorder=3)

    for bar, z, w in zip(bars, z_vals, weights):
        contrib = z * w
        xoff    = 0.06 if z >= 0 else -0.06
        ha      = "left" if z >= 0 else "right"
        ax.text(
            bar.get_width() + xoff,
            bar.get_y() + bar.get_height() / 2,
            f"z={z:+.2f}  w={w:.0%}  →{contrib:+.3f}",
            va="center", ha=ha, fontsize=8.2, color="#333333",
        )

    ax.set_yticks(y_pos)
    ax.set_yticklabels(labels, fontsize=9)
    ax.axvline(0, color="#888888", linewidth=0.9, linestyle="--", alpha=0.7)
    ax.set_xlabel("Signed Z-Score  (inversion applied)", fontsize=9)
    ax.set_title(title, fontsize=11, fontweight="bold", pad=8)
    ax.grid(True, axis="x", alpha=0.3, linestyle="--", zorder=0)
    ax.spines[["top", "right"]].set_visible(False)
    max_abs = max(abs(z) for z in z_vals) if z_vals else 1.0
    ax.set_xlim(-max_abs - 1.2, max_abs + 1.8)


# ─────────────────────────────────────────────────────────────────────────────
# Build & display charts
# ─────────────────────────────────────────────────────────────────────────────

def build_charts(
    li_comp:    dict[str, float],
    li_series:  list[SeriesResult],
    ll_comp:    dict[str, float],
    ll_series:  list[SeriesResult],
    save_dir:   Path,
) -> None:
    save_dir.mkdir(exist_ok=True)

    fig = plt.figure(figsize=(22, 14), facecolor="#F5F5F5")
    gs  = fig.add_gridspec(
        2, 2,
        width_ratios=[1.5, 1],
        height_ratios=[1, 1],
        hspace=0.42,
        wspace=0.38,
        left=0.05, right=0.95,
        top=0.93,  bottom=0.07,
    )

    ax_li   = fig.add_subplot(gs[0, 0])
    ax_li_b = fig.add_subplot(gs[0, 1])
    ax_ll   = fig.add_subplot(gs[1, 0])
    ax_ll_b = fig.add_subplot(gs[1, 1])

    for ax in (ax_li, ax_li_b, ax_ll, ax_ll_b):
        ax.set_facecolor("#FAFAFA")

    _plot_timeseries(
        ax_li, li_comp,
        "LIIMSI — Leading Inflation Indicators Strength Index",
        "#E53935", LIIMSI_BANDS, liimsi_regime, trailing=24,
    )
    _plot_components(
        ax_li_b, li_series,
        "LIIMSI Components (latest month)",
        "#EF9A9A", "#90CAF9",
    )

    _plot_timeseries(
        ax_ll, ll_comp,
        "LLMSI — Leading Labor Market Strength Index",
        "#1565C0", LLMSI_BANDS, llmsi_regime, trailing=24,
    )
    _plot_components(
        ax_ll_b, ll_series,
        "LLMSI Components (latest month)",
        "#A5D6A7", "#FFCC80",
    )

    now_str = datetime.now().strftime("%Y-%m-%d %H:%M")
    fig.suptitle(
        "Federal Reserve Economic Data — Leading Indicators Dashboard",
        fontsize=15, fontweight="bold", color="#212121", y=0.975,
    )
    fig.text(
        0.5, 0.01,
        f"Source: FRED St. Louis  |  60-month rolling z-score  |  "
        f"Renormalized weighted mean  |  Generated {now_str}",
        ha="center", fontsize=8.5, color="#757575",
    )

    out = save_dir / "fed_dashboard.png"
    fig.savefig(out, dpi=150, bbox_inches="tight", facecolor="#F5F5F5")
    plt.close(fig)
    print(f"\n  Chart saved → {out}")
    try:
        subprocess.Popen(["xdg-open", str(out)])
        print("  Opening chart in image viewer ...")
    except Exception as e:
        print(f"  (could not auto-open: {e})")


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

def main() -> None:
    print("=" * 74)
    print("  FED DASHBOARD  —  LIIMSI & LLMSI")
    print(f"  {date.today().isoformat()}  │  7-year lookback  │  60-month rolling z-score")
    print("=" * 74)

    api_key = load_fred_key()

    print(f"\n[LIIMSI] Fetching {len(LIIMSI_SERIES)} inflation series ...")
    li_comp, li_series = compute_index(LIIMSI_SERIES, api_key, "LIIMSI")

    print(f"\n[LLMSI]  Fetching {len(LLMSI_SERIES)} labor series ...")
    ll_comp, ll_series = compute_index(LLMSI_SERIES, api_key, "LLMSI")

    print_log("LIIMSI", li_comp, li_series, liimsi_regime)
    print_log("LLMSI",  ll_comp, ll_series, llmsi_regime)

    print("\nBuilding charts ...")
    build_charts(
        li_comp, li_series,
        ll_comp, ll_series,
        save_dir=SCRIPT_DIR / "charts",
    )


if __name__ == "__main__":
    main()
