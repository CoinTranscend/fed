#!/usr/bin/env python3
"""
Fed Compare — Regular (Coincident) vs Leading Indicators
==========================================================
Overlays four composite indices on two charts so you can see the
lead-lag relationship between current conditions and the forward signal.

  ISI   — Inflation Strength Index (current / coincident)
           10 series, equal-weight rolling z-score
           What the Fed is actually reacting TO right now.

  LIIMSI— Leading Inflation Indicators (1–3 month forward signal)
           7 series, weighted rolling z-score  [from fed_dashboard.py logic]
           What inflation should look like in coming months.

  LSI   — Labor Strength Index (current / coincident)
           9 series, equal-weight rolling z-score
           Where the labor market IS today.

  LLMSI — Leading Labor Market Strength Index (2–6 month forward signal)
           7 series, weighted rolling z-score  [from fed_dashboard.py logic]
           Where labor is HEADING.

Series (existing options-trading-system framework):
  ISI:  CPILFESL  PCEPILFE  PCETRIM12M159SFRBDAL  CORESTICKM159SFRBATL
        PPIFIS  T5YIE  T10YIE  CUSR0000SAH1  PPIACO  MICH
  LSI:  PAYEMS  UNRATE  ICSA  CCSA  JTSJOL  JTSQUR  CES0500000003
        CIVPART  MANEMP

Algo (both indices use the same mechanics as the existing framework):
  1. Fetch 7-year history, monthly avg aggregation from FRED
  2. YoY transform where flagged  ( (v / v[i-12]) - 1 )
  3. Rolling 60-month z-score (min 24 periods, sample std)
  4. Inversion for negative-direction indicators
  5. Equal-weight average of available z-scores (resampled monthly)

Layout:
  Row 1: Inflation overlay (ISI solid + LIIMSI dashed), every point labeled
  Row 2: Labor overlay    (LSI solid + LLMSI dashed),  every point labeled
  Row 3: ISI component bars  |  LSI component bars
"""

from __future__ import annotations

import os, sys, math, requests, subprocess
from pathlib import Path
from datetime import date, datetime
from dataclasses import dataclass
from typing import Optional

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
from matplotlib.lines import Line2D

SCRIPT_DIR = Path(__file__).resolve().parent

# ── reuse helpers from fed_dashboard ─────────────────────────────────────────
sys.path.insert(0, str(SCRIPT_DIR))
from fed_dashboard import (
    load_fred_key, fetch_fred_series,
    forward_fill, apply_yoy, rolling_zscore,
    SeriesSpec,
    LIIMSI_SERIES, LLMSI_SERIES,
    liimsi_regime, llmsi_regime,
)


# ─────────────────────────────────────────────────────────────────────────────
# Regular (coincident/lagging) series definitions
# ─────────────────────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class RegSeries:
    id:     str
    label:  str
    short:  str
    yoy:    bool
    invert: bool = False


# ── ISI: series the Fed actually targets ─────────────────────────────────────
# Sources: FOMC statements, SEP, Beige Book, Powell speeches
ISI_SERIES: list[RegSeries] = [
    # Core measures — Fed's primary gauges
    RegSeries("CPILFESL",             "Core CPI",               "Core CPI",    yoy=True),   # ▲ primary indicator
    RegSeries("PCEPILFE",             "Core PCE",               "Core PCE",    yoy=True),   # ▲ Fed's preferred target
    RegSeries("PCETRIM12M159SFRBDAL", "Trimmed Mean PCE",       "Trim PCE",    yoy=False),  # published as YoY %
    RegSeries("CORESTICKM159SFRBATL", "Sticky Price CPI",       "Sticky CPI",  yoy=False),  # published as YoY %
    # Pipeline & upstream
    RegSeries("PPIFIS",               "PPI Final Demand",       "PPI FD",      yoy=True),
    RegSeries("PPIACO",               "PPI All Commodities",    "PPI Cmdty",   yoy=True),
    # Market & shelter
    RegSeries("T5YIE",                "5Y Breakeven",           "5Y BE",       yoy=False),
    RegSeries("T10YIE",               "10Y Breakeven",          "10Y BE",      yoy=False),
    RegSeries("CUSR0000SAH1",         "CPI Shelter",            "Shelter",     yoy=True),   # largest CPI weight
    # Expectations
    RegSeries("MICH",                 "UMich 1Y Expectations",  "UMich Exp",   yoy=False),
]

