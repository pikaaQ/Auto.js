/**
 * 模拟人为点击操作
 * 源文件：${skill_base_dir}/lib/HumanClick.js
 *
 * 方法：
 *   humanClickRect(region) — 在指定区域内模拟人为点击（随机偏移 + 滑入手势）
 *   randRange(min, max)    — 随机整数 [min, max]
 *   randInt(max)           — 随机整数 [0, max)
 *
 * region 格式：{ left, top, right, bottom }，来自 OCR 识别结果或组件 bounds()
 *
 * 所有点击操作禁止直接调用组件的 .click() 方法，必须使用 humanClickRect()。
 */

function HumanClick() {

  /**
   * 随机整数 [min, max]
   * @param {number} min
   * @param {number} max
   * @returns {number}
   */
  this.randRange = function (min, max) {
    return min + Math.floor(Math.random() * (max - min + 1));
  };

  /**
   * 随机整数 [0, max)
   * @param {number} max
   * @returns {number}
   */
  this.randInt = function (max) {
    return Math.floor(Math.random() * max);
  };

  /**
   * 在指定区域内模拟人为点击。
   * 特性：区域内随机偏移（避免每次点击位置固定）、手指从附近滑入（而非直接出现在目标点）。
   * @param {{ left: number, top: number, right: number, bottom: number }} region
   */
  this.humanClickRect = function (region) {
    var self = this;
    // 在区域内随机偏移（避免每次点击位置固定，被检测为自动化）
    var marginX = Math.round((region.right - region.left) * 0.22);
    var marginY = Math.round((region.bottom - region.top) * 0.22);
    var x = region.left + marginX + self.randInt(region.right - region.left - 2 * marginX);
    var y = region.top + marginY + self.randInt(region.bottom - region.top - 2 * marginY);

    // 模拟手指从附近滑入（而非直接出现在目标点上）
    var approachX = x + self.randRange(-8, 8);
    var approachY = y + self.randRange(-8, 8);
    var pressDuration = 60 + self.randInt(40);

    gesture(pressDuration + self.randInt(30),
      [approachX, approachY, 10],
      [x, y, pressDuration]);

    log("[点击] (%d,%d) 区域 [%d,%d-%d,%d]", x, y, region.left, region.top, region.right, region.bottom);
  };
}

module.exports = new HumanClick();