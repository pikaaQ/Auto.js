---
name: autoxjs-developer
description: "AutoX.js 脚本开发助手。侧重脚本编写、推送、调试、诊断的完整开发流程。依赖 autoxjs-connector 提供手机连接能力。Triggers: 当需要编写/调试/推送 AutoX.js 脚本、分析日志、诊断手机端问题时自动激活。不处理连接协议。"
---

# AutoX.js 脚本开发

依赖 `autoxjs-connector` 技能提供的手机连接能力。

⚠️ **在和手机交互时仅能使用 `autoxjs-connector` 技能提供的手机连接能力，禁止使用adb、新建http服务等方案，如果`autoxjs-connector` 技能中的能力失败，应该告知用户，不要自作主张使用其他方案。**
⚠️ **严格遵守开发和探索中的方案和准则，不要自作主张采用其他方案**
⚠️ **需要注意到AutoXjs与nodejs存在一些解决方案上的不同，不能完全用nodejs上的经验来进行判断，以本指引和实际测试结果为准**

## 安装

### 前置依赖

本技能依赖 `autoxjs-connector` 技能提供手机连接能力，**必须先安装 `autoxjs-connector`**（安装方法见其文档）。

### 安装

```bash
# 方法一：使用安装脚本（符号链接，与项目源码保持同步）
bash ${skill_base_dir}/autoxjs-developer/install.sh

# 方法二：手动复制
mkdir -p ~/.config/opencode/skills
cp -r ${skill_base_dir}/autoxjs-developer ~/.config/opencode/skills/
```

安装后**重启 opencode** 使技能生效。

## 项目结构约定

### 项目结构
项目 follow `sample-project/` 的目录结构来规划，新建项目时使用实际项目名代替 `{project}`，项目从语法上来说使模块化的node项目：

```
{project}/
├── project.json              ← 项目配置（必需，含 ignore 列表）
├── main.js                   ← 入口脚本
├── lib/                      ← 工具库，autoxjs必须使用 singletonRequirer 进行模块导入
│   ├── SingletonRequirer.js  ← 单例模式导入器（路径解析详见下方）
│   ├── HumanClick.js         ← 模拟人为点击
│   ├── ScreenCapturePermissionUtil.js  ← 截图权限管理
│   ├── ShizukuUtils.js       ← Shizuku 绑定 + 无障碍服务
│   └── PrintExceptionStack.js          ← 异常堆栈打印
├── actions/                  ← 操作步骤，按功能拆分模块
├── ui/                       ← UI 界面相关
│   └── assets/               ← UI 资源文件
├── docs/                     ← 流程文档、页面分析（不推送至手机）
│   ├── readme.md             ← 项目说明
│   ├── flow.md               ← 流程步骤描述
│   ├── dev_tools.md          ← 开发工具使用说明
│   └── pages/                ← 页面分析记录
│       └── 页面名.md
├── devtools/                 ← 开发工具脚本（不推送至手机）
├── phone_data/               ← 从手机拉取的文件（日志、截图等，不推送至手机）
├── .omo/                     ← opencode 配置
├── .git                      ← 版本控制
├── .gitignore                ← 版本控制忽略的文件
└── ...                       ← 其他辅助文件
```

**项目的模块化方式与nodejs稍有不同，需要使用`lib/` 下的工具模块 `singletonRequirer` 代替 `require` 导入，具体使用方式可见 lib.md**

### 项目结构首次推送到手机

首次使用 `save_project` 推送整个项目目录到手机后就可以开始调试开发了。注意 `run_project` 是直接运行项目，并不建议在推送时使用。`project.json` 的 `ignore` 字段会跳过无需推送的文件。

```json
{
  "name": "sample-project",
  "packageName": "com.mizzle",
  "versionName": "1.0",
  "versionCode": 1,
  "main": "main.js",
  "ignore": [
    ".gitignore", ".omo", ".codegraph",
    "docs", "devtools", "phone_data"
  ]
}
```

`project.json` 中的 `ignore` 列表用于控制推送时跳过哪些文件/目录（如 `docs/`、`devtools/`、`phone_data/`），减少推送流量。`ui/assets/` 未发生变化时，可临时将其加入 `ignore` 以加速推送。

