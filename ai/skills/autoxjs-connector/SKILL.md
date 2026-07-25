---
name: autoxjs-connector
description: "AutoX.js 手机连接协议。管理 WebSocket 连接、发送协议命令、文件传输。负责与 AutoX.js App 的通信层。不涉及脚本开发与调试。Triggers: 当需要连接手机、检查连接状态、发送基础命令(screenshot/dump/exec/run/pull_file)时自动激活。"
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
bash ${skill_base_dir}/autoxjs-connector/install.sh

# 方法二：手动复制
mkdir -p ~/.config/opencode/skills
cp -r ${skill_base_dir}/autoxjs-connector ~/.config/opencode/skills/
```

安装后**重启 opencode** 使技能生效。

## 激活条件

当需要以下操作时自动激活：

1. 连接/断开手机
2. 检查连接状态
3. 发送基础协议命令（截屏、dump、exec、run、pull_file、run_project、save_project）
4. 拉取/推送文件

**脚本开发、调试、诊断等场景应使用 `autoxjs-developer` 技能。**

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

**当需要连接手机时，必须按以下步骤执行。**

### Step 1: 检查并启动 Server

server 是常驻后台进程，一旦启动将持续运行，不会随任务结束或连接断开而关闭。

先检查服务端是否已在运行：
```bash
python3 "${skill_base_dir}/autoxjs-connector/server.py" --send '{"cmd":"status"}' --port 9317
```

- **返回了有效状态**（包含 `"ws":` 等字段）→ 服务端已在运行且状态正常，**不要重启**，直接跳到 Step 3（引导用户连接）
- **命令失败或返回异常** → 说明服务端未运行或状态异常；或本次任务修改了 `server.py` / 协议 / 连接相关代码 → 此时才执行下面的启动命令：

```bash
nohup python3 "${skill_base_dir}/autoxjs-connector/server.py" --port 9317 --host 0.0.0.0 > /tmp/autoxjs-server.log 2>&1 &
```

验证启动：
```bash
python3 "${skill_base_dir}/autoxjs-connector/server.py" --send '{"cmd":"status"}' --port 9317
```
输出应包含 `"ws": "ws://0.0.0.0:9317"`。若失败则报错并中止流程（但 **不关闭 server**，若已部分启动则保持运行）。

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
python3 "${skill_base_dir}/autoxjs-connector/server.py" --send '{"cmd":"status"}' --port 9317
```

解析返回 JSON：
- **`"connected": true`** → 连接成功，输出设备信息并进入操作阶段
- **`"connected": false`** → 服务端已在运行但手机未连接，**不要重启 server**，用 `question` 工具让用户选择：
  ```
  header: "📱 连接失败"
  question: "手机未连接。请确认：\n1. 手机和 PC 在同一网络\n2. 输入了正确的 IP: {IP}:9317\n3. 已点击「连接电脑」按钮"
  options:
    - label: "重试"
      description: "重新操作后点击"
    - label: "取消"
      description: "终止当前任务"
  ```

## 原子操作

所有命令通过 `python3 "${skill_base_dir}/autoxjs-connector/server.py" --send '<json>' --port 9317` 发送。

| 操作 | 命令 | 说明 |
|------|------|------|
| 查询状态 | `{"cmd":"status"}` | 返回连接状态和设备信息 |
| 截屏 | `{"cmd":"screenshot"}` | 截取手机屏幕，返回 `local_path` |
| 获取组件树 | `{"cmd":"dump"}` | 获取当前界面 UI 组件树 (XML) |
| 执行 JS | `{"cmd":"exec","script":"..."}` | 在手机执行 JS（**不会返回值**，见下方提示） |
| 推送脚本 | `{"cmd":"run","script":"...","name":"x.js","wait":false}` | 推送并执行脚本（fire-and-forget） |
| 拉取文件 | `{"cmd":"pull_file","path":"..."}` | 拉取手机文件到 `phone_data/` |
| 保存项目 | `{"cmd":"save_project","project_dir":"..."}` | 推送项目目录到手机（仅保存，不执行） |
| 运行项目 | `{"cmd":"run_project","project_dir":"..."}` | 推送项目目录到手机并远程执行 |

### exec 命令的局限性（重要）

**`exec` 的 `result` 始终为空 `{}`。** 经源码验证 `onSuccess` 的 result 参数始终为 null，无法获取返回值。需要返回值的场景请用 **`run` fire-and-forget + 写文件 → `pull_file`**。

- ✅ `exec` 可触发副作用：toast、文件写入、console.log
- ❌ `exec` 不可获取任何返回值

## 连接断开处理

如果操作过程中连接断开（命令返回 `"手机未连接"`）：
1. **不要重启 server** — 服务端本身运行正常，只是手机端断开
2. 用 `question` 工具引导用户重新连接手机
3. 验证连接成功后再继续
4. 如果用户选择取消，终止任务

## 终止

任务完成或用户终止时：
1. **不要 shutdown server** — server 为常驻进程，应持续运行供后续任务复用
2. 告知用户已断开（server 仍在后台运行，下次任务自动复用）

## 协议参考

完整协议文档见 `references/protocol.md`。

## TCP 控制接口

所有命令通过 TCP 控制端口（19317）发送 JSON 行，接收 JSON 行响应。

也可通过 `--send` 命令行快捷发送，`--port` 会自动推导控制端口（port + 10000）：
```bash
python3 "${skill_base_dir}/autoxjs-connector/server.py" --send '{"cmd":"screenshot"}' --port 9317
```

### TCP 直连模板

```python
import json, socket, time

def ctrl(cmd_data, timeout=15):
    s = socket.socket(); s.settimeout(timeout)
    s.connect(("127.0.0.1", 19317))
    s.sendall((json.dumps(cmd_data) + "\n").encode())
    time.sleep(0.5)
    resp = s.recv(65535)
    s.close()
    return json.loads(resp.decode())
```

可用命令列表：

| cmd | 参数 | 说明 |
|-----|------|------|
| status | - | 查询连接状态 |
| command | command, params, wait | 发送原始命令（wait=true 等待结果 / false 即发即走） |
| run | script, name, wait | 推送执行脚本（默认 wait=false，fire-and-forget） |
| exec | script, wait | 执行 JS 并返回结果（**始终返回空 result**） |
| screenshot | - | 截图并保存到本地 |
| dump | - | 获取 UI 组件树 |
| pull_file | path | 拉取手机文件 |
| save_project | project_dir | 推送项目到手机（仅保存，不执行） |
| run_project | project_dir | 推送项目到手机并执行 |
| wait | timeout | 等待指定秒数（用于同步） |
| shutdown | - | 停止服务端（仅当用户明确要求关闭时使用；不要自动调用） |