# ── LSI: series the Fed tracks for employment mandate ────────────────────────
LSI_SERIES: list[RegSeries] = [
    # Headline labor market
    RegSeries("PAYEMS",       "Non-Farm Payrolls",         "NFP",         yoy=True,  invert=False),  # ▲ headline
    RegSeries("UNRATE",       "Unemployment Rate (U3)",    "U3",          yoy=False, invert=True),   # ▲ inverted
    RegSeries("CIVPART",      "Labor Force Participation", "LFPR",        yoy=False, invert=False),
    # Claims (coincident level, NOT YoY — different from LLMSI which uses YoY)
    RegSeries("ICSA",         "Initial Claims",            "Init Claims", yoy=False, invert=True),
    RegSeries("CCSA",         "Continuing Claims",         "Cont Claims", yoy=False, invert=True),
    # JOLTS
    RegSeries("JTSJOL",       "Job Openings (JOLTS)",      "JOLTS Open",  yoy=True,  invert=False),
    RegSeries("JTSQUR",       "Quits Rate",                "Quits Rate",  yoy=False, invert=False),
    # Wages & sector
    RegSeries("CES0500000003","Avg Hourly Earnings",       "AHE",         yoy=True,  invert=False),
    RegSeries("MANEMP",       "Mfg Employment",            "Mfg Emp",     yoy=True,  invert=False),
]


# ─────────────────────────────────────────────────────────────────────────────
# Regular composite engine  (equal-weight, same 60-month z-score window)
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class RegResult:
    """Per-series result for a regular composite."""
    spec:           RegSeries
    latest_month:   str            = ""
    latest_raw:     Optional[float] = None   # raw FRED value
    latest_trans:   Optional[float] = None   # after YoY (or same as raw if no YoY)
    latest_z_raw:   Optional[float] = None   # z-score before inversion (for log display)
    latest_z_signed:Optional[float] = None   # z-score after sign flip (for composite)
    wc: int                         = 0
    wm: Optional[float]             = None   # rolling window mean (of trans)
    ws: Optional[float]             = None   # rolling window std  (of trans)

    # back-compat alias
    @property
    def latest_z(self) -> Optional[float]:
        return self.latest_z_raw


def compute_regular(
    series_list: list[RegSeries],
    api_key:     str,
    name:        str,
) -> tuple[pd.Series, list[RegResult]]:
    """
    Fetch + process a regular (equal-weight) composite.
    Returns (composite_monthly_series, list[RegResult]).
    """
    start_date = date(date.today().year - 7, date.today().month, 1).strftime("%Y-%m-%d")

    # ── Fetch ──────────────────────────────────────────────────────────────
    raw_series: dict[str, list[tuple[str, Optional[float]]]] = {}
    for spec in series_list:
        try:
            print(f"    {spec.id:<25} {spec.label}", flush=True)
            data = fetch_fred_series(spec.id, api_key, start_date)
            if data:
                raw_series[spec.id] = data
        except Exception as e:
            print(f"      (warning: {e})")

    all_months = sorted({ym for s in raw_series.values() for ym, _ in s})

    # ── Process ────────────────────────────────────────────────────────────
    zscore_by_series: dict[str, dict[str, Optional[float]]] = {}
    reg_results: list[RegResult] = []

    for spec in series_list:
        raw_list = raw_series.get(spec.id)
        if not raw_list:
            continue

        raw_map = dict(raw_list)
        filled  = forward_fill(all_months, raw_map, limit=2)

        if spec.yoy:
            trans, _ = apply_yoy(all_months, filled)
        else:
            trans = filled

        z_scores, win_stats = rolling_zscore(all_months, trans, window=60, min_periods=24)

        sign = -1.0 if spec.invert else 1.0
        zs   = {m: (z_scores[m] * sign if z_scores[m] is not None else None)
                for m in all_months}
        zscore_by_series[spec.id] = zs

        latest_month = next((m for m in reversed(all_months)
                             if z_scores.get(m) is not None), "")
        rr = RegResult(spec=spec, latest_month=latest_month)
        if latest_month:
            sign              = -1.0 if spec.invert else 1.0
            rr.latest_raw     = filled.get(latest_month)
            rr.latest_trans   = trans.get(latest_month)
            rr.latest_z_raw   = z_scores.get(latest_month)
            rr.latest_z_signed= (z_scores[latest_month] * sign
                                 if z_scores.get(latest_month) is not None else None)
            st = win_stats.get(latest_month, {})
            rr.wc = st.get("count", 0)
            rr.wm = st.get("mean")
            rr.ws = st.get("std")
        reg_results.append(rr)

    # ── Composite: equal-weight mean ───────────────────────────────────────
    # Build a DataFrame then resample monthly → mean across available z-scores
    df_rows: dict[str, dict[str, Optional[float]]] = {}
    for sid, zmap in zscore_by_series.items():
        for m, z in zmap.items():
            df_rows.setdefault(m, {})[sid] = z

    records: list[dict] = []
    for m in sorted(df_rows.keys()):
        vals = [v for v in df_rows[m].values() if v is not None and not math.isnan(v)]
        if vals:
            records.append({"date": pd.Timestamp(f"{m}-01"), "composite": sum(vals) / len(vals)})

    if not records:
        return pd.Series(dtype=float, name=name), reg_results

    df      = pd.DataFrame(records).set_index("date")
    monthly = df["composite"].resample("MS").last().dropna()
    monthly.name = name
    return monthly, reg_results


