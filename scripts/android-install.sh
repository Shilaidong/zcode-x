#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Zcode-x Android (Termux) 一键安装与配置脚本
#
# 使用方式:
#   curl -fsSL https://raw.githubusercontent.com/Shilaidong/zcode-x/main/scripts/android-install.sh | bash
# ==============================================================================

set -euo pipefail

RELEASE_VERSION="${ZCODE_VERSION:-3.14.3}"
RELEASE_TAG="v${RELEASE_VERSION}-x"
GITHUB_REPO="Shilaidong/zcode-x"
TARBALL_NAME="zcode-${RELEASE_VERSION}.tar.gz"
DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${RELEASE_TAG}/${TARBALL_NAME}"

# 颜色与输出格式
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

info() {
  echo -e "${BLUE}[Zcode-x]${NC} $1"
}

success() {
  echo -e "${GREEN}[Zcode-x]${NC} $1"
}

warn() {
  echo -e "${YELLOW}[Zcode-x 注意]${NC} $1"
}

error() {
  echo -e "${RED}[Zcode-x 错误]${NC} $1" >&2
}

info "开始安装 Zcode-x for Android..."

# 1. 检查 Termux 环境
IS_TERMUX=false
if [ -d "/data/data/com.termux" ] || [ -n "${TERMUX_VERSION:-}" ]; then
  IS_TERMUX=true
fi

if [ "$IS_TERMUX" = false ] && [ ! -f "/etc/os-release" ]; then
  warn "未检测到 Termux 或标准 Linux 环境，脚本将以通用模式尝试安装。"
fi

# 2. 如果在 Termux 原生环境下，安装并配置 proot-distro (Ubuntu ARM64)
if [ "$IS_TERMUX" = true ] && [ ! -f "/.dockerenv" ] && [ ! -f "/etc/proot-distro" ]; then
  info "检测到运行在 Termux 原生宿主中。"
  info "正在准备 Termux 基础包 (proot-distro, curl, tar)..."

  pkg update -y || apt-get update -y
  pkg install -y proot-distro curl tar || apt-get install -y proot-distro curl tar

  # 检查是否已安装 ubuntu
  if ! proot-distro list | grep -q "ubuntu (installed)"; then
    info "正在安装 Ubuntu (ARM64 glibc 环境，用于提供标准的 POSIX 与 Node.js 运行时)..."
    proot-distro install ubuntu
  else
    info "Ubuntu (proot-distro) 已就绪。"
  fi

  # 将安装脚本注入到 Ubuntu 容器内执行核心安装
  info "正在配置 Ubuntu 环境及 Node.js 运行时..."
  proot-distro login ubuntu -- bash -c "
    set -euo pipefail
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y curl tar git ca-certificates xz-utils

    # 安装 Node.js 22 LTS (若未安装)
    if ! command -v node >/dev/null 2>&1; then
      echo '[Ubuntu] 正在安装 Node.js 22 LTS...'
      curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
      apt-get install -y nodejs
    fi

    # 安装 ripgrep
    if ! command -v rg >/dev/null 2>&1; then
      apt-get install -y ripgrep || true
    fi

    # 部署 Zcode-x
    INSTALL_DIR=\"/root/.zcode-x/runtime\"
    BIN_DIR=\"/usr/local/bin\"
    mkdir -p \"\$INSTALL_DIR/releases/${RELEASE_VERSION}\" \"\$BIN_DIR\"

    echo '[Ubuntu] 正在下载 Zcode-x 核心发行包...'
    TMP_DIR=\$(mktemp -d)
    ARCHIVE=\"\$TMP_DIR/${TARBALL_NAME}\"
    curl -fL \"${DOWNLOAD_URL}\" -o \"\$ARCHIVE\"

    echo '[Ubuntu] 正在解压安装...'
    TARGET=\"\$INSTALL_DIR/releases/${RELEASE_VERSION}\"
    rm -rf \"\$TARGET.new\"
    mkdir -p \"\$TARGET.new\"
    tar -xzf \"\$ARCHIVE\" -C \"\$TARGET.new\"
    rm -rf \"\$TARGET\"
    mv \"\$TARGET.new/zcode\" \"\$TARGET\"
    rm -rf \"\$TARGET.new\" \"\$TMP_DIR\"
    ln -sfn \"\$TARGET\" \"\$INSTALL_DIR/current\"

    # 创建可执行命令
    cat > \"\$BIN_DIR/zcode-x\" <<'SH'
