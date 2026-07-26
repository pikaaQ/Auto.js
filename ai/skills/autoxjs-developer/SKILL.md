---
name: autoxjs-developer
description: "AutoX.js 脚本开发助手。侧重脚本编写、推送、调试、诊断的完整开发流程。依赖 autoxjs-connector 提供手机连接能力。Triggers: 当需要编写/调试/推送 AutoX.js 脚本、分析日志、诊断手机端问题时自动激活。不处理连接协议。"
---

# AutoX.js 脚本开发

依赖 `autoxjs-connector` 技能提供的手机连接能力。

⚠️ **在和手机交互时仅能使用 `autoxjs-connector` 技能提供的手机连接能力，禁止使用adb、新建http服务等方案，如果`autoxjs-connector` 技能中的能力失败，应该告知用户，不要自作主张使用其他方案。**
⚠️ **严格遵守开发和探索中的方案和准则，不要自作主张采用其他方案**

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

## 项目目录结构约定

项目根目录采用**双层结构**，同名子目录存放实际项目源码，与手机同步：

```
my-script-project/                ← 项目根目录（git / opencode / 辅助文件）
├── my-script-project/            ← 同名子目录，实际项目源码，与手机双向同步
│   ├── project.json              ← 项目定义（必需，格式见下方）
│   ├── main.js                   ← 入口脚本
│   └── ...                       ← 其他脚本/资源文件
├── my-script-project_test/       ← 诊断项目，用于截图OCR诊断，长期存在
│   ├── project.json              ← name: "{project_name}_test", main: "main.js"
│   ├── main.js                   ← 诊断脚本，每次按需修改
│   └── pic/
│       └── .gitkeep              ← 占位，诊断时替换为当前截图 diag.png
├── phone_data/                   ← 从手机拉取的文件（日志、截图等），不同步到手机
├── .omo/                         ← opencode 配置
├── .git/                         ← 版本控制
├── README.md                     ← 项目说明
└── ...                           ← 编译临时文件等辅助路径
```

> 推送项目时 `project_dir` 指向**同名子目录**（即包含 `project.json` 的目录），而非根目录。

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

## lib 模块

> 以下模块的源文件位于 `${skill_base_dir}/lib/`，开发新项目时可直接复制到项目 `lib/` 目录下。

### SingletonRequirer — 模块引用机制（必读） <a id="singleton-requirer"></a>

**源文件**：`${skill_base_dir}/lib/SingletonRequirer.js`（依赖 `${skill_base_dir}/lib/PrintExceptionStack.js`）

**⚠️ 当项目代码拆分多个模块时，必须使用 SingletonRequirer，禁止直接 `require`。**

AutoJS 的 `require()` **没有模块缓存**——每次调用 `require('./foo.js')` 都会重新执行整个文件，返回全新的实例。这与 Node.js 的 `require()` 行为完全不同。

SingletonRequirer 在 `global` 对象上模拟了模块缓存层，确保所有模块共享同一个实例。

**❌ 禁止写法：**
```javascript
let myModule = require('./lib/MyModule.js')
let anotherModule = require('./lib/AnotherModule.js')
```

**✅ 正确写法：**
```javascript
let singletonRequire = require('./lib/SingletonRequirer.js')(runtime, this)
let myModule = singletonRequire('MyModule')
let anotherModule = singletonRequire('AnotherModule')
```

**特性**：

| 特性 | 说明 |
|------|------|
| 懒加载 | 首次访问时才 `require()`，减少启动开销 |
| 全局单例 | 所有模块通过 `global` 共享同一实例，状态一致 |
| 循环依赖解耦 | 延迟加载自动断开循环依赖链 |
| 使用追踪 | 内置 `useCount` 统计，可通过 `singletonRequire('ModuleName', true)` 打印 |

**模块内部引用其他模块**：
```javascript
// 在 lib/MyModule.js 内部
let singletonRequire = require('./SingletonRequirer.js')(runtime, global)
let OtherModule = singletonRequire('OtherModule')

function MyModule() {
  this.doSomething = function () {
    OtherModule.helper()
  }
}
module.exports = new MyModule()
```

**直接 require 的后果**：

| 问题 | 后果 |
|------|------|
| 多实例 | 状态不共享，全局状态分裂 |
| 重复初始化 | 模块被重复创建，浪费内存 |
| 循环依赖 | 模块 A require B，B require A → 死锁或拿到 undefined |


## 基础指令
### 脚本根目录探测与缓存（首次开发前执行）

开发过程中的日志拉取、脚本保存等操作需要知道手机上的脚本根目录。此路径**不是固定的**，取决于 App 语言（中文 `/脚本/`、英文 `/Scripts/`）和用户自定义设置。

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

使用 `save_project`（仅保存）或 `run_project`（保存并执行）推送整个项目目录到手机。

> ⚠️ `project_dir` 指向**同名子目录**（包含 `project.json` 的目录），而非项目根目录。

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

```bash
CALL="python3 ${skill_base_dir}/autoxjs-connector/call.py"
$CALL "{\"cmd\":\"save_project\",\"project_dir\":\"/path/to/your/project\"}" --port 9317
```

#### 执行项目（不保存到本地）
与`save_project`方案一致，将 `save_project` 改为 `run_project` 即可。

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
3. 根据流程和页面的研究结果，依次开发流程中每个步骤的脚本，开发完成后验证单个步骤是否复合要求，验证方式见 探索与验证方法 章节。
4. 将所有步骤根据流程进行组合，完成脚本编写，提示用户测试验证。

### 探索与验证方法

当需要探索页面和验证操作时，使用test目录的项目来进行。先在test目录项目中编辑好脚本，然后推送执行，最后拉取执行日志来分析，达成探索和验证的目的。

#### 如何探索

探索页面时，test项目脚本逻辑为：
1. 进入页面
2. **截图 + mlkocr**：
    探索/验证时脚本中**使用 Shizuku 执行 `screencap` 截图**（无需申请截图权限，无需弹窗），固定保存到当前test项目在手机中的目录下的pic目录。然后读取截图后用 `mlkocr` 识别文字，识别结果写入log。
    **如果 Shizuku 截图失败，提示用户检查 Shizuku 是否运行，而不是自作主张采用其他方法**

示例（含绑定 + 截图）：
```javascript
// === Shizuku 绑定 ===
var proto = Object.getPrototypeOf($shizuku);
if (!proto.isRunning()) {
  proto.requestPermission();
  sleep(2000);
  // 已授权时 requestPermission 不会重新触发回调，用反射直接绑定
  if (!proto.isRunning()) {
    var clazz = proto.getClass();
    var bindMethod = clazz.getDeclaredMethod("bindUserService");
    bindMethod.setAccessible(true);
    bindMethod.invoke(proto);
    sleep(3000);
  }
}

// === 截图 ===
var path = files.cwd() + '/pic/diag.png';
log('截图保存路径: ' + path);
var result = $shizuku("screencap -p " + path);
if (result.code !== 0) {
  log('截图失败: ' + result.error);
  exit();
}

// 读取截图并用 mlkocr 识别文字
var img = images.read(path);
var raw = $mlKitOcr.detect(img);
log('OCR 结果数量: %d', raw ? raw.length : 0);
for (var i = 0; i < (raw ? raw.length : 0); i++) {
  log('OCR[%d]: label=%s bounds=[%d,%d,%d,%d]',
    i, raw[i].label,
    raw[i].bounds.left, raw[i].bounds.top,
    raw[i].bounds.right, raw[i].bounds.bottom);
}
img.recycle();
```
3. **Dump 组件树**：获取当前界面 UI 组件树 XML，分析组件的 className、desc、text、bounds、clickable 等属性