# ─────────────────────────────────────────────────────────────────────────────
# Leading composite  (reuse from fed_dashboard.py)
# ─────────────────────────────────────────────────────────────────────────────

def _leading_to_series(
    composite_dict: dict[str, float],
    name: str,
) -> pd.Series:
    """Convert the dict-based leading composite to a monthly pd.Series."""
    if not composite_dict:
        return pd.Series(dtype=float, name=name)
    records = [(pd.Timestamp(f"{m}-01"), v) for m, v in sorted(composite_dict.items())]
    s = pd.Series({r[0]: r[1] for r in records}, name=name)
    return s.resample("MS").last().dropna()


def compute_leading(
    series_list: list[SeriesSpec],
    api_key:     str,
    name:        str,
) -> tuple[pd.Series, list]:
    """Wrapper around fed_dashboard.compute_index → pd.Series."""
    from fed_dashboard import compute_index
    composite_dict, series_results = compute_index(series_list, api_key, name)
    s = _leading_to_series(composite_dict, name)
    return s, series_results


# ─────────────────────────────────────────────────────────────────────────────
# Regime classifiers for regular indices
# ─────────────────────────────────────────────────────────────────────────────

def isi_regime(v: float) -> tuple[str, str]:
    if v >= 0.8:  return ("HOT",        "#B71C1C")
    if v >= 0.3:  return ("ELEVATED",   "#E53935")
    if v >= -0.3: return ("NEUTRAL",    "#FFA726")
    if v >= -0.8: return ("COOLING",    "#42A5F5")
    return               ("LOW",        "#1565C0")


def lsi_regime(v: float) -> tuple[str, str]:
    if v >= 0.8:  return ("ROBUST",     "#1B5E20")
    if v >= 0.3:  return ("STRONG",     "#2E7D32")
    if v >= -0.3: return ("NEUTRAL",    "#F57F17")
    if v >= -0.8: return ("SOFTENING",  "#E65100")
    return               ("WEAK",       "#B71C1C")


def macro_regime(lsi_val: float, isi_val: float) -> str:
    if lsi_val >  0.3 and isi_val >  0.3: return "HOT ECONOMY — Fed hold/hike"
    if lsi_val >  0.3 and isi_val <= 0.3: return "GOLDILOCKS — Strong labor, contained inflation"
    if lsi_val <= -0.3 and isi_val > 0.3: return "STAGFLATION RISK — Weak labor + hot inflation"
    if lsi_val <= -0.3 and isi_val <=-0.3: return "RECESSION RISK — Both weakening, cuts likely"
    if isi_val >  lsi_val + 0.3:           return "INFLATION DOMINANT — Fed price-stability focus"
    if lsi_val >  isi_val + 0.3:           return "LABOR DOMINANT — Employment mandate in focus"
    return "NEUTRAL — Wait-and-see mode"


# ─────────────────────────────────────────────────────────────────────────────
# Computation log
# ─────────────────────────────────────────────────────────────────────────────

