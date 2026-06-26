#!/bin/bash
set -euo pipefail

# SelfAnalyst + ActivityWatch Uninstaller (Linux/macOS)

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

INSTALL_DIR="${HOME}/.self-analyst"

echo -e "${YELLOW}==========================================${NC}"
echo -e "${YELLOW}  SelfAnalyst Uninstaller${NC}"
echo -e "${YELLOW}==========================================${NC}"
echo ""

if [ ! -d "${INSTALL_DIR}" ]; then
    echo -e "${RED}Install directory not found: ${INSTALL_DIR}${NC}"
    echo "Nothing to uninstall."
    exit 0
fi

# ── Stop processes ─────────────────────────────────────────────
echo "Stopping processes..."
pkill -f "self-analyst" 2>/dev/null || true
pkill -f "aw-qt" 2>/dev/null || true
pkill -f "aw-server" 2>/dev/null || true
echo "  Processes stopped."

# ── Keep memory? ───────────────────────────────────────────────
echo ""
echo -e "${CYAN}Do you want to keep your memory data (goals, patterns, logs)?${NC}"
read -r -p "  Keep memory.json? [Y/n]: " KEEP </dev/tty
KEEP=${KEEP:-Y}

if [[ "${KEEP}" =~ ^[Yy] ]]; then
    if [ -f "${INSTALL_DIR}/memory.json" ]; then
        cp "${INSTALL_DIR}/memory.json" "${HOME}/.self-analyst-memory-backup.json"
        echo -e "${GREEN}  Memory saved to ~/.self-analyst-memory-backup.json${NC}"
    else
        echo "  No memory.json found to save."
    fi
fi

# ── Remove install dir ─────────────────────────────────────────
echo ""
echo "Removing ${INSTALL_DIR} ..."
rm -rf "${INSTALL_DIR}"

echo ""
echo -e "${GREEN}==========================================${NC}"
echo -e "${GREEN}  Uninstall complete${NC}"
echo -e "${GREEN}==========================================${NC}"
echo ""
echo "Manual cleanup (if desired):"
echo "  - rm ~/.self-analyst-memory-backup.json  (if you don't need it)"
echo "  - Remove PATH entry for ~/.self-analyst/bin in ~/.bashrc"
echo "  - ActivityWatch: pip uninstall activitywatch"
