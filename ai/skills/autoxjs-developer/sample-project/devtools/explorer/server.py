#!/usr/bin/env python3
"""
页面探索工具 - Web 服务
======================
手动探索 AutoX.js 页面，记录 OCR/DUMP 结果和页面跳转关系。

用法:
    python3 server.py --project /path/to/project --port 9317
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import threading
from http.server import HTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs

FLOW_FILE = "flow.json"
EXPLORE_DIR = "explore"
SKILL_BASE_DIR = Path(__file__).resolve().parent.parent.parent.parent
CONNECTOR_CALL = str(SKILL_BASE_DIR / "autoxjs-connector" / "call.py")


class ExploreHandler(SimpleHTTPRequestHandler):

    def __init__(self, *args, **kwargs):
        self.project_root = None
        self.connector_port = 9317
        self.dir_path = "/storage/emulated/0/脚本"
        super().__init__(*args, **kwargs)

    def log_message(self, format, *args):
        print(f"[{self.log_date_time_string()}] {format % args}")

    def _send_json(self, data, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode())

    def _send_file(self, path, mime):
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        with open(path, "rb") as f:
            shutil.copyfileobj(f, self.wfile)

    def _send_error(self, msg, status=400):
        self._send_json({"error": msg}, status)

    def _call_phone(self, cmd: dict) -> dict:
        """通过 call.py 发送命令到手机"""
        try:
            result = subprocess.run(
                [sys.executable, CONNECTOR_CALL, json.dumps(cmd), "--port", str(self.connector_port)],
                capture_output=True, text=True, timeout=60
            )
            if result.returncode != 0:
                return {"error": f"call.py 错误: {result.stderr}"}
            return json.loads(result.stdout)
        except subprocess.TimeoutExpired:
            return {"error": "命令超时"}
        except Exception as e:
            return {"error": str(e)}

    def _load_flow(self) -> dict:
        flow_path = os.path.join(self.project_root, FLOW_FILE)
        if os.path.exists(flow_path):
            with open(flow_path, encoding="utf-8") as f:
                return json.load(f)
        return {"pages": []}

    def _save_flow(self, flow: dict):
        flow_path = os.path.join(self.project_root, FLOW_FILE)
        with open(flow_path, "w", encoding="utf-8") as f:
            json.dump(flow, f, ensure_ascii=False, indent=2)

    def _find_page(self, flow: dict, page_id: str) -> dict | None:
        for p in flow.get("pages", []):
            if p.get("id") == page_id:
                return p
        return None

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        params = parse_qs(parsed.query)

        # API 路由
        if path == "/api/flow":
            flow = self._load_flow()
            self._send_json(flow)

        elif path == "/api/explore/pages":
            flow = self._load_flow()
            page_list = []
            for p in flow.get("pages", []):
                page_dir = os.path.join(self.project_root, EXPLORE_DIR, p["id"])
                has_result = os.path.exists(os.path.join(page_dir, "screenshot.png"))
                page_list.append({
                    "id": p["id"],
                    "name": p.get("name", p["id"]),
                    "explored": has_result,
                    "transition_count": len(p.get("transitions", [])),
                })
            self._send_json(page_list)

        elif path.startswith("/api/explore/") and path.endswith("/result"):
            page_id = path.split("/")[3]
            page_dir = os.path.join(self.project_root, EXPLORE_DIR, page_id)
            if not os.path.exists(page_dir):
                self._send_error("页面尚未探索", 404)
                return
            result = {"page_id": page_id, "files": {}}
            for fname in ["screenshot.png", "ocr.json", "dump.xml"]:
                fpath = os.path.join(page_dir, fname)
                if os.path.exists(fpath):
                    result["files"][fname] = True
                else:
                    result["files"][fname] = False
            # 读取 OCR 数据
            ocr_path = os.path.join(page_dir, "ocr.json")
            if os.path.exists(ocr_path):
                with open(ocr_path, encoding="utf-8") as f:
                    result["ocr"] = json.load(f)
            else:
                result["ocr"] = []
            # 读取 Dump 数据（简化版，只提取关键组件信息）
            dump_path = os.path.join(page_dir, "dump.xml")
            if os.path.exists(dump_path):
                with open(dump_path, encoding="utf-8") as f:
                    result["dump"] = f.read()
            else:
                result["dump"] = ""
            # 读取已有跳转信息
            flow = self._load_flow()
            page = self._find_page(flow, page_id)
            result["transitions"] = page.get("transitions", []) if page else []
            self._send_json(result)

        elif path.startswith("/api/explore/") and path.endswith("/screenshot.png"):
            page_id = path.split("/")[3]
            fpath = os.path.join(self.project_root, EXPLORE_DIR, page_id, "screenshot.png")
            if os.path.exists(fpath):
                self._send_file(fpath, "image/png")
            else:
                self._send_error("截图不存在", 404)

        elif path == "/api/config":
            self._send_json({
                "dir_path": self.dir_path,
                "project_root": self.project_root,
                "connector_port": self.connector_port,
            })

        elif path == "/" or path == "/index.html":
            static_dir = os.path.join(os.path.dirname(__file__), "static")
            index_path = os.path.join(static_dir, "index.html")
            if os.path.exists(index_path):
                self._send_file(index_path, "text/html; charset=utf-8")
            else:
                self._send_error("index.html not found", 404)

        elif path.startswith("/static/"):
            static_dir = os.path.join(os.path.dirname(__file__), "static")
            fpath = os.path.join(static_dir, path[8:])
            if os.path.exists(fpath):
                ext = os.path.splitext(fpath)[1]
                mime = {
                    ".html": "text/html; charset=utf-8",
                    ".js": "application/javascript; charset=utf-8",
                    ".css": "text/css; charset=utf-8",
                    ".png": "image/png",
                    ".svg": "image/svg+xml",
                }.get(ext, "application/octet-stream")
                self._send_file(fpath, mime)
            else:
                self._send_error("文件不存在", 404)
        else:
            self._send_error("未知路由", 404)

    def do_PUT(self):
        parsed = urlparse(self.path)
        path = parsed.path
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length).decode() if content_length > 0 else "{}"
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            self._send_error("JSON 解析失败")
            return

        if path == "/api/flow":
            if "pages" in data:
                self._save_flow(data)
                self._send_json({"status": "ok"})
            else:
                self._send_error("无效的 flow 数据")
        else:
            self._send_error("未知路由", 404)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length).decode() if content_length > 0 else "{}"
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            self._send_error("JSON 解析失败")
            return

        if path == "/api/explore":
            # 执行探索
            page_id = data.get("page_id", "")
            if not page_id:
                self._send_error("缺少 page_id")
                return

            flow = self._load_flow()
            page = self._find_page(flow, page_id)
            page_name = page.get("name", page_id) if page else page_id

            # 生成探索脚本
            script = self._generate_explore_script(page_id)
            if not script:
                self._send_error("脚本生成失败")
                return

            # 推送执行
            cmd = {
                "cmd": "run",
                "name": f"_explore_{page_id}.js",
                "script": script,
                "wait": False,
            }
            resp = self._call_phone(cmd)
            if resp.get("error"):
                self._send_error(resp["error"])
                return

            # 等待执行完成
            self._send_json({
                "status": "running",
                "page_id": page_id,
                "message": f"探索 {page_name} 中...",
            })

            # 后台等待并拉取结果
            thread = threading.Thread(
                target=self._pull_explore_results,
                args=(page_id,),
                daemon=True,
            )
            thread.start()

        elif path == "/api/explore/poll":
            page_id = data.get("page_id", "")
            done_path = os.path.join(self.project_root, EXPLORE_DIR, page_id, "done.txt")
            if os.path.exists(done_path):
                with open(done_path) as f:
                    status = f.read().strip()
                self._send_json({"status": status, "page_id": page_id})
            else:
                self._send_json({"status": "running", "page_id": page_id})

        elif path == "/api/flow/transition":
            # 添加/更新跳转
            page_id = data.get("page_id", "")
            target_id = data.get("target_id", "")
            method = data.get("method", "ocr")  # ocr / dump
            label = data.get("label", "")
            bounds = data.get("bounds")  # [left, top, right, bottom]

            if not page_id or not target_id:
                self._send_error("缺少 page_id 或 target_id")
                return

            flow = self._load_flow()
            page = self._find_page(flow, page_id)
            if not page:
                self._send_error(f"页面 {page_id} 不存在")
                return

            # 添加跳转
            transition = {
                "target": target_id,
                "method": method,
                "label": label,
            }
            if bounds:
                transition["bounds"] = bounds

            if "transitions" not in page:
                page["transitions"] = []
            page["transitions"].append(transition)
            self._save_flow(flow)
            self._send_json({"status": "ok", "transition": transition})

        elif path == "/api/flow/page":
            # 添加新页面
            page_id = data.get("id", "")
            page_name = data.get("name", page_id)
            description = data.get("description", "")

            if not page_id:
                self._send_error("缺少 id")
                return

            flow = self._load_flow()
            if self._find_page(flow, page_id):
                self._send_error(f"页面 {page_id} 已存在")
                return

            flow["pages"].append({
                "id": page_id,
                "name": page_name,
                "description": description,
                "transitions": [],
            })
            self._save_flow(flow)
            self._send_json({"status": "ok"})

        else:
            self._send_error("未知路由", 404)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def _generate_explore_script(self, page_id: str) -> str:
        """生成探索脚本"""
        dir_path = self.dir_path
        return f'''"autojs";
var pageId = "{page_id}";
var exploreDir = "{dir_path}/explore/" + pageId;
files.ensureDir(exploreDir);

// 绑定 Shizuku
var proto = Object.getPrototypeOf($shizuku);
if (!proto.isRunning()) {{
  proto.requestPermission();
  sleep(2000);
  if (!proto.isRunning()) {{
    var clazz = proto.getClass();
    var bindMethod = clazz.getDeclaredMethod("bindUserService");
    bindMethod.setAccessible(true);
    bindMethod.invoke(proto);
    sleep(3000);
  }}
}}
if (!proto.isRunning()) {{
  files.write(exploreDir + "/done.txt", "shizuku_failed");
  exit();
}}

// 截图
var picPath = exploreDir + "/screenshot.png";
var result = $shizuku("screencap -p " + picPath);
if (result.code !== 0) {{
  files.write(exploreDir + "/error.txt", "截图失败: " + result.error);
  files.write(exploreDir + "/done.txt", "error");
  exit();
}}

// OCR
var img = images.read(picPath);
if (img) {{
  var raw = $mlKitOcr.detect(img);
  var ocrList = [];
  for (var i = 0; i < (raw ? raw.length : 0); i++) {{
    ocrList.push({{
      label: raw[i].label,
      bounds: {{
        left: raw[i].bounds.left,
        top: raw[i].bounds.top,
        right: raw[i].bounds.right,
        bottom: raw[i].bounds.bottom
      }}
    }});
  }}
  files.write(exploreDir + "/ocr.json", JSON.stringify(ocrList));
  img.recycle();
}}

// Dump 组件树
var xml = UiSelector.dump();
if (xml) {{
  files.write(exploreDir + "/dump.xml", xml);
}}

files.write(exploreDir + "/done.txt", "ok");
log("=== 探索完毕: " + pageId + " ===");
'''

    def _pull_explore_results(self, page_id: str):
        """后台拉取探索结果"""
        time.sleep(5)
        local_dir = os.path.join(self.project_root, EXPLORE_DIR, page_id)
        os.makedirs(local_dir, exist_ok=True)
        phone_dir = f"{self.dir_path}/explore/{page_id}"

        files_to_pull = [
            "done.txt", "screenshot.png", "ocr.json", "dump.xml", "error.txt"
        ]
        for fname in files_to_pull:
            phone_path = f"{phone_dir}/{fname}"
            local_path = os.path.join(local_dir, fname)
            cmd = {"cmd": "pull_file", "path": phone_path, "local_path": local_dir}
            resp = self._call_phone(cmd)
            if resp.get("success") and os.path.exists(local_path):
                print(f"  ✓ 已拉取 {fname}")
            else:
                print(f"  - 无 {fname}")

        # 更新 flow.json 探索状态
        flow = self._load_flow()
        page = self._find_page(flow, page_id)
        if page:
            page["explored"] = True
            self._save_flow(flow)
        print(f"  ✓ 探索 {page_id} 完成")


def main():
    parser = argparse.ArgumentParser(description="AutoX.js 页面探索工具")
    parser.add_argument("--project", default=".", help="项目根目录")
    parser.add_argument("--port", type=int, default=9317, help="connector 端口")
    parser.add_argument("--dir-path", default="/storage/emulated/0/脚本", help="手机脚本根目录")
    parser.add_argument("--http-port", type=int, default=5000, help="Web 服务端口")
    args = parser.parse_args()

    project_root = os.path.abspath(args.project)
    print(f"📁 项目目录: {project_root}")
    print(f"📱 手机脚本目录: {args.dir_path}")
    print(f"🔌 Connector 端口: {args.port}")
    print(f"🌐 Web 服务: http://localhost:{args.http_port}")

    # 确保 explore 目录存在
    os.makedirs(os.path.join(project_root, EXPLORE_DIR), exist_ok=True)

    # 启动 HTTP 服务
    server = HTTPServer(("0.0.0.0", args.http_port), ExploreHandler)
    server.project_root = project_root
    server.connector_port = args.port
    server.dir_path = args.dir_path

    # 将 server 实例属性传递给 handler
    handler = ExploreHandler
    handler.project_root = project_root
    handler.connector_port = args.port
    handler.dir_path = args.dir_path

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务已停止")


if __name__ == "__main__":
    main()