def _print_regular_log(
    name:     str,
    monthly:  pd.Series,
    results:  list[RegResult],
    classify: callable,
) -> None:
    if monthly.empty:
        print(f"\n  {name}: no data.\n"); return

    last_dt  = monthly.index[-1]
    last_val = monthly.iloc[-1]
    last_m   = last_dt.strftime("%Y-%m")
    regime, _= classify(last_val)

    W = 74
    print(f"\n{'═'*W}")
    print(f"  {name}  —  Computation Log")
    print(f"{'═'*W}")
    print(f"  Composite: {last_val:+.4f}   ({last_m})   Regime: {regime}")
    print(f"  Method:    Equal-weight mean of {len([r for r in results if r.latest_z is not None])} available series")
    print()

    contrib_rows = []

    for rr in results:
        spec = rr.spec
        tag  = "  [YoY]" if spec.yoy else ""
        inv  = "  [inverted]" if spec.invert else ""
        print(f"  ┌─ {spec.id}  —  {spec.label}{tag}{inv}")

        if rr.latest_z is None:
            print(f"  └─ No data at {last_m}\n"); continue

        if rr.latest_raw is not None:
            print(f"  │  FRED value:     {rr.latest_raw:>14.4f}  ({rr.latest_month})")

        if spec.yoy and rr.latest_trans is not None and rr.latest_raw is not None:
            print(f"  │  YoY transform:  {rr.latest_trans:>+14.6f}  = (raw / prior_12m) − 1")
            val_for_z = rr.latest_trans
        elif not spec.yoy and rr.latest_raw is not None:
            print(f"  │  YoY:            not applied  (series in comparable units)")
            val_for_z = rr.latest_raw
        else:
            val_for_z = rr.latest_trans

        if rr.wm is not None and rr.ws is not None and val_for_z is not None:
            print(f"  │  Rolling window: {rr.wc} obs  │  mean={rr.wm:+.6f}  │  std={rr.ws:.6f}")
            z_raw = (val_for_z - rr.wm) / rr.ws if rr.ws > 1e-10 else 0.0
            print(f"  │  Z-score:        ({val_for_z:+.6f} − {rr.wm:+.6f}) / {rr.ws:.6f} = {z_raw:+.4f}")
            if spec.invert:
                print(f"  │  After invert:   {z_raw:+.4f} × −1 = {-z_raw:+.4f}")
        contrib_rows.append((spec.short, rr.latest_z_raw, rr.latest_z_signed))
        print(f"  └──────────────────────────────────────────────────────────\n")

    if contrib_rows:
        n   = len(contrib_rows)
        s   = sum(v for _, _, v in contrib_rows if v is not None)
        avg = s / n if n else float("nan")
        print(f"  {'── COMPOSITE DERIVATION ──':^{W-4}}")
        print(f"  (signed z-scores — inversion already applied)")
        print()
        print(f"  {'Indicator':<25}  {'Z raw':>9}  {'Z signed':>9}")
        print(f"  {'─'*25}  {'─'*9}  {'─'*9}")
        for label, z_raw, z_signed in contrib_rows:
            inv_marker = " *" if z_raw != z_signed else ""
            print(f"  {label:<25}  {z_raw:>+9.4f}  {z_signed:>+9.4f}{inv_marker}")
        print(f"  {'─'*25}  {'─'*9}  {'─'*9}")
        print(f"  {'Equal-weight mean':<25}  {'':>9}  {avg:>+9.4f}")
        print(f"  (* = inverted series)")

    # Monthly history
    hist = monthly.iloc[-12:]
    print(f"\n  {'── MONTHLY HISTORY (last 12) ──':^{W-4}}")
    print()
    print(f"  {'Month':<10}  {'Composite':>10}  {'Regime':<12}  {'MoM Chg':>8}")
    print(f"  {'─'*10}  {'─'*10}  {'─'*12}  {'─'*8}")
    prev = None
    for dt, v in hist.items():
        rname, _ = classify(v)
        mom = f"{v - prev:+.3f}" if prev is not None else "—"
        print(f"  {dt.strftime('%Y-%m'):<10}  {v:>+10.4f}  {rname:<12}  {mom:>8}")
        prev = v
    print(f"{'═'*W}")


# ─────────────────────────────────────────────────────────────────────────────
# Chart helpers
# ─────────────────────────────────────────────────────────────────────────────

