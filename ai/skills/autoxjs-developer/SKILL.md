---
name: autoxjs-developer
description: "AutoX.js 脚本开发助手。侧重脚本编写、推送、调试、诊断的完整开发流程。依赖 autoxjs-connector 提供手机连接能力。Triggers: 当需要编写/调试/推送 AutoX.js 脚本、分析日志、诊断手机端问题时自动激活。不处理连接协议。"
---

# AutoX.js 脚本开发

依赖 `autoxjs-connector` 技能提供的手机连接能力。在执行开发操作前，确保手机已连接（通过 `autoxjs-connector` 的连接流程）。

> ⚠️ 在和手机交互时仅能使用 `autoxjs-connector` 技能提供的手机连接能力，禁止使用adb、新建http服务等方案，如果`autoxjs-connector` 技能中的能力失败，应该告知用户，不要自作主张使用其他方案。

## 安装

### 前置依赖

```bash
pip install websockets
```

本技能依赖 `autoxjs-connector` 技能提供手机连接能力，**必须先安装 `autoxjs-connector`**（安装方法见其文档）。不要通过本技能安装 `autoxjs-connector`，也不要调用 `autoxjs-connector/install.sh`。

### 全局安装（推荐）

技能默认只在当前项目加载。全局安装后可在所有 opencode 项目中使用：

```bash
# 方法一：使用安装脚本（符号链接，与项目源码保持同步）
bash ${skill_base_dir}/autoxjs-developer/install.sh

# 方法二：手动复制
mkdir -p ~/.config/opencode/skills
cp -r ${skill_base_dir}/autoxjs-developer ~/.config/opencode/skills/
```

⚠️ 本技能不包含连接能力，**必须单独安装 `autoxjs-connector` 技能**。

安装后**重启 opencode** 使技能生效。

## 脚本参考

本技能提供了 AutoX.js API 的脚本示例，位于 `sample/` 目录下。在编写脚本时，如需使用 autoX.js 的特定 API（如 OCR、HTTP 请求、文件读写、UI 控件操作等），**优先参考 `sample/` 下对应分类的脚本**，了解 API 的调用方式和参数格式。

示例目录分类：

| 分类 | 说明 |
|------|------|
| `OCR/` | OCR 文字识别相关 |
| `YOLO/` | YOLO 模型推理 |
| `图片与图色处理/` | 截图、找图、找色、图片处理 |
| `无障碍/` | 无障碍服务、组件查找与操作 |
| `界面控件/` | UI 控件选择与交互 |
| `HTTP网络请求/` | 网络请求 API |
| `文件读写/` | 文件与目录操作 |
| `调用Java API/` | Java 接口调用 |
| 其余 | 协程、多线程、传感器、定时器、悬浮窗等 |

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

### 推送项目到手机

使用 `save_project`（仅保存）或 `run_project`（保存并执行）推送整个项目目录到手机。

> ⚠️ **项目必须包含 `project.json`**，字段要求：
> ```json
> {
>   "name": "项目名",
>   "packageName": "com.example.app",
>   "versionName": "1.0",
>   "versionCode": 1,
>   "main": "main.js"
> }
> ```
> 缺少这些字段会导致 `ProjectLauncher` 抛出"无效项目"异常。

```python
import json, socket, time

payload = json.dumps({"cmd": "save_project", "project_dir": "/path/to/your/project"})
s = socket.socket(); s.settimeout(30)
s.connect(("127.0.0.1", 19317))
s.sendall((payload + "\n").encode())
time.sleep(5)
resp = s.recv(65535)
print(resp.decode()[:500])
s.close()
```

如需保存并自动执行，将 `save_project` 改为 `run_project` 即可。

## 拉取日志

日志文件路径格式：`{dir_path}/.logs/autojs-log4j[-debug].txt`

```bash
# {dir_path} 来自脚本根目录探测（已缓存）
# 优先尝试 release 版本，若不存在则尝试 debug 版本
python3 "${skill_base_dir}/autoxjs-connector/server.py" --send '{"cmd":"pull_file","path":"{dir_path}/.logs/autojs-log4j.txt"}' --port 9317
```

## 开发工作要求

### 流程要求：检查 → 执行 → 验证

对于需求中的每一步操作，代码必须实现 **检查 → 执行 → 验证** 闭环：

- **检查**：判断当前状态，确认是否需要执行操作
- **执行**：执行具体操作
- **验证**：确认操作生效

对于连续操作，上一步的验证和下一步的检查可以合并（用下一步的可执行条件当作上一步的完成条件）。

代码中使用注释体现每一步操作的流程结构：

```javascript
// === Step 1: 打开某 App ===
// [检查] App 是否已在前台
if (!currentPackage().contains("com.example.app")) {
    // [执行] 打开 App
    app.launchPackage("com.example.app");
    // [验证] App 已打开
    waitForPackage("com.example.app", 5000);
}

// === Step 2: 跳过开屏广告 ===
// [检查] 广告是否存在
let ad = text("跳过").findOne(2000);
if (ad) {
    // [执行] 点击跳过
    ad.click();
    // [验证] 菜单已出现（= 下一步的检查，合并到 Step 3）
}

// === Step 3: 点击菜单项 ===
// [检查] 菜单是否出现（与上一步验证合并，无需重复）
let menu = desc("目标菜单").findOne(3000);
// [执行] 点击菜单
menu.click();
// [验证] 已到达目标页面
waitForActivity("TargetActivity", 5000);
```

