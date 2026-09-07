#!/bin/bash
set -euo pipefail

# SelfAnalyst + ActivityWatch Installer (Linux/macOS)
# Hermes-style one-shot setup script

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

INSTALL_DIR="${HOME}/.self-analyst"
BIN_DIR="${INSTALL_DIR}/bin"
LIB_DIR="${INSTALL_DIR}/lib"
CONFIG_DIR="${INSTALL_DIR}/config"
MEMORY_FILE="${INSTALL_DIR}/memory.json"
CONFIG_FILE="${CONFIG_DIR}/application.properties"
JAR_PATTERN="self-analyst-app-*.jar"   # 构建产物名，含版本号
JAR_NAME="self-analyst-app.jar"        # 安装后的固定名，与便携包保持一致

# 在指定目录中查找构建产物；shade 插件的 original-*.jar 因前缀不同自然排除。
find_app_jar() {
    local dir="$1"
    [ -d "${dir}" ] || return 1
    local found
    found="$(find "${dir}" -maxdepth 1 -name "${JAR_PATTERN}" -type f 2>/dev/null | sort | head -n 1)"
    [ -n "${found}" ] || return 1
    printf '%s' "${found}"
}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo -e "${CYAN}==========================================${NC}"
echo -e "${CYAN}  SelfAnalyst + ActivityWatch Installer${NC}"
echo -e "${CYAN}==========================================${NC}"
echo ""

# ── 1. Check Java 21+ ──────────────────────────────────────────
echo -e "${YELLOW}[1/6] Checking Java 21+...${NC}"
if ! command -v java &>/dev/null; then
    echo -e "${RED}ERROR: Java not found. Install JDK 21+ and retry.${NC}"
    echo "  Ubuntu: sudo apt install openjdk-21-jdk"
    echo "  macOS:  brew install openjdk@21"
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '\d+' | head -1 || echo "0")
echo "  Found Java version: ${JAVA_VER}"
if [ "${JAVA_VER}" -lt 21 ]; then
    echo -e "${RED}ERROR: Java ${JAVA_VER} < 21. Please upgrade to JDK 21+.${NC}"
    exit 1
fi
echo -e "${GREEN}  OK${NC}"

# ── 2. Check Python 3 + pip ────────────────────────────────────
echo -e "${YELLOW}[2/6] Checking Python 3 + pip...${NC}"
PYTHON=""
for py in python3 python; do
    if command -v "$py" &>/dev/null; then
        PY_VER=$("$py" --version 2>&1 | grep -oP '3\.\d+' | head -1 || echo "0")
        if [ "${PY_VER%%.*}" = "3" ]; then
            PYTHON="$py"
            break
        fi
    fi
done
if [ -z "${PYTHON}" ]; then
    echo -e "${RED}ERROR: Python 3 not found. Install Python 3.10+ and retry.${NC}"
    exit 1
fi
echo "  Found: $(${PYTHON} --version)"

pip_exe=""
for pip in pip3 pip; do
    if command -v "$pip" &>/dev/null; then
        pip_exe="$pip"
        break
    fi
done
if [ -z "${pip_exe}" ]; then
    echo -e "${YELLOW}  pip not found, attempting to install via ensurepip...${NC}"
    ${PYTHON} -m ensurepip --upgrade 2>/dev/null || true
    pip_exe="${PYTHON} -m pip"
else
    pip_exe="${PYTHON} -m pip"
fi
echo -e "${GREEN}  OK${NC}"

# ── 3. Install ActivityWatch ────────────────────────────────────
echo -e "${YELLOW}[3/6] Installing ActivityWatch...${NC}"
AW_INSTALLED=0
if ${PYTHON} -c "import aw_core" 2>/dev/null; then
    echo "  ActivityWatch already installed."
    AW_INSTALLED=1
else
    echo "  Running: ${pip_exe} install --user activitywatch"
    if ${pip_exe} install --user activitywatch 2>&1; then
        echo -e "${GREEN}  ActivityWatch installed successfully.${NC}"
        AW_INSTALLED=1
    else
        echo -e "${YELLOW}  WARNING: pip install activitywatch failed.${NC}"
        echo "  SelfAnalyst will still work if ActivityWatch is running elsewhere."
        echo "  You can install it manually: pip install activitywatch"
    fi
