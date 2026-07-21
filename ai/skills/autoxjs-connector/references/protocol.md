# AutoX.js Connector 协议文档

基于 VSCode Auto.js Extension 协议扩展。WebSocket，默认端口 9317。

## 基础消息格式

```json
{
  "type": "<message_type>",
  "message_id": "<timestamp_random>",
  "data": { }
}
```

## 握手流程

```
手机 → PC: {"type":"hello", "data":{"device_name":"...", "client_version":2, "app_version":"...", "app_version_code":700}}
PC → 手机: {"type":"hello", "data":{"version":"1.0.0", "debug":true}}
           (10 秒超时，无 hello 则断开)
```

## 标准命令 (PC → 手机)

| type | command | data | 说明 |
|------|---------|------|------|
| command | `run` | id, name, script | 执行脚本 |
| command | `stop` | id | 停止脚本 |
| command | `rerun` | id, name, script | 重启脚本 |
| command | `stopAll` | — | 停止所有 |
| command | `save` | id, name, script | 保存脚本到手机 |
| bytes_command | `run_project` | id, md5, ... | 推送并执行项目 |
| bytes_command | `save_project` | id, md5, name | 推送并保存项目 |

## 扩展命令 (PC → 手机)

| type | command | data | 说明 |
|------|---------|------|------|
| command | `screenshot` | id | 截屏，返回图片 |
| command | `dump` | id | 获取 UI 组件树 (XML) |
| command | `exec` | id, params: {script} | 执行 JS 并返回结果 |
| command | `pull_file` | id, params: {path} | 拉取手机文件 |
| command | `push_file` | id, params: {path} | 推送文件到手机（需二进制帧） |
| close | — | — | 关闭连接 |

## 扩展回包 (手机 → PC)

```json
// 命令执行结果
{"type":"command_result", "data":{"command_id":"s1", "success":true, "result":{...}}}

// 日志推送
{"type":"log", "data":{"log":"console output"}}

// 心跳
{"type":"ping", "data":{}}
PC → 手机: {"type":"pong", "data":{}}
```

## 二进制传输

### PC → 手机（推送项目/文件）

1. 先发二进制数据帧（App 按 MD5 缓存）
2. 再发 JSON 元数据（`bytes_command` 类型）

支持的 `bytes_command`：

| command | data 字段 | 说明 |
|---------|-----------|------|
| `run_project` | id, name | 推送 ZIP 项目并执行 |
| `save_project` | id, name | 推送 ZIP 项目到 App 保存（不执行） |
| `push_file` | path | 推送单个文件（按 path 写入手机） |

`save_project` 示例：
```json
{"type":"bytes_command", "message_id":"...", "command":"save_project", "md5":"abc123", "data":{"id":"MyProject", "name":"MyProject"}}
```

`push_file` 示例：
```json
{"type":"bytes_command", "message_id":"...", "command":"push_file", "md5":"def456", "data":{"path":"/sdcard/脚本/test.js"}}
```

### 手机 → PC（回传截图/文件）

1. 先发 JSON: `{"type":"command_result", "data":{"command_id":"...", "success":true, "result":{"md5":"...", "path":"..."}}}`
2. 再发二进制数据帧

## 示例会话

```
[连接建立]
手机 → PC: {"type":"hello", "data":{"device_name":"Xiaomi 14", "client_version":2, "app_version":"7.0.0", "app_version_code":700}}
PC → 手机: {"type":"hello", "data":{"version":"1.0.0", "debug":true}}

[截图]
PC → 手机: {"type":"command", "message_id":"1747350000_0.123", "data":{"command":"screenshot", "id":"s1"}}
手机 → PC: {"type":"command_result", "data":{"command_id":"s1", "success":true, "result":{"md5":"abc123", "path":"screenshot_20260715_143000.png"}}}
手机 → PC: [二进制帧: PNG 图片数据]

[执行脚本]
PC → 手机: {"type":"command", "message_id":"1747350001_0.456", "data":{"command":"exec", "id":"e1", "params":{"script":"toast('hello');"}}}
手机 → PC: {"type":"log", "data":{"log":"hello"}}
手机 → PC: {"type":"command_result", "data":{"command_id":"e1", "success":true, "result":{"output":"hello\\n"}}}
```