### 探索与验证方法

当需要编写操作脚本时，先用以下固定手段**探索**页面结构：

1. **截图 + mlkocr**：使用 AutoX.js 脚本截取当前页面，用 `mlkocr` 识别文字
2. **Dump 组件树**：获取当前界面 UI 组件树 XML，分析组件的 className、desc、text、bounds、clickable 等属性

结合 OCR 结果和组件树信息判断如何执行操作。

然后用**临时诊断脚本**发送到手机上调试定位。诊断完成后，必须清理临时脚本（详见诊断工作流 Step 6）。

> 如果截图 OCR + dump 无法获取到必要信息，**必须请示用户**是否可以拉取截图使用 `look_at` 工具进行分析，不得擅自猜测。

### 操作定位方案优先级

按以下优先级选择操作定位方式：

1. **🥇 组件查找（首选）** — `desc()` / `text()` / `className()` / `id()` 等选择器
   - 优点：稳定、不受屏幕分辨率影响
   - 使用：`desc("按钮").findOne(3000)`、`textContains("确认").click()`
2. **🥈 OCR 文字识别（mlkocr）** — 当组件无 desc/text 属性时使用
   - 使用：截图 → 裁剪 → mlkocr 识别文字坐标 → 点击坐标
   - 如果 mlkocr 识别结果不理想，使用**模糊匹配**：
     - **目标文字量多**（≥3个字）：匹配文字量达到目标文字的 60% 以上即通过
     - **目标文字量少**（<3个字）：所有文字相似的结果都纳入匹配，只要命中其中一个即通过
3. **🥉 找色** — 当 OCR 也无法获取有效信息时使用
   - 从截图中取特征颜色点，使用 `findColor()` / `findColorEquals()` 定位
4. **🏅 找图（最后手段）** — 以上均不行时
   - 裁剪截图中特征区域为模板图，使用 `findImage()` 匹配

## 诊断工作流（手机端调试流程）

当需要排查手机端问题时（如组件查找失败、OCR 不识别、流程卡住），按以下闭环执行：

```
推送含详细日志的诊断脚本 → 拉取日志 → 分析日志 → 修复代码 → 推送修复
```

**AI 应自主完成整个闭环，无需用户介入手机操作。**

### Step 1: 编写诊断脚本

在 `/tmp/{project_name}/` 下创建诊断脚本（`{project_name}` 为当前项目目录名，如 `Auto.js`），包含：
- `console.show()` 显示控制台
- 使用 `log()` 输出探测结果
- 逐一测试可能的查找方式并打印结果
- 通过最后一条日志 `=== 完毕 ===` 标记结束

**记录脚本本地路径（如 `/tmp/{project_name}/diagnose_xxx.js`），后续清理用。**

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
# 脚本已在手机后台执行 **不保存到手机**，无需手动清理
time.sleep(3)  # 等待日志写入
```

> 如果用 `save` + `run` 两步法推送诊断脚本，记录保存的文件名，后续需清理。

### Step 3: 拉取日志

用缓存的 `dir_path` 构造日志路径：

```bash
# 优先 debug 版本，若失败则尝试 release 版本
python3 "${skill_base_dir}/autoxjs-connector/server.py" --send '{"cmd":"pull_file","path":"{dir_path}/.logs/autojs-log4j-debug.txt"}' --port 9317
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

### Step 6: 清理诊断脚本

诊断流程结束后，必须清理所有临时文件：

1. **PC 端**：删除诊断脚本（如 `/tmp/{project_name}/diagnose_xxx.js`）
2. **手机端**：如果是用 `save` 保存到手机的，用 `run` 执行删除；如果是用 `run` 直接推送执行的（不保存），无需清理

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

# 清理保存到手机的诊断脚本（如果通过 save 保存过）
ctrl({"cmd": "run", "script": "files.remove(\"{dir_path}/diagnose.js\");",
      "name": ".cleanup.js", "wait": False})
time.sleep(1)
# 清理探测残留（首次探测时写入的 .sdir.txt）
ctrl({"cmd": "run", "script": 'files.remove("/sdcard/.sdir.txt");',
      "name": ".cleanup_sdir.js", "wait": False})
time.sleep(1)
# 清理手机上的临时清理脚本自身
ctrl({"cmd": "run", "script": 'files.remove(files.cwd() + "/.cleanup.js");\n' +
      'files.remove(files.cwd() + "/.cleanup_sdir.js");',
      "name": ".cleanup_self.js", "wait": False})
```

## 常见开发问题

| 问题 | 原因 | 解决 |
|------|------|------|
| `widget.desc` 返回函数引用 | desc 是方法不是属性 | 用 `widget.desc()` |
| `findOne(2000)` 返回 null | 超时太短或选择器不匹配 | 确认 desc/text 是否存在，增大超时 |
| 点击无效 | 组件 clickable=false | 直接用 `.click()` 仍可触发坐标点击 |
| 日志找不到 | 路径不对（因语言/设置不同） | 先执行脚本根目录探测 |
| `exec` 返回空 result | `onSuccess` 的 result 始终为 null | 用 `run` + 写文件 + `pull_file` |
| `run` 带 `wait=true` 超时 | `run` 不回 `command_result` | 用 `wait=false`，等几秒后拉日志 |
