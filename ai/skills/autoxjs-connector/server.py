#!/usr/bin/env python3
"""
AutoX.js Connector - WebSocket Server
======================================
Implements the VSCode Auto.js extension protocol + custom extensions.

Usage:
    python server.py --port 9317 --workspace ./phone_data

Control API (JSON lines over TCP on localhost:19317):
    {"cmd":"status"}                          → connection state
    {"cmd":"command","command":"dump"}         → send command to phone
    {"cmd":"run","script":"..."}              → run script on phone
    {"cmd":"exec","script":"..."}             → exec JS, return output
    {"cmd":"wait","command_id":"..."}         → wait for specific result
    {"cmd":"shutdown"}                        → stop server
"""

import asyncio
import json
import hashlib
import os
import sys
import time
import random
import tempfile
import argparse
import threading
from datetime import datetime
from pathlib import Path

try:
    import websockets
    from websockets.asyncio.server import serve, ServerConnection
except ImportError:
    print("缺少依赖：pip install websockets")
    sys.exit(1)


HANDSHAKE_TIMEOUT = 10
VERSION = "1.0.0"


def _ts() -> str:
    """返回当前时间戳，格式: [2026/08/04 09:26:28.111]"""
    now = datetime.now()
    return f"[{now.strftime('%Y/%m/%d %H:%M:%S')}.{now.microsecond // 1000:03d}]"


class Device:
    """已连接的手机设备"""

    def __init__(self, websocket: ServerConnection):
        self.ws = websocket
        self.device_name = "unknown"
        self.app_version = ""
        self.app_version_code = 0
        self.client_version = 0
        self.connected_at = time.time()

        # 等待 command_result 的回调: {command_id: future}
        self._result_futures: dict[str, asyncio.Future] = {}
        # 日志缓冲区
        self.logs: list[str] = []
        self._log_futures: list[asyncio.Future] = []
        # 二进制数据缓冲区: {md5: bytes}
        self._pending_bytes: dict[str, bytes] = {}

    @property
    def name(self) -> str:
        return self.device_name or f"device_{id(self)}"

    def expect_result(self, command_id: str) -> asyncio.Future:
        fut = asyncio.get_event_loop().create_future()
        self._result_futures[command_id] = fut
        return fut

    def deliver_result(self, command_id: str, data: dict):
        fut = self._result_futures.pop(command_id, None)
        if fut and not fut.done():
            fut.set_result(data)

    def deliver_log(self, log_text: str):
        self.logs.append(log_text)
        for fut in self._log_futures:
            if not fut.done():
                fut.set_result(log_text)
        self._log_futures.clear()

    async def wait_log(self, timeout: float = 30.0) -> str:
        fut = asyncio.get_event_loop().create_future()
        self._log_futures.append(fut)
        try:
            return await asyncio.wait_for(fut, timeout=timeout)
        except asyncio.TimeoutError:
            return ""

    def store_bytes(self, md5: str, data: bytes):
        self._pending_bytes[md5] = data

    def take_bytes(self, md5: str) -> bytes | None:
        return self._pending_bytes.pop(md5, None)

    async def send_json(self, msg: dict):
        await self.ws.send(json.dumps(msg, ensure_ascii=False))

    async def send_bytes(self, data: bytes):
        await self.ws.send(data)

    def is_alive(self) -> bool:
        try:
            return not self.ws.close_code
        except Exception:
            return False


