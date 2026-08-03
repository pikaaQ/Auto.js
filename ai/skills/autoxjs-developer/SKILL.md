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
项目 follow `sample-project/` 的目录结构来规划, 目录结构如下：

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
├── actions/                  ← 操作步骤，包括Pages.js页面定义文件,子目录pages下方每个页面的单元操作文件,以及为达成不同目标的操作组合文件
│   ├── Pages.js              ← 页面名定义常量，`checkPage()` 方法判断当前所在的页面
│   ├── SignIn.js             ← 为了达成一个流程(当前流程是签到)所做的一系列操作组合.
│   └── pages/                ← 在不同页面上的操作,每个页面多个操作封装成一个页面的Actions文件
│       └── SamplePageActions.js ← 在单页面上的原子操作合集(示例，实际按{页面名}命名，如HomeActions.js)
├── ui/                       ← UI 界面相关
│   └── assets/               ← UI 资源文件
├── docs/                     ← 流程文档、页面分析（不推送至手机）
│   ├── readme.md             ← 项目说明
│   ├── flow.md               ← 流程步骤描述
│   ├── dev_tools.md          ← 开发工具使用说明
│   └── pages/                ← 页面分析总结文档
│       └── 页面名.md
│   └── explore/              ← 页面探索原始记录
│       └── 页面名
│            └── shot1.png    ← 截图1
│            └── orc1.txt     ← ocr结果1
│            └── dump1.txt    ← dump中的组件列表1
├── devtools/                 ← 开发工具脚本（不推送至手机）
├── test/                     ← 单元测试脚本
├── phone_data/               ← 从手机拉取的文件（日志、截图等，不推送至手机）
├── .omo/                     ← opencode 配置
├── .git                      ← 版本控制
├── .gitignore                ← 版本控制忽略的文件
└── ...                       ← 其他辅助文件
```

新建项目时可以直接将`sample-project/`复制后使用实际项目名代替 `{project}`, 复制后可以将部分样板代码(一般文件名带有 `sample` 或 `template`的)删除.

如果是现有项目适配和重构, 先将`sample-project/`中 `lib` 和 `devtools` 目录和下面的工具文件同步到项目,然后按功能模块重构.

**项目从语法上来说使模块化的node项目, 但是项目的模块化方式与nodejs稍有不同，需要使用`lib/` 下的工具模块 `singletonRequirer` 代替 `require` 导入，具体使用方式可见 lib.md**

## 与手机协同开发

需要先将项目推送到手机,并完成脚本根目录探测与缓存,作为项目协同的初始化. 
然后再按照本文档规定的开发方法和要求来完成后续开发流程. 在开发过程中会用到脚本推送/运行,项目推送/运行,手机上的日志拉取等对应指令.

### 首次推送到手机,完成手机上项目的初始化

使用 `save_project` (具体使用方法见 [协同指令和脚本](#id-协同指令和脚本anchor) 部分)推送整个项目目录到手机。

`project.json` 中的 `ignore` 列表用于控制推送时跳过哪些文件/目录（如 `docs/`、`devtools/`、`phone_data/`），减少推送流量。
后续 `ui/assets/` 未发生变化时，可临时在推送时将其加入 `ignore` 以加速推送，减少推送流量。

project.json是项目定义文件,内容如下:
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

### 脚本根目录探测与缓存

开发过程中的日志拉取、图片保存等操作需要知道手机上的脚本根目录。此路径**不是固定的**，取决于 App 语言（中文 `/脚本/`、英文 `/Scripts/`）和用户自定义设置。

> ⚡ **`dir_path` 缓存在 Agent 的当前 session 记忆中。** 首次探测后记下来，后续所有操作直接使用，不许每次都探测。

#### 探测方法

使用 `devtools/detect_root_dir.sh` 脚本一步完成探测与自动清理：

```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"
cp ${skill_base_dir}/autoxjs-developer/sample-project/devtools/detect_root_dir.sh ./devtools/
DIR_PATH=$(bash ./devtools/detect_root_dir.sh --call "$CALL" --port 9317)
echo "脚本根目录: $DIR_PATH"   # 记下来，后续复用
```

日志文件路径格式：`{dir_path}/.logs/autojs-log4j[-debug].txt`,绝大部分时候都是release构建.

- debug 构建 → `autojs-log4j-debug.txt`
- release 构建 → `autojs-log4j.txt`

<a id="id-协同指令和脚本anchor"></a>

### 与手机协同的指令和脚本

#### 推送并直接执行（不会保存到手机）
推送并直接执行的脚本需要时内联的(非模块化的),一般用于探索,或devtools中要在手机上执行的脚本,他们与项目中用到的模块没有依赖关系.
```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"
SCRIPT=$(cat 本地脚本.js)
$CALL "{\"cmd\":\"run\",\"name\":\"my_script.js\",\"script\":$(python3 -c "import json,sys; print(json.dumps(sys.stdin.read()))" <<< "$SCRIPT"),\"wait\":false}" --port 9317
# 脚本已在手机后台执行
sleep 3  # 等日志写入
```

> ⚠️ **`run` 命令不回 `command_result`**，`wait=true` 会超时。必须用 `wait=false` + sleep,通过执行完后读取日志检查执行情况.

#### 推送并保存到手机
推送并保存到手机再执行的脚本,一般用于test下的单元测试脚本,他们依赖项目中的代码模块.

`save` 命令只支持保存到 `{dir_path}` 根目录，因此需先保存到临时文件名，再移动到目标位置。路径根据脚本在项目中的位置自动推导。如推送 `actions/my_action.js` 整体脚本如下：

```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"

