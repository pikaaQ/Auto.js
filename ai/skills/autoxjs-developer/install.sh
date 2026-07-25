#!/usr/bin/env bash
set -e

# AutoX.js Developer Skill - Global Installer
# Installs the skill to opencode's global skill directory.
# After installation, the skill is available in ALL opencode projects.
#
# ⚠️ 本技能依赖 autoxjs-connector 提供手机连接能力，
#    请确保 autoxjs-connector 已全局安装。

SKILL_NAME="autoxjs-developer"
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"
GLOBAL_DIR="${HOME}/.config/opencode/skills/${SKILL_NAME}"

echo "🤖 AutoX.js Developer Skill - 全局安装"
echo ""
echo "来源: ${SOURCE_DIR}  (项目路径: ai/skills/autoxjs-developer/)"
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
echo "  2. 确保 autoxjs-connector 也已全局安装"
echo "  3. 在任意 autoX.js 项目中，当需要编写/调试/推送脚本时，"
echo "     技能将自动激活。"
echo "  4. 编写脚本时可参考 ${SOURCE_DIR}/sample/ 下的 API 示例。"
echo ""
echo "  卸载："
echo "    rm -rf ${GLOBAL_DIR}"
echo ""
