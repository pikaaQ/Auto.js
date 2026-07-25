---
name: autoxjs-developer
description: "AutoX.js 脚本开发助手。侧重脚本编写、推送、调试、诊断的完整开发流程。依赖 autoxjs-connector 提供手机连接能力。Triggers: 当需要编写/调试/推送 AutoX.js 脚本、分析日志、诊断手机端问题时自动激活。不处理连接协议。"
---

# AutoX.js 脚本开发

依赖 `autoxjs-connector` 技能提供的手机连接能力。在执行开发操作前，确保手机已连接（通过 `autoxjs-connector` 的连接流程）。

## 脚本根目录探测与缓存（首次开发前执行）

开发过程中的日志拉取、脚本保存等操作需要知道手机上的脚本根目录。此路径**不是固定的**，取决于 App 语言（中文 `/脚本/`、英文 `/Scripts/`）和用户自定义设置。

> ⚡ **`dir_path` 缓存在 Agent 的当前 session 记忆中。** 首次探测后记下来，后续所有操作直接使用，不许每次都探测。

### 探测方法

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

日志文件路径格式：`{dir_path}/.logs/autojs-log4j[-debug].txt`

- debug 构建 → `autojs-log4j-debug.txt`
- release 构建 → `autojs-log4j.txt`

## 推送脚本到手机

### 推送并自动执行（不会保存到手机）

```python
import json, socket, time

s = socket.socket(); s.settimeout(10)
s.connect(("127.0.0.1", 19317))
s.sendall((json.dumps({"cmd": "run", "name": "my_script.js",
    "script": open("本地脚本.js", encoding="utf-8").read(),
    "wait": False}) + "\n").encode())
time.sleep(0.5); print(s.recv(65535).decode()); s.close()
# 脚本已在手机后台执行
time.sleep(3)  # 等日志写入
```

### 推送并保存到手机

```python
import json, socket, time

payload = json.dumps({"cmd": "command", "command": "save",
    "params": {"name": "手机端名称.js", "script": open("本地脚本.js", encoding="utf-8").read()},
    "wait": False})
s = socket.socket(); s.settimeout(15)
s.connect(("127.0.0.1", 19317))
s.sendall((payload + "\n").encode())
time.sleep(1.5)
print(s.recv(65535).decode()); s.close()
```

### 远程启动已保存的脚本

```python
import json, socket, time

s = socket.socket(); s.settimeout(10)
s.connect(("127.0.0.1", 19317))
s.sendall((json.dumps({"cmd": "run", "name": "已保存的脚本.js", "wait": False}) + "\n").encode())
time.sleep(0.5); print(s.recv(65535).decode()); s.close()
time.sleep(3)  # 等日志写入
```

> ⚠️ **`run` 命令不回 `command_result`**，`wait=true` 会超时。必须用 `wait=false` + sleep。

## 拉取日志

日志文件路径格式：`{dir_path}/.logs/autojs-log4j[-debug].txt`

```bash
# {dir_path} 来自脚本根目录探测（已缓存）
# 优先尝试 release 版本，若不存在则尝试 debug 版本
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"pull_file","path":"{dir_path}/.logs/autojs-log4j.txt"}' --port 9317
```

## 诊断工作流（手机端调试流程）

当需要排查手机端问题时（如组件查找失败、OCR 不识别、流程卡住），按以下闭环执行：

```
推送含详细日志的诊断脚本 → 拉取日志 → 分析日志 → 修复代码 → 推送修复
```

**AI 应自主完成整个闭环，无需用户介入手机操作。**

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

用缓存的 `dir_path` 构造日志路径：

```bash
# 优先 debug 版本，若失败则尝试 release 版本
python3 ai/skills/autoxjs-connector/server.py --send '{"cmd":"pull_file","path":"{dir_path}/.logs/autojs-log4j-debug.txt"}' --port 9317
```

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

## 常见开发问题

| 问题 | 原因 | 解决 |
|------|------|------|
| `widget.desc` 返回函数引用 | desc 是方法不是属性 | 用 `widget.desc()` |
| `findOne(2000)` 返回 null | 超时太短或选择器不匹配 | 确认 desc/text 是否存在，增大超时 |
| 点击无效 | 组件 clickable=false | 直接用 `.click()` 仍可触发坐标点击 |
| 日志找不到 | 路径不对（因语言/设置不同） | 先执行脚本根目录探测 |
| `exec` 返回空 result | `onSuccess` 的 result 始终为 null | 用 `run` + 写文件 + `pull_file` |
| `run` 带 `wait=true` 超时 | `run` 不回 `command_result` | 用 `wait=false`，等几秒后拉日志 |
