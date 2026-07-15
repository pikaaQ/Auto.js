#!/usr/bin/env bash
set -e

# AutoX.js Connector Skill - Global Installer
# Installs the skill to opencode's global skill directory.
# After installation, the skill is available in ALL opencode projects.

SKILL_NAME="autoxjs-connector"
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"
GLOBAL_DIR="${HOME}/.config/opencode/skills/${SKILL_NAME}"

echo "🔌 AutoX.js Connector Skill - 全局安装"
echo ""
echo "来源: ${SOURCE_DIR}"
echo "目标: ${GLOBAL_DIR}"
echo ""

# Create global skills directory if needed
mkdir -p "${HOME}/.config/opencode/skills"

# Check if already installed
if [ -e "${GLOBAL_DIR}" ]; then
    echo "⚠️  检测到已存在的安装: ${GLOBAL_DIR}"
    read -p "是否覆盖? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "❌ 安装已取消"
        exit 1
    fi
    rm -rf "${GLOBAL_DIR}"
fi

# Create symlink (preferred - stays in sync with project updates)
if command -v ln &> /dev/null; then
    ln -s "${SOURCE_DIR}" "${GLOBAL_DIR}"
    echo "✅ 符号链接已创建: ${GLOBAL_DIR} → ${SOURCE_DIR}"
else
    # Fallback: copy
    cp -r "${SOURCE_DIR}" "${GLOBAL_DIR}"
    echo "✅ 已复制到: ${GLOBAL_DIR}"
fi

echo ""
echo "📋 安装完成！使用方式："
echo ""
echo "  1. 重启 opencode（技能在启动时加载，不热重载）"
echo "  2. 在任意 opencode 项目中，当检测到 AutoX.js 项目且需要手机端操作时，"
echo "     技能将自动激活。"
echo ""
echo "  首次使用："
echo "    pip install websockets"
echo "    python3 ${SOURCE_DIR}/server.py --port 9317 --host 0.0.0.0"
echo ""
echo "  卸载："
echo "    rm -rf ${GLOBAL_DIR}"
echo ""
