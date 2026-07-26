package org.autojs.autojs.pluginclient;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;
import android.util.Log;
import android.util.Pair;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.stardust.app.GlobalAppContext;
import com.stardust.util.MapBuilder;

import org.autojs.autojs.BuildConfig;

import java.io.File;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.subjects.PublishSubject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.ByteString;

/**
 * Created by Stardust on 2017/5/11.
 */

public class DevPluginService {

    private static final int CLIENT_VERSION = 2;
    private static final String LOG_TAG = "DevPluginService";
    private static final String TYPE_HELLO = "hello";
    private static final String TYPE_BYTES_COMMAND = "bytes_command";
    private static final long HANDSHAKE_TIMEOUT = 10 * 1000;
    private static final long HEARTBEAT_INTERVAL = 10 * 1000;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_TIMEOUT = 3 * 1000;

    public static class State {

        public static final int DISCONNECTED = 0;
        public static final int CONNECTING = 1;
        public static final int CONNECTED = 2;

        private final int mState;
        private final Throwable mException;

        public State(int state, Throwable exception) {
            mState = state;
            mException = exception;
        }

        public State(int state) {
            this(state, null);
        }

        public int getState() {
            return mState;
        }

        public Throwable getException() {
            return mException;
        }
    }

    private static final int PORT = 9317;
    private static DevPluginService sInstance = new DevPluginService();
    private final PublishSubject<State> mConnectionState = PublishSubject.create();
    private final DevPluginResponseHandler mResponseHandler;
    private final HashMap<String, JsonWebSocket.Bytes> mBytes = new HashMap<>();
    private final HashMap<String, JsonObject> mRequiredBytesCommands = new HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile JsonWebSocket mSocket;
    private String mHost;
    private volatile boolean mHeartbeatActive;
    private final Runnable mHeartbeatTask = this::heartbeatTick;
    private volatile boolean mIsReconnecting;
    private int mReconnectAttempts;

    public static DevPluginService getInstance() {
        return sInstance;
    }

    public DevPluginService() {
        File cache = new File(GlobalAppContext.get().getCacheDir(), "remote_project");
        mResponseHandler = new DevPluginResponseHandler(cache);
    }

    @AnyThread
    public boolean isConnected() {
        return mSocket != null && !mSocket.isClosed();
    }

    @AnyThread
    public boolean isDisconnected() {
        return mSocket == null || mSocket.isClosed();
    }

    @AnyThread
    public void disconnectIfNeeded() {
        if (isDisconnected())
            return;
        disconnect();
    }

    @AnyThread
    public void disconnect() {
        Log.i(LOG_TAG, "disconnect: 主动断开连接, 重连=" + mIsReconnecting);
        mIsReconnecting = false;
        stopHeartbeat();
        mSocket.close();
        mSocket = null;
    }

    public Observable<State> connectionState() {
        return mConnectionState;
    }

    @AnyThread
    public Observable<JsonWebSocket> connectToServer(String host) {
        Log.i(LOG_TAG, "connectToServer: host=" + host);
        mIsReconnecting = false;
        mReconnectAttempts = 0;
        return connectInner(host);
    }

    @AnyThread
    private Observable<JsonWebSocket> connectInner(String host) {
        int port = PORT;
        String ip = host;
        int i = host.lastIndexOf(':');
        if (i > 0 && i < host.length() - 1) {
            port = Integer.parseInt(host.substring(i + 1));
            ip = host.substring(0, i);
        }
        mHost = ip + ":" + port;
        Log.d(LOG_TAG, "connectInner: 连接至 " + mHost + " (重连=" + mIsReconnecting + ")");
        stopHeartbeat();
        disconnectIfNeeded();
        mConnectionState.onNext(new State(State.CONNECTING));

        return socket(ip, port)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(this::onSocketError);
    }