# PC项目根目录（project.json 所在目录）
PROJECT_ROOT="."
# 本地脚本路径（相对于项目根目录）
SCRIPT_FILE="actions/my_action.js"

# 从 project.json 读取项目名,项目名是该项目再手机上的顶层目录
PROJECT_NAME=$(python3 -c "import json; print(json.load(open('$PROJECT_ROOT/project.json'))['name'])" 2>/dev/null || echo "project")

# 计算脚本在项目中的相对路径
REL_PATH=$(python3 -c "import os.path; print(os.path.relpath('$SCRIPT_FILE', '$PROJECT_ROOT'))" 2>/dev/null || basename "$SCRIPT_FILE")

# 手机端目标路径: {project_name}/{REL_PATH}
PHONE_PATH="$PROJECT_NAME/$REL_PATH"
DIR_PART=$(dirname "$PHONE_PATH")

# 1. 确保手机子目录存在
$CALL "{\"cmd\":\"exec\",\"script\":\"files.ensureDir('$DIR_PART');\",\"wait\":true}" --port 9317

# 2. save 只能保存到根目录，用临时文件名保存
SCRIPT=$(cat "$SCRIPT_FILE")
TMP_NAME=".save_tmp_$(date +%s).js"
$CALL "{\"cmd\":\"command\",\"command\":\"save\",\"params\":{\"name\":\"$TMP_NAME\",\"script\":$(python3 -c "import json,sys; print(json.dumps(sys.stdin.read()))" <<< "$SCRIPT")},\"wait\":false}" --port 9317
sleep 1

