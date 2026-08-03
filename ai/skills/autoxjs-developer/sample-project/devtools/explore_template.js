"autojs";
/*
 * 页面探索通用脚本
 * ================
 * 用法：复制此文件，修改 TODO 部分，通过 run 指令推送执行。
 * 注意：此脚本为单脚本推送，不能使用 lib/ 下的模块，所有代码必须内联。
 *
 * 输出：结果写入指定路径的 JSON 文件，结构如下：
 *   {
 *     "screenshot": "tmp/diag.png",
 *     "ocr": [ { "label": "...", "bounds": {...} } ],
 *     "components": {
 *       "clickable": [ ... ],
 *       "textNodes": [ ... ],
 *       "descNodes": [ ... ],
 *       "classSummary": { "android.widget.TextView": 10, ... }
 *     }
 *   }
 *
 * 筛选逻辑参考 AutoScriptBase 控件可视化工具（控件可视化/index.html）：
 * 多维度筛选链路：可见性(visibleOnly) → 内容(hasContent) → 在屏(inScreen) → 属性(filterFunc)
 * 替代仅依赖单一 className 匹配的不完整方式。
 */

// TODO: 输出 JSON 文件路径
var OUTPUT_PATH = files.cwd() + "/tmp/explore_result.json";

log("=== 开始 ===");

// ─── 1. 进入目标页面 ─────────────────────────────────
// TODO: 在此编写进入目标页面的操作
// 例如：打开某 App、点击某按钮等
// app.launchPackage("com.example.app");
// sleep(3000);

// ─── 2. Shizuku 绑定 + 截图 ─────────────────────────
var proto = Object.getPrototypeOf($shizuku);
if (!proto.isRunning()) {
  proto.requestPermission();
  sleep(2000);
  if (!proto.isRunning()) {
    var clazz = proto.getClass();
    var bindMethod = clazz.getDeclaredMethod("bindUserService");
    bindMethod.setAccessible(true);
    bindMethod.invoke(proto);
    sleep(3000);
  }
}
if (!proto.isRunning()) {
  log("❌ Shizuku 未运行，请检查 Shizuku 是否已启动");
  exit();
}
log("✓ Shizuku 已绑定");

var tmpDir = files.cwd() + "/tmp";
// 清空 tmp 目录，确保每次执行结果独立
if (files.exists(tmpDir)) {
  files.removeDir(tmpDir);
}
if (!files.exists(tmpDir)) {
  files.ensureDir(tmpDir);
}
var picPath = tmpDir + "/diag.png";
log("截图保存路径: " + picPath);
var result = $shizuku("screencap -p " + picPath);
if (result.code !== 0) {
  log("❌ 截图失败: " + result.error);
  exit();
}
log("✓ 截图成功");

// ─── 3. MLKit OCR 识别 ───────────────────────────────
var img = images.read(picPath);
if (!img) {
  log("❌ 无法读取截图");
  exit();
}
var raw = $mlKitOcr.detect(img);
log("OCR 结果数量: %d", raw ? raw.length : 0);
var ocrResults = [];
for (var i = 0; i < (raw ? raw.length : 0); i++) {
  ocrResults.push({
    label: raw[i].label,
    bounds: {
      left: raw[i].bounds.left,
      top: raw[i].bounds.top,
      right: raw[i].bounds.right,
      bottom: raw[i].bounds.bottom
    }
  });
}
img.recycle();

// ─── 4. Dump 组件树 + 多维度筛选 ────────────────────
// 参考控件可视化工具的筛选链路：
//   visibleOnly (可见性) → hasContent (内容) → inScreen (在屏) → filterFunc (属性)
// 按维度依次输出，替代原仅查 className("android.widget.Button") 的单一方式。
// 注：不依赖 UiSelector.dump()（该 API 不存在），通过 auto.service 直接访问组件树。
var components = {};
if (auto.service != null) {
  log("=== 组件树关键节点 ===");

  // 维度1: 可见 + 可点击（不限于 Button，含 ImageView/TextView/View 等可点击节点）
  log("--- 可见·可点击节点 ---");
  var clickableNodes = visibleToUser(true).clickable(true).find();
  log("可点击可见组件数量: %d", clickableNodes.size());
  components.clickable = [];
  for (var j = 0; j < clickableNodes.size(); j++) {
    var w = clickableNodes.get(j);
    var info = {
      className: w.className(),
      desc: w.desc(),
      text: w.text(),
      bounds: w.bounds()
    };
    components.clickable.push(info);
  }

  // 维度2: 可见 + 有文本内容（类似 hasContent 筛选）
  log("--- 可见·有文本节点 ---");
  var textNodes = visibleToUser(true).textMatches(".+").find();
  log("有文本节点数量: %d", textNodes.size());
  components.textNodes = [];
  for (var j = 0; j < textNodes.size(); j++) {
    var w = textNodes.get(j);
    components.textNodes.push({
      className: w.className(),
      text: w.text(),
      bounds: w.bounds()
    });
  }

  // 维度3: 可见 + 有描述内容（desc）
  log("--- 可见·有desc节点 ---");
  var descNodes = visibleToUser(true).descMatches(".+").find();
  log("有desc节点数量: %d", descNodes.size());
  components.descNodes = [];
  for (var j = 0; j < descNodes.size(); j++) {
    var w = descNodes.get(j);
    components.descNodes.push({
      className: w.className(),
      desc: w.desc(),
      bounds: w.bounds()
    });
  }

  // 维度4: 可见控件按 className 分类汇总（了解页面组件构成，方便定位目标）
  log("--- 可见控件按类型汇总 ---");
  var allVisible = visibleToUser(true).find();
  var classSummary = {};
  for (var j = 0; j < allVisible.size(); j++) {
    var cn = allVisible.get(j).className();
    classSummary[cn] = (classSummary[cn] || 0) + 1;
  }
  components.classSummary = classSummary;
}

// ─── 5. 写入 JSON 文件 ───────────────────────────────
var output = {
  screenshot: picPath,
  ocr: ocrResults,
  components: components
};
files.write(OUTPUT_PATH, JSON.stringify(output, null, 2));
log("✓ 结果已保存到: " + OUTPUT_PATH);

log("=== 完毕 ===");