    @AnyThread
    private Observable<JsonWebSocket> socket(String ip, int port) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS);
        if (mIsReconnecting) {
            builder.connectTimeout(RECONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
            Log.d(LOG_TAG, "socket: 重连模式, connectTimeout=" + RECONNECT_TIMEOUT + "ms");
        }
        OkHttpClient client = builder.build();
        String url = ip + ":" + port;
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "ws://" + url;
        }
        return Observable.just(new JsonWebSocket(client, new Request.Builder()
                .url(url)
                .build()))
                .doOnNext(socket -> {
                    mSocket = socket;
                    subscribeMessage(socket);
                    sayHelloToServer(socket);
                });
    }

    @SuppressLint("CheckResult")
    private void subscribeMessage(JsonWebSocket socket) {
        socket.data()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnComplete(() -> {
                    Log.w(LOG_TAG, "subscribeMessage: data 流结束, 触发断线");
                    mConnectionState.onNext(new State(State.DISCONNECTED));
                })
                .subscribe(data -> onSocketData(socket, data), this::onSocketError);
        socket.bytes()
                .doOnComplete(() -> {
                    Log.w(LOG_TAG, "subscribeMessage: bytes 流结束, 触发断线");
                    mConnectionState.onNext(new State(State.DISCONNECTED));
                })
                .subscribe(data -> onSocketData(socket, data), this::onSocketError);
    }

    @MainThread
    private void onSocketError(Throwable e) {
        Log.e(LOG_TAG, "onSocketError: " + e.getMessage(), e);
        if (mSocket != null) {
            mConnectionState.onNext(new State(State.DISCONNECTED, e));
            mSocket.close();
            mSocket = null;
        }
    }

    @MainThread
    private void onSocketData(JsonWebSocket jsonWebSocket, JsonElement element) {
        if (!element.isJsonObject()) {
            Log.w(LOG_TAG, "onSocketData: not json object: " + element);
            return;
        }
        try {
            JsonObject obj = element.getAsJsonObject();
            JsonElement typeElement = obj.get("type");
            if (typeElement == null || !typeElement.isJsonPrimitive()) {
                return;
            }
            String type = typeElement.getAsString();
            if (type.equals(TYPE_HELLO)) {
                onServerHello(jsonWebSocket, obj);
                return;
            }
            if ("ping".equals(type)) {
                write(mSocket, "pong", new JsonObject());
                return;
            }
            if ("pong".equals(type)) {
                return;
            }
            if (TYPE_BYTES_COMMAND.equals(type)) {
                String md5 = obj.get("md5").getAsString();
                JsonWebSocket.Bytes bytes = mBytes.remove(md5);
                if (bytes != null) {
                    handleBytes(obj, bytes);
                } else {
                    mRequiredBytesCommands.put(md5, obj);
                }
                return;
            }
            mResponseHandler.handle(obj);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @SuppressLint("CheckResult")
    private void handleBytes(JsonObject obj, JsonWebSocket.Bytes bytes) {
        mResponseHandler.handleBytes(obj, bytes)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(dir -> {
                    obj.get("data").getAsJsonObject().add("dir", new JsonPrimitive(dir.getPath()));
                    mResponseHandler.handle(obj);
                });

    }

    @WorkerThread
    private void onSocketData(JsonWebSocket jsonWebSocket, JsonWebSocket.Bytes bytes) {
        JsonObject command = mRequiredBytesCommands.remove(bytes.md5);
        if (command != null) {
            handleBytes(command, bytes);
        } else {
            mBytes.put(bytes.md5, bytes);
        }
    }

    @WorkerThread
    private void sayHelloToServer(JsonWebSocket socket) {
        Log.d(LOG_TAG, "sayHelloToServer: 发送 hello");
        writeMap(socket, TYPE_HELLO, new MapBuilder<String, Object>()
                .put("device_name", Build.BRAND + " " + Build.MODEL)
                .put("client_version", CLIENT_VERSION)
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("app_version_code", BuildConfig.VERSION_CODE)
                .build());
        mHandler.postDelayed(() -> {
            if (mSocket != socket && !socket.isClosed()) {
                onHandshakeTimeout(socket);
            }
        }, HANDSHAKE_TIMEOUT);
    }

    @MainThread
    private void onHandshakeTimeout(JsonWebSocket socket) {
        Log.i(LOG_TAG, "onHandshakeTimeout: 握手超时");
        mConnectionState.onNext(new State(State.DISCONNECTED, new SocketTimeoutException("handshake timeout")));
        socket.close();
    }

    @MainThread
    private void onServerHello(JsonWebSocket jsonWebSocket, JsonObject message) {
        Log.i(LOG_TAG, "onServerHello: 连接成功, 启动心跳");
        mSocket = jsonWebSocket;
        mConnectionState.onNext(new State(State.CONNECTED));
        startHeartbeat();
    }

    @AnyThread
    private static boolean write(JsonWebSocket socket, String type, JsonObject data) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.add("data", data);
        return socket.write(json);
    }

    @AnyThread
    private static boolean writePair(JsonWebSocket socket, String type, Pair<String, String> pair) {
        JsonObject data = new JsonObject();
        data.addProperty(pair.first, pair.second);
        return write(socket, type, data);
    }

    @AnyThread
    private static boolean writeMap(JsonWebSocket socket, String type, Map<String, ?> map) {
        JsonObject data = new JsonObject();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                data.addProperty(entry.getKey(), (String) value);
            } else if (value instanceof Character) {
                data.addProperty(entry.getKey(), (Character) value);
            } else if (value instanceof Number) {
                data.addProperty(entry.getKey(), (Number) value);
            } else if (value instanceof Boolean) {
                data.addProperty(entry.getKey(), (Boolean) value);
            } else if (value instanceof JsonElement) {
                data.add(entry.getKey(), (JsonElement) value);
            } else {
                throw new IllegalArgumentException("cannot put value " + value + " into json");
            }
        }
        return write(socket, type, data);
    }


    @SuppressLint("CheckResult")
    @AnyThread
    public void log(String log) {
        if (!isConnected())
            return;
        writePair(mSocket, "log", new Pair<>("log", log));
    }

    @AnyThread
    public boolean sendCommandResult(String commandId, boolean success, JsonObject result) {
        if (!isConnected())
            return false;
        JsonObject data = new JsonObject();
        data.addProperty("command_id", commandId);
        data.addProperty("success", success);
        if (result != null) {
            data.add("result", result);
        }
        return write(mSocket, "command_result", data);
    }

    @AnyThread
    public boolean sendCommandResult(String commandId, boolean success) {
        return sendCommandResult(commandId, success, null);
    }

    @AnyThread
    public boolean sendNullResult(String commandId, boolean success, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("message", message);
        return sendCommandResult(commandId, success, result);
    }

    @AnyThread
    public boolean sendBytes(ByteString bytes) {
        if (!isConnected())
            return false;
        return mSocket.sendBytes(bytes);
    }

    // ── Heartbeat ──────────────────────────────────────

    @MainThread
    private void startHeartbeat() {
        stopHeartbeat();
        mHeartbeatActive = true;
        mHandler.post(mHeartbeatTask);
    }

    @MainThread
    private void heartbeatTick() {
        if (!mHeartbeatActive) {
            return;
        }
        if (isDisconnected()) {
            Log.w(LOG_TAG, "heartbeatTick: 检测到断线 -> 触发重连");
            attemptReconnect();
            return;
        }
        JsonObject emptyData = new JsonObject();
        if (!write(mSocket, "ping", emptyData)) {
            Log.w(LOG_TAG, "heartbeatTick: ping 发送失败 -> 触发重连");
            attemptReconnect();
            return;
        }
        mHandler.postDelayed(mHeartbeatTask, HEARTBEAT_INTERVAL);
    }

    @MainThread
    private void stopHeartbeat() {
        mHeartbeatActive = false;
        mHandler.removeCallbacks(mHeartbeatTask);
    }

    // ── Reconnection ───────────────────────────────────

    private void attemptReconnect() {
        if (!mHeartbeatActive) {
            Log.d(LOG_TAG, "attemptReconnect: 心跳已停止, 不重连");
            return;
        }
        if (mHost == null) {
            Log.w(LOG_TAG, "attemptReconnect: host 为 null, 无法重连");
            return;
        }
        if (mIsReconnecting) {
            Log.d(LOG_TAG, "attemptReconnect: 已在重连中, 跳过");
            return;
        }
        mIsReconnecting = true;
        mReconnectAttempts = 0;
        Log.i(LOG_TAG, "attemptReconnect: 开始重连 -> " + mHost + " (最多 " + MAX_RECONNECT_ATTEMPTS + " 次)");
        doReconnect();
    }

    @SuppressLint("CheckResult")
    private void doReconnect() {
        if (!mIsReconnecting) {
            Log.d(LOG_TAG, "doReconnect: 重连已取消, 停止");
            return;
        }
        mReconnectAttempts++;
        if (mReconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(LOG_TAG, "doReconnect: 已重连 " + MAX_RECONNECT_ATTEMPTS + " 次均失败, 放弃");
            mIsReconnecting = false;
            stopHeartbeat();
            mConnectionState.onNext(new State(State.DISCONNECTED));
            return;
        }
        Log.i(LOG_TAG, "doReconnect: 第 " + mReconnectAttempts + " / " + MAX_RECONNECT_ATTEMPTS + " 次尝试");

        connectInner(mHost)
                .flatMap(socket -> connectionState()
                        .filter(s -> s.getState() == State.CONNECTED || s.getState() == State.DISCONNECTED)
                        .take(1)
                )
                .timeout(RECONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
                .subscribe(
                        state -> {
                            if (state.getState() == State.CONNECTED) {
                                Log.i(LOG_TAG, "doReconnect: 第 " + mReconnectAttempts + " 次重连成功 ✓");
                                mIsReconnecting = false;
                                mReconnectAttempts = 0;
                            } else {
                                Throwable ex = state.getException();
                                Log.w(LOG_TAG, "doReconnect: 第 " + mReconnectAttempts + " 次重连后断线" +
                                        (ex != null ? ": " + ex.getMessage() : ""));
                                throw new RuntimeException("reconnect failed: 状态=" + state.getState() +
                                        (ex != null ? ", " + ex.getMessage() : ""));
                            }
                        },
                        error -> {
                            Log.w(LOG_TAG, "doReconnect: 第 " + mReconnectAttempts + " 次重连失败: " + error.getMessage());
                            disconnectIfNeeded();
                            doReconnect();
                        }
                );
    }
}