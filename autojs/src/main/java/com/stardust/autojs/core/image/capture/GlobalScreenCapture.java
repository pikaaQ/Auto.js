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
        Log.d(TAG, "initCapture: " + mScreenDensity);
        if (mScreenDensity == 0) {
            mScreenDensity = ScreenMetrics.getDeviceScreenDensity();
        }
        mContext = context.getApplicationContext();
        mData = (Intent) data.clone();
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
        Log.d(TAG, "initCapture: hasPermission set to " + hasPermission + " (mMediaProjection=" + (mMediaProjection != null ? "valid" : "null") + ")");
    }

    private void ensureForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !foregroundServiceStarted) {
            mContext.startForegroundService(new Intent(mContext, CaptureForegroundService.class));
            try {
                this.wait(5000);
            } catch (InterruptedException e) {
                Log.e(TAG, "ensureForegroundService: wait 被中断", e);
                throw new ScriptInterruptedException();
            }
            if (!foregroundServiceStarted) {
                Log.e(TAG, "ensureForegroundService: 前台服务启动超时(5s)或失败");
            }
        }
    }

    public synchronized void notifyStarted() {
        this.foregroundServiceStarted = true;
        this.notify();
    }

    public void foregroundServiceDown() {
        this.foregroundServiceStarted = false;
        Log.w(TAG, "foregroundServiceDown: 前台服务已停止");
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
        if (!hasPermission) {
            Log.e(TAG, "hasPermission: 标志为false");
            return false;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !foregroundServiceStarted) {
            Log.e(TAG, "hasPermission: 前台服务已丢失，尝试重新启动");
            ensureForegroundService();
            if (!foregroundServiceStarted) {
                Log.e(TAG, "hasPermission: 重新启动前台服务失败");
            }
            return foregroundServiceStarted;
        }
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
        try {
            MediaProjection newMediaProjection = mProjectionManager.getMediaProjection(Activity.RESULT_OK, (Intent) mData.clone());
            if (newMediaProjection == null) {
                Log.e(TAG, "grantMediaProjection: getMediaProjection returned null");
                return;
            }
            if (mMediaProjection != null) {
                mMediaProjection.stop();
            }
            mMediaProjection = newMediaProjection;
        } catch (Exception e) {
            Log.e(TAG, "grantMediaProjection: 获取新projection失败 " + e);
            release();
        }
    }

    @SuppressLint("WrongConstant")
    private void initVirtualDisplay(int width, int height, int screenDensity) {
        Log.d(TAG, "initVirtualDisplay: w=" + width + " h=" + height + " d=" + screenDensity + " projection=" + (mMediaProjection != null ? "valid" : "null"));
        if (mMediaProjection == null) {
            grantMediaProjection();
            if (mMediaProjection == null) {
                throw new IllegalStateException("mediaProjection 初始化失败，无法刷新");
            }
        }
        mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        try {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG,
                    width, height, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(), null, null);
        } catch (SecurityException e) {
            Log.e(TAG, "initVirtualDisplay: SecurityException " + e);
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
        Log.d(TAG, "unregister: " + runtime);
        Boolean wasRegistered = registeredRuntimes.remove(runtime);
        Iterator<ScriptRuntime> keyRuntime = registeredRuntimes.keySet().iterator();
        while (keyRuntime.hasNext()) {
            ScriptRuntime scriptRuntime = keyRuntime.next();
            Looper looper = scriptRuntime.loopers.getMainLooper();
            if (looper == null || !looper.getThread().isAlive()) {
                keyRuntime.remove();
            }
        }
        noRegister = registeredRuntimes.size() == 0;
        if (noRegister && wasRegistered != null) {
            Log.d(TAG, "全部引擎已注销，释放截图权限，清除通知");
            release();
        }
    }

    public synchronized void register(ScriptRuntime runtime) {
        Looper looper = runtime.loopers.getMainLooper();
        Log.d(TAG, "新引擎注册：" + (looper != null ? looper.getThread().getName() : runtime.engines.myEngine().toString()) + " hasPermission? " + hasPermission);
        noRegister = false;
        registeredRuntimes.put(runtime, true);
        // 如果 MediaProjection 仍在但 VirtualDisplay 已被 release() 释放，重新创建截图管道
        if (mMediaProjection != null && mVirtualDisplay == null) {
            Log.d(TAG, "register: MediaProjection 存活但 VirtualDisplay 为空，重新创建");
            refreshVirtualDisplay(getOrientation());
        }
    }

    private void release() {
        Log.w(TAG, "release: 释放截图资源（保留 MediaProjection 权限） hasPermission=" + hasPermission + " foregroundServiceStarted=" + foregroundServiceStarted);
        noRegister = true;
        // 保留截图权限标志，使得下次脚本运行时无需重新授权
        // hasPermission = false;
        mOrientation = -1;
        // 保留前台服务，避免 Android 12+ 权限丢失
        // foregroundServiceStarted = false;
        if (mImageAcquireLooper != null) {
            mImageAcquireLooper.quit();
            mImageAcquireLooper = null;
        }
        // 保留 MediaProjection 实例，这是截图权限的核心资源
        // 一旦释放需要重新弹窗授权，Android 10+ 上 Intent 单次有效，无法用 tryRestore 恢复
        // if (mMediaProjection != null) {
        //     mMediaProjection.stop();
        //     mMediaProjection = null;
        // }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
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
        // 保留前台服务，避免 Android 12+ 前台服务丢失导致权限不可用
        // mContext.stopService(new Intent(mContext, CaptureForegroundService.class));
    }

}
