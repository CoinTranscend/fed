#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# backup_to_usb.sh — Back up Fed Dashboard to a connected USB drive
#
# What it copies:
#   • Latest APK  (releases/FedDashboard-v*.apk — newest by version)
#   • All APKs    (full releases/ history)
#   • README.md   (full project documentation)
#   • INSTALL.txt (plain-text quick-start, written fresh each run)
#
# Usage:
#   ./backup_to_usb.sh              # auto-detect USB drive
#   ./backup_to_usb.sh /path/to/usb # use a specific mount point
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASES_DIR="$SCRIPT_DIR/releases"
README="$SCRIPT_DIR/README.md"
BACKUP_FOLDER="FedDashboard-Backup"

# ── Colour helpers ────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info() { echo -e "${GREEN}[fed]${NC} $*"; }
warn() { echo -e "${YELLOW}[fed]${NC} $*"; }
die()  { echo -e "${RED}[fed] ERROR:${NC} $*" >&2; exit 1; }

# ── Find the latest APK ───────────────────────────────────────────────────────
LATEST_APK=$(ls -1 "$RELEASES_DIR"/FedDashboard-v*.apk 2>/dev/null | sort -V | tail -1)
[[ -z "$LATEST_APK" ]] && die "No APK found in $RELEASES_DIR"
LATEST_VERSION=$(basename "$LATEST_APK" .apk | sed 's/FedDashboard-//')
info "Latest APK: $(basename "$LATEST_APK")  ($LATEST_VERSION)"