## 与手机协同开发
### 脚本根目录探测与缓存（首次开发前执行）

开发过程中的日志拉取、图片保存等操作需要知道手机上的脚本根目录。此路径**不是固定的**，取决于 App 语言（中文 `/脚本/`、英文 `/Scripts/`）和用户自定义设置。

> ⚡ **`dir_path` 缓存在 Agent 的当前 session 记忆中。** 首次探测后记下来，后续所有操作直接使用，不许每次都探测。

#### 探测方法

```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"

# Step A: 推送探测脚本（fire-and-forget）
# run 执行时 working directory = Pref.getScriptDirPath()
# 因此 files.cwd() 就是实际脚本目录
$CALL '{"cmd":"run","script":"files.write(\"/sdcard/.sdir.txt\", files.cwd());","name":".detect_sdir.js","wait":false}' --port 9317
sleep 1.5

# Step B: 拉取探测结果，提取 local_path 并读取文件内容
$CALL '{"cmd":"pull_file","path":"/sdcard/.sdir.txt"}' --port 9317 > /tmp/pull_result.json
LOCAL_FILE=$(python3 -c "import json; print(json.load(open('/tmp/pull_result.json'))['result']['local_path'])")
dir_path=$(cat "$LOCAL_FILE" 2>/dev/null || echo "/storage/emulated/0/脚本")
echo "脚本根目录: $dir_path"   # 记下来，后续复用
```

日志文件路径格式：`{dir_path}/.logs/autojs-log4j[-debug].txt`

- debug 构建 → `autojs-log4j-debug.txt`
- release 构建 → `autojs-log4j.txt`

### 推送脚本到手机

#### 推送并自动执行（不会保存到手机）

```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"
SCRIPT=$(cat 本地脚本.js)
$CALL "{\"cmd\":\"run\",\"name\":\"my_script.js\",\"script\":$(python3 -c "import json,sys; print(json.dumps(sys.stdin.read()))" <<< "$SCRIPT"),\"wait\":false}" --port 9317
# 脚本已在手机后台执行
sleep 3  # 等日志写入
```

#### 推送并保存到手机

```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"
SCRIPT=$(cat 本地脚本.js)
$CALL "{\"cmd\":\"command\",\"command\":\"save\",\"params\":{\"name\":\"手机端名称.js\",\"script\":$(python3 -c "import json,sys; print(json.dumps(sys.stdin.read()))" <<< "$SCRIPT")},\"wait\":false}" --port 9317
```

#### 远程启动已保存的脚本

```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"
$CALL '{"cmd":"run","name":"已保存的脚本.js","wait":false}' --port 9317
sleep 3  # 等日志写入
```

> ⚠️ **`run` 命令不回 `command_result`**，`wait=true` 会超时。必须用 `wait=false` + sleep。

#### 推送项目到手机

使用 `save_project` 推送整个项目目录到手机。
```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"
$CALL "{\"cmd\":\"save_project\",\"project_dir\":\"/path/to/your/project\"}" --port 9317
```

#### 执行项目（不保存到本地）
与`save_project`协议一致，将 `save_project` 指令改为 `run_project` 即可。

#### 拉取日志

日志文件路径格式：`{dir_path}/.logs/autojs-log4j[-debug].txt`

```bash
# {dir_path} 来自脚本根目录探测（已缓存）
# 优先尝试 release 版本，若不存在则尝试 debug 版本
# local_path 指定当前项目的 phone_data/ 目录，确保文件保存到正确的项目
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"
$CALL "{\"cmd\":\"pull_file\",\"path\":\"{dir_path}/.logs/autojs-log4j.txt\",\"local_path\":\"{project_root}/phone_data\"}" --port 9317
```

## 开发方法与要求

### 开发流程：规划 → 探索 → 单元开发 → 单元验证 → 完成脚本 → 用户测试