def _plot_overlay(
    ax:            plt.Axes,
    regular:       pd.Series,
    leading:       pd.Series,
    reg_label:     str,
    lead_label:    str,
    reg_color:     str,
    lead_color:    str,
    title:         str,
    classify_reg:  callable,
    trailing:      int = 30,
) -> None:
    """
    Overlay chart: regular (solid, thick) + leading (dashed) on same axes.
    Both series have every data point labeled.
    """
    # Align to same date range
    both   = pd.DataFrame({"reg": regular, "lead": leading}).dropna(how="all")
    if len(both) > trailing:
        both = both.iloc[-trailing:]
    if both.empty:
        ax.text(0.5, 0.5, "No data", ha="center", va="center", transform=ax.transAxes)
        return

    xs = both.index

    # ── Zero line ────────────────────────────────────────────────────────
    ax.axhline(0, color="#AAAAAA", linewidth=0.9, linestyle="--", alpha=0.7, zorder=1)

    # ── Fill between (divergence shading) ─────────────────────────────────
    has_both = both.dropna()
    if not has_both.empty:
        ax.fill_between(
            has_both.index,
            has_both["reg"], has_both["lead"],
            where=has_both["reg"] >= has_both["lead"],
            alpha=0.09, color=reg_color, interpolate=True, zorder=0,
            label="_nolegend_",
        )
        ax.fill_between(
            has_both.index,
            has_both["reg"], has_both["lead"],
            where=has_both["reg"] < has_both["lead"],
            alpha=0.09, color=lead_color, interpolate=True, zorder=0,
            label="_nolegend_",
        )

    # ── Regular line (solid, thicker) ──────────────────────────────────────
    if "reg" in both.columns:
        reg_vals = both["reg"].dropna()
        ax.plot(reg_vals.index, reg_vals.values,
                color=reg_color, linewidth=2.6, zorder=4,
                marker="o", markersize=5,
                markerfacecolor="white", markeredgecolor=reg_color, markeredgewidth=1.8,
                label=reg_label, solid_capstyle="round")

        for i, (x, y) in enumerate(zip(reg_vals.index, reg_vals.values)):
            dy = 13 if i % 2 == 0 else -17
            ax.annotate(f"{y:+.2f}", xy=(x, y), xytext=(0, dy),
                        textcoords="offset points", ha="center",
                        fontsize=7, color=reg_color, fontweight="bold", zorder=6)

    # ── Leading line (dashed, slightly thinner) ─────────────────────────────
    if "lead" in both.columns:
        lead_vals = both["lead"].dropna()
        ax.plot(lead_vals.index, lead_vals.values,
                color=lead_color, linewidth=2.0, linestyle="--", zorder=3,
                marker="D", markersize=4,
                markerfacecolor="white", markeredgecolor=lead_color, markeredgewidth=1.5,
                label=lead_label, dash_capstyle="round")

        for i, (x, y) in enumerate(zip(lead_vals.index, lead_vals.values)):
            dy = -20 if i % 2 == 0 else 15
            ax.annotate(f"{y:+.2f}", xy=(x, y), xytext=(0, dy),
                        textcoords="offset points", ha="center",
                        fontsize=7, color=lead_color, fontweight="bold",
                        alpha=0.85, zorder=6)

    # ── Latest-value callout ────────────────────────────────────────────────
    for col, color, classify, offset in [
        ("reg",  reg_color,  classify_reg, (+40, +22)),
        ("lead", lead_color, None,         (+40, -28)),
    ]:
        if col not in both.columns:
            continue
        vals = both[col].dropna()
        if vals.empty:
            continue
        lx, ly = vals.index[-1], vals.iloc[-1]
        if classify:
            rname, _ = classify(ly)
            label = f" {ly:+.3f}\n {rname}"
        else:
            label = f" {ly:+.3f}"
        ax.annotate(
            label,
            xy=(lx, ly), xytext=offset, textcoords="offset points",
            fontsize=8.5, fontweight="bold", color=color,
            bbox=dict(boxstyle="round,pad=0.3", fc="white", ec=color, lw=1.2, alpha=0.93),
            arrowprops=dict(arrowstyle="->", color=color, lw=1.2),
            zorder=7,
        )

    # ── Styling ────────────────────────────────────────────────────────────
    ax.set_title(title, fontsize=12, fontweight="bold", pad=10)
    ax.set_ylabel("Composite Z-Score", fontsize=10)
    ax.legend(loc="upper left", fontsize=9, framealpha=0.9,
              handles=[
                  Line2D([0],[0], color=reg_color,  linewidth=2.6, marker="o", markersize=6, label=reg_label),
                  Line2D([0],[0], color=lead_color, linewidth=2.0, marker="D", markersize=5, linestyle="--", label=lead_label),
              ])
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%b '%y"))
    ax.xaxis.set_major_locator(mdates.MonthLocator(interval=2))
    plt.setp(ax.xaxis.get_majorticklabels(), rotation=35, ha="right", fontsize=8.5)
    ax.grid(True, alpha=0.25, linestyle="--", zorder=2)
    ax.set_xlim(xs[0] - pd.Timedelta(days=15), xs[-1] + pd.Timedelta(days=60))
    all_y = list(both["reg"].dropna()) + list(both["lead"].dropna())
    if all_y:
        ypad = max(abs(min(all_y)), abs(max(all_y))) * 0.30 + 0.55
        ax.set_ylim(min(all_y) - ypad, max(all_y) + ypad)
    ax.spines[["top", "right"]].set_visible(False)


