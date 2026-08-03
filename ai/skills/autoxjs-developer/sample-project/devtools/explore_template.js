"autojs";
/*
 * 页面探索通用脚本
 * ================
 * 用法：复制此文件，修改 TODO 部分，通过 run 指令推送执行。
 * 注意：此脚本为单脚本推送，不能使用 lib/ 下的模块，所有代码必须内联。
 *
 * 筛选逻辑参考 AutoScriptBase 控件可视化工具（控件可视化/index.html）：
 * 多维度筛选链路：可见性(visibleOnly) → 内容(hasContent) → 在屏(inScreen) → 属性(filterFunc)
 * 替代仅依赖单一 className 匹配的不完整方式。
 */

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
for (var i = 0; i < (raw ? raw.length : 0); i++) {
  log("OCR[%d]: label=%s bounds=[%d,%d,%d,%d]",
    i, raw[i].label,
    raw[i].bounds.left, raw[i].bounds.top,
    raw[i].bounds.right, raw[i].bounds.bottom);
}
img.recycle();

// ─── 4. Dump 组件树 + 多维度筛选 ────────────────────
// 参考控件可视化工具的筛选链路：
//   visibleOnly (可见性) → hasContent (内容) → inScreen (在屏) → filterFunc (属性)
// 按维度依次输出，替代原仅查 className("android.widget.Button") 的单一方式。
var xml = UiSelector.dump();
if (xml) {
  log("=== 组件树关键节点 ===");

  // 维度1: 可见 + 可点击（不限于 Button，含 ImageView/TextView/View 等可点击节点）
  log("--- 可见·可点击节点 ---");
  var clickableNodes = visibleToUser(true).clickable(true).find();
  log("可点击可见组件数量: %d", clickableNodes.size());
  for (var j = 0; j < clickableNodes.size(); j++) {
    var w = clickableNodes.get(j);
    log("  clickable[%d]: className=%s desc=%s text=%s bounds=%s",
      j, w.className(), w.desc(), w.text(), JSON.stringify(w.bounds()));
  }

  // 维度2: 可见 + 有文本内容（类似 hasContent 筛选）
  log("--- 可见·有文本节点 ---");
  var textNodes = visibleToUser(true).textMatches(".+").find();
  log("有文本节点数量: %d", textNodes.size());
  for (var j = 0; j < textNodes.size(); j++) {
    var w = textNodes.get(j);
    log("  text[%d]: className=%s text=%s bounds=%s",
      j, w.className(), w.text(), JSON.stringify(w.bounds()));
  }

  // 维度3: 可见 + 有描述内容（desc）
  log("--- 可见·有desc节点 ---");
  var descNodes = visibleToUser(true).descMatches(".+").find();
  log("有desc节点数量: %d", descNodes.size());
  for (var j = 0; j < descNodes.size(); j++) {
    var w = descNodes.get(j);
    log("  desc[%d]: className=%s desc=%s bounds=%s",
      j, w.className(), w.desc(), JSON.stringify(w.bounds()));
  }

  // 维度4: 可见控件按 className 分类汇总（了解页面组件构成，方便定位目标）
  log("--- 可见控件按类型汇总 ---");
  var allVisible = visibleToUser(true).find();
  var classSummary = {};
  for (var j = 0; j < allVisible.size(); j++) {
    var cn = allVisible.get(j).className();
    classSummary[cn] = (classSummary[cn] || 0) + 1;
  }
  var sortedClasses = Object.keys(classSummary).sort(function (a, b) {
    return classSummary[b] - classSummary[a];
  });
  for (var k = 0; k < sortedClasses.length; k++) {
    log("  %s: %d个", sortedClasses[k], classSummary[sortedClasses[k]]);
  }
}

log("=== 完毕 ===");