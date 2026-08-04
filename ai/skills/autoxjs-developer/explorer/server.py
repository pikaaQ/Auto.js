#!/usr/bin/env python3
"""
页面探索工具 - Web 服务
======================
全局常驻服务。手动探索 AutoX.js 页面，记录 OCR/DUMP 结果和页面跳转关系。
项目路径由前端每次请求携带，服务端无状态。

用法:
    python3 server.py --http-port 5000
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

SKILL_BASE_DIR = Path(__file__).resolve().parent.parent.parent
CONNECTOR_CALL = str(SKILL_BASE_DIR / "autoxjs-connector" / "call.py")
CONNECTOR_PORT = 9317
DIR_PATH = "/storage/emulated/0/脚本"

# 服务级配置（跨请求持久化）
_STATE = {
    "connector_port": CONNECTOR_PORT,
    "dir_path": DIR_PATH,
    "projects_dir": None,
}


class ExploreHandler(SimpleHTTPRequestHandler):

    def _get_project(self, req_data=None, query_params=None):
        """从请求中获取项目路径：POST body 或 GET query"""
        if req_data and isinstance(req_data, dict):
            path = req_data.get("project_path", "")
            if path:
                return path
        if query_params:
            path = query_params.get("project_path", [None])[0]
            if path:
                return path
        return None

    def _flow_path(self, project_path):
        return os.path.join(project_path, "docs", "flow.json")

    def _explore_dir(self, project_path, page_id=None):
        base = os.path.join(project_path, "docs", "explore")
        if page_id:
            return os.path.join(base, page_id)
        return base

    def _load_flow(self, project_path):
        if not project_path:
            return {"pages": []}
        path = self._flow_path(project_path)
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        return {"pages": []}

    def _save_flow(self, flow, project_path):
        path = self._flow_path(project_path)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(flow, f, ensure_ascii=False, indent=2)

    def _find_page(self, flow, page_id):
        for p in flow.get("pages", []):
            if p.get("id") == page_id:
                return p
        return None

    def _scan_projects(self):
        projects = []
        pd = _STATE["projects_dir"]
        if not pd or not os.path.isdir(pd):
            return projects
        for entry in os.listdir(pd):
            path = os.path.join(pd, entry)
            if not os.path.isdir(path) or entry.startswith("."):
                continue
            if (os.path.exists(os.path.join(path, "project.json")) or
                os.path.exists(os.path.join(path, "lib")) or
                os.path.exists(os.path.join(path, "actions")) or
                os.path.exists(os.path.join(path, "docs"))):
                projects.append({"name": entry, "path": path})
        return projects

    def _call_phone(self, cmd):
        try:
            result = subprocess.run(
                [sys.executable, CONNECTOR_CALL, json.dumps(cmd), "--port", str(_STATE["connector_port"])],
                capture_output=True, text=True, timeout=60
            )
            if result.returncode != 0:
                return {"error": f"call.py 错误: {result.stderr}"}
            return json.loads(result.stdout)
        except subprocess.TimeoutExpired:
            return {"error": "命令超时"}
        except Exception as e:
            return {"error": str(e)}

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

    def _flatten_dump(self, node, depth=0):
        """展平 dump 树，过滤不可见节点，返回 [{bounds, text, className, ...}]"""
        if not node or not isinstance(node, dict):
            return []
        result = []
        # 只保留可见节点
        if node.get("visible") and node.get("bounds"):
            bounds = node["bounds"]
            # bounds 格式 "(left,top,right,bottom)" 或 "[left,top][right,bottom]"
            import re
            m = re.match(r"[\(\[](\d+),(\d+)[,\)]\s*[\(\[]?(\d+),(\d+)[\)\]]?", str(bounds))
            if m:
                result.append({
                    "bounds": {
                        "left": int(m.group(1)), "top": int(m.group(2)),
                        "right": int(m.group(3)), "bottom": int(m.group(4)),
                    },
                    "text": node.get("text") or "",
                    "className": node.get("className") or "",
                    "clickable": node.get("clickable", False),
                    "depth": depth,
                })
        # 递归子节点（child0, child1, ...）
        for key in sorted(node.keys()):
            if key.startswith("child") and isinstance(node[key], dict):
                result.extend(self._flatten_dump(node[key], depth + 1))
        return result

    def _generate_explore_script(self, page_id):
        dp = _STATE["dir_path"]
        return f'''"autojs";
var pageId = "{page_id}";
var tmp = "{dp}/tmp/";
log("=== 探索开始: " + pageId + " ===");
log("Step1: 绑定 Shizuku");
var proto = Object.getPrototypeOf($shizuku);
if (!proto.isRunning()) {{
  log("Shizuku 未运行, 尝试绑定");
  proto.requestPermission(); sleep(2000);
  if (!proto.isRunning()) {{
    log("requestPermission 后仍未运行, 反射绑定");
    var clazz = proto.getClass();
    var bindMethod = clazz.getDeclaredMethod("bindUserService");
    bindMethod.setAccessible(true); bindMethod.invoke(proto); sleep(3000);
  }}
}}
log("Shizuku 运行状态: " + proto.isRunning());
if (!proto.isRunning()) {{ log("Shizuku 不可用"); exit(); }}
log("Step2: 创建并清理 tmp 目录");
files.removeDir(tmp);
files.ensureDir(tmp);
log("tmp 目录就绪: " + files.exists(tmp));
log("Step3: 截图");
var picPath = tmp + "screenshot.png";
var result = $shizuku("screencap -p " + picPath);
log("截图结果: code=" + result.code + " error=" + result.error);
if (result.code !== 0) {{ log("截图失败: " + result.error); exit(); }}
log("Step4: OCR");
var img = images.read(picPath);
if (img) {{
  var raw = $mlKitOcr.detect(img);
  var ocrList = [];
  for (var i = 0; i < (raw ? raw.length : 0); i++) {{
    ocrList.push({{
      label: raw[i].label,
      bounds: {{ left: raw[i].bounds.left, top: raw[i].bounds.top, right: raw[i].bounds.right, bottom: raw[i].bounds.bottom }}
    }});
  }}
  files.write(tmp + "ocr.json", JSON.stringify(ocrList));
  img.recycle();
}}
log("=== 探索完毕: " + pageId + " ===");
'''

    def _pull_explore_results(self, page_id, project_path):
        time.sleep(5)
        local_dir = self._explore_dir(project_path, page_id)
        os.makedirs(local_dir, exist_ok=True)
        tmp = f"{_STATE['dir_path']}/tmp"
        # 拉取截图和 OCR
        for fname in ["screenshot.png", "ocr.json"]:
            phone_path = f"{tmp}/{fname}"
            local_path = os.path.join(local_dir, fname)
            cmd = {"cmd": "pull_file", "path": phone_path, "local_path": local_dir}
            print(f"  拉取: {phone_path} -> {local_dir}")
            resp = self._call_phone(cmd)
            print(f"  响应: {json.dumps(resp, ensure_ascii=False)[:200]}")
            if resp.get("success") and os.path.exists(local_path):
                print(f"  ✓ 已拉取 {fname}")
            else:
                print(f"  - 无 {fname}")
        # 通过协议命令获取 UI 组件树
        print("  获取 UI 组件树...")
        dump_resp = self._call_phone({"cmd": "dump"})
        if dump_resp.get("success") and dump_resp.get("result", {}).get("dump"):
            dump_path = os.path.join(local_dir, "dump.json")
            # 展平树、过滤不可见节点、提取 bounds/text
            flat = self._flatten_dump(dump_resp["result"]["dump"])
            with open(dump_path, "w", encoding="utf-8") as f:
                json.dump(flat, f, ensure_ascii=False, indent=2)
            print(f"  ✓ 已获取 dump.json ({len(flat)} 个可见节点)")
        else:
            print(f"  - 无 dump（{dump_resp.get('error', 'unknown')}）")
        flow = self._load_flow(project_path)
        page = self._find_page(flow, page_id)
        if page:
            page["explored"] = True
            self._save_flow(flow, project_path)
        print(f"  ✓ 探索 {page_id} 完成")

    def log_message(self, format, *args):
        print(f"[{self.log_date_time_string()}] {format % args}")

    # ─── GET ──────────────────────────────────────────

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        if path == "/api/projects":
            self._send_json({"projects": self._scan_projects()})

        elif path == "/api/flow":
            project = self._get_project(query_params=qs)
            if not project:
                self._send_error("缺少 project_path")
                return
            self._send_json(self._load_flow(project))

        elif path == "/api/explore/pages":
            project = self._get_project(query_params=qs)
            if not project:
                self._send_error("缺少 project_path")
                return
            flow = self._load_flow(project)
            page_list = []
            for p in flow.get("pages", []):
                has = os.path.exists(os.path.join(self._explore_dir(project, p["id"]), "screenshot.png"))
                page_list.append({
                    "id": p["id"], "name": p.get("name", p["id"]),
                    "explored": has, "transition_count": len(p.get("transitions", [])),
                })
            self._send_json(page_list)

        elif path.startswith("/api/explore/") and path.endswith("/result"):
            page_id = path.split("/")[3]
            project = self._get_project(query_params=qs)
            if not project:
                self._send_error("缺少 project_path")
                return
            page_dir = self._explore_dir(project, page_id)
            if not os.path.exists(page_dir):
                self._send_error("页面尚未探索", 404)
                return
            result = {"page_id": page_id, "files": {}}
            for f in ["screenshot.png", "ocr.json", "dump.json"]:
                result["files"][f] = os.path.exists(os.path.join(page_dir, f))
            ocr_p = os.path.join(page_dir, "ocr.json")
            result["ocr"] = json.load(open(ocr_p, encoding="utf-8")) if os.path.exists(ocr_p) else []
            dump_p = os.path.join(page_dir, "dump.json")
            result["dump"] = json.dumps(json.load(open(dump_p, encoding="utf-8")), ensure_ascii=False) if os.path.exists(dump_p) else ""
            flow = self._load_flow(project)
            page = self._find_page(flow, page_id)
            result["transitions"] = page.get("transitions", []) if page else []
            self._send_json(result)

        elif path.startswith("/api/explore/") and path.endswith("/screenshot.png"):
            page_id = path.split("/")[3]
            project = self._get_project(query_params=qs)
            if not project:
                self._send_error("缺少 project_path")
                return
            fpath = os.path.join(self._explore_dir(project, page_id), "screenshot.png")
            if os.path.exists(fpath):
                self._send_file(fpath, "image/png")
            else:
                self._send_error("截图不存在", 404)

        elif path == "/api/config":
            self._send_json({
                "dir_path": _STATE["dir_path"],
                "connector_port": _STATE["connector_port"],
            })

        elif path == "/" or path == "/index.html":
            p = os.path.join(os.path.dirname(__file__), "static", "index.html")
            if os.path.exists(p):
                self._send_file(p, "text/html; charset=utf-8")
            else:
                self._send_error("index.html not found", 404)

        elif path.startswith("/static/"):
            p = os.path.join(os.path.dirname(__file__), "static", path[8:])
            if os.path.exists(p):
                ext = os.path.splitext(p)[1]
                mime = {".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8",
                        ".css": "text/css; charset=utf-8", ".png": "image/png", ".svg": "image/svg+xml"}.get(ext, "application/octet-stream")
                self._send_file(p, mime)
            else:
                self._send_error("文件不存在", 404)
        else:
            self._send_error("未知路由", 404)

    # ─── POST ─────────────────────────────────────────

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        cl = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(cl).decode() if cl > 0 else "{}"
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            self._send_error("JSON 解析失败")
            return

        if path == "/api/explore":
            page_id = data.get("page_id", "")
            project = self._get_project(data)
            if not page_id or not project:
                self._send_error("缺少 page_id 或 project_path")
                return
            # 确保本地目录存在
            os.makedirs(self._explore_dir(project), exist_ok=True)
            os.makedirs(self._explore_dir(project, page_id), exist_ok=True)
            script = self._generate_explore_script(page_id)
            resp = self._call_phone({"cmd": "run", "name": f"_explore_{page_id}.js", "script": script, "wait": False})
            if resp.get("error"):
                self._send_error(resp["error"])
                return
            self._send_json({"status": "running", "page_id": page_id})
            threading.Thread(target=self._pull_explore_results, args=(page_id, project), daemon=True).start()

        elif path == "/api/explore/poll":
            page_id = data.get("page_id", "")
            project = self._get_project(data)
            if not project:
                self._send_error("缺少 project_path")
                return
            done_path = os.path.join(self._explore_dir(project, page_id), "done.txt")
            if os.path.exists(done_path):
                with open(done_path) as f:
                    self._send_json({"status": f.read().strip(), "page_id": page_id})
            else:
                self._send_json({"status": "running", "page_id": page_id})

        elif path == "/api/flow/transition":
            project = self._get_project(data)
            if not project:
                self._send_error("缺少 project_path")
                return
            page_id = data.get("page_id", "")
            target_id = data.get("target_id", "")
            if not page_id or not target_id:
                self._send_error("缺少 page_id 或 target_id")
                return
            flow = self._load_flow(project)
            page = self._find_page(flow, page_id)
            if not page:
                self._send_error(f"页面 {page_id} 不存在")
                return
            t = {"target": target_id, "method": data.get("method", "ocr"), "label": data.get("label", "")}
            if data.get("bounds"):
                t["bounds"] = data["bounds"]
            page.setdefault("transitions", []).append(t)
            self._save_flow(flow, project)
            self._send_json({"status": "ok", "transition": t})

        elif path == "/api/flow/page":
            project = self._get_project(data)
            if not project:
                self._send_error("缺少 project_path")
                return
            page_id = data.get("id", "")
            if not page_id:
                self._send_error("缺少 id")
                return
            flow = self._load_flow(project)
            if self._find_page(flow, page_id):
                self._send_error(f"页面 {page_id} 已存在")
                return
            flow["pages"].append({"id": page_id, "name": data.get("name", page_id), "description": data.get("description", ""), "transitions": []})
            self._save_flow(flow, project)
            self._send_json({"status": "ok"})

        else:
            self._send_error("未知路由", 404)

    # ─── PUT ──────────────────────────────────────────

    def do_PUT(self):
        cl = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(cl).decode() if cl > 0 else "{}"
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            self._send_error("JSON 解析失败")
            return

        if parsed_path(self.path) == "/api/flow":
            project = self._get_project(data)
            if not project:
                self._send_error("缺少 project_path")
                return
            if "pages" in data:
                self._save_flow(data, project)
                self._send_json({"status": "ok"})
            else:
                self._send_error("无效的 flow 数据")
        else:
            self._send_error("未知路由", 404)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()


def parsed_path(url):
    return urlparse(url).path


def main():
    parser = argparse.ArgumentParser(description="AutoX.js 页面探索工具")
    parser.add_argument("--projects-dir", default=".", help="项目目录扫描根目录")
    parser.add_argument("--connector-port", type=int, default=CONNECTOR_PORT,
                        help=f"Connector WebSocket 端口 (默认 {CONNECTOR_PORT})")
    parser.add_argument("--dir-path", default=DIR_PATH, help="手机脚本根目录")
    parser.add_argument("--http-port", type=int, default=5000, help="Web 服务端口")
    args = parser.parse_args()

    _STATE["connector_port"] = args.connector_port
    _STATE["dir_path"] = args.dir_path
    _STATE["projects_dir"] = os.path.abspath(args.projects_dir)

    print(f"📁 项目扫描目录: {_STATE['projects_dir']}")
    print(f"📱 手机脚本目录: {_STATE['dir_path']}")
    print(f"🔌 Connector 端口: {_STATE['connector_port']}")
    print(f"🌐 Web 服务: http://localhost:{args.http_port}")
    print(f"💡 打开浏览器，选择项目开始探索")

    server = HTTPServer(("0.0.0.0", args.http_port), ExploreHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务已停止")


if __name__ == "__main__":
    main()