def _plot_components(
    ax:          plt.Axes,
    results:     list[RegResult],
    title:       str,
    pos_color:   str,
    neg_color:   str,
) -> None:
    labels, z_vals = [], []
    for rr in results:
        if rr.latest_z_signed is not None:
            labels.append(rr.spec.short)
            z_vals.append(rr.latest_z_signed)

    if not labels:
        ax.text(0.5, 0.5, "No data", ha="center", va="center", transform=ax.transAxes); return

    colors = [pos_color if z >= 0 else neg_color for z in z_vals]
    bars   = ax.barh(list(range(len(labels))), z_vals,
                     color=colors, alpha=0.78, edgecolor="#ffffff88", linewidth=0.5, zorder=3)

    for bar, z in zip(bars, z_vals):
        xoff = 0.06 if z >= 0 else -0.06
        ha   = "left" if z >= 0 else "right"
        ax.text(bar.get_width() + xoff, bar.get_y() + bar.get_height() / 2,
                f"{z:+.2f}", va="center", ha=ha, fontsize=8.5, color="#333333")

    ax.set_yticks(list(range(len(labels))))
    ax.set_yticklabels(labels, fontsize=9)
    ax.axvline(0, color="#888888", linewidth=0.9, linestyle="--", alpha=0.7)
    ax.set_xlabel("Signed Z-Score (equal-weight)", fontsize=9)
    ax.set_title(title, fontsize=11, fontweight="bold", pad=8)
    ax.grid(True, axis="x", alpha=0.25, linestyle="--", zorder=0)
    ax.spines[["top", "right"]].set_visible(False)
    max_abs = max(abs(z) for z in z_vals) if z_vals else 1.0
    ax.set_xlim(-max_abs - 0.8, max_abs + 1.5)


# ─────────────────────────────────────────────────────────────────────────────
# Build & save chart
# ─────────────────────────────────────────────────────────────────────────────