fi

# ── 4. Build SelfAnalyst ────────────────────────────────────────
echo -e "${YELLOW}[4/6] Building SelfAnalyst...${NC}"

# Check if jar already exists in target/
JAR_SOURCE=""
TARGET_DIR="${PROJECT_DIR}/self-analyst-app/target"
if JAR_SOURCE="$(find_app_jar "${TARGET_DIR}")"; then
    echo "  Using existing build."
elif JAR_SOURCE="$(find_app_jar "${PROJECT_DIR}")"; then
    echo "  Using jar from project root."
elif command -v mvn &>/dev/null; then
    echo "  Running: mvn package -DskipTests -q"
    cd "${PROJECT_DIR}"
    if mvn package -DskipTests -q 2>&1; then
        JAR_SOURCE="$(find_app_jar "${TARGET_DIR}")"
        echo -e "${GREEN}  Build successful.${NC}"
    else
        echo -e "${RED}ERROR: Maven build failed.${NC}"
        exit 1
    fi
elif command -v mvnw &>/dev/null; then
    echo "  Running: ./mvnw package -DskipTests -q"
    cd "${PROJECT_DIR}"
    if ./mvnw package -DskipTests -q 2>&1; then
        JAR_SOURCE="$(find_app_jar "${TARGET_DIR}")"
        echo -e "${GREEN}  Build successful.${NC}"
    else
        echo -e "${RED}ERROR: Maven wrapper build failed.${NC}"
        exit 1
    fi
else
    echo -e "${RED}ERROR: Maven not found and no pre-built jar available.${NC}"
    echo "  Install Maven: https://maven.apache.org/install.html"
    echo "  Or build manually with 'mvn package -DskipTests' in the project root."
    exit 1
fi

if [ -z "${JAR_SOURCE}" ] || [ ! -f "${JAR_SOURCE}" ]; then
    echo -e "${RED}ERROR: Could not find or build self-analyst jar.${NC}"
    exit 1
fi
echo "  Jar: ${JAR_SOURCE} ($(du -h "${JAR_SOURCE}" | cut -f1))"

# ── 5. Create install directory ─────────────────────────────────
echo -e "${YELLOW}[5/6] Creating install directory...${NC}"
mkdir -p "${BIN_DIR}" "${LIB_DIR}" "${CONFIG_DIR}"
echo "  Install dir: ${INSTALL_DIR}"

# Copy jar
cp "${JAR_SOURCE}" "${LIB_DIR}/${JAR_NAME}"
echo "  Copied jar to ${LIB_DIR}/${JAR_NAME}"

# Generate config
if [ -f "${CONFIG_FILE}" ]; then
    echo "  Config file already exists, skipping."