class AutoJSServer:
    """WebSocket 服务端"""

    def __init__(self, host: str = "0.0.0.0", port: int = 9317,
                 ctrl_port: int = 19317, workspace: str = "./phone_data"):
        self.host = host
        self.port = port
        self.ctrl_port = ctrl_port
        self.workspace = Path(workspace).absolute()
        self.workspace.mkdir(parents=True, exist_ok=True)

        self.device: Device | None = None
        self._server = None
        self._ctrl_server = None

    @property
    def connected(self) -> bool:
        return self.device is not None and self.device.is_alive()

    async def start(self):
        """启动 WebSocket 服务端 + 控制 TCP 接口"""
        self._server = await serve(
            self._handle_connection,
            self.host,
            self.port,
            ping_interval=10,
            ping_timeout=30,
            max_size=2**30,  # 1GB，避免大文件（如日志）被 WebSocket 帧限制截断
        )
        # TCP 控制接口
        self._ctrl_server = await asyncio.start_server(
            self._handle_ctrl,
            "127.0.0.1",
            self.ctrl_port,
        )
        sock = next(iter(self._server.sockets))
        addr = sock.getsockname()
        print(_ts() + " " + json.dumps({
            "event": "server_started",
            "ws": f"ws://{self.host}:{self.port}",
            "ctrl": f"tcp://127.0.0.1:{self.ctrl_port}",
            "workspace": str(self.workspace),
        }))
        sys.stdout.flush()

    async def wait_closed(self):
        await self._server.wait_closed()

    async def stop(self):
        if self._ctrl_server:
            self._ctrl_server.close()
        if self._server:
            self._server.close()

    # ─── WebSocket 连接处理 ────────────────────────────

    async def _handle_connection(self, websocket: ServerConnection):
        """处理手机 WebSocket 连接"""
        device = Device(websocket)
        self.device = device
        try:
            async for raw in websocket:
                if isinstance(raw, bytes):
                    await self._on_binary(device, raw)
                else:
                    await self._on_message(device, raw)
        except Exception as e:
            print(_ts() + " " + json.dumps({"event": "device_disconnected", "reason": str(e)}))
            sys.stdout.flush()
        finally:
            if self.device is device:
                self.device = None

    async def _on_message(self, device: Device, raw: str):
        """处理文本消息"""
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            return

        msg_type = msg.get("type", "")
        data = msg.get("data", {})

        if msg_type == "hello":
            device.device_name = data.get("device_name", "unknown")
            device.client_version = data.get("client_version", 0)
            device.app_version = data.get("app_version", "")
            device.app_version_code = data.get("app_version_code", 0)
            print(_ts() + " " + json.dumps({
                "event": "device_connected",
                "device_name": device.device_name,
                "app_version": device.app_version,
                "app_version_code": device.app_version_code,
            }))
            sys.stdout.flush()
            await device.send_json({
                "type": "hello",
                "data": {"version": VERSION, "debug": True},
            })

        elif msg_type == "log":
            log_text = data.get("log", "")
            print(f"{_ts()} [手机日志] {log_text}")
            sys.stdout.flush()
            device.deliver_log(log_text)

        elif msg_type == "command_result":
            cid = data.get("command_id", "")
            result = data.get("result", "")
            result_size = len(str(result))
            summary = {k: v for k, v in data.items() if k != "result"}
            summary["result"] = f"<{result_size} chars>" if result_size > 200 else result
            print(f"{_ts()} ← 指令结果: {cid} -> {summary}")
            sys.stdout.flush()
            device.deliver_result(cid, data)

        elif msg_type == "ping":
            await device.send_json({"type": "pong", "data": {}})

        elif msg_type == "pong":
            pass  # heartbeat ok

    async def _on_binary(self, device: Device, raw: bytes):
        """处理二进制消息"""
        # 用 SHA256 作为临时 key，等待后续 JSON 匹配
        md5 = hashlib.md5(raw).hexdigest()
        device.store_bytes(md5, raw)

    # ─── 命令发送 ─────────────────────────────────────

    async def send_command(self, command: str, *, _wait: bool = True, **kwargs) -> dict:
        """发送命令到手机

        _wait=True: 等待 command_result 回包（默认）
        _wait=False: 只发送，不等待（fire-and-forget，适用于标准 VSCode 协议命令）
        """
        if not self.connected:
            return {"success": False, "error": "手机未连接"}

        cmd_id = f"{int(time.time()*1000)}_{random.random()}"
        payload = {
            "type": "command",
            "message_id": cmd_id,
            "data": {"command": command, "id": cmd_id, **kwargs},
        }
        safe_kwargs = {}
        for k, v in kwargs.items():
            v_str = str(v)
            if len(v_str) > 200:
                safe_kwargs[k] = f"<{len(v_str)} chars>"
            else:
                safe_kwargs[k] = v
        print(f"{_ts()} → 发送指令: {command} (id={cmd_id}, params={safe_kwargs})")
        sys.stdout.flush()
        await self.device.send_json(payload)

        if not _wait:
            return {"success": True, "command_id": cmd_id, "fire_and_forget": True}

        fut = self.device.expect_result(cmd_id)
        try:
            result = await asyncio.wait_for(fut, timeout=60.0)
            return result
        except asyncio.TimeoutError:
            return {"success": False, "error": "命令超时"}

    async def run_script(self, script: str, name: str = "remote.js", *, _wait: bool = True) -> dict:
        """推送并执行脚本"""
        return await self.send_command("run", name=name, script=script, _wait=_wait)

    async def exec_js(self, script: str, *, _wait: bool = True) -> dict:
        """执行 JS 并返回结果"""
        return await self.send_command("exec", params={"script": script}, _wait=_wait)

    async def dump_ui(self) -> dict:
        """获取 UI 组件树"""
        return await self.send_command("dump")

    async def pull_file(self, path: str, local_path: str | None = None) -> dict:
        """拉取手机文件。local_path 为 PC 端保存目录，默认使用 workspace。"""
        result = await self.send_command("pull_file", params={"path": path})
        if result.get("success"):
            md5 = result.get("result", {}).get("md5", "")
            file_data = self.device.take_bytes(md5) if md5 else None
            if file_data:
                save_dir = Path(local_path) if local_path else self.workspace
                save_dir.mkdir(parents=True, exist_ok=True)
                save_path = save_dir / Path(path).name
                save_path.write_bytes(file_data)
                result["result"]["local_path"] = str(save_path)
        return result

    def _load_ignore_patterns(self, project_dir: str) -> list[str]:
        """从 project.json 读取 ignore 列表"""
        proj_json = Path(project_dir) / "project.json"
        if not proj_json.is_file():
            return []
        try:
            with open(proj_json, "r", encoding="utf-8") as f:
                config = json.load(f)
            return config.get("ignore", [])
        except (json.JSONDecodeError, OSError):
            return []

    def _should_ignore(self, rel_path: str, ignore_patterns: list[str]) -> bool:
        """判断相对路径是否匹配 ignore 规则"""
        for pattern in ignore_patterns:
            pattern = pattern.strip("/")
            if not pattern:
                continue
            if rel_path == pattern:
                return True
            if rel_path.startswith(pattern + "/"):
                return True
        return False

    def _zip_project(self, project_dir: str) -> tuple[bytes, str]:
        """打包项目目录为 ZIP，返回 (zip_data, dir_name, md5)

        读取 project.json 中的 ignore 列表，跳过匹配的文件。
        """
        import zipfile
        import io

        proj_path = Path(project_dir)
        if not proj_path.is_dir():
            raise FileNotFoundError(f"目录不存在: {project_dir}")

        ignore_patterns = self._load_ignore_patterns(project_dir)

        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            for file in proj_path.rglob("*"):
                if not file.is_file():
                    continue
                if file.name == ".DS_Store":
                    continue
                rel_path = str(file.relative_to(proj_path))
                if self._should_ignore(rel_path, ignore_patterns):
                    continue
                zf.write(file, rel_path)
        zip_data = buf.getvalue()
        md5 = hashlib.md5(zip_data).hexdigest()
        return zip_data, proj_path.name, md5

    async def _send_bytes_command(self, command: str, project_dir: str) -> dict:
        """通用 bytes_command：打包项目 → 发二进制 → 发 JSON → 等结果"""
        if not self.connected:
            return {"success": False, "error": "手机未连接"}
        try:
            zip_data, dir_name, md5 = self._zip_project(project_dir)
        except FileNotFoundError as e:
            return {"success": False, "error": str(e)}

        msg_id = f"{int(time.time()*1000)}_{random.random()}"
        print(f"{_ts()} → 发送二进制指令: {command} (project={dir_name}, zip_size={len(zip_data)} bytes)")
        sys.stdout.flush()
        await self.device.send_bytes(zip_data)
        await self.device.send_json({
            "type": "bytes_command",
            "message_id": msg_id,
            "md5": md5,
            "data": {"command": command, "id": dir_name, "name": dir_name},
        })
        return {"success": True, "md5": md5, "size": len(zip_data)}

    async def run_project(self, project_dir: str) -> dict:
        return await self._send_bytes_command("run_project", project_dir)

    async def save_project(self, project_dir: str) -> dict:
        return await self._send_bytes_command("save_project", project_dir)

    # ─── TCP 控制接口 ──────────────────────────────

    async def _handle_ctrl(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        """处理本地控制命令 (JSON lines)"""
        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                try:
                    req = json.loads(line.decode().strip())
                except json.JSONDecodeError:
                    continue

                resp = await self._handle_ctrl_command(req)
                writer.write((json.dumps(resp, ensure_ascii=False) + "\n").encode())
                await writer.drain()
        except Exception as e:
            try:
                writer.write((json.dumps({"error": f"server error: {e}"}) + "\n").encode())
                await writer.drain()
            except Exception:
                pass
        finally:
            writer.close()

    async def _handle_ctrl_command(self, req: dict) -> dict:
        """处理控制命令"""
        cmd = req.get("cmd", "")

        if cmd == "status":
            return {
                "connected": self.connected,
                "device": {
                    "name": self.device.device_name if self.device else None,
                    "app_version": self.device.app_version if self.device else None,
                } if self.device else None,
            }

        elif cmd == "command":
            command = req.get("command", "")
            params = req.get("params", {})
            _wait = req.get("wait", True)
            result = await self.send_command(command, _wait=_wait, **params)
            return result

        elif cmd == "run":
            script = req.get("script", "")
            name = req.get("name", "remote.js")
            _wait = req.get("wait", False)  # run 默认 fire-and-forget
            result = await self.run_script(script, name, _wait=_wait)
            return result

        elif cmd == "exec":
            script = req.get("script", "")
            _wait = req.get("wait", True)
            result = await self.exec_js(script, _wait=_wait)
            return result

        elif cmd == "dump":
            result = await self.dump_ui()
            return result

        elif cmd == "pull_file":
            path = req.get("path", "")
            local_path = req.get("local_path", None)
            result = await self.pull_file(path, local_path)
            return result

        elif cmd == "run_project":
            project_dir = req.get("project_dir", "")
            result = await self.run_project(project_dir)
            return result

        elif cmd == "save_project":
            project_dir = req.get("project_dir", "")
            result = await self.save_project(project_dir)
            return result

        elif cmd == "shutdown":
            asyncio.create_task(self.stop())
            return {"success": True, "message": "shutting down"}

        elif cmd == "wait":
            # 等待一段时间，用于同步
            await asyncio.sleep(req.get("timeout", 1.0))
            return {"success": True}

        return {"success": False, "error": f"未知命令: {cmd}"}


# ─── 命令行工具 ──────────────────────────────────

def send_ctrl_command(ctrl_port: int, req: dict, timeout: float = 30.0) -> dict:
    """发送控制命令到运行中的服务器"""
    import socket
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect(("127.0.0.1", ctrl_port))
        s.sendall((json.dumps(req, ensure_ascii=False) + "\n").encode())
        # 循环接收直到遇到换行符，避免大响应（如 dump UI 树）被截断
        buf = b""
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
            if b"\n" in buf:
                break
        resp = buf.decode()
        return json.loads(resp) if resp else {"error": "no response"}
    finally:
        s.close()


def main():
    parser = argparse.ArgumentParser(description="AutoX.js WebSocket Connector")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址 (默认 0.0.0.0)")
    parser.add_argument("--port", type=int, default=9317, help="WebSocket 端口 (默认 9317)")
    parser.add_argument("--ctrl-port", type=int, default=None, help="控制接口端口 (默认 port+10000)")
    parser.add_argument("--workspace", default="./phone_data", help="工作目录")
    parser.add_argument("--send", help="发送控制命令 (JSON)")
    parser.add_argument("--timeout", type=float, default=30.0, help="命令超时")
    args = parser.parse_args()

    if args.ctrl_port is None:
        ctrl_port = args.port + 10000
    else:
        ctrl_port = args.ctrl_port

    # 发送命令模式
    if args.send:
        try:
            req = json.loads(args.send)
            resp = send_ctrl_command(ctrl_port, req, args.timeout)
            print(json.dumps(resp, ensure_ascii=False, indent=2))
        except Exception as e:
            print(json.dumps({"error": str(e)}))
        return

    # 服务端模式
    server = AutoJSServer(
        host=args.host,
        port=args.port,
        ctrl_port=ctrl_port,
        workspace=args.workspace,
    )

    async def run_server():
        await server.start()
        while True:
            await asyncio.sleep(3600)

    try:
        asyncio.run(run_server())
    except KeyboardInterrupt:
        print(_ts() + " " + json.dumps({"event": "server_stopped"}))


if __name__ == "__main__":
    main()