def build_charts(
    isi:      pd.Series, isi_res:  list[RegResult],
    lsi:      pd.Series, lsi_res:  list[RegResult],
    liimsi:   pd.Series,
    llmsi:    pd.Series,
    save_dir: Path,
) -> None:
    save_dir.mkdir(exist_ok=True)

    fig = plt.figure(figsize=(22, 16), facecolor="#F5F5F5")
    gs  = fig.add_gridspec(
        3, 2,
        width_ratios=[1.6, 1],
        height_ratios=[1, 1, 0.9],
        hspace=0.48, wspace=0.36,
        left=0.05, right=0.95,
        top=0.93,  bottom=0.06,
    )
    ax_infl    = fig.add_subplot(gs[0, 0])
    ax_isi_b   = fig.add_subplot(gs[0, 1])
    ax_labor   = fig.add_subplot(gs[1, 0])
    ax_lsi_b   = fig.add_subplot(gs[1, 1])
    ax_summary = fig.add_subplot(gs[2, :])

    for ax in (ax_infl, ax_isi_b, ax_labor, ax_lsi_b, ax_summary):
        ax.set_facecolor("#FAFAFA")

    # ── Inflation overlay ──────────────────────────────────────────────────
    _plot_overlay(
        ax_infl, isi, liimsi,
        reg_label="ISI — Regular (Core CPI · PCE · Shelter · Breakevenss)",
        lead_label="LIIMSI — Leading (1–3 month forward signal)",
        reg_color="#C62828", lead_color="#EF9A9A",
        title="Inflation: Current Conditions (solid) vs Leading Signal (dashed)",
        classify_reg=isi_regime, trailing=30,
    )

    # ── ISI component bars ─────────────────────────────────────────────────
    _plot_components(ax_isi_b, isi_res,
                     "ISI — Component Z-Scores (latest)", "#EF9A9A", "#BBDEFB")

    # ── Labor overlay ──────────────────────────────────────────────────────
    _plot_overlay(
        ax_labor, lsi, llmsi,
        reg_label="LSI — Regular (NFP · Unemployment · JOLTS · AHE)",
        lead_label="LLMSI — Leading (2–6 month forward signal)",
        reg_color="#1565C0", lead_color="#90CAF9",
        title="Labor: Current Conditions (solid) vs Leading Signal (dashed)",
        classify_reg=lsi_regime, trailing=30,
    )

    # ── LSI component bars ─────────────────────────────────────────────────
    _plot_components(ax_lsi_b, lsi_res,
                     "LSI — Component Z-Scores (latest)", "#A5D6A7", "#FFCC80")

    # ── Summary table (bottom row) ─────────────────────────────────────────
    ax_summary.axis("off")
    now_str = datetime.now().strftime("%Y-%m-%d %H:%M")

    # Build summary text
    def _last(s: pd.Series) -> tuple[str, float]:
        if s.empty: return "N/A", float("nan")
        return s.index[-1].strftime("%Y-%m"), s.iloc[-1]

    isi_m,  isi_v  = _last(isi)
    lsi_m,  lsi_v  = _last(lsi)
    li_m,   li_v   = _last(liimsi)
    ll_m,   ll_v   = _last(llmsi)

    regime_str = macro_regime(lsi_v, isi_v) if not (math.isnan(lsi_v) or math.isnan(isi_v)) else "N/A"
    ir, _  = isi_regime(isi_v)   if not math.isnan(isi_v)  else ("?", "")
    lr, _  = lsi_regime(lsi_v)   if not math.isnan(lsi_v)  else ("?", "")
    lir, _ = liimsi_regime(li_v) if not math.isnan(li_v)   else ("?", "")
    llr, _ = llmsi_regime(ll_v)  if not math.isnan(ll_v)   else ("?", "")

    table_data = [
        ["", "Index", "Value", "As Of", "Regime"],
        ["INFLATION", "ISI (current)",   f"{isi_v:+.3f}", isi_m, ir],
        ["",          "LIIMSI (leading)",f"{li_v:+.3f}",  li_m,  lir],
        ["LABOR",     "LSI (current)",   f"{lsi_v:+.3f}", lsi_m, lr],
        ["",          "LLMSI (leading)", f"{ll_v:+.3f}",  ll_m,  llr],
        ["MACRO",     "Regime",          regime_str, "", ""],
    ]

    col_w = [0.08, 0.20, 0.10, 0.10, 0.20]
    col_x = [0.01]
    for w in col_w[:-1]:
        col_x.append(col_x[-1] + w)

    row_h = 0.14
    y0    = 0.88
    for r, row in enumerate(table_data):
        y = y0 - r * row_h
        bg = "#ECECEC" if r == 0 else ("#FFF8E1" if row[0] else "white")
        ax_summary.axhspan(y - row_h, y, color=bg, alpha=0.7, transform=ax_summary.transAxes)
        for c, (text, x) in enumerate(zip(row, col_x)):
            weight = "bold" if r == 0 or c == 0 else "normal"
            color  = "#333333"
            if r > 0 and c == 2:  # value column
                try:
                    v = float(text)
                    color = "#C62828" if v > 0 else "#1565C0"
                    weight = "bold"
                except ValueError:
                    color = "#333333"
            ax_summary.text(x, y - row_h * 0.4, text,
                            transform=ax_summary.transAxes,
                            fontsize=9.5, fontweight=weight, color=color, va="center")

    ax_summary.set_xlim(0, 1); ax_summary.set_ylim(0, 1)
    ax_summary.set_title("Summary", fontsize=10.5, fontweight="bold",
                          loc="left", pad=6, color="#555555")

    # ── Footer & title ─────────────────────────────────────────────────────
    fig.suptitle(
        "Fed Dashboard — Current Conditions vs Leading Indicators",
        fontsize=15, fontweight="bold", color="#212121", y=0.975,
    )
    fig.text(
        0.5, 0.01,
        f"Source: FRED St. Louis  │  60-month rolling z-score  │  "
        f"Solid = coincident (equal-weight)  │  Dashed = leading (weighted)  │  "
        f"Generated {now_str}",
        ha="center", fontsize=8.5, color="#757575",
    )

    out = save_dir / "fed_compare.png"
    fig.savefig(out, dpi=150, bbox_inches="tight", facecolor="#F5F5F5")
    plt.close(fig)
    print(f"\n  Chart saved → {out}")
    try:
        subprocess.Popen(["xdg-open", str(out)])
        print("  Opening chart in image viewer ...")
    except Exception as e:
        print(f"  (could not auto-open: {e})")


# ─────────────────────────────────────────────────────────────────────────────
# Summary comparison table (terminal)
# ─────────────────────────────────────────────────────────────────────────────