1. 先根据用户描述，规划脚本流程, 并和用户确认，看用户有没有补充，直至流程清晰并得到用户确认，保留流程文档，后续根据文档来进行开发。
2. 开始探索流程中出现的每个页面，探索方式见 探索与验证方法 章节。对探索的每个页面进行组件分析得到充分认知，判断组件操作后的结果，并将页面组件记录到组件文档。完成当前页面后，自动用脚本操作进入下一个页面，循环操作直到记录所有页面。
3. 基于2中对每个页面的深入研究，生成 `checkPage()` 方法。探索时在 `docs/pages/` 下为每个页面命名并记录特征，在 `actions/Pages.js` 中将这些页面名定义为常量，`checkPage()` 根据 OCR/组件树特征判断当前页面，返回对应的页面常量或 `"unknown"`。模板代码见 `sample-project/actions/Pages.js`，引入后通过 `singletonRequirer` 导入使用。这个方法可供每个动作的检查和验证环节使用（知道当前在哪个页面很重要）。
4. 根据流程和页面的研究结果，依次开发流程中每个步骤的脚本，开发完成后验证单个步骤是否复合要求，验证方式见 探索与验证方法 章节。
5. 将所有步骤根据流程进行组合，完成脚本编写，提示用户测试验证。

### 探索与验证方法

当需要探索页面和验证操作时，在 `devtools/` 下编辑好脚本，然后推送执行（`run` 指令，探索脚本为单脚本，不会保存到手机，因此 **不能使用 `lib/` 下的模块**，所有代码必须内联），最后拉取执行日志来分析，达成探索和验证的目的。

#### 如何探索

**探索页面时， 应该尽可能多的获取到全部页面信息来进行分析然后保存到文档，而不是仅针对当前任务中的特征来分析，因为尽可能多的页面信息可以使脚本更健壮，也方便后续测试和维护。**

`devtools/` 下提供了通用探索脚本模板 `explore_template.js`，复制后修改 TODO 部分即可使用。模板已包含 `=== 开始 ===` 和 `=== 完毕 ===` 日志标记。

探索脚本逻辑为：
1. 检查项目下有没有tmp目录，没有就先创建
2. 进入页面
3. **截图 + mlkocr**：
    探索/验证时脚本中**使用 Shizuku 执行 `screencap` 截图**（无需申请截图权限，无需弹窗），固定保存到当前项目的tmp目录。然后读取截图后用 `mlkocr` 识别文字，识别结果写入log。
    **如果 Shizuku 截图失败，提示用户检查 Shizuku 是否运行，而不是自作主张采用其他方法**
4. **Dump 组件树**：获取当前界面 UI 组件树 XML，分析组件的 className、desc、text、bounds、clickable 等属性


同一页面，将该脚本逻辑执行多次，获得尽可能全部可能的OCR结果和组件树dump结果。待脚本执行完成后，结合 OCR 结果和组件树信息，分析当前页面，并在docs目录下记录页面文档信息。
**如果上述方式分析出的信息无法达成流程要求，可以在申请用户同意后，将截图拉取到项目中，使用look_at分析图片，这种操作必须申请用户同意后才可实施。**

#### 如何验证
验证操作时，项目脚本逻辑为：
1. 检查项目下有没有tmp目录，没有就先创建
2. 进入操作的前置页面
3. 执行单元操作
4. 截图 + mlkocr + dump组件树（方案同探索中的2、3），判断操作后的页面和页面组件是否和预期一致。


### 开发要求与约定：
对非devtool下的代码(即在项目中实际运行的代码)，在开发时有如下要求：

#### 整体流程，需要模块清晰，与流程文档一致，尽量将一组相关的操作封装为一个函数

#### **如下功能必须使用 lib 下的模块**
##### 模块化导入时必须使用 `singletonRequirer` 
##### 截图权限获取时必须使用`ScreenCapturePermissionUtil`，仅可在main.js入口时调用一次
##### 所有点击操作禁止直接调用组件的 `.click()` 方法，点击操作必须使用`HumanClick`
##### 授权无障碍时先使用 Shizuku 管理 `ShizukuUtils`，如果绑定失败或授权失败再使用auto()申请人工授权


#### 对流程的单个步骤，需要采取 检查 → 执行 → 验证 的执行三步方案闭环：

- **检查**：判断当前所在页面是否符合流程要求，确认是否需要执行操作
- **执行**：执行具体操作
- **验证**：确认操作生效，跳转到了复合流程的页面/达成了效果