# 3. 移动到目标路径（move 会删除源文件）
$CALL "{\"cmd\":\"run\",\"script\":\"files.move('$TMP_NAME','$PHONE_PATH');\",\"name\":\".move_tmp.js\",\"wait\":false}" --port 9317
```

> 若移动失败，残留的 `.save_tmp_*.js` 临时文件可在手机 `{dir_path}` 根目录手动清理。

- `project.json` 中 `name` 为 `my-project` → 例：`actions/my_action.js` → 目标 `{dir_path}/my-project/actions/my_action.js`
- 例：`main.js`（根目录） → 目标 `{dir_path}/my-project/main.js`

#### 远程启动已保存的脚本

需注意 推送并保存到手机 将脚本进行了移动, 这种情况下,已保存的脚本路径需要是 移动到的实际位置相对{dir_path}的路径,如上例子应该是 `my-project/actions/my_action.js`
```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"
$CALL '{"cmd":"run","name":"已保存的脚本路径","wait":false}' --port 9317
sleep 3  # 等日志写入
```

> ⚠️ **`run` 命令不回 `command_result`**，`wait=true` 会超时。必须用 `wait=false` + sleep,通过执行完后读取日志检查执行情况.

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
2. 开始规划探索, 规划页面探索流程,并和用户确认. 确认后生成探索规划文档.
3. 根据探索规划文档, 开始探索流程中出现的每个页面，探索方式见 探索与验证方法 章节。对探索的每个页面进行组件分析得到充分认知，记录每个页面的 名字/组件位置/组件特点/文本等,判断组件操作后的结果，并将页面组件记录到组件文档`docs/pages/`。完成当前页面后，自动用脚本操作进入下一个页面，循环操作直到记录所有页面。
4. 基于3中对单个页面的深入研究,生成每个页面的单元操作脚本`{pageNaem}Actions.js`,脚本中每个函数包含 定位元素+操作元素 的原子操作, 开发完成后验证单个操作是否达成目的, 验证方式见 探索与验证方法 章节, 可以 边探索边生成边验证。
5. 所有页面探索完成后, 基于对每个页面的深入研究，生成 `actions/Pages.js`, `actions/Pages.js` 中包含每个页面名常量(可以直观关联到对应的 `{pageNaem}Actions.js`),包含`checkPage()` 方法: 根据 OCR/组件树特征判断当前页面，返回对应的页面常量或 `"unknown"`。模板代码见 `sample-project/actions/Pages.js`, 这个方法可供每个动作的检查和验证环节使用（知道当前在哪个页面很重要）。
6. 根据流程和页面的研究结果，开发流程的操作的脚本(一系列原子操作的组合), 如果流程分支复杂,可以将流程分解为多个子流程的操作脚本, 然后用总流程管理子流程.
7. 完成脚本编写，提示用户测试验证。

### 探索与验证方法

当需要探索页面和验证操作时，在 `devtools/` 下编辑好脚本，然后推送执行（`run` 指令，探索脚本为单脚本，不会保存到手机，因此 **不能使用 `lib/` 下的模块**，所有代码必须内联），最后拉取执行日志来分析，达成探索和验证的目的。

#### 如何探索

**探索页面时， 应该尽可能多的获取到全部页面信息来进行分析然后保存到文档，而不是仅针对当前任务中的特征来分析，因为尽可能多的页面信息可以使脚本更健壮，也方便后续测试和维护。**

使用 `devtools/explore_template.js` 脚本模板进行检查(如果自己进入页面,需要复制后修改 TODO 使用,如果用户已经进入指定页面,可以直接使用)：

探索脚本逻辑为：
1. 检查项目下有没有tmp目录，没有就先创建
2. 进入页面
3. **截图 + mlkocr**：
    探索/验证时脚本中**使用 Shizuku 执行 `screencap` 截图**（无需申请截图权限，无需弹窗），固定保存到当前项目的tmp目录。然后读取截图后用 `mlkocr` 识别文字，识别结果写入log。
    **如果 Shizuku 截图失败，提示用户检查 Shizuku 是否运行，而不是自作主张采用其他方法**
4. **Dump 组件树**：获取当前界面 UI 组件树 XML，分析组件的 className、desc、text、bounds、clickable 等属性


同一页面，将该脚本逻辑执行多次(一般在进入后依次间隔500ms 1s 3s秒各执行一次,执行3次,以判断开屏广告,开屏弹窗,加载等待等各种状态)，获得尽可能全部可能的OCR结果和组件树dump结果。每执行依次, 将探索过程获取的截图/OCR/DUMP结果都保存到对应页面的探索文档目录下( `/docs/explore/页面/`), 待脚本多次执行完成后，结合多次 OCR 结果和组件树信息，分析当前页面，并在docs目录下记录页面文档信息。
**如果上述方式分析出的信息无法达成流程要求，可以在申请用户同意后，将截图拉取到项目中，使用look_at分析图片，这种操作必须申请用户同意后才可实施。**

#### 如何验证
验证操作时，项目脚本逻辑为：
1. 检查项目下有没有tmp目录，没有就先创建
2. 进入操作的前置页面
3. 执行单元操作
4. 截图 + mlkocr + dump组件树（方案同探索中的2、3），判断操作后的页面和页面组件是否和预期一致。

### 探索工具（手动快速探索）

`explorer/` 目录提供了一个 Web 工具，用于手动快速探索页面并记录结果。它是全局常驻服务，与具体项目解耦，启动后可在浏览器中选择项目进行操作。

**启动：**
```bash
python3 ${skill_base_dir}/autoxjs-developer/explorer/server.py --http-port 5000
```

**功能：**
1. 启动后在浏览器打开 `http://localhost:5000`，选择项目
2. 在 `flow.json` 中定义所有需探索的页面及跳转关系（存储在项目 `docs/flow.json`）
3. 选择页面 → 点击「探索」→ 自动执行 Shizuku 截图 + OCR + Dump
4. 探索结果保存在项目 `docs/explore/{页面ID}/` 目录
5. 在页面上切换 OCR/DUMP 叠加层，查看组件识别结果
6. 点击组件标注跳转，自动记录到 `flow.json`