def print_comparison_table(
    isi:    pd.Series,
    lsi:    pd.Series,
    liimsi: pd.Series,
    llmsi:  pd.Series,
) -> None:
    # Align all four to monthly
    df = pd.DataFrame({
        "ISI":    isi,
        "LIIMSI": liimsi,
        "LSI":    lsi,
        "LLMSI":  llmsi,
    }).resample("MS").last()

    last = df.dropna(how="all").iloc[-18:]  # last 18 months

    print(f"\n{'═'*80}")
    print("  MONTHLY COMPARISON  —  Current Conditions vs Leading Indicators")
    print(f"{'═'*80}")
    print(f"  {'Month':<10}  {'ISI':>8}  {'LIIMSI':>8}  {'Δ(lead-curr)':>13}  │  {'LSI':>8}  {'LLMSI':>8}  {'Δ(lead-curr)':>13}")
    print(f"  {'─'*10}  {'─'*8}  {'─'*8}  {'─'*13}  │  {'─'*8}  {'─'*8}  {'─'*13}")

    for dt, row in last.iterrows():
        m  = dt.strftime("%Y-%m")
        i  = f"{row['ISI']:>+8.3f}"  if not pd.isna(row['ISI'])    else "     N/A"
        li = f"{row['LIIMSI']:>+8.3f}" if not pd.isna(row['LIIMSI']) else "     N/A"
        l  = f"{row['LSI']:>+8.3f}"  if not pd.isna(row['LSI'])    else "     N/A"
        ll = f"{row['LLMSI']:>+8.3f}" if not pd.isna(row['LLMSI'])  else "     N/A"
        di = (f"{row['LIIMSI']-row['ISI']:>+13.3f}"
              if not (pd.isna(row['LIIMSI']) or pd.isna(row['ISI'])) else "          N/A")
        dl = (f"{row['LLMSI']-row['LSI']:>+13.3f}"
              if not (pd.isna(row['LLMSI']) or pd.isna(row['LSI'])) else "          N/A")
        print(f"  {m:<10}  {i}  {li}  {di}  │  {l}  {ll}  {dl}")

    # Latest spread interpretation
    last_row = df.dropna(how="all").iloc[-1]
    print()
    if not (pd.isna(last_row.get("ISI")) or pd.isna(last_row.get("LIIMSI"))):
        d = last_row["LIIMSI"] - last_row["ISI"]
        print(f"  Inflation spread  LIIMSI − ISI = {d:+.3f}  "
              f"({'leading MORE inflationary' if d > 0 else 'leading LESS inflationary'} than current reading)")
    if not (pd.isna(last_row.get("LSI")) or pd.isna(last_row.get("LLMSI"))):
        d = last_row["LLMSI"] - last_row["LSI"]
        print(f"  Labor spread      LLMSI − LSI  = {d:+.3f}  "
              f"({'leading STRONGER' if d > 0 else 'leading WEAKER'} than current reading)")
    print(f"{'═'*80}")


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

def main() -> None:
    print("=" * 74)
    print("  FED COMPARE  —  Current Conditions vs Leading Indicators")
    print(f"  {date.today().isoformat()}  │  60-month rolling z-score")
    print("=" * 74)

    api_key = load_fred_key()

    # ── Regular Inflation (ISI) ────────────────────────────────────────────
    print(f"\n[ISI] Fetching {len(ISI_SERIES)} regular inflation series ...")
    isi, isi_res = compute_regular(ISI_SERIES, api_key, "ISI")

    # ── Regular Labor (LSI) ───────────────────────────────────────────────
    print(f"\n[LSI] Fetching {len(LSI_SERIES)} regular labor series ...")
    lsi, lsi_res = compute_regular(LSI_SERIES, api_key, "LSI")

    # ── Leading Inflation (LIIMSI) ────────────────────────────────────────
    print(f"\n[LIIMSI] Fetching {len(LIIMSI_SERIES)} leading inflation series ...")
    liimsi, li_res = compute_leading(LIIMSI_SERIES, api_key, "LIIMSI")

    # ── Leading Labor (LLMSI) ─────────────────────────────────────────────
    print(f"\n[LLMSI] Fetching {len(LLMSI_SERIES)} leading labor series ...")
    llmsi, ll_res = compute_leading(LLMSI_SERIES, api_key, "LLMSI")

    # ── Logs ──────────────────────────────────────────────────────────────
    _print_regular_log("ISI  — Inflation Strength Index",  isi,   isi_res, isi_regime)
    _print_regular_log("LSI  — Labor Strength Index",      lsi,   lsi_res, lsi_regime)
    print_comparison_table(isi, lsi, liimsi, llmsi)

    # ── Charts ────────────────────────────────────────────────────────────
    print("\nBuilding charts ...")
    build_charts(isi, isi_res, lsi, lsi_res, liimsi, llmsi,
                 save_dir=SCRIPT_DIR / "charts")


if __name__ == "__main__":
    main()
