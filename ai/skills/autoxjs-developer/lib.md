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
