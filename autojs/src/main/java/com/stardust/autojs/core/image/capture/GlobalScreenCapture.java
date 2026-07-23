package com.stardust.autojs.core.image.capture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.OrientationEventListener;

import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.exception.ScriptException;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.lang.ThreadCompat;
import com.stardust.util.ScreenMetrics;

import org.mozilla.javascript.ast.Loop;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import androidx.annotation.Nullable;

/**
 * Created by TonyJiangWJ on 2022/1/22
 */
public class GlobalScreenCapture {
    public static final int ORIENTATION_AUTO = Configuration.ORIENTATION_UNDEFINED;
    public static final int ORIENTATION_LANDSCAPE = Configuration.ORIENTATION_LANDSCAPE;
    public static final int ORIENTATION_PORTRAIT = Configuration.ORIENTATION_PORTRAIT;

    private static final String TAG = "GlobalScreenCapture";
    private final ConcurrentHashMap<ScriptRuntime, Boolean> registeredRuntimes = new ConcurrentHashMap<>();

    private MediaProjectionManager mProjectionManager;
    private ImageReader mImageReader;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private volatile Looper mImageAcquireLooper;
    private volatile Image mUnderUsingImage;
    private Context mContext;
    private Intent mData;
    private Handler mHandler;
    private final AtomicReference<Image> mCachedImage = new AtomicReference<>();
    private volatile Exception mException;
    private boolean foregroundServiceStarted = false;

    private int mScreenDensity;
    private int mOrientation = -1;
    private int mDetectedOrientation;
    private OrientationEventListener mOrientationEventListener;

    private boolean hasPermission;
    private boolean noRegister;
    private volatile boolean mForceCapture = false;

    private ReentrantLock captureLock = new ReentrantLock();

    private Condition captureComplete = captureLock.newCondition();

    @SuppressLint("StaticFieldLeak")
    private static volatile GlobalScreenCapture INSTANCE;

    private GlobalScreenCapture() {
        mScreenDensity = ScreenMetrics.getDeviceScreenDensity();
    }

