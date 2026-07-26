/**
 * 截图权限管理
 * 源文件：${skill_base_dir}/lib/ScreenCapturePermissionUtil.js
 *
 * 方法：
 *   requestScreenPermission() — 申请截图权限（非阻塞弹窗），轮询等待就绪，成功返回 true
 *
 * 禁止使用 requestScreenCapture(true)（阻塞弹窗）。
 */

function ScreenCapturePermissionUtil() {

  /**
   * 申请截图权限（非阻塞弹窗），轮询等待权限真正就绪。
   * @returns {boolean} 是否成功获取权限
   */
  this.requestScreenPermission = function () {
    requestScreenCapture(false);
    var screenWait = 0;
    while (screenWait < 30) {
      var testImg = captureScreen();
      if (testImg) {
        testImg.recycle();
        break;
      }
      sleep(1000);
      screenWait++;
      log("[启动] 等待截图权限... (%d/30)", screenWait);
    }
    if (screenWait >= 30) {
      log("❌ 截图授权超时或失败");
      return false;
    }
    log("[启动] ✓ 截图权限已就绪");
    return true;
  };
}

module.exports = new ScreenCapturePermissionUtil();