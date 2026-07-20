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

## 通信协议约束（MANDATORY）

**所有手机端交互必须通过 autoxjs 协议完成**，即：

```
AI → server.py (TCP 控制端口 19317 / --send) → WebSocket (端口 9317) → AutoX.js App → 手机操作
```

具体规定：
1. **截图、组件树分析、脚本推送/执行、文件拉取、UI 探测** — 全部走 autoxjs 协议（`save`/`run`/`exec`/`dump`/`screenshot`/`pull_file` 等命令），见本文件各章节
2. **禁止使用 adb、root shell、SSH 或其他非 autoxjs 手段**与手机交互

**例外规则**：如果因为 autoxjs 协议不支持导致必须使用 adb/root/其他方式，**必须先通过 `question` 工具请求用户同意**，说明理由和具体命令，用户批准后才能执行。未经用户明确同意，不得使用 adb/root 等替代方式。

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

### 拉取日志
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"pull_file","path":"/sdcard/脚本/.logs/autojs-log4j.txt"}' --port 9317
```

## 脚本开发工作流（测试循环）

> 日志文件通常位于手机 `/sdcard/脚本/.logs/` 目录下，主日志文件为 `autojs-log4j.txt`。所有日志拉取统一使用 `pull_file` 命令，见下方 Step 4。

调试脚本时严格按以下循环执行：

### Step 1: 推送并保存脚本
用 `save` 命令将本地脚本推送到手机保存。推荐使用 TCP 直连模板（见「推送并保存脚本到手机」）。

### Step 2: 远程启动运行
用 `run` 命令远程启动已保存的脚本（只传 `name` 不含 `script`），`wait: true` 等待执行完成。见「远程启动已保存的脚本」模板。

**为什么用 save + run 两步，而不是一步到位？**
- `save` 用小超时（15s）快速推送，即使大脚本也不超时
- `run` 单独远程启动，`wait` 超时设 60s+，避免推送和运行互相影响
- 两步独立，更容易定位问题（推送失败 or 运行失败）

### Step 3: 在脚本中打关键日志
在脚本的关键节点添加 `console.log()`，例如：
```javascript
console.log("找到按钮：" + widget.desc());
console.log("开始看广告...");
console.log("进入第 X 轮循环");
console.log("弹窗处理完成");
```
这些日志会写入 `/sdcard/脚本/.logs/autojs-log4j.txt`。

### Step 4: 拉取日志查看结果
运行结束后，使用 `pull_file` 命令拉取日志文件查看各阶段输出。**所有日志拉取必须走 `pull_file` 指令**，不得使用 adb pull 或其他方式。见「拉取手机文件（使用 TCP 直连）」模板。

### Step 5: 清理（重要）
- **脚本不符合用户需求或只是临时测试** → 完成后**必须**删除手机上保存的脚本文件，避免污染 App 的脚本列表
- 删除方式：使用 `exec` 执行 `shell("rm /sdcard/脚本/手机端名称.js", true)`（文件管理器路径以实际为准）
- **脚本被用户采纳、需要保留** → 不删除，通知用户脚本已保存到手机

### 快速清理命令
```python
import json, socket
# 删除手机上保存的脚本
payload = json.dumps({"cmd": "exec", "script":
    'files.remove("/sdcard/脚本/手机端名称.js")', "wait": True})
s = socket.socket(); s.settimeout(10)
s.connect(("127.0.0.1", 19317))
s.sendall((payload + "\n").encode())
resp = s.recv(65535); print(resp.decode()); s.close()
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

### 推送、保存并自动执行（一步到位）
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
payload = json.dumps({"cmd": "pull_file", "path": "/sdcard/脚本/.logs/autojs-log4j.txt"})
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
推送诊断脚本并远程执行 → 拉取日志 → 分析日志 → 修复代码 → 推送修复
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

将脚本内容通过 `run` 命令推送到手机并自动执行，`wait=true` 会等待脚本执行完毕：

```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"command","command":"run","params":{"name":"diagnose.js","script":"...script content..."},"wait":true}' --port 9317
```

如果脚本已保存到手机，可只传 name 远程启动：
```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"command","command":"run","params":{"name":"diagnose.js"},"wait":true}' --port 9317
```

`wait=true` 会阻塞直到脚本运行结束，此时日志已写入手机存储。

> 也可以先用 `save` 推送，再用 `run` 远程执行（两步法）。推荐一步到位用上面第一条命令。

### Step 3: 拉取日志

AutoX.js 日志文件位于手机 `/sdcard/脚本/.logs/autojs-log4j.txt`：

```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"pull_file","path":"/sdcard/脚本/.logs/autojs-log4j.txt"}' --port 9317
```

成功拉取后，日志保存在 `phone_data/autojs-log4j.txt`。找到诊断脚本标记头（如 `=== 诊断名称 ===`）到最后之间的内容进行分析。

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

### exec 命令的局限性

```bash
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"exec","script":"1+1"}' --port 9317
```

`exec` 适用于返回简单值的表达式，但对于涉及 UI 操作、异步等待的复杂脚本，返回值可能为空。复杂调试请使用上面的**诊断工作流**（推送 → 运行 → 拉日志）。

### 常见坑

| 问题 | 原因 | 解决 |
|------|------|------|
| `widget.desc` 返回函数引用 | desc 是方法不是属性 | 用 `widget.desc()` |
| `findOne(2000)` 返回 null | 超时太短或选择器不匹配 | 确认 desc/text 是否存在，增大超时 |
| 点击无效 | 组件 clickable=false | 直接用 `.click()` 仍可触发坐标点击 |
| `exec` 返回空 result | 脚本涉及 UI/异步操作 | 改用推送脚本 + 拉日志方式 |
| 日志找不到 | 路径不对 | 默认在 `/sdcard/脚本/.logs/autojs-log4j.txt` |

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