else
    echo ""
    echo -e "${CYAN}  ── LLM API Configuration ──${NC}"
    echo "  Enter your API key (leave blank to skip):"
    read -r -p "    API Key: " API_KEY </dev/tty
    echo "  Enter API base URL [https://api.openai.com/v1]:"
    read -r -p "    Base URL: " API_BASE_URL </dev/tty
    API_BASE_URL=${API_BASE_URL:-https://api.openai.com/v1}
    echo "  Enter model name [gpt-4o]:"
    read -r -p "    Model: " API_MODEL </dev/tty
    API_MODEL=${API_MODEL:-gpt-4o}

    cat > "${CONFIG_FILE}" <<CFGEOF
# LLM configuration
llm.api-key=${API_KEY}
llm.base-url=${API_BASE_URL}
llm.model=${API_MODEL}

# ActivityWatch
aw.base-url=http://localhost:5600/api/0
aw.timeout=15000

# Memory
memory.dir=\${user.home}/.self-analyst
CFGEOF
    echo -e "${GREEN}  Config saved.${NC}"
fi

# ── 6. Generate launcher scripts ─────────────────────────────────
echo -e "${YELLOW}[6/6] Generating launcher scripts...${NC}"

# Main launcher: self-analyst.sh
cat > "${BIN_DIR}/self-analyst.sh" <<'LAUNCHER'
#!/bin/bash
INSTALL_DIR="${HOME}/.self-analyst"
JAR="${INSTALL_DIR}/lib/self-analyst-app.jar"
AW_URL="http://localhost:5600/api/0"

# Check if ActivityWatch is running; if not, start it
if ! curl -s --connect-timeout 2 "${AW_URL}/info" > /dev/null 2>&1; then
    echo "[SelfAnalyst] ActivityWatch not running, starting..."
    aw-qt &
    # Wait for AW to be ready
    for i in $(seq 1 20); do
        if curl -s --connect-timeout 2 "${AW_URL}/info" > /dev/null 2>&1; then
            echo "[SelfAnalyst] ActivityWatch connected."
            break
        fi
        sleep 0.5
    done
fi

exec java -Dfile.encoding=UTF-8 \
    -Dsun.stdout.encoding=UTF-8 \
    -Dsun.stderr.encoding=UTF-8 \
    -jar "${JAR}" "$@"
LAUNCHER
chmod +x "${BIN_DIR}/self-analyst.sh"

# AW starter
cat > "${BIN_DIR}/start-aw.sh" <<'AWSTART'
#!/bin/bash
echo "Starting ActivityWatch..."
aw-qt &
sleep 2
if curl -s --connect-timeout 2 "http://localhost:5600/api/0/info" > /dev/null 2>&1; then
    echo "ActivityWatch is running on http://localhost:5600"
else
    echo "WARNING: ActivityWatch may not have started. Check logs."
fi
AWSTART
chmod +x "${BIN_DIR}/start-aw.sh"

# AW stopper
cat > "${BIN_DIR}/stop-aw.sh" <<'AWSTOP'
#!/bin/bash
echo "Stopping ActivityWatch..."
pkill -f "aw-qt" 2>/dev/null || true
pkill -f "aw-server" 2>/dev/null || true
echo "ActivityWatch stopped."
AWSTOP
chmod +x "${BIN_DIR}/stop-aw.sh"

# Uninstaller
cat > "${BIN_DIR}/uninstall.sh" <<'UNINSTALL'
#!/bin/bash
set -euo pipefail
INSTALL_DIR="${HOME}/.self-analyst"
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}SelfAnalyst Uninstaller${NC}"
echo ""

# Stop processes
echo "Stopping processes..."
pkill -f "aw-qt" 2>/dev/null || true
pkill -f "aw-server" 2>/dev/null || true
echo "  Processes stopped."

# Ask about keeping memory
echo ""
echo "Do you want to keep your memory data (goals, patterns, logs)?"
read -r -p "  Keep memory.json? [Y/n]: " KEEP
KEEP=${KEEP:-Y}

if [[ "${KEEP}" =~ ^[Yy] ]]; then
    if [ -f "${INSTALL_DIR}/memory.json" ]; then
        cp "${INSTALL_DIR}/memory.json" "${HOME}/.self-analyst-memory-backup.json"
        echo -e "${GREEN}  Memory saved to ~/.self-analyst-memory-backup.json${NC}"
    fi
fi

# Remove install dir
echo "Removing ${INSTALL_DIR} ..."
rm -rf "${INSTALL_DIR}"

echo ""
echo -e "${GREEN}Uninstall complete.${NC}"
echo ""
echo "Manual cleanup (if desired):"
echo "  - Remove ~/.self-analyst-memory-backup.json if you don't need it"
echo "  - Remove ~/bin/self-analyst symlink if you created one"
echo "  - ActivityWatch can be uninstalled with: pip uninstall activitywatch"
UNINSTALL
chmod +x "${BIN_DIR}/uninstall.sh"

echo -e "${GREEN}  Launcher scripts generated.${NC}"

# ── Done ─────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}==========================================${NC}"
echo -e "${GREEN}  SelfAnalyst installation complete!${NC}"
echo -e "${GREEN}==========================================${NC}"
echo ""
echo "  Install location: ${INSTALL_DIR}"
echo "  Launcher:         ${BIN_DIR}/self-analyst.sh"
echo ""
echo -e "${CYAN}  To add to PATH (optional):${NC}"
echo "    echo 'export PATH=\"${BIN_DIR}:\$PATH\"' >> ~/.bashrc"
echo "    source ~/.bashrc"
echo ""
echo -e "${CYAN}  Quick start:${NC}"
echo "    ${BIN_DIR}/self-analyst.sh"
echo ""
echo -e "${CYAN}  Uninstall:${NC}"
echo "    ${BIN_DIR}/uninstall.sh"
