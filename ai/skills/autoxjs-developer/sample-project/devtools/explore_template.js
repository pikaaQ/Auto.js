"autojs";
/*
 * 页面探索通用脚本
 * ================
 * 用法：复制此文件，修改 TODO 部分，通过 run 指令推送执行。
 * 注意：此脚本为单脚本推送，不能使用 lib/ 下的模块，所有代码必须内联。
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

// ─── 4. Dump 组件树 ─────────────────────────────────
var xml = UiSelector.dump();
if (xml) {
  // 输出关键组件信息（desc/text/className/bounds/clickable）
  log("=== 组件树关键节点 ===");
  // TODO: 根据实际需要解析 XML 或使用选择器查找关键组件
  // 示例：查找所有可点击的组件
  var clickables = className("android.widget.Button").find();
  log("可点击按钮数量: %d", clickables.size());
  for (var j = 0; j < clickables.size(); j++) {
    var w = clickables.get(j);
    log("  Button[%d]: desc=%s text=%s bounds=%s",
      j, w.desc(), w.text(), JSON.stringify(w.bounds()));
  }
}

log("=== 完毕 ===");