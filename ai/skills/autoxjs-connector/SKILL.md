---
name: autoxjs-connector
description: "AutoX.js 手机自动化开发助手。自动检测当前项目是否为基于 AutoX.js 构建的手机自动化项目，当任务需要探索手机端或调试时，自动启动 WebSocket 服务端并引导用户连接手机。Triggers: 当项目是基于 AutoX.js 构建的手机自动化项目且当前任务涉及截图、UI分析、脚本调试、手机文件操作等需要手机端的场景时自动激活。"
---

# AutoX.js 手机连接器

## 安装

### 前置依赖

```bash
pip install websockets
```

### 全局安装（推荐）

技能默认只在当前项目加载。全局安装后可在所有 opencode 项目中使用：

```bash
# 方法一：使用安装脚本（推荐 — 符号链接，与项目源码保持同步）
bash ai/skills/autoxjs-connector/install.sh

# 方法二：手动复制
mkdir -p ~/.config/opencode/skills
cp -r ai/skills/autoxjs-connector ~/.config/opencode/skills/
```

安装后**重启 opencode** 使技能生效。

### 只对当前项目生效（无需安装，不推荐）

技能源码在本项目的 `ai/skills/autoxjs-connector/` 下。要让 opencode 发现它，需要全局安装（见上方）或将 `ai/skills` 添加到 opencode 配置的 `skills.paths` 中。

### 验证安装

```bash
# 重启 opencode 后，检查技能是否可用：
# 在 opencode 中运行：
skill(name="autoxjs-connector")
# 应返回 skill 内容而不是 "not found"
```

## 激活条件

当 **同时满足** 以下条件时，自动按流程执行：
1. 当前项目是基于 AutoX.js 构建的手机自动化项目
2. 当前任务需要手机端参与，如：截图、UI 组件树分析、脚本调试、推送脚本到手机、拉取手机文件

不需要用户显式说出触发词。AI 根据上下文自行判断是否需要连接手机。

## 用户交互规则（MANDATORY）

**所有需要用户操作的场景，必须使用 `question` 工具，只提供一个操作按钮。**

正确模式：
```
question:
  header: "📱 xxx"
  question: "操作步骤说明..."
  options:
    - label: "我已连接"    ← 唯一选项
      description: "..."
```

禁止使用 `ask` 或自然语言等待用户回复。必须用 `question` 工具。

## 连接流程（MANDATORY）

**当激活条件满足时，必须按以下步骤执行。**

### Step 1: 启动 Server

使用 `nohup` 在后台启动 WebSocket 服务端：
```bash
nohup python3 ai/skills/autoxjs-connector/server.py --port 9317 --host 0.0.0.0 > /tmp/autoxjs-server.log 2>&1 &
```

验证启动：
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"status"}' --port 9317
```
输出应包含 `"ws": "ws://0.0.0.0:9317"`。若失败则报错并终止。

### Step 2: 检测本机 IP

```bash
hostname -I | awk '{print $1}'
```

取第一个 IP 作为对外 IP（通常是内网 IP，如 `192.168.x.x`）。

### Step 3: 引导用户连接（question 强制）

使用 `question` 工具，只提供 **一个选项** "我已连接"：

```
header: "📱 连接 AutoX.js 手机"
question: >
  请在手机上操作：
  1. 打开 AutoX.js App
  2. 侧拉菜单 → 连接电脑
  3. 输入 IP: {IP}:9317
  4. 点击连接

  连接后点击下方「我已连接」继续。
options:
  - label: "我已连接"
    description: "确认手机已连接后点击继续"
```

### Step 4: 验证连接

用户点击"我已连接"后，检查连接状态：
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"status"}' --port 9317
```

解析返回 JSON：
- **`"connected": true`** → 连接成功，输出设备信息并进入操作阶段
- **`"connected": false`** → 用 `question` 工具让用户选择：
  ```
  header: "📱 连接失败"
  question: "手机未连接。请确认：\n1. 手机和 PC 在同一网络\n2. 输入了正确的 IP: {IP}:9317\n3. 已点击「连接电脑」按钮"
  options:
    - label: "重试"
      description: "重新操作后点击"
    - label: "取消"
      description: "终止当前任务"
  ```

### Step 5: 操作阶段

连接成功后，根据原始任务目标执行操作。

## 原子操作

所有命令通过 `--send` 发送，端口统一用 9317。

### 截图
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"screenshot"}' --port 9317
```
返回包含 `local_path`（本地保存路径）。截图后可配合 `look_at` 分析截图内容。

### 分析界面（截图 + 组件树）

先截图 → 再用 `look_at` 分析截图 → 同时 dump 组件树：
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"dump"}' --port 9317
```

### 执行脚本（等待结果）
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"exec","script":"console.log(\"hello\")"}' --port 9317
```

### 推送脚本（fire-and-forget）
标准 VSCode 协议命令，手机不回 `command_result`，必须用 `"wait":false`：
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"run","script":"...","name":"test.js","wait":false}' --port 9317
```

### 拉取文件
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"pull_file","path":"/sdcard/screenshot.png"}' --port 9317
```

## 连接断开处理

如果操作过程中连接断开（命令返回 `"手机未连接"`）：
1. 用 `question` 工具引导用户重新连接
2. 验证连接成功后再继续
3. 如果用户选择取消，终止任务

## 终止

任务完成或用户终止时：
1. 发 shutdown 命令：`` python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"shutdown"}' --port 9317 ``
2. 告知用户已断开

## 协议参考

完整协议文档见 `references/protocol.md`。

## TCP 控制接口

所有命令通过 TCP 控制端口（19317）发送 JSON 行，接收 JSON 行响应。

也可通过 `--send` 命令行快捷发送，`--port` 会自动推导控制端口（port + 10000）：
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"screenshot"}' --port 9317
```

可用命令列表：
| cmd | 参数 | 说明 |
|-----|------|------|
| status | - | 查询连接状态 |
| command | command, params, wait | 发送原始命令（wait=true 等待结果 / false 即发即走） |
| run | script, name, wait | 推送执行脚本（默认 wait=false） |
| exec | script, wait | 执行 JS 并返回结果（默认 wait=true） |
| screenshot | - | 截图并保存到本地 |
| dump | - | 获取 UI 组件树 |
| pull_file | path | 拉取手机文件 |
| push_project | project_dir | 推送项目到手机 |
| shutdown | - | 停止服务端 |
