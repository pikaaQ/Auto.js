---
name: autoxjs-connector
description: "AutoX.js 手机自动化开发助手。自动检测当前项目是否为基于 AutoX.js 构建的手机自动化项目，当任务需要探索手机端或调试时，先检查 WebSocket 服务端状态；server 为常驻后台进程，一旦启动持续运行，除非用户明确要求关闭。即使任务结束、连接断开也不自动关闭 server。Triggers: 当项目是基于 AutoX.js 构建的手机自动化项目且当前任务涉及截图、UI分析、脚本调试、手机文件操作等需要手机端的场景时自动激活。"
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

### Step 1: 检查并启动 Server

server 是常驻后台进程，一旦启动将持续运行，不会随任务结束或连接断开而关闭。

先检查服务端是否已在运行：
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"status"}' --port 9317
```

- **返回了有效状态**（包含 `"ws":` 等字段）→ 服务端已在运行且状态正常，**不要重启**，直接跳到 Step 3（引导用户连接）
- **命令失败或返回异常** → 说明服务端未运行或状态异常；或本次任务修改了 `server.py` / 协议 / 连接相关代码 → 此时才执行下面的启动命令：

```bash
nohup python3 ai/skills/autoxjs-connector/server.py --port 9317 --host 0.0.0.0 > /tmp/autoxjs-server.log 2>&1 &
```

验证启动：
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"status"}' --port 9317
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
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"status"}' --port 9317
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

### Step 5: 寻找 App 脚本根目录

**如果后续任务涉及拉取日志或手机上的项目文件，发现没有缓存根目录时, 必须先重新执行此步骤。** 

#### 探测方法

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

# Step A: 推送探测脚本（fire-and-forget）
# run 执行时 working directory = Pref.getScriptDirPath()
# 因此 files.cwd() 就是实际脚本目录
ctrl({"cmd": "run", "script": 'files.write("/sdcard/.sdir.txt", files.cwd());',
      "name": ".detect_sdir.js", "wait": False})
time.sleep(1.5)

# Step B: 拉取探测结果
result = ctrl({"cmd": "pull_file", "path": "/sdcard/.sdir.txt"})
dir_path = "/storage/emulated/0/脚本"           # 中文默认兜底
if result.get("success"):
    with open(result["result"]["local_path"]) as f:
        dir_path = f.read().strip()

print(f"脚本根目录: {dir_path}")                # 记下来，后续复用
```

#### 缓存规则

> ⚡ **`dir_path` 缓存在Agent的当前session记忆中。** 第一次探测到后记下来，后续所有拉日志、拉文件操作直接使用，不许每次都探测。


### Step 6: 操作阶段

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

### 拉取日志
```bash
# {dir_path} 来自 Step 5 探测的脚本根目录（同一 session 已缓存）
# 日志文件实际路径格式：`{dir_path}/.logs/autojs-log4j[-debug].txt`
# 优先尝试 release 版本
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"pull_file","path":"{dir_path}/.logs/autojs-log4j.txt"}' --port 9317
# 若不存在则尝试 release 版本
# python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"pull_file","path":"{dir_path}/.logs/autojs-log4j-debug.txt"}' --port 9317
```

## 常用命令模板（经过实战验证）

以下是在本项目中验证过的可靠操作方式。推荐使用 TCP 直连方式（绕过 `--send`，对大脚本更稳定）。

### 推送并保存脚本到手机
```python
import json, socket, time
script = open('本地脚本.js', encoding='utf-8').read()
payload = json.dumps({"cmd": "command", "command": "save",
    "params": {"name": "手机端名称.js", "script": script}, "wait": False})
s = socket.socket(); s.settimeout(15)
s.connect(("127.0.0.1", 19317))
s.sendall((payload + "\n").encode())
time.sleep(1.5)
resp = s.recv(65535)
print(resp.decode()); s.close()
```

### 推送并自动执行（不会保存到手机）
```python
import json, socket, time
script = open('本地脚本.js', encoding='utf-8').read()
payload = json.dumps({"cmd": "command", "command": "run",
    "params": {"name": "手机端名称.js", "script": script}, "wait": True})
s = socket.socket(); s.settimeout(60)  # 超时设长，等待脚本执行完毕
s.connect(("127.0.0.1", 19317))
s.sendall((payload + "\n").encode())
time.sleep(3)  # 给执行留缓冲
resp = s.recv(65535)
print(resp.decode()); s.close()
# 此时日志已写入手机，可立即 pull_file
```

### 远程启动已保存的脚本
```python
import json, socket, time
payload = json.dumps({"cmd": "command", "command": "run",
    "params": {"name": "手机端名称.js"}, "wait": True})
s = socket.socket(); s.settimeout(60)
s.connect(("127.0.0.1", 19317))
s.sendall((payload + "\n").encode())
time.sleep(3)
resp = s.recv(65535); print(resp.decode()); s.close()
```

### 拉取手机文件（使用 TCP 直连）
```python
import json, socket, time
# {dir_path} 来自 Step 5 探测的脚本根目录
payload = json.dumps({"cmd": "pull_file", "path": f"{dir_path}/.logs/autojs-log4j-debug.txt"})
s = socket.socket(); s.settimeout(15)
s.connect(("127.0.0.1", 19317))
s.sendall((payload + "\n").encode())
time.sleep(2)
resp = s.recv(65535)
# 响应中 local_path 指向本地保存的文件
print(json.loads(resp.decode()).get("result", {}).get("local_path", ""));
s.close()
```

