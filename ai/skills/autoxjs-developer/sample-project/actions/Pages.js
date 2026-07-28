/**
 * 页面识别模块
 * ============
 * 由探索阶段生成，根据 OCR/组件树特征判断当前页面。
 *
 * 使用方式：
 *   let singletonRequirer = require('../lib/SingletonRequirer.js')(runtime, this);
 *   let Pages = singletonRequirer('/actions/Pages');
 *   Pages.checkPage();          // 返回当前页面常量
 *   Pages.checkPage() === Pages.PAGES.HOME;  // 判断是否在某个页面
 *
 * 探索阶段：在 docs/pages/ 下为每个页面命名并记录特征
 * 开发阶段：在 PAGES 中添加常量，在 checkPage() 中补充匹配逻辑
 */

// 页面常量（探索阶段在 docs/pages/ 中为每个页面命名后补充）
const PAGES = {
  HOME: "主页",
  MEMBER: "会员页",
  // TODO: 探索后补充其他页面
};

/**
 * 判断当前所在页面
 * 根据一次截图 OCR + 组件树特征识别当前页面
 * @returns {string} 页面常量值，无法识别时返回 "unknown"
 */
function checkPage() {
  try {
    var img = captureScreen();
    if (!img) return "unknown";

    var raw = $mlKitOcr.detect(img);
    img.recycle();

    if (!raw || raw.length === 0) return "unknown";

    // TODO: 根据 OCR 结果和组件特征匹配页面
    // 示例：if (raw.some(function(r) { return r.label.indexOf("会员") >= 0; })) return PAGES.MEMBER;

    return "unknown";
  } catch (e) {
    log("checkPage 异常: " + e);
    return "unknown";
  }
}

module.exports = { checkPage, PAGES };