待脚本执行完成后，结合 OCR 结果和组件树信息，分析当前页面，并记录页面文档。**如果上述方式分析出的信息无法达成流程要求，可以在申请用户同意后，将截图拉取到项目中，使用look_at分析图片，这种操作必须申请用户同意后才可实施。**

#### 如何验证
验证操作时，test项目脚本逻辑为：
1. 进入操作的前置页面
2. 执行单元操作
3. 截图 + mlkocr + dump组件树（方案同探索中的2、3），判断操作后的页面和页面组件是否和预期一致。


### 开发要求与约定：
最终在手机上运行的代码在子目录my-script-project/中，对于这些代码，在开发时有如下要求：

#### 整体流程，需要模块清晰，与流程文档一致，尽量将一组相关的操作封装为一个函数

#### 模块引用：必须使用 SingletonRequirer，禁止直接 require

AutoJS 的 `require()` 没有模块缓存，直接 `require` 会导致多实例、状态分裂、循环依赖等问题（详见 [lib 模块 → SingletonRequirer](#singleton-requirer)）。

```javascript
// ❌ 错误
let myModule = require('./lib/MyModule.js')

// ✅ 正确
let singletonRequire = require('./lib/SingletonRequirer.js')(runtime, this)
let myModule = singletonRequire('MyModule')
let { someFunc } = singletonRequire('MyModule')  // 支持解构
```

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
// [验证] 已到达目标页面
waitForActivity("TargetActivity", 5000);
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

#### 截图权限获取：`ScreenCapturePermissionUtil`

**源文件**：`${skill_base_dir}/lib/ScreenCapturePermissionUtil.js`

| 方法 | 说明 |
|------|------|
| `requestScreenPermission()` | 申请截图权限（非阻塞弹窗），轮询等待就绪，成功返回 true |

**模块化使用**（通过 SingletonRequirer）：
```javascript
let singletonRequire = require('./lib/SingletonRequirer.js')(runtime, this)
let captureUtil = singletonRequire('ScreenCapturePermissionUtil')
if (!captureUtil.requestScreenPermission()) exit();
```

**非模块化**：直接复制函数到脚本中即可使用。

#### Shizuku 管理：`ShizukuUtils`

**源文件**：`${skill_base_dir}/lib/ShizukuUtils.js`

| 方法 | 说明 |
|------|------|
| `ensureShizuku()` | 绑定 Shizuku 服务（自动处理过期引用），成功返回 true |
| `enableAccessibility()` | 通过 Shizuku 启用无障碍服务，成功返回 true |

**模块化使用**（通过 SingletonRequirer）：
```javascript
let singletonRequire = require('./lib/SingletonRequirer.js')(runtime, this)
let shizukuUtil = singletonRequire('ShizukuUtils')
if (!shizukuUtil.ensureShizuku()) exit();
shizukuUtil.enableAccessibility();
```

**非模块化**：直接复制函数到脚本中即可使用。

#### 点击操作：`HumanClick`

**源文件**：`${skill_base_dir}/lib/HumanClick.js`

| 方法 | 说明 |
|------|------|
| `humanClickRect(region)` | 在区域内模拟人为点击（随机偏移 + 滑入手势） |
| `randRange(min, max)` | 辅助函数：随机整数 [min, max] |
| `randInt(max)` | 辅助函数：随机整数 [0, max) |

`region` 格式：`{ left, top, right, bottom }`，来自 OCR 识别结果或组件 `bounds()`。

**所有点击操作禁止直接调用组件的 `.click()` 方法**，必须使用 `humanClickRect()`。

**模块化使用**（通过 SingletonRequirer）：
```javascript
let singletonRequire = require('./lib/SingletonRequirer.js')(runtime, this)
let { humanClickRect } = singletonRequire('HumanClick')
humanClickRect(region)
```

**非模块化**：直接复制函数到脚本中即可使用。


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