# ── Locate the USB drive ──────────────────────────────────────────────────────
if [[ $# -ge 1 ]]; then
    USB_ROOT="$1"
    [[ -d "$USB_ROOT" ]] || die "Path not found: $USB_ROOT"
    info "Using specified path: $USB_ROOT"
else
    SEARCH_ROOTS=("/media/$USER" "/run/media/$USER" "/media" "/mnt")
    CANDIDATES=()
    for root in "${SEARCH_ROOTS[@]}"; do
        if [[ -d "$root" ]]; then
            while IFS= read -r mp; do
                [[ -z "$mp" || "$mp" == "$SCRIPT_DIR"* ]] && continue
                grep -q "^[^ ]* $mp " /proc/mounts 2>/dev/null || continue
                CANDIDATES+=("$mp")
            done < <(find "$root" -maxdepth 1 -mindepth 1 -type d 2>/dev/null)
        fi
    done

    if [[ ${#CANDIDATES[@]} -eq 0 ]]; then
        die "No USB drive detected.\n       Plug in your USB drive and try again, or run:\n       $0 /path/to/usb"
    elif [[ ${#CANDIDATES[@]} -eq 1 ]]; then
        USB_ROOT="${CANDIDATES[0]}"
        info "Auto-detected USB: $USB_ROOT"
    else
        echo ""
        warn "Multiple drives found — pick one:"
        for i in "${!CANDIDATES[@]}"; do
            echo "  [$((i+1))] ${CANDIDATES[$i]}"
        done
        echo ""
        read -rp "Enter number (1-${#CANDIDATES[@]}): " CHOICE
        [[ "$CHOICE" =~ ^[0-9]+$ && "$CHOICE" -ge 1 && "$CHOICE" -le ${#CANDIDATES[@]} ]] \
            || die "Invalid selection."
        USB_ROOT="${CANDIDATES[$((CHOICE-1))]}"
        info "Selected: $USB_ROOT"
    fi
fi

DEST="$USB_ROOT/$BACKUP_FOLDER"

# ── Write INSTALL.txt (always refreshed) ─────────────────────────────────────
write_install_guide() {
    local apk_name
    apk_name=$(basename "$LATEST_APK")
    cat <<GUIDE
═══════════════════════════════════════════════════════════
  Fed Dashboard — $LATEST_VERSION — Install Guide
═══════════════════════════════════════════════════════════

WHAT IS THIS?
  Fed Dashboard is a standalone Android app for tracking the US macro
  environment through composite z-score indices built on FRED data.

  Five indices:
    ISI    — Inflation Strength Index        (10 series, coincident)
    LIIMSI — Leading Inflation Index          (7 series, leads 2–6 months)
    LSI    — Labor Strength Index             (9 series, coincident)
    LLMSI  — Leading Labor Index              (7 series, leads 2–6 months)
    RRI    — Recession Risk Index             (8 series, leads 6–18 months)

  The Recession tab includes optional Gemini AI analysis with a 30-year
  history chart export.

REQUIREMENTS
  • Android phone, version 8.0 or newer  (Android 8 = API 26)
  • Free FRED API key  →  fred.stlouisfed.org/docs/api/api_key.html
  • (Optional) Google Gemini API key  →  aistudio.google.com/app/apikey

─────────────────────────────────────────────────────────────
STEP 1 — Allow installation from file manager / USB
─────────────────────────────────────────────────────────────
  1. On your phone open Settings
  2. Go to  Apps → Special app access → Install unknown apps
  3. Find your file manager (e.g. Files, My Files, Solid Explorer)
  4. Enable  "Allow from this source"

  Note: you only need to do this once.

─────────────────────────────────────────────────────────────
STEP 2 — Get the APK onto your phone
─────────────────────────────────────────────────────────────
  Option A — USB OTG (plug this drive directly into phone):
    Open your file manager → navigate to this folder
    Tap  $apk_name

  Option B — USB cable to computer:
    Copy  $apk_name  to your phone's Downloads folder
    Open it from your file manager

─────────────────────────────────────────────────────────────
STEP 3 — Install
─────────────────────────────────────────────────────────────
  1. Tap the APK file
  2. Tap Install → Open

─────────────────────────────────────────────────────────────
STEP 4 — First launch
─────────────────────────────────────────────────────────────
  1. Open  Fed Dashboard
  2. Go to  Settings tab
  3. Paste your FRED API key → tap  Save FRED Key
     (free at fred.stlouisfed.org — takes ~1 minute to register)
  4. (Optional) Paste your Gemini API key → tap  Save Gemini Key
     (free at aistudio.google.com — enables AI recession analysis)
  5. Go to any index tab — data fetches automatically

─────────────────────────────────────────────────────────────
USING THE APP
─────────────────────────────────────────────────────────────
  Inflation tab    ISI (coincident) overlaid with LIIMSI (leading)
                   Chart shows 4 years · pull down to refresh
                   "⬇ HD Chart" saves 2000×900 PNG to Downloads

  Labor tab        LSI (coincident) overlaid with LLMSI (leading)
                   Same layout as Inflation

  Recession tab    RRI composite (8-series leading indicator)
                   "✦ AI Analysis" — Gemini analyses the 48-month
                   trajectory with Google Search grounding; provides
                   timing estimate and watchlist (requires Gemini key)
                   "⬇ HD Chart" — saves current 4-year chart
                   "⬇ 30-Year History Chart" — fetches full 30-year
                   composite history with NBER recession shading (~30s)

  Settings tab     Save/update FRED and Gemini API keys

─────────────────────────────────────────────────────────────
REGIME GUIDE (all indices use z-score composite)
─────────────────────────────────────────────────────────────
  RRI / ISI / LIIMSI:
    LOW RISK / COOLING / ELEVATED  ≥ +0.5
    STABLE   / RISING  / ANCHORED  ≥  0.0
    CAUTION  / ANCHORED / RISING   ≥ −0.5
    WARNING  / WEAK    / ELEVATED  ≥ −1.0
    CRITICAL / DEFLATIONARY        < −1.0

─────────────────────────────────────────────────────────────
TROUBLESHOOTING
─────────────────────────────────────────────────────────────
  "No data — tap ↻"
    → Check FRED API key is saved in Settings
    → Check internet connection

  AI Analysis shows nothing
    → Add Gemini API key in Settings first
    → Fetch recession data (tap ↻) before requesting AI analysis

  30-year chart takes a while
    → Expected — it makes ~10 FRED API calls for 32 years of data
    → Keep the screen on; a snackbar confirms when saved

═══════════════════════════════════════════════════════════
  Fed Dashboard $LATEST_VERSION  —  Private / CoinTranscend
  Not financial advice.
═══════════════════════════════════════════════════════════
GUIDE
}

# ── Do the backup ─────────────────────────────────────────────────────────────
echo ""
info "Destination: $DEST"
echo ""

if ! mkdir -p "$DEST" 2>/dev/null; then
    die "Cannot write to $USB_ROOT\n       Fix permissions with:\n       sudo chown -R \$USER:\$USER $USB_ROOT"
fi

info "Copying APKs…"
cp "$RELEASES_DIR"/FedDashboard-v*.apk "$DEST/"

info "Copying README.md…"
cp "$README" "$DEST/README.md"

info "Writing INSTALL.txt…"
write_install_guide > "$DEST/INSTALL.txt"

echo ""
info "Backup complete!  Contents of $DEST:"
ls -lh "$DEST"
echo ""
info "Latest APK to install: $(basename "$LATEST_APK")"
info "Follow INSTALL.txt on the drive for setup instructions."
