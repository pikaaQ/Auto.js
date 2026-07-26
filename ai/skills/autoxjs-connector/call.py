#!/usr/bin/env python3
"""
AutoX.js Connector - 通用命令调用脚本
=======================================
向运行中的 server.py 发送控制命令，输出格式化 JSON 结果。

用法:
    python3 call.py '{"cmd":"status"}' --port 9317
    python3 call.py '{"cmd":"dump"}' --port 9317
    python3 call.py '{"cmd":"pull_file","path":"/sdcard/test.txt","local_path":"./phone_data"}' --port 9317

--port 为 WebSocket 端口，控制端口自动推导为 port+10000。
"""

import argparse
import json
import socket
import sys


def send_cmd(req: dict, ctrl_port: int, timeout: float = 30.0) -> dict:
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect(("127.0.0.1", ctrl_port))
        s.sendall((json.dumps(req, ensure_ascii=False) + "\n").encode())
        buf = b""
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
            if b"\n" in buf:
                break
        return json.loads(buf.decode()) if buf else {"error": "no response"}
    finally:
        s.close()


def main():
    parser = argparse.ArgumentParser(description="AutoX.js Connector - 通用命令调用")
    parser.add_argument("cmd", help="JSON 命令字符串")
    parser.add_argument("--port", type=int, default=9317, help="WebSocket 端口 (默认 9317，控制端口 +10000)")
    parser.add_argument("--timeout", type=float, default=30.0, help="超时秒数 (默认 30)")
    args = parser.parse_args()

    try:
        req = json.loads(args.cmd)
    except json.JSONDecodeError as e:
        print(json.dumps({"error": f"JSON 解析失败: {e}"}, ensure_ascii=False))
        sys.exit(1)

    ctrl_port = args.port + 10000
    try:
        result = send_cmd(req, ctrl_port, args.timeout)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception as e:
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == "__main__":
    main()