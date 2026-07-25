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
echo "来源: ${SOURCE_DIR}  (项目路径: ai/skills/autoxjs-connector/)"
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

# 复制到全局目录（使用 copy 而非 symlink，确保 skill base directory 正确解析）
cp -r "${SOURCE_DIR}" "${GLOBAL_DIR}"
echo "✅ 已安装到: ${GLOBAL_DIR}"

echo ""
echo "📋 安装完成！使用方式："
echo ""
echo "  1. 重启 opencode（技能在启动时加载，不热重载）"
echo "  2. 在任意 opencode 项目中，当检测到 AutoX.js 项目且需要手机端操作时，"
echo "     技能将自动激活。"
echo ""
echo "  首次使用："
echo "    pip install websockets"
echo "    python3 ${GLOBAL_DIR}/server.py --port 9317 --host 0.0.0.0"
echo ""
echo "  卸载："
echo "    rm -rf ${GLOBAL_DIR}"
echo ""