### 获取 UI 组件树（dump）
```python
import json, socket, time
payload = json.dumps({"cmd": "dump"})
s = socket.socket(); s.settimeout(15)
s.connect(("127.0.0.1", 19317))
s.sendall((payload + "\n").encode())
time.sleep(3)
resp = s.recv(65535)
# 写入文件分析
with open('/tmp/ui_dump.json', 'w') as f: f.write(resp.decode())
s.close()
```

## 连接断开处理

如果操作过程中连接断开（命令返回 `"手机未连接"`）：
1. **不要重启 server** — 服务端本身运行正常，只是手机端断开
2. 用 `question` 工具引导用户重新连接手机
3. 验证连接成功后再继续
4. 如果用户选择取消，终止任务

## 诊断工作流（手机端调试）

当需要排查手机端问题时（如组件查找失败、OCR 不识别、流程卡住），按以下闭环执行：

### 工作流

```
推送含有详细日志的诊断脚本并远程执行 → 拉取日志 → 分析日志 → 修复代码 → 推送修复
```

**AI 应自主完成整个闭环，无需用户介入手机操作。** 用户只需确保手机已连接。

### Step 1: 编写诊断脚本

在 `/tmp/` 下创建诊断脚本，包含：
- `console.show()` 显示控制台
- 使用 `log()` 输出探测结果
- 逐一测试可能的查找方式并打印结果
- 通过最后一条日志 `=== 完毕 ===` 标记结束

关键注意：
- **`widget.desc()` 和 `widget.text()` 是方法，不是属性** — 必须加括号调用
- 组件属性如 `bounds()`、`className()`、`clickable()` 也都是方法
- 调试父组件树时递归调用 `widget.children()` 遍历

### Step 2: 推送并远程执行

> ⚠️ **`run` 命令不会回 `command_result`**，即使 `wait=true` 也会超时。正确做法：`wait=false` 推送，等几秒后直接拉日志。

```python
import json, socket, time

s = socket.socket(); s.settimeout(10)
s.connect(("127.0.0.1", 19317))
s.sendall((json.dumps({"cmd": "run", "name": "diagnose.js",
    "script": "console.log(\"=== 诊断开始 ===\");\n// ...诊断代码...\nconsole.log(\"=== 完毕 ===\");",
    "wait": False}) + "\n").encode())
time.sleep(0.5); print(s.recv(65535).decode()); s.close()
# 脚本已在后台执行，等待日志写入
time.sleep(3)
```

也可先用 `save` 推送再用 `run` 执行（两步法）。

### Step 3: 拉取日志

用 Step 5 的探测结果 `dir_path` 构造日志路径：

```bash
# {dir_path} 来自 Step 5（同一 session 已缓存）
# 优先 debug 版本，若失败则尝试 release 版本
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"pull_file","path":"{dir_path}/.logs/autojs-log4j-debug.txt"}' --port 9317
```

日志格式：`{dir_path}/.logs/autojs-log4j[-debug].txt`

成功拉取后，日志保存在 `phone_data/` 下。找到诊断脚本标记头（如 `=== 诊断名称 ===`）到最后之间的内容进行分析。

### Step 4: 分析日志

关键检查点：
- **组件能否被找到** — `findOne()` 返回 null 还是有效对象
- **组件的 className** — 确认是 `ViewGroup`、`TextView`、`ImageView` 等
- **desc/text 属性** — 文本在 desc 还是 text 中
- **clickable 状态** — 不可点击的组件需要 `.click()` 或坐标点击
- **bounds 坐标** — 确认位置是否符合预期
- **父组件树结构** — 通过 `widget.parent().children()` 遍历

### Step 5: 修复并推送

根据诊断结果修改主脚本，再次推送远程执行验证。

## 提示

### exec 命令的局限性（重要）

```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"exec","script":"1+1"}' --port 9317
```

**`exec` 的 `result` 始终为空 `{}`。** 经源码验证 `onSuccess` 的 result 参数始终为 null，无法获取返回值。需要返回值的场景请用 **`run` fire-and-forget + 写文件 → `pull_file`**。

- ✅ `exec` 可触发副作用：toast、文件写入、console.log
- ❌ `exec` 不可获取任何返回值

### 常见坑

| 问题 | 原因 | 解决 |
|------|------|------|
| `widget.desc` 返回函数引用 | desc 是方法不是属性 | 用 `widget.desc()` |
| `findOne(2000)` 返回 null | 超时太短或选择器不匹配 | 确认 desc/text 是否存在，增大超时 |
| 点击无效 | 组件 clickable=false | 直接用 `.click()` 仍可触发坐标点击 |
| `exec` 返回空 result | `onSuccess` 的 result 始终为 null | 用 `run` + 写文件 + `pull_file` |
| 日志找不到 | 路径不对（因语言/设置不同） | 先执行 Step 5 探测 `dir_path` |
| `run` 带 `wait=true` 超时 | `run` 不回 `command_result` | 用 `wait=false`，等几秒后拉日志 |

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
| shutdown | - | 停止服务端（仅当用户明确要求关闭时使用；不要自动调用） |
