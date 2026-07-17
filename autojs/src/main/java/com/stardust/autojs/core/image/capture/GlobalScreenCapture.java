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
            Log.d(TAG, "ensureForegroundService: 尝试启动前台服务 foregroundServiceStarted=" + foregroundServiceStarted);
            mContext.startForegroundService(new Intent(mContext, CaptureForegroundService.class));
            Log.d(TAG, "ensureForegroundService: startForegroundService 已调用，等待 notifyStarted 通知");
            try {
                this.wait(5000);
                Log.d(TAG, "ensureForegroundService: 已收到通知/wait返回，foregroundServiceStarted=" + foregroundServiceStarted);
            } catch (InterruptedException e) {
                Log.e(TAG, "ensureForegroundService: wait 被中断", e);
                throw new ScriptInterruptedException();
            }
            if (!foregroundServiceStarted) {
                Log.e(TAG, "ensureForegroundService: 前台服务启动超时(5s)或失败，foregroundServiceStarted仍为false");
            }
        } else {
            Log.d(TAG, "ensureForegroundService: 条件不满足，跳过 (Q=" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) + " fgStarted=" + foregroundServiceStarted + ")");
        }
    }

    public synchronized void notifyStarted() {
        this.foregroundServiceStarted = true;
        Log.d(TAG, "notifyStarted: foregroundServiceStarted 已设为 true，通知等待线程");
        this.notify();
    }

    public void foregroundServiceDown() {
        this.foregroundServiceStarted = false;
        Log.w(TAG, "foregroundServiceDown: 前台服务已停止");
    }

    public synchronized boolean hasPermission() {
        boolean result;
        if (!hasPermission) {
            Log.e(TAG, "hasPermission: hasPermission标志为false");
            result = false;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !foregroundServiceStarted) {
            Log.e(TAG, "hasPermission: 前台服务已丢失，尝试重新启动 foregroundServiceStarted=" + foregroundServiceStarted);
            ensureForegroundService();
            result = foregroundServiceStarted;
            Log.d(TAG, "hasPermission: 重启前台服务后 foregroundServiceStarted=" + result);
        } else {
            result = true;
        }
        Log.d(TAG, "hasPermission: 返回 " + result + " (hasPermission=" + hasPermission + " fgStarted=" + foregroundServiceStarted + ")");
        return result;
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
            Log.d(TAG, "grantMediaProjection: 成功获取新projection");
            if (mMediaProjection != null) {
                mMediaProjection.stop();
            }
            mMediaProjection = newMediaProjection;
        } catch (Exception e) {
            Log.e(TAG, "grantMediaProjection: 获取新projection失败 可能只是MIUI的bug " + e);
            release();
        }
    }

    @SuppressLint("WrongConstant")
    private void initVirtualDisplay(int width, int height, int screenDensity) {
        Log.d(TAG, "initVirtualDisplay: mMediaProjection is " + (mMediaProjection != null ? "valid" : "null") + " w=" + width + " h=" + height + " d=" + screenDensity);
        if (mMediaProjection == null) {
            Log.d(TAG, "initVirtualDisplay: mMediaProjection == null, 尝试grantMediaProjection");
            grantMediaProjection();
            if (mMediaProjection == null) {
                Log.d(TAG, "initVirtualDisplay: grantMediaProjection后仍为null，抛出异常");
                throw new IllegalStateException("mediaProjection 初始化失败，无法刷新");
            }
        }
        Log.d(TAG, "initVirtualDisplay: width:" + width + ",height:" + height + ",density:" + screenDensity);
        mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        try {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG,
                    width, height, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(), null, null);
        } catch (SecurityException e) {
            Log.e(TAG, "initVirtualDisplay: createVirtualDisplay SecurityException: " + e);
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
        Log.d(TAG, "注册imageListener: ");
        mImageReader.setOnImageAvailableListener(reader -> {
            try {
                if (noRegister && !mForceCapture) {
                    Log.v(TAG, "setImageListener: noRegister=true 且未在capture中，丢弃此帧");
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
        Log.d(TAG, "capture: 线程=" + currentThread.getName() + " hasPermission=" + hasPermission + " noRegister=" + noRegister
                + " foregroundServiceStarted=" + foregroundServiceStarted + " mVirtualDisplay=" + (mVirtualDisplay != null)
                + " mMediaProjection=" + (mMediaProjection != null) + " mImageReader=" + (mImageReader != null));
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
                } else {
                    Log.d(TAG, "capture: 加锁等待获取截图");
                    long waitStart = System.currentTimeMillis();
                    captureLock.lock();
                    try {
                        captureComplete.await(2, TimeUnit.SECONDS);
                        Log.d(TAG, "capture: 获取到截图信号或超时，等待耗时：" + (System.currentTimeMillis() - waitStart) + "ms");
                        cachedImage = getCachedImage();
                        if (cachedImage != null) {
                            return cachedImage;
                        }
                        Log.d(TAG, "capture: 获取到截图信号，但是图片已经被其他脚本获取 重新获取");
                    } catch (InterruptedException ex) {
                        throw new ScriptInterruptedException();
                    } finally {
                        captureLock.unlock();
                    }
                }
                if (System.currentTimeMillis() - startTime > 1000) {
                    startTime = System.currentTimeMillis();
                    Log.d(TAG, "capture: 获取截图失败，刷新virtualDisplay, mVirtualDisplay=" + (mVirtualDisplay != null) + " mMediaProjection=" + (mMediaProjection != null));
                    if (mVirtualDisplay != null) {
                        // VirtualDisplay仍有效，直接重新创建（不获取新MediaProjection，避免Android 16上non-current错误）
                        Log.d(TAG, "capture: mVirtualDisplay有效，直接刷新VirtualDisplay");
                        this.refreshVirtualDisplay(getOrientation());
                    } else if (mMediaProjection != null) {
                        Log.d(TAG, "capture: mMediaProjection有效，跳过grantMediaProjection，仅重建VirtualDisplay");
                        this.refreshVirtualDisplay(getOrientation());
                    } else {
                        Log.d(TAG, "capture: 全部失效，走grantMediaProjection + refresh");
                        this.grantMediaProjection();
                        this.refreshVirtualDisplay(getOrientation());
                    }
                    if (retryLimit-- <= 0) {
                        Log.d(TAG, "capture: 获取截图异常，重试多次失败 退出");
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
    }

    private void release() {
        Log.w(TAG, "release: 开始释放截图资源 hasPermission=" + hasPermission + " noRegister=" + noRegister
                + " foregroundServiceStarted=" + foregroundServiceStarted);
        noRegister = true;
        hasPermission = false;
        mOrientation = -1;
        foregroundServiceStarted = false;
        if (mImageAcquireLooper != null) {
            Log.d(TAG, "release: 退出 mImageAcquireLooper");
            mImageAcquireLooper.quit();
            mImageAcquireLooper = null;
        }
        if (mMediaProjection != null) {
            Log.d(TAG, "release: 停止 MediaProjection");
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        if (mVirtualDisplay != null) {
            Log.d(TAG, "release: 释放 VirtualDisplay");
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            Log.d(TAG, "release: 关闭 ImageReader");
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
        mContext.stopService(new Intent(mContext, CaptureForegroundService.class));
    }

}
