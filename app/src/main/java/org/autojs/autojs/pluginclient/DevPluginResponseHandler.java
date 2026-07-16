package org.autojs.autojs.pluginclient;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.Looper;
import android.text.TextUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.core.image.ImageWrapper;
import com.stardust.autojs.core.image.capture.GlobalScreenCapture;
import com.stardust.autojs.core.image.capture.ScreenCaptureRequestActivity;
import com.stardust.autojs.core.image.capture.ScreenCaptureRequester;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.SimpleScriptExecutionListener;
import com.stardust.autojs.project.ProjectLauncher;
import com.stardust.autojs.script.StringScriptSource;
import com.stardust.io.Zip;
import com.stardust.pio.PFiles;
import com.stardust.util.MD5;
import com.stardust.view.accessibility.AccessibilityService;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeInfo;

import org.autojs.autojs.Pref;
import org.autojs.autojs.R;
import org.autojs.autojs.autojs.AutoJs;
import org.autojs.autojs.model.script.Scripts;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okio.ByteString;

/**
 * Created by Stardust on 2017/5/11.
 */

public class DevPluginResponseHandler implements Handler {

    private static final String TAG = "DevPluginRspHandler";

    private final HashMap<String, ScriptExecution> mScriptExecutions = new HashMap<>();
    private final File mCacheDir;

    private Router mRouter = new Router.RootRouter("type")
            .handler("command", new Router("command")
                    .handler("run", data -> {
                        String script = data.get("script").getAsString();
                        String name = getName(data);
                        String id = data.get("id").getAsString();
                        runScript(id, name, script);
                        return true;
                    })
                    .handler("stop", data -> {
                        String id = data.get("id").getAsString();
                        stopScript(id);
                        return true;
                    })
                    .handler("save", data -> {
                        String script = data.get("script").getAsString();
                        String name = getName(data);
                        saveScript(name, script);
                        return true;
                    })
                    .handler("rerun", data -> {
                        String id = data.get("id").getAsString();
                        String script = data.get("script").getAsString();
                        String name = getName(data);
                        stopScript(id);
                        runScript(id, name, script);
                        return true;
                    })
                    .handler("stopAll", data -> {
                        AutoJs.getInstance().getScriptEngineService().stopAllAndToast();
                        return true;
                    })
                    // ── exec: 执行 JS 并返回结果 ──────────────────
                    .handler("exec", data -> {
                        String id = data.get("id").getAsString();
                        JsonElement paramsEl = data.get("params");
                        String script = (paramsEl != null && paramsEl.isJsonObject())
                                ? paramsEl.getAsJsonObject().get("script").getAsString()
                                : "";
                        if (TextUtils.isEmpty(script)) {
                            DevPluginService.getInstance().sendCommandResult(id, false, json("error", "empty script"));
                            return true;
                        }
                        execScript(id, script);
                        return true;
                    })
                    // ── screenshot: 截屏并返回图片 ────────────────
                    .handler("screenshot", data -> {
                        String id = data.get("id").getAsString();
                        takeScreenshot(id);
                        return true;
                    })
                    // ── dump: 获取 UI 组件树 ──────────────────────
                    .handler("dump", data -> {
                        String id = data.get("id").getAsString();
                        dumpUi(id);
                        return true;
                    })
                    // ── pull_file: 拉取手机文件 ────────────────────
                    .handler("pull_file", data -> {
                        String id = data.get("id").getAsString();
                        JsonElement paramsEl = data.get("params");
                        String path = (paramsEl != null && paramsEl.isJsonObject())
                                ? paramsEl.getAsJsonObject().get("path").getAsString()
                                : "";
                        if (TextUtils.isEmpty(path)) {
                            DevPluginService.getInstance().sendCommandResult(id, false, json("error", "empty path"));
                            return true;
                        }
                        pullFile(id, path);
                        return true;
                    }))
            .handler("bytes_command", new Router("command")
                    .handler("run_project", data -> {
                        launchProject(data.get("dir").getAsString());
                        return true;
                    })
                    .handler("save_project", data -> {
                        saveProject(data.get("name").getAsString(), data.get("dir").getAsString());
                        return true;
                    }));


