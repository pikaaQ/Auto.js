/**
 * Shizuku 管理：绑定 + 无障碍服务
 * 源文件：${skill_base_dir}/lib/ShizukuUtils.js
 *
 * 方法：
 *   ensureShizuku()      — 绑定 Shizuku 服务，成功返回 true
 *   enableAccessibility() — 启用无障碍服务，成功返回 true
 *
 * 使用前需确保 Shizuku App 已在手机运行。
 */

function ShizukuUtils() {

  /**
   * 确保 Shizuku 服务已绑定（脚本入口调用一次）。
   * 自动处理旧引用清理和重新绑定。
   * @returns {boolean} 是否绑定成功
   */
  this.ensureShizuku = function () {
    var proto = Object.getPrototypeOf($shizuku);
    if (proto.isRunning()) {
      // 检查 userService 是否过期（Binder 断开后引用可能残留）
      if (!proto.isShizukuRunning()) {
        var clazz = proto.getClass();
        var field = clazz.getDeclaredField("userService");
        field.setAccessible(true);
        field.set(proto, null);
      } else {
        return true;
      }
    }

    // 请求权限绑定
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

    if (!proto.isRunning()) {
      log("❌ Shizuku 绑定失败，请检查 Shizuku 是否运行");
      return false;
    }
    log("✓ Shizuku 已绑定");
    return true;
  };

  /**
   * 通过 Shizuku settings 命令启用无障碍服务。
   * @returns {boolean} 是否成功
   */
  this.enableAccessibility = function () {
    var svc = context.getPackageName() + "/com.jy.recorder.AccessibilityService";
    // 或直接指定：var svc = "com.jy.recorder.modify/com.jy.recorder.AccessibilityService";

    var r1 = $shizuku("settings put secure enabled_accessibility_services " + svc);
    if (r1.code !== 0) {
      log("❌ 设置无障碍服务失败: " + r1.error);
      return false;
    }

    var r2 = $shizuku("settings put secure accessibility_enabled 1");
    if (r2.code !== 0) {
      log("❌ 启用无障碍失败: " + r2.error);
      return false;
    }

    log("✓ 无障碍服务已启用");
    return true;
  };
}

module.exports = new ShizukuUtils();