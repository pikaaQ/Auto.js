#!/bin/bash
# detect_root_dir.sh - 探测 AutoX.js 手机端脚本根目录
# =====================================================
# 原理: run 命令执行时 working directory = Pref.getScriptDirPath(),
# 用 files.cwd() 拿到该路径写入临时文件，再 pull_file 拉回 PC。
#
# 用法:
#   bash devtools/detect_root_dir.sh --call "python3 path/to/call.py" [--port PORT]
#
# 选项:
#   --call  必需。call.py 的调用命令，如 "python3 /path/to/call.py"
#   --port  WebSocket 端口，默认 9317，控制端口自动推导为 +10000
#
# 输出:
#   stdout: 脚本根目录路径，如 /storage/emulated/0/脚本
#   stderr: 执行过程日志
#   退出码 0 表示探测成功（含 fallback 到默认路径）
#
# 清理:
#   脚本成功后自动删除:
#   - 手机: /sdcard/.sdir.txt（探测文件）
#   - PC:   server.py workspace 中的 .sdir.txt 副本

set -euo pipefail

# ── 参数解析 ─────────────────────────────────────

CALL=""
PORT=9317

while [[ $# -gt 0 ]]; do
  case "$1" in
    --call)
      CALL="$2"
      shift 2
      ;;
    --port)
      PORT="$2"
      shift 2
      ;;
    *)
      echo "错误: 未知参数 $1" >&2
      echo "用法: bash detect_root_dir.sh --call \"python3 path/to/call.py\" [--port PORT]" >&2
      exit 1
      ;;
  esac
done

if [ -z "$CALL" ]; then
  echo "错误: 必须指定 --call 参数" >&2
  echo "用法: bash detect_root_dir.sh --call \"python3 path/to/call.py\" [--port PORT]" >&2
  exit 1
fi

# ── 临时文件（脚本退出自动清理） ────────────────

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
PULL_RESULT="$TMP_DIR/pull_result.json"

# ── Step 1: 推送探测脚本 ────────────────────────

echo ">>> [1/3] 推送探测脚本到手机 ..." >&2

# run 命令的 name 仅作标识，不会在手机文件系统创建文件
$CALL '{"cmd":"run","script":"files.write(\"/sdcard/.sdir.txt\", files.cwd());","name":".detect_sdir.js","wait":false}' --port "$PORT" > /dev/null 2>&1 || true

# 等手机端的 files.write 完成
sleep 1.5

# ── Step 2: 拉取探测结果 ────────────────────────

echo ">>> [2/3] 拉取探测结果 ..." >&2

$CALL "{\"cmd\":\"pull_file\",\"path\":\"/sdcard/.sdir.txt\"}" --port "$PORT" > "$PULL_RESULT" 2>&1 || {
  echo "警告: pull_file 命令失败，使用默认路径" >&2
  DIR_PATH="/storage/emulated/0/脚本"
}

if [ -z "${DIR_PATH:-}" ]; then
  LOCAL_FILE=$(python3 -c "
import json, sys
try:
    data = json.load(open('$PULL_RESULT'))
    print(data.get('result', {}).get('local_path', ''))
except Exception:
    print('')
" 2>/dev/null || echo "")

  if [ -n "$LOCAL_FILE" ] && [ -f "$LOCAL_FILE" ]; then
    DIR_PATH=$(cat "$LOCAL_FILE" 2>/dev/null || echo "")
    # 清理 PC 上 server.py workspace 中的 .sdir.txt 副本
    rm -f "$LOCAL_FILE" 2>/dev/null || true
  fi
fi

# Fallback 到默认路径
if [ -z "${DIR_PATH:-}" ]; then
  DIR_PATH="/storage/emulated/0/脚本"
  echo "警告: 拉取结果为空，使用默认路径: $DIR_PATH" >&2
fi

echo ">>> 脚本根目录: $DIR_PATH" >&2

# ── Step 3: 清理手机临时文件 ────────────────────

echo ">>> [3/3] 清理手机临时文件 ..." >&2

# 删除 /sdcard/.sdir.txt（try-catch 避免文件不存在时报错）
$CALL '{"cmd":"run","script":"try{files.remove(\"/sdcard/.sdir.txt\")}catch(e){}","name":".cleanup.js","wait":false}' --port "$PORT" > /dev/null 2>&1 || true
sleep 0.3

# 清理脚本自身（run 命令不保存文件，但 name 可能被 auto.js 缓存到 cwd）
$CALL '{"cmd":"run","script":"try{files.remove(files.cwd()+\"/.cleanup.js\")}catch(e){}","name":".cleanup_self.js","wait":false}' --port "$PORT" > /dev/null 2>&1 || true

echo ">>> 探测与清理完成 ✓" >&2

# ── 输出结果（仅路径，供调用方捕获） ────────────

echo "$DIR_PATH"