**流程：**
1. 先规划探索流程，通过「+ 添加页面」添加所有待探索页面
2. 选择一个页面，在手机上手动进入该页面，点击「探索」
3. 等待探索完成，查看截图和 OCR/DUMP 结果
4. 点击组件标注跳转目标页面
5. 重复 2-4 直到所有页面探索完毕
6. `flow.json` 和 `explore/` 目录可供 AI 后续分析使用


### 开发要求与约定：
对非devtool下的代码(即在项目中实际运行的代码,主要包括main.js, actions/, test/ 下代码)，在开发时有如下要求：

#### 整体流程，需要模块清晰，与流程文档一致，尽量将一组相关的操作封装为一个函数

#### **如下功能必须使用 lib 下的模块**
##### 模块化导入时必须使用 `singletonRequirer` 
##### 截图权限获取时必须使用`ScreenCapturePermissionUtil`，仅可在main.js入口时调用一次
##### 所有点击操作禁止直接调用组件的 `.click()` 方法，点击操作必须使用`HumanClick`
##### 授权无障碍时先使用 Shizuku 管理 `ShizukuUtils`，如果绑定失败或授权失败再使用auto()申请人工授权


#### 对流程的操作脚本，需要采取 检查 → 执行 → 验证 的执行三步方案闭环：

- **检查**：判断当前所在页面是否符合流程要求，确认是否需要执行操作
- **执行**：执行具体操作
- **验证**：确认操作生效，跳转到了复合流程的页面/达成了效果

对于连续操作，上一步的验证和下一步的检查可以合并（上一步的完成条件和下一步的检查条件一致时不做重复检查）。

代码中使用注释体现每一步操作的流程结构：

参考 `actions/SignIn_sample.js`，其中演示了如何在 `[检查]` / `[执行]` / `[验证]` 三段中使用 `Pages.checkPage()` 判断页面状态。

#### 对每个Page中的Actions中的操作的定位方案做要求

代码中，如果需要定位目标元素，需按以下优先级选择操作定位方式，这些方式在探索页面时就要考虑哪种更可靠：

1. **🥇 组件查找（首选）** — `desc()` / `text()` / `className()` / `id()` 等选择器
   - 优点：稳定、不受屏幕分辨率影响
   - 缺点: 可能会有隐藏的组件干扰定位(如有些app开屏广告时就可以通过组件查找找到menu,但实际看不到且点不到menu)
   - 使用：`desc("按钮").findOne(3000)`、`textContains("确认").click()`
2. **🥈 OCR 文字识别（mlkocr）** — 当组件无 desc/text 属性时使用
   - 使用：截图 → 裁剪 → mlkocr 识别文字坐标 → 点击坐标
   - **正式脚本**中使用 `captureScreen()` 截图
   - 因为 mlkocr 识别结果不理想，使用**模糊匹配**，每个组件的模糊匹配可以单独封装成一个函数：
     - **目标文字量多**（≥3个字）：匹配词组量达到目标词组的 60% 以上,且匹配到的词组前后顺序与标准语句的前后一致, 即通过
     - **目标文字量少**（<3个字）：所有文字相似的结果都纳入匹配，只要命中其中一个即通过
3. **🥉 找色** — 当 OCR 也无法获取有效信息时使用
   - 从截图中取特征颜色点，使用 `findColor()` / `findColorEquals()` 定位
4. **🏅 找图（最后手段）** — 以上均不行时
   - 裁剪截图中特征区域为模板图，使用 `findImage()` 匹配

#### 对每个Page中的Actions中的操作的点击方案做要求

参考lib.md, **需要使用指定的通用方法进行点击,不可使用元素的click方法**

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
# 清理手机上的临时清理脚本自身
$CALL '{"cmd":"run","script":"files.remove(files.cwd() + \"/.cleanup.js\");","name":".cleanup_self.js","wait":false}' --port 9317
```