对于连续操作，上一步的验证和下一步的检查可以合并（上一步的完成条件和下一步的检查条件一致时不做重复检查）。

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
// [验证] 菜单是否出现
let menu = desc("目标菜单").findOne(3000);

// === Step 3: 点击菜单项 ===
// [检查] 菜单是否出现（与上一步验证合并，无需重复）
// [执行] 点击菜单
menu.click();
// [验证] 使用页面特征检查是否已到达目标页面
Pages.checkPage() == Pages.PAGES.HOME;
```

#### 操作定位方案

代码中，如果需要定位目标元素，需按以下优先级选择操作定位方式，这些方式在探索页面时就要考虑：

1. **🥇 组件查找（首选）** — `desc()` / `text()` / `className()` / `id()` 等选择器
   - 优点：稳定、不受屏幕分辨率影响
   - 使用：`desc("按钮").findOne(3000)`、`textContains("确认").click()`
2. **🥈 OCR 文字识别（mlkocr）** — 当组件无 desc/text 属性时使用
   - 使用：截图 → 裁剪 → mlkocr 识别文字坐标 → 点击坐标
   - **正式脚本**中使用 `captureScreen()` 截图
   - 因为 mlkocr 识别结果不理想，使用**模糊匹配**，每个组件的模糊匹配可以单独封装成一个函数：
     - **目标文字量多**（≥3个字）：匹配词组量达到目标词组的 60% 以上即通过
     - **目标文字量少**（<3个字）：所有文字相似的结果都纳入匹配，只要命中其中一个即通过
3. **🥉 找色** — 当 OCR 也无法获取有效信息时使用
   - 从截图中取特征颜色点，使用 `findColor()` / `findColorEquals()` 定位
4. **🏅 找图（最后手段）** — 以上均不行时
   - 裁剪截图中特征区域为模板图，使用 `findImage()` 匹配


#### 脚本参考

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


#### 提示与避坑

- 探索、验证脚本中应该使用 `log()` 输出探测结果（**不要调用 `console.show()`**，控制台窗口遮挡屏幕会导致 OCR 不准）
- 探索、验证脚本逐一测试可能的查找方式并打印结果，然后根据优先级在开发时使用
- 探索、验证脚本通过第一条日志`=== 开始 ===`，最后一条日志 `=== 完毕 ===` 标记开始结束，这样方便每次检查日志时快速定位
- **`widget.desc()` 和 `widget.text()` 是方法，不是属性** — 必须加括号调用
- 组件属性如 `bounds()`、`className()`、`clickable()` 也都是方法
- 调试父组件树时递归调用 `widget.children()` 遍历
- 探索、验证脚本模板：
```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"
$CALL "{\"cmd\":\"run\",\"name\":\"diagnose.js\",\"script\":\"log('=== 开始 ===');\n// ...诊断代码...\nlog('=== 完毕 ===');\",\"wait\":false}" --port 9317
# 脚本已在手机后台执行 **不保存到手机**，无需手动清理
sleep 3  # 等待日志写入
```
- 如果用 `save` + `run` 两步法推送诊断脚本，记录保存的文件名，完成后记得清理。
- 临时拉取的日志、文件保存在phone_data下，使用后清理，清理脚本模板（包括手机和PC上）：
```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"

# 清理保存到手机的诊断脚本（如果通过 save 保存过）
$CALL "{\"cmd\":\"run\",\"script\":\"files.remove('{dir_path}/diagnose.js');\",\"name\":\".cleanup.js\",\"wait\":false}" --port 9317
sleep 1
# 清理探测残留（首次探测时写入的 .sdir.txt）
$CALL '{"cmd":"run","script":"files.remove(\"/sdcard/.sdir.txt\");","name":".cleanup_sdir.js","wait":false}' --port 9317
sleep 1
# 清理手机上的临时清理脚本自身
$CALL '{"cmd":"run","script":"files.remove(files.cwd() + \"/.cleanup.js\");\nfiles.remove(files.cwd() + \"/.cleanup_sdir.js\");","name":".cleanup_self.js","wait":false}' --port 9317
```