    public static GlobalScreenCapture getInstance() {
        if (INSTANCE == null) {
            synchronized (GlobalScreenCapture.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GlobalScreenCapture();
                }
            }
        }
        return INSTANCE;
    }

    public synchronized void initCapture(Context context, Intent data, int orientation) {
        Log.d(TAG, "initCapture: mScreenDensity=" + mScreenDensity + " orientation=" + orientation
                + " hasPermission=" + hasPermission + " caller=" + Thread.currentThread().getName());
        if (mScreenDensity == 0) {
            mScreenDensity = ScreenMetrics.getDeviceScreenDensity();
        }
        mContext = context.getApplicationContext();
        mData = (Intent) data.clone();
        Log.d(TAG, "initCapture: 准备获取 MediaProjection, SDK=" + Build.VERSION.SDK_INT);
        ensureForegroundService();
        mProjectionManager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mMediaProjection = mProjectionManager.getMediaProjection(Activity.RESULT_OK, (Intent) data.clone());
        Log.d(TAG, "initCapture: getMediaProjection returned " + (mMediaProjection != null ? "valid" : "null (will fail!)"));
        new Thread(() -> {
            mHandler = new Handler(Looper.getMainLooper());
            synchronized (GlobalScreenCapture.this) {
                GlobalScreenCapture.this.notifyAll();
            }
        }).start();
        synchronized (this) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                throw new ScriptInterruptedException();
            }
        }
        observeOrientation();
        setOrientation(orientation);
        hasPermission = mMediaProjection != null;
        Log.d(TAG, "initCapture: 完成 hasPermission=" + hasPermission + " mMediaProjection="
                + (mMediaProjection != null ? "valid" : "null") + " mVirtualDisplay="
                + (mVirtualDisplay != null ? "valid" : "null"));
    }

    private void ensureForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !foregroundServiceStarted) {
            Log.d(TAG, "ensureForegroundService: 启动前台服务 SDK=" + Build.VERSION.SDK_INT);
            mContext.startForegroundService(new Intent(mContext, CaptureForegroundService.class));
            try {
                this.wait(5000);
            } catch (InterruptedException e) {
                Log.e(TAG, "ensureForegroundService: wait 被中断", e);
                throw new ScriptInterruptedException();
            }
            if (!foregroundServiceStarted) {
                Log.e(TAG, "ensureForegroundService: 前台服务启动超时(5s)或失败");
            } else {
                Log.d(TAG, "ensureForegroundService: 前台服务启动成功");
            }
        } else {
            Log.d(TAG, "ensureForegroundService: 无需启动 SDK=" + Build.VERSION.SDK_INT + " started=" + foregroundServiceStarted);
        }
    }

    public synchronized void notifyStarted() {
        this.foregroundServiceStarted = true;
        this.notify();
    }

    public void foregroundServiceDown() {
        this.foregroundServiceStarted = false;
        Log.w(TAG, "foregroundServiceDown: 前台服务已停止 hasPermission=" + hasPermission
                + " mp=" + (mMediaProjection != null) + " registeredRuntimes=" + registeredRuntimes.size());
    }

    /**
     * 从已保存的授权数据（侧边栏授予）中恢复截图权限，避免重新弹窗
     */
    public synchronized boolean tryRestore() {
        if (hasPermission) return true;
        if (mData == null || mContext == null) {
            Log.e(TAG, "tryRestore: 无已保存的授权数据，无法恢复");
            return false;
        }
        Log.d(TAG, "tryRestore: 尝试从已保存的授权数据恢复截图权限");
        try {
            initCapture(mContext, mData, mOrientation == -1 ? ORIENTATION_AUTO : mOrientation);
            return hasPermission;
        } catch (Exception e) {
            Log.e(TAG, "tryRestore: 恢复失败", e);
            return false;
        }
    }

    public synchronized boolean hasPermission() {
        Log.d(TAG, "hasPermission: hasPermission=" + hasPermission + " foregroundServiceStarted="
                + foregroundServiceStarted + " mp=" + (mMediaProjection != null)
                + " vd=" + (mVirtualDisplay != null) + " SDK=" + Build.VERSION.SDK_INT
                + " registeredRuntimes=" + registeredRuntimes.size());
        if (!hasPermission) {
            Log.e(TAG, "hasPermission: 返回 false (标志为false)");
            return false;
        }
        // 尝试确保前台服务运行，但即使失败也返回 true
        // Android 16+ 系统可能频繁杀掉前台服务，导致 foregroundServiceStarted 为 false
        // 但 MediaProjection 本身在 app 存活期间仍然有效，无需重新弹窗授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !foregroundServiceStarted) {
            Log.w(TAG, "hasPermission: 前台服务已丢失，尝试重新启动");
            ensureForegroundService();
            if (!foregroundServiceStarted) {
                Log.w(TAG, "hasPermission: 前台服务启动失败，但 hasPermission 为 true，继续返回 true");
            }
        }
        Log.d(TAG, "hasPermission: 返回 true");
        return true;
    }

    public void setOrientation(int orientation) {
        if (mOrientation == orientation) {
            return;
        }
        mOrientation = orientation;
        mDetectedOrientation = mContext.getResources().getConfiguration().orientation;
        refreshVirtualDisplay(getOrientation());
    }

    private int getOrientation() {
        return mOrientation == ORIENTATION_AUTO ? mDetectedOrientation : mOrientation;
    }

    private void observeOrientation() {
        mOrientationEventListener = new OrientationEventListener(mContext) {
            @Override
            public void onOrientationChanged(int o) {
                int orientation = mContext.getResources().getConfiguration().orientation;
                if (mOrientation == ORIENTATION_AUTO && mDetectedOrientation != orientation) {
                    mDetectedOrientation = orientation;
                    try {
                        refreshVirtualDisplay(orientation);
                    } catch (Exception e) {
                        e.printStackTrace();
                        mException = e;
                    }
                }
            }

        };
        if (mOrientationEventListener.canDetectOrientation()) {
            mOrientationEventListener.enable();
        }
    }

    private void refreshVirtualDisplay(int orientation) {
        if (mImageAcquireLooper != null) {
            mImageAcquireLooper.quit();
        }
        if (mImageReader != null) {
            mImageReader.close();
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
        }
        int screenHeight = ScreenMetrics.getOrientationAwareScreenHeight(orientation);
        int screenWidth = ScreenMetrics.getOrientationAwareScreenWidth(orientation);
        initVirtualDisplay(screenWidth, screenHeight, mScreenDensity);
        startAcquireImageLoop();
    }

    private void grantMediaProjection() {
        Log.d(TAG, "grantMediaProjection: 尝试重新获取 MediaProjection mData=" + (mData != null));
        try {
            MediaProjection newMediaProjection = mProjectionManager.getMediaProjection(Activity.RESULT_OK, (Intent) mData.clone());
            if (newMediaProjection == null) {
                Log.e(TAG, "grantMediaProjection: getMediaProjection returned null (Intent 可能已失效)");
                return;
            }
            if (mMediaProjection != null) {
                Log.d(TAG, "grantMediaProjection: 停止旧 MediaProjection");
                mMediaProjection.stop();
            }
            mMediaProjection = newMediaProjection;
            Log.d(TAG, "grantMediaProjection: 成功获取新 MediaProjection");
        } catch (Exception e) {
            Log.e(TAG, "grantMediaProjection: 获取新projection失败 " + e);
            release();
        }
    }

    @SuppressLint("WrongConstant")
    private void initVirtualDisplay(int width, int height, int screenDensity) {
        Log.d(TAG, "initVirtualDisplay: w=" + width + " h=" + height + " d=" + screenDensity
                + " mp=" + (mMediaProjection != null ? "valid" : "null")
                + " hasPermission=" + hasPermission);
        if (mMediaProjection == null) {
            Log.w(TAG, "initVirtualDisplay: mMediaProjection 为 null，尝试重新获取");
            grantMediaProjection();
            if (mMediaProjection == null) {
                Log.e(TAG, "initVirtualDisplay: grantMediaProjection 后仍为 null，抛出异常");
                throw new IllegalStateException("mediaProjection 初始化失败，无法刷新");
            }
        }
        mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        try {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG,
                    width, height, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(), null, null);
            Log.d(TAG, "initVirtualDisplay: 创建成功 vd=" + (mVirtualDisplay != null));
        } catch (SecurityException e) {
            Log.e(TAG, "initVirtualDisplay: SecurityException, MediaProjection 可能已失效", e);
            release();
        }
    }

    private void startAcquireImageLoop() {
        if (mImageReader == null) {
            // 初始化virtualDisplay异常
            return;
        }

        setImageListener(mHandler);
    }

    private void setImageListener(Handler handler) {
        mImageReader.setOnImageAvailableListener(reader -> {
            try {
                if (noRegister && !mForceCapture) {
                    reader.acquireLatestImage();
                    return;
                }
                captureLock.lock();
                try {
                    Image oldCacheImage = mCachedImage.getAndSet(null);
                    if (oldCacheImage != null) {
                        oldCacheImage.close();
                    }
                    mCachedImage.set(reader.acquireLatestImage());
                    captureComplete.signal();
                } finally {
                    captureLock.unlock();
                }
            } catch (Exception e) {
                mException = e;
            }

        }, handler);
    }

    @Nullable
    public synchronized Image capture() {
        Thread currentThread = ThreadCompat.currentThread();
        Log.d(TAG, "capture: " + currentThread.getName() + " permission=" + hasPermission
                + " vd=" + (mVirtualDisplay != null) + " mp=" + (mMediaProjection != null));
        Exception e = mException;
        if (e != null) {
            mException = null;
            Log.e(TAG, "capture: 发现待处理异常", e);
            throw new ScriptException(e);
        }
        // 允许capture期间接收帧，即使noRegister=true
        mForceCapture = true;
        try {
            Thread thread = currentThread;
            long startTime = System.currentTimeMillis();
            int retryLimit = 5;
            while (!thread.isInterrupted()) {
                Image cachedImage = getCachedImage();
                if (cachedImage != null) {
                    return cachedImage;
                }
                captureLock.lock();
                try {
                    if (captureComplete.await(2, TimeUnit.SECONDS)) {
                        cachedImage = getCachedImage();
                        if (cachedImage != null) {
                            return cachedImage;
                        }
                    }
                } catch (InterruptedException ex) {
                    throw new ScriptInterruptedException();
                } finally {
                    captureLock.unlock();
                }
                if (System.currentTimeMillis() - startTime > 1000) {
                    startTime = System.currentTimeMillis();
                    if (mVirtualDisplay != null) {
                        this.refreshVirtualDisplay(getOrientation());
                    } else if (mMediaProjection != null) {
                        this.refreshVirtualDisplay(getOrientation());
                    } else {
                        this.grantMediaProjection();
                        this.refreshVirtualDisplay(getOrientation());
                    }
                    if (retryLimit-- <= 0) {
                        Log.w(TAG, "capture: 重试多次失败，退出");
                        break;
                    }
                }
            }
            throw new ScriptInterruptedException();
        } finally {
            mForceCapture = false;
        }
    }

    private Image getCachedImage() {
        Image cachedImage = mCachedImage.getAndSet(null);
        if (cachedImage != null) {
            if (mUnderUsingImage != null) {
                mUnderUsingImage.close();
            }
            mUnderUsingImage = cachedImage;
            return cachedImage;
        }
        return null;
    }

    public synchronized void unregister(ScriptRuntime runtime) {
        Log.d(TAG, "unregister: runtime=" + runtime + " 注销前注册数=" + registeredRuntimes.size()
                + " hasPermission=" + hasPermission + " mp=" + (mMediaProjection != null));
        Boolean wasRegistered = registeredRuntimes.remove(runtime);
        // 清理已死亡的 runtime
        Iterator<ScriptRuntime> keyRuntime = registeredRuntimes.keySet().iterator();
        int deadCount = 0;
        while (keyRuntime.hasNext()) {
            ScriptRuntime scriptRuntime = keyRuntime.next();
            Looper looper = scriptRuntime.loopers.getMainLooper();
            if (looper == null || !looper.getThread().isAlive()) {
                keyRuntime.remove();
                deadCount++;
            }
        }
        noRegister = registeredRuntimes.size() == 0;
        Log.d(TAG, "unregister: 清理后注册数=" + registeredRuntimes.size() + " 清理死亡=" + deadCount
                + " noRegister=" + noRegister + " wasRegistered=" + wasRegistered);
        if (noRegister && wasRegistered != null) {
            Log.d(TAG, "unregister: 全部引擎已注销，调用 release()");
            release();
        }
    }

    public synchronized void register(ScriptRuntime runtime) {
        Looper looper = runtime.loopers.getMainLooper();
        Log.d(TAG, "register: 新引擎注册 " + (looper != null ? looper.getThread().getName() : runtime.engines.myEngine().toString())
                + " hasPermission=" + hasPermission + " mp=" + (mMediaProjection != null)
                + " vd=" + (mVirtualDisplay != null) + " 注册后总数=" + (registeredRuntimes.size() + 1));
        noRegister = false;
        registeredRuntimes.put(runtime, true);
        // 如果 MediaProjection 仍在但 VirtualDisplay 已被 release() 释放，重新创建截图管道
        if (mMediaProjection != null && mVirtualDisplay == null) {
            Log.d(TAG, "register: MediaProjection 存活但 VirtualDisplay 为空，重新创建");
            refreshVirtualDisplay(getOrientation());
        } else if (mMediaProjection == null) {
            Log.w(TAG, "register: mMediaProjection 为 null，无法创建截图管道");
        }
    }

    private void release() {
        Log.w(TAG, "release: 释放可重建资源，保留 MediaProjection 权限 hasPermission=" + hasPermission
                + " foregroundServiceStarted=" + foregroundServiceStarted
                + " mp=" + (mMediaProjection != null) + " vd=" + (mVirtualDisplay != null)
                + " registeredRuntimes=" + registeredRuntimes.size());
        noRegister = true;
        mOrientation = -1;
        if (mImageAcquireLooper != null) {
            Log.d(TAG, "release: 停止图片获取循环");
            mImageAcquireLooper.quit();
            mImageAcquireLooper = null;
        }
        if (mVirtualDisplay != null) {
            Log.d(TAG, "release: 释放 VirtualDisplay");
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            Log.d(TAG, "release: 释放 ImageReader");
            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
            mImageReader = null;
        }
        if (mUnderUsingImage != null) {
            mUnderUsingImage.close();
            mUnderUsingImage = null;
        }
        Image cachedImage = mCachedImage.getAndSet(null);
        if (cachedImage != null) {
            cachedImage.close();
        }
        if (mOrientationEventListener != null) {
            mOrientationEventListener.disable();
            mOrientationEventListener = null;
        }
        Log.d(TAG, "release: 完成 (保留: hasPermission=" + hasPermission + " mp=" + (mMediaProjection != null)
                + " foregroundService=" + foregroundServiceStarted + ")");
    }

}