    public DevPluginResponseHandler(File cacheDir) {
        mCacheDir = cacheDir;
        if (cacheDir.exists()) {
            if (cacheDir.isDirectory()) {
                PFiles.deleteFilesOfDir(cacheDir);
            } else {
                cacheDir.delete();
                cacheDir.mkdirs();
            }
        } else {
            cacheDir.mkdirs();
        }
    }

    @Override
    public boolean handle(JsonObject data) {
        return mRouter.handle(data);
    }

    public Observable<File> handleBytes(JsonObject data, JsonWebSocket.Bytes bytes) {
        String id = data.get("data").getAsJsonObject().get("id").getAsString();
        String idMd5 = MD5.md5(id);
        return Observable.fromCallable(() -> {
            File dir = new File(mCacheDir, idMd5);
            Zip.unzip(new ByteArrayInputStream(bytes.byteString.toByteArray()), dir);
            return dir;
        })
                .subscribeOn(Schedulers.io());
    }

    // ─── exec ──────────────────────────────────────────

    @SuppressLint("CheckResult")
    private void execScript(String id, String script) {
        try {
            ExecutionConfig config = new ExecutionConfig();
            config.setWorkingDirectory(Pref.getScriptDirPath());
            AutoJs.getInstance().getScriptEngineService().execute(
                    new StringScriptSource("[exec]" + id, script),
                    new SimpleScriptExecutionListener() {
                        @Override
                        public void onSuccess(ScriptExecution execution, Object result) {
                            JsonObject resultObj = new JsonObject();
                            if (result != null) {
                                resultObj.addProperty("output", String.valueOf(result));
                            }
                            DevPluginService.getInstance().sendCommandResult(id, true, resultObj);
                        }

                        @Override
                        public void onException(ScriptExecution execution, Throwable e) {
                            DevPluginService.getInstance().sendCommandResult(id, false,
                                    json("error", e.getMessage() != null ? e.getMessage() : "unknown error"));
                        }
                    },
                    config);
        } catch (Exception e) {
            DevPluginService.getInstance().sendCommandResult(id, false,
                    json("error", e.getMessage() != null ? e.getMessage() : "execution failed"));
        }
    }

    // ─── screenshot ────────────────────────────────────

    @SuppressLint({"CheckResult", "WrongConstant"})
    private void takeScreenshot(String id) {
        if (GlobalScreenCapture.getInstance().hasPermission()) {
            new Thread(() -> doCaptureAndSend(id), "screenshot-capture").start();
            return;
        }
        ScreenCaptureRequester.Callback callback = (result, data) -> {
            if (result == Activity.RESULT_OK && data != null) {
                new Thread(() -> {
                    try {
                        GlobalScreenCapture.getInstance().initCapture(
                                GlobalAppContext.get(), data, Configuration.ORIENTATION_UNDEFINED);
                        doCaptureAndSend(id);
                    } catch (Exception e) {
                        DevPluginService.getInstance().sendCommandResult(id, false,
                                json("error", e.getMessage() != null ? e.getMessage() : "capture init failed"));
                    }
                }).start();
            } else {
                DevPluginService.getInstance().sendCommandResult(id, false,
                        json("error", "user denied screen capture permission"));
            }
        };
        new android.os.Handler(Looper.getMainLooper()).post(
                () -> ScreenCaptureRequestActivity.request(GlobalAppContext.get(), callback));
    }