#!/usr/bin/env sh
export ZCODE_PRODUCT_FLAVOR=\"zcode-x\"
exec node \"/root/.zcode-x/runtime/current/bin/zcode.mjs\" \"\$@\"
SH
    chmod +x \"\$BIN_DIR/zcode-x\"
    ln -sf \"\$BIN_DIR/zcode-x\" \"\$BIN_DIR/zcode\"
    echo '[Ubuntu] Zcode-x 核心已就绪！'
  "

  # 在 Termux 宿主注册全局快捷指令 `zcode-x`
  TERMUX_BIN="$PREFIX/bin/zcode-x"
  mkdir -p "$PREFIX/bin"
  cat > "$TERMUX_BIN" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Zcode-x Termux 入口
# ==============================================================================
if [ "$1" = "web" ] || [ "$1" = "start" ]; then
  shift || true
  echo "========================================================"
  echo "🚀 Zcode-x Web 工作区服务正在启动..."
  echo "📱 手机或平板浏览器请访问: http://127.0.0.1:3030"
  echo "🌐 同局域网电脑亦可访问对应 IP 地址"
  echo "💡 提示: 按 Ctrl+C 停止服务"
  echo "========================================================"
  exec proot-distro login ubuntu -- zcode-x --web --host 0.0.0.0 --port 3030 --no-open "$@"
else
  exec proot-distro login ubuntu -- zcode-x "$@"
fi
EOF
  chmod +x "$TERMUX_BIN"
  ln -sf "$TERMUX_BIN" "$PREFIX/bin/zcode"

  echo ""
  success "🎉 Zcode-x 在 Android (Termux) 环境下安装成功！"
  echo ""
  echo "--------------------------------------------------------"
  echo "常用命令："
  echo "  1. 启动 Web 界面版（推荐在浏览器中使用）："
  echo -e "     ${GREEN}zcode-x web${NC}"
  echo "     然后在手机浏览器中打开: http://127.0.0.1:3030"
  echo ""
  echo "  2. 在终端中使用命令行 Agent (TUI)："
  echo -e "     ${GREEN}zcode-x${NC}"
  echo ""
  echo "  3. 进入完整的 Ubuntu 终端工作环境："
  echo -e "     ${GREEN}proot-distro login ubuntu${NC}"
  echo "--------------------------------------------------------"
  echo ""
  warn "后台保活建议："
  echo "  - 在 Termux 中执行: termux-wake-lock (防止手机锁屏休眠网络)"
  echo "  - 系统设置 -> 电池 -> 关闭对 Termux 的后台耗电限制与省电优化"
  echo "  - Android 12+ 若遇到多进程被杀，可在开发者选项中关闭 Phantom 进程限制"
  echo ""
  exit 0
fi

# 3. 如果直接在标准 Linux (如 proot 内部或普通 Linux ARM64) 下执行
info "在标准 Linux 环境中执行安装..."
INSTALL_DIR="${ZCODE_DIST_HOME:-$HOME/.zcode-x/runtime}"
BIN_DIR="${ZCODE_DIST_BIN_DIR:-/usr/local/bin}"
[ -w "$BIN_DIR" ] || BIN_DIR="$HOME/.local/bin"

mkdir -p "$INSTALL_DIR/releases/${RELEASE_VERSION}" "$BIN_DIR"

info "下载发行包: ${DOWNLOAD_URL} ..."
TMP_DIR=$(mktemp -d)
ARCHIVE="$TMP_DIR/${TARBALL_NAME}"
curl -fL "${DOWNLOAD_URL}" -o "$ARCHIVE"

info "解压并安装..."
TARGET="$INSTALL_DIR/releases/${RELEASE_VERSION}"
rm -rf "$TARGET.new"
mkdir -p "$TARGET.new"
tar -xzf "$ARCHIVE" -C "$TARGET.new"
rm -rf "$TARGET"
mv "$TARGET.new/zcode" "$TARGET"
rm -rf "$TARGET.new" "$TMP_DIR"
ln -sfn "$TARGET" "$INSTALL_DIR/current"

cat > "$BIN_DIR/zcode-x" <<SH
#!/usr/bin/env sh
export ZCODE_PRODUCT_FLAVOR="zcode-x"
exec node "$INSTALL_DIR/current/bin/zcode.mjs" "\$@"
SH
chmod +x "$BIN_DIR/zcode-x"
ln -sf "$BIN_DIR/zcode-x" "$BIN_DIR/zcode"

success "Zcode-x ${RELEASE_VERSION} 安装完成！"
echo "运行 'zcode-x web' 启动网页版，或 'zcode-x' 启动终端版。"
