项目lib下包含多个公用功能模块，如果项目中使用到则必须使用lib下的模块，以规范统一。

#### 模块引用：必须使用 SingletonRequirer，禁止直接 require

**源文件**：`lib/SingletonRequirer.js`

AutoJS 的 `require()` 没有模块缓存，直接 `require` 会导致多实例、状态分裂、循环依赖等问题。

```javascript
// ❌ 错误
let myModule = require('./lib/MyModule.js')

// ✅ 正确
let singletonRequirer = require('./lib/SingletonRequirer.js')(runtime, this)
let myModule = singletonRequirer('MyModule')
let { someFunc } = singletonRequirer('MyModule')  // 支持解构
```

#### SingletonRequirer 使用规范

**1. `require` 路径必须使用相对路径**

```javascript
// 从项目根目录加载
var singletonRequire = require('./lib/SingletonRequirer.js')(runtime, this)

// 从 ui/ 子目录加载
var singletonRequire = require('../lib/SingletonRequirer.js')(runtime, this)
```

**2. `singletonRequire` 调用必须在文件顶部，禁止在函数内部调用**

所有模块依赖必须在文件顶部一次性加载，确保统一使用 `/` 前缀绝对路径，避免重复加载和实例不一致。

```javascript
// ✅ 正确：文件顶部加载，/ 前缀绝对路径
var singletonRequire = require('../lib/SingletonRequirer.js')(runtime, this)
var screenUtils = singletonRequire('/lib/ScreenUtils')
var S = singletonRequire('./StateContainer').S
var frameConfig = singletonRequire('/ui/FrameConfig')

// ❌ 错误：在函数内部调用
function doSomething() {
  var screenUtils = singletonRequire('/lib/ScreenUtils')  // 禁止
}
```


### 截图权限获取：`ScreenCapturePermissionUtil`

**源文件**：`lib/ScreenCapturePermissionUtil.js`

| 方法 | 说明 |
|------|------|
| `requestScreenPermission()` | 申请截图权限（非阻塞弹窗），轮询等待就绪，成功返回 true |

```javascript
let singletonRequirer = require('./lib/SingletonRequirer.js')(runtime, this)
let captureUtil = singletonRequirer('ScreenCapturePermissionUtil')
if (!captureUtil.requestScreenPermission()) exit();
```

#### Shizuku 管理：`ShizukuUtils`

**源文件**：`${skill_base_dir}/lib/ShizukuUtils.js`
| 方法 | 说明 |
|------|------|
| `ensureShizuku()` | 绑定 Shizuku 服务（自动处理过期引用），成功返回 true |
| `enableAccessibility()` | 通过 Shizuku 启用无障碍服务，成功返回 true |

**模块化使用**（通过 SingletonRequirer）：
```javascript
let singletonRequirer = require('./lib/SingletonRequirer.js')(runtime, this)
let shizukuUtil = singletonRequirer('ShizukuUtils')
if (!shizukuUtil.ensureShizuku()) exit();
shizukuUtil.enableAccessibility();
```

#### 点击操作：`HumanClick`

**源文件**：`lib/HumanClick.js`

| 方法 | 说明 |
|------|------|
| `humanClickRect(region)` | 在区域内模拟人为点击（随机偏移 + 滑入手势） |
| `randRange(min, max)` | 辅助函数：随机整数 [min, max] |
| `randInt(max)` | 辅助函数：随机整数 [0, max) |

`region` 格式：`{ left, top, right, bottom }`，来自 OCR 识别结果或组件 `bounds()`。

**所有点击操作禁止直接调用组件的 `.click()` 方法**，必须使用 `humanClickRect()`。
```javascript
let singletonRequirer = require('./lib/SingletonRequirer.js')(runtime, this)
let { humanClickRect } = singletonRequirer('HumanClick')
humanClickRect(region)
```

#### 持久化存储：`LockableStorage`

**源文件**：`lib/LockableStorage.js`

底层使用 Android `SharedPreferences` + `.commit()`（同步写入，返回 boolean），适合多脚本互斥场景。

| 方法 | 说明 |
|------|------|
| `LockableStorage.put(key, value)` | 写入字符串，返回 boolean 表示是否成功 |
| `LockableStorage.get(key, defaultValue)` | 读取字符串，不存在返回 defaultValue |
| `LockableStorage.clear()` | 清空所有数据 |
| `lockableStorages.create(name)` | 创建/获取指定 name 的存储实例 |
| `lockableStorages.remove(name)` | 创建实例并清空 |

**用法**：

```javascript
let singletonRequirer = require('./lib/SingletonRequirer.js')(runtime, this)
let { create } = singletonRequirer('LockableStorage')
let storage = create('my_config')
storage.put('key1', 'hello')
let val = storage.get('key1')       // 'hello'
let ok = storage.put('lock', '1')   // true=写入成功，false=被其他脚本锁住
storage.clear()
```

**典型场景**：多脚本互斥锁（如 `RunningQueueDispatcher` 中的 `WRITE_LOCK_KEY` 争用）。`.commit()` 是阻塞同步的，能立即返回是否写入成功，而 `.apply()` 异步不返回结果。因此用 `.commit()` 做锁判断。`LockableStorage` 本身只负责读/写，锁的争用逻辑由调用方实现（如 `RunningQueueDispatcher.lock()`）。