    private void doCaptureAndSend(String id) {
        try {
            Image image = GlobalScreenCapture.getInstance().capture();
            Bitmap bitmap = ImageWrapper.toBitmap(image);
            String tempPath = mCacheDir.getAbsolutePath() + "/screenshot_" + id + ".png";
            new File(tempPath).getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(tempPath);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            bitmap.recycle();
            image.close();
            sendScreenshotFile(id, tempPath);
        } catch (Exception e) {
            DevPluginService.getInstance().sendCommandResult(id, false,
                    json("error", e.getMessage() != null ? e.getMessage() : "capture failed"));
        }
    }

    private void sendScreenshotFile(String id, String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            DevPluginService.getInstance().sendCommandResult(id, false,
                    json("error", "screenshot file not found"));
            return;
        }
        Observable.fromCallable(() -> {
            byte[] data = readAllBytes(file);
            file.delete();
            return data;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(data -> {
                    String md5 = MD5.md5(data);
                    ByteString byteString = ByteString.of(data);
                    DevPluginService.getInstance().sendBytes(byteString);

                    JsonObject result = new JsonObject();
                    result.addProperty("path", filePath);
                    result.addProperty("md5", md5);
                    result.addProperty("size", data.length);
                    DevPluginService.getInstance().sendCommandResult(id, true, result);
                }, error ->
                        DevPluginService.getInstance().sendCommandResult(id, false,
                                json("error", error.getMessage() != null ? error.getMessage() : "file read failed"))
                );
    }

    // ─── dump ──────────────────────────────────────────

    private void dumpUi(String id) {
        if (AccessibilityService.Companion.getInstance() == null) {
            DevPluginService.getInstance().sendCommandResult(id, false,
                    json("error", "no accessibility service"));
            return;
        }
        LayoutInspector inspector = AutoJs.getInstance().getLayoutInspector();
        LayoutInspector.CaptureAvailableListener listener = new LayoutInspector.CaptureAvailableListener() {
            @Override
            public void onCaptureAvailable(NodeInfo capture) {
                inspector.removeCaptureAvailableListener(this);
                // Serialize NodeInfo to JSON
                JsonObject dump = nodeInfoToJson(capture);
                JsonObject result = new JsonObject();
                result.add("dump", dump);
                DevPluginService.getInstance().sendCommandResult(id, true, result);
            }
        };
        inspector.addCaptureAvailableListener(listener);
        if (!inspector.captureCurrentWindow()) {
            inspector.removeCaptureAvailableListener(listener);
            DevPluginService.getInstance().sendCommandResult(id, false,
                    json("error", "captureCurrentWindow failed"));
        }
    }

    private JsonObject nodeInfoToJson(NodeInfo node) {
        JsonObject obj = new JsonObject();
        if (node == null) return obj;

        obj.addProperty("className", safeStr(node.getClassName()));
        obj.addProperty("text", node.getText());
        obj.addProperty("packageName", safeStr(node.getPackageName()));
        obj.addProperty("bounds", node.getBounds());
        obj.addProperty("depth", node.getDepth());
        obj.addProperty("drawingOrder", node.getDrawingOrder());
        obj.addProperty("visible", node.getVisibleToUser());
        obj.addProperty("clickable", node.getClickable());
        obj.addProperty("longClickable", node.getLongClickable());
        obj.addProperty("checked", node.getChecked());
        obj.addProperty("scrollable", node.getScrollable());
        obj.addProperty("editable", node.getEditable());
        obj.addProperty("selected", node.getSelected());
        obj.addProperty("enabled", node.getEnabled());
        obj.addProperty("focused", node.getFocused());
        List<NodeInfo> children = node.getChildren();
        if (children != null && !children.isEmpty()) {
            for (int i = 0; i < children.size(); i++) {
                NodeInfo child = children.get(i);
                if (child != null) {
                    obj.add("child" + i, nodeInfoToJson(child));
                }
            }
        }
        return obj;
    }

    // ─── pull_file ─────────────────────────────────────

    @SuppressLint("CheckResult")
    private void pullFile(String id, String path) {
        Observable.fromCallable(() -> readAllBytes(new File(path)))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(data -> {
                    if (data == null || data.length == 0) {
                        DevPluginService.getInstance().sendCommandResult(id, false,
                                json("error", "file empty or not found"));
                        return;
                    }
                    String md5 = MD5.md5(data);
                    ByteString byteString = ByteString.of(data);
                    DevPluginService.getInstance().sendBytes(byteString);

                    JsonObject result = new JsonObject();
                    result.addProperty("path", path);
                    result.addProperty("md5", md5);
                    result.addProperty("size", data.length);
                    DevPluginService.getInstance().sendCommandResult(id, true, result);
                }, error ->
                        DevPluginService.getInstance().sendCommandResult(id, false,
                                json("error", error.getMessage() != null ? error.getMessage() : "read failed"))
                );
    }

    // ─── existing methods ──────────────────────────────

    private void runScript(String viewId, String name, String script) {
        if (TextUtils.isEmpty(name)) {
            name = "[" + viewId + "]";
        } else {
            name = PFiles.getNameWithoutExtension(name);
        }
        mScriptExecutions.put(viewId, Scripts.INSTANCE.run(new StringScriptSource("[remote]" + name, script)));
    }


    private void launchProject(String dir) {
        try {
            new ProjectLauncher(dir)
                    .launch(AutoJs.getInstance().getScriptEngineService());
        } catch (Exception e) {
            e.printStackTrace();
            GlobalAppContext.toast(R.string.text_invalid_project);
        }
    }


    private void stopScript(String viewId) {
        ScriptExecution execution = mScriptExecutions.get(viewId);
        if (execution != null) {
            execution.getEngine().forceStop();
            mScriptExecutions.remove(viewId);
        }
    }

    private String getName(JsonObject data) {
        JsonElement element = data.get("name");
        if (element instanceof JsonNull) {
            return null;
        }
        return element.getAsString();
    }

    private void saveScript(String name, String script) {
        if (TextUtils.isEmpty(name)) {
            name = "untitled";
        }
        name = PFiles.getNameWithoutExtension(name);
        if (!name.endsWith(".js")) {
            name = name + ".js";
        }
        File file = new File(Pref.getScriptDirPath(), name);
        PFiles.ensureDir(file.getPath());
        PFiles.write(file, script);
        GlobalAppContext.toast(R.string.text_script_save_successfully);
    }


    @SuppressLint("CheckResult")
    private void saveProject(String name, String dir) {
        if (TextUtils.isEmpty(name)) {
            name = "untitled";
        }
        name = PFiles.getNameWithoutExtension(name);
        File toDir = new File(Pref.getScriptDirPath(), name);
        Observable.fromCallable(() -> {
            copyDir(new File(dir), toDir);
            return toDir.getPath();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(dest ->
                                GlobalAppContext.toast(R.string.text_project_save_success, dest),
                        err ->
                                GlobalAppContext.toast(R.string.text_project_save_error, err.getMessage())
                        );

    }

    private void copyDir(File fromDir, File toDir) throws FileNotFoundException {
        toDir.mkdirs();
        File[] files = fromDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                copyDir(file, new File(toDir, file.getName()));
            } else {
                FileOutputStream fos = new FileOutputStream(new File(toDir, file.getName()));
                PFiles.write(new FileInputStream(file), fos, true);
            }
        }
    }

    // ─── helpers ───────────────────────────────────────

    private static byte[] readAllBytes(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            int remaining = data.length;
            while (remaining > 0) {
                int read = fis.read(data, offset, remaining);
                if (read == -1) break;
                offset += read;
                remaining -= read;
            }
            return data;
        } finally {
            fis.close();
        }
    }

    private static JsonObject json(String key, String value) {
        JsonObject obj = new JsonObject();
        obj.addProperty(key, value);
        return obj;
    }

    private static String safeStr(CharSequence cs) {
        return cs != null ? cs.toString() : "";
    }
}
