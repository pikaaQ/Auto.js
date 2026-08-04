// ─── 状态 ───────────────────────────────────────────
let currentPageId = null;
let ocrData = [], dumpData = null, selectedComponent = null, componentList = [];
let flow = { pages: [] }, projectPath = null;
const STORAGE_KEY = "explorer_project_paths";

// ─── 初始化 ─────────────────────────────────────────
document.addEventListener("DOMContentLoaded", () => {
  const lastPath = localStorage.getItem(STORAGE_KEY + "_last");
  if (lastPath) {
    projectPath = lastPath;
    document.getElementById("project-name").textContent = lastPath.split("/").pop();
    initProject();
  } else {
    showProjectSelector();
  }
});

async function initProject() {
  await loadFlow();
  renderPageList();
  document.getElementById("detail-title").textContent = "选择一个页面开始探索";
}

// ─── 本地存储 ───────────────────────────────────────
function getSavedPaths() {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || "[]"); }
  catch { return []; }
}

function savePath(path) {
  let paths = getSavedPaths().filter(p => p !== path);
  paths.unshift(path);
  if (paths.length > 10) paths = paths.slice(0, 10);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(paths));
  localStorage.setItem(STORAGE_KEY + "_last", path);
}

function deleteSavedPath(path) {
  let paths = getSavedPaths().filter(p => p !== path);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(paths));
  if (localStorage.getItem(STORAGE_KEY + "_last") === path) {
    localStorage.removeItem(STORAGE_KEY + "_last");
  }
}

// ─── API 调用 ───────────────────────────────────────
async function api(method, path, body) {
  if (body && projectPath) body.project_path = projectPath;
  const opts = { method, headers: { "Content-Type": "application/json" } };
  if (body) opts.body = JSON.stringify(body);
  const r = await fetch(path, opts);
  if (!r.ok) {
    const err = await r.json().catch(() => ({ error: r.statusText }));
    throw new Error(err.error || r.statusText);
  }
  return r.json();
}

function qs(params) {
  return "?" + Object.entries(params).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&");
}

// ─── 项目选择 ───────────────────────────────────────
async function showProjectSelector() {
  const overlay = document.getElementById("project-selector");
  overlay.style.display = "flex";

  // 历史记录
  const saved = getSavedPaths();
  const historySection = document.getElementById("history-list");
  const historyItems = document.getElementById("history-items");
  if (saved.length > 0) {
    historySection.style.display = "block";
    historyItems.innerHTML = "";
    saved.forEach(p => {
      const div = document.createElement("div");
      div.className = "project-item";
      div.innerHTML = `<div style="flex:1"><div class="project-item-name">${p.split("/").pop()}</div><div class="project-item-path">${p}</div></div><button class="del-btn" onclick="event.stopPropagation();deleteSavedPath('${p}');showProjectSelector();" style="color:#f44336;background:none;border:none;cursor:pointer;font-size:16px;">×</button>`;
      div.onclick = () => selectProject(p);
      historyItems.appendChild(div);
    });
  } else {
    historySection.style.display = "none";
  }

  // 扫描项目
  const list = document.getElementById("project-list");
  list.innerHTML = "<p style='color:#999;font-size:13px;'>扫描中...</p>";
  try {
    const data = await fetch("/api/projects").then(r => r.json());
    list.innerHTML = "";
    if (!data.projects || data.projects.length === 0) {
      list.innerHTML = "<p style='color:#999;font-size:13px;'>未找到项目，请手动输入路径</p>";
      return;
    }
    data.projects.forEach(p => {
      const div = document.createElement("div");
      div.className = "project-item";
      div.innerHTML = `<div><div class="project-item-name">${p.name}</div><div class="project-item-path">${p.path}</div></div>`;
      div.onclick = () => selectProject(p.path);
      list.appendChild(div);
    });
  } catch (e) {
    list.innerHTML = `<p style='color:#f44336;font-size:13px;'>扫描失败: ${e.message}</p>`;
  }
}

function closeProjectSelector() {
  document.getElementById("project-selector").style.display = "none";
}

async function selectProject(path) {
  projectPath = path;
  savePath(path);
  document.getElementById("project-name").textContent = path.split("/").pop();
  closeProjectSelector();
  await initProject();
}

async function selectManualPath() {
  const path = document.getElementById("manual-path").value.trim();
  if (!path) return;
  await selectProject(path);
}

// ─── 加载 flow ──────────────────────────────────────
async function loadFlow() {
  if (!projectPath) return;
  try { flow = await fetch("/api/flow" + qs({ project_path: projectPath })).then(r => r.json()); }
  catch { flow = { pages: [] }; }
}

async function refreshResult() {
  if (!currentPageId || !projectPath) return;
  await loadResult(currentPageId);
}

// ─── 渲染页面列表 ───────────────────────────────────
async function renderPageList() {
  if (!projectPath) return;
  let data = [];
  try { data = await fetch("/api/explore/pages" + qs({ project_path: projectPath })).then(r => r.json()); }
  catch { data = []; }
  const list = document.getElementById("page-list");
  list.innerHTML = "";
  document.getElementById("page-count").textContent = data.length;
  if (data.length === 0) {
    list.innerHTML = '<div style="padding:12px;color:#999;font-size:13px;text-align:center;">暂无页面<br>点击「+ 添加页面」</div>';
    return;
  }
  data.forEach(p => {
    const div = document.createElement("div");
    div.className = "page-item" + (p.id === currentPageId ? " active" : "");
    div.innerHTML = `<span class="page-name">${p.name}</span><span class="page-badge ${p.explored ? 'done' : 'pending'}">${p.explored ? '✓' : '○'}</span>`;
    div.onclick = () => selectPage(p.id);
    list.appendChild(div);
  });
}

// ─── 选择页面 ───────────────────────────────────────
async function selectPage(pageId) {
  currentPageId = pageId;
  document.getElementById("btn-explore").disabled = false;
  document.getElementById("btn-refresh").disabled = false;
  document.querySelectorAll(".page-item").forEach(el => el.classList.remove("active"));
  document.querySelectorAll(".page-item").forEach(el => {
    if (el.querySelector(".page-name").textContent === (flow.pages.find(p => p.id === pageId)?.name || pageId)) el.classList.add("active");
  });
  document.getElementById("detail-title").textContent = "📄 " + (flow.pages.find(p => p.id === pageId)?.name || pageId);
  // 显示页面信息编辑
  document.getElementById("page-info").style.display = "block";
  document.getElementById("edit-page-id").value = pageId;
  document.getElementById("edit-page-name").value = flow.pages.find(p => p.id === pageId)?.name || pageId;
  // 显示加载中
  document.getElementById("placeholder").style.display = "block";
  document.getElementById("placeholder").innerHTML = "<p>⏳ 正在加载...</p>";
  document.getElementById("image-wrapper").style.display = "none";
  document.getElementById("toolbar").style.display = "none";
  document.getElementById("toggle-none").checked = true;
  document.getElementById("transitions-status").style.display = "block";
  document.getElementById("transitions-status").textContent = "⏳ 正在加载...";
  document.getElementById("transitions-list").style.display = "none";
  document.getElementById("transition-form").style.display = "none";
  await loadResult(pageId);
}

// ─── 加载探索结果 ───────────────────────────────────
async function loadResult(pageId) {
  if (!projectPath) return;
  let data;
  try { data = await fetch(`/api/explore/${pageId}/result` + qs({ project_path: projectPath })).then(r => r.json()); }
  catch {
    document.getElementById("placeholder").style.display = "block";
    document.getElementById("placeholder").innerHTML = "<p>该页面尚未探索</p>";
    document.getElementById("image-wrapper").style.display = "none";
    document.getElementById("toolbar").style.display = "none";
    document.getElementById("transitions-status").style.display = "block";
    document.getElementById("transitions-status").textContent = "该页面尚未探索";
    document.getElementById("transitions-list").style.display = "none";
    document.getElementById("transition-form").style.display = "none";
    return;
  }
  if (!data.files || !data.files["screenshot.png"]) {
    document.getElementById("placeholder").style.display = "block";
    document.getElementById("placeholder").innerHTML = "<p>该页面尚未探索</p>";
    document.getElementById("image-wrapper").style.display = "none";
    document.getElementById("toolbar").style.display = "none";
    document.getElementById("transitions-status").style.display = "block";
    document.getElementById("transitions-status").textContent = "该页面尚未探索";
    document.getElementById("transitions-list").style.display = "none";
    document.getElementById("transition-form").style.display = "none";
    return;
  }
  const img = document.getElementById("screenshot-img");
  img.src = `/api/explore/${pageId}/screenshot.png` + qs({ project_path: projectPath }) + "&t=" + Date.now();
  img.onload = function() {
    document.getElementById("image-wrapper").style.display = "inline-block";
    document.getElementById("toolbar").style.display = "flex";
    document.getElementById("transitions-status").style.display = "none";
    document.getElementById("transitions-list").style.display = "block";
    document.getElementById("transition-form").style.display = "block";
    // 默认选中 DUMP（有结果时），否则 OCR
    if (data.dump) { document.getElementById("toggle-dump").checked = true; }
    else { document.getElementById("toggle-ocr").checked = true; }
    toggleOverlay();
    fitImage();
  };
  };
  ocrData = data.ocr || [];
  dumpData = data.dump ? parseDumpData(data.dump) : null;
  renderTransitions(data.transitions || []);
}

function fitImage() {
  var container = document.getElementById("canvas-container");
  var img = document.getElementById("screenshot-img");
  var canvas = document.getElementById("overlay-canvas");
  var cw = container.clientWidth;
  var ch = container.clientHeight;
  var iw = img.naturalWidth;
  var ih = img.naturalHeight;
  if (cw <= 0 || ch <= 0 || iw <= 0 || ih <= 0) return;
  // 计算缩放比例，填满容器
  var scale = Math.min(cw / iw, ch / ih);
  var dw = Math.round(iw * scale);
  var dh = Math.round(ih * scale);
  img.style.width = dw + "px";
  img.style.height = dh + "px";
  canvas.width = iw;
  canvas.height = ih;
  canvas.style.width = dw + "px";
  canvas.style.height = dh + "px";
  renderOverlay();
}

window.addEventListener("resize", function() {
  var img = document.getElementById("screenshot-img");
  if (img && img.style.width) fitImage();
});

function parseDumpData(data) {
  // JSON 格式：扁平节点数组 [{bounds, text, className, clickable, depth}]
  if (typeof data === "string") {
    try { data = JSON.parse(data); } catch { return []; }
  }
  if (Array.isArray(data)) {
    return data.map(n => ({
      _left: n.bounds.left, _top: n.bounds.top,
      _right: n.bounds.right, _bottom: n.bounds.bottom,
      text: n.text || "", className: n.className || "", clickable: n.clickable,
    })).filter(n => n._right > n._left && n._bottom > n._top);
  }
  return [];
}

function setupCanvas() {
  const img = document.getElementById("screenshot-img");
  const canvas = document.getElementById("overlay-canvas");
  canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
  canvas.style.width = "100%"; canvas.style.height = "100%";
}

function toggleOverlay() {
  clearTransitionForm();
  renderOverlay();
}

function renderOverlay() {
  const canvas = document.getElementById("overlay-canvas");
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const showOcr = document.getElementById("toggle-ocr").checked;
  const showDump = document.getElementById("toggle-dump").checked;
  componentList = [];
  if (showOcr) {
    ocrData.forEach(item => {
      const b = item.bounds; if (!b) return;
      const x = b.left, y = b.top, w = b.right - b.left, h = b.bottom - b.top;
      if (w <= 0 || h <= 0) return;
      componentList.push({ id: `ocr_${item.label}_${x}_${y}`, type: "ocr", label: item.label, bounds: { left: x, top: y, right: b.right, bottom: b.bottom } });
ctx.strokeStyle = "#f44336"; ctx.lineWidth = 4; ctx.strokeRect(x, y, w, h);
      var fs = Math.max(10, h * 2 / 3);
      ctx.fillStyle = "rgba(244, 67, 54, 0.7)"; ctx.font = "bold " + fs + "px sans-serif";
      var tw = ctx.measureText(item.label).width;
      var bh = fs + 4;
      ctx.fillRect(x, y - bh, Math.min(tw + 6, w), bh);
      ctx.fillStyle = "#fff"; ctx.fillText(item.label, x + 3, y - 3);
    });
  }
  if (showDump && dumpData) {
    // 按 depth 从小到大排序，同 depth 时面积大的在前（被包含的小面积在后，优先被点击）
    var sorted = dumpData.slice().sort(function(a, b) {
      if (a.depth !== b.depth) return a.depth - b.depth;
      var areaA = (a._right - a._left) * (a._bottom - a._top);
      var areaB = (b._right - b._left) * (b._bottom - b._top);
      return areaB - areaA; // 面积大的在前
    });
    sorted.forEach(function(node) {
      var x = node._left, y = node._top, w = node._right - node._left, h = node._bottom - node._top;
      if (w <= 0 || h <= 0) return;
      var label = node.text || node.desc || "";
      var fullLabel = (node.className || "") + " " + (node.text || "") + " " + (node.desc || "");
      componentList.push({ id: "dump_" + (fullLabel.trim() || x + "_" + y), type: "dump", label: fullLabel.trim(), bounds: { left: x, top: y, right: node._right, bottom: node._bottom } });
      ctx.strokeStyle = "#9c27b0"; ctx.lineWidth = 3; ctx.setLineDash([4, 2]); ctx.strokeRect(x, y, w, h); ctx.setLineDash([]);
      if (label) {
        var fs = Math.max(10, Math.min(h / 2, 64));
        ctx.fillStyle = "rgba(156, 39, 176, 0.7)"; ctx.font = "bold " + fs + "px sans-serif";
        var tw = ctx.measureText(label).width;
        var bh = fs + 4;
        ctx.fillRect(x, y - bh, Math.min(tw + 4, w), bh);
        ctx.fillStyle = "#fff"; ctx.fillText(label, x + 2, y - 3);
      }
    });
  }
  // 高亮选中组件
  if (selectedComponent) {
    var b = selectedComponent.bounds;
    var x = b.left, y = b.top, w = b.right - b.left, h = b.bottom - b.top;
    if (w > 0 && h > 0) {
      ctx.strokeStyle = "#00e676"; ctx.lineWidth = 8; ctx.strokeRect(x, y, w, h);
      ctx.strokeStyle = "#fff"; ctx.lineWidth = 2; ctx.setLineDash([4, 4]); ctx.strokeRect(x, y, w, h); ctx.setLineDash([]);
    }
  }
}

document.getElementById("overlay-canvas").addEventListener("click", (e) => {
  if (componentList.length === 0) return;
  const canvas = e.target;
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width, scaleY = canvas.height / rect.height;
  const mx = (e.clientX - rect.left) * scaleX, my = (e.clientY - rect.top) * scaleY;
  let clicked = null;
  for (var i = componentList.length - 1; i >= 0; i--) {
    const c = componentList[i];
    const b = c.bounds;
    if (mx >= b.left && mx <= b.right && my >= b.top && my <= b.bottom) { clicked = c; break; }
  }
  if (!clicked) return;
  selectedComponent = clicked;
  updateTransitionForm(clicked);
  renderOverlay();
});

function updateTransitionForm(component) {
  document.getElementById("tf-title").textContent = "📌 " + (component.label || "未命名组件");
  document.getElementById("tf-info").textContent = "位置: [" + component.bounds.left + "," + component.bounds.top + " - " + component.bounds.right + "," + component.bounds.bottom + "]";
  document.getElementById("tf-confirm").disabled = false;
  var select = document.getElementById("tf-target");
  select.disabled = false;
  select.innerHTML = "";
  flow.pages.forEach(function(p) {
    if (p.id !== currentPageId) {
      var o = document.createElement("option"); o.value = p.id; o.textContent = p.name; select.appendChild(o);
    }
  });
  var c = document.createElement("option"); c.value = "__custom__"; c.textContent = "手动输入..."; select.appendChild(c);
  document.getElementById("tf-method").disabled = false;
  document.getElementById("tf-method").value = component.type;
}

function clearTransitionForm() {
  selectedComponent = null;
  document.getElementById("tf-title").textContent = "选择组件";
  document.getElementById("tf-info").textContent = "点击截图中的组件来标注跳转";
  document.getElementById("tf-confirm").disabled = true;
  document.getElementById("tf-target").disabled = true;
  document.getElementById("tf-method").disabled = true;
  var img = document.getElementById("screenshot-img");
  if (img && img.style.width) renderOverlay();
}

async function confirmTransition() {
  if (!selectedComponent) return;
  var target = document.getElementById("tf-target").value;
  var targetId = target;
  if (target === "__custom__") {
    targetId = prompt("输入目标页面ID:"); if (!targetId) return;
    await api("POST", "/api/flow/page", { id: targetId, name: targetId });
    await loadFlow();
  }
  await api("POST", "/api/flow/transition", {
    page_id: currentPageId, target_id: targetId,
    method: document.getElementById("tf-method").value,
    label: selectedComponent.label,
    bounds: [selectedComponent.bounds.left, selectedComponent.bounds.top, selectedComponent.bounds.right, selectedComponent.bounds.bottom],
  });
  clearTransitionForm();
  await loadFlow();
  // 仅刷新跳转列表
  if (currentPageId) {
    var page = flow.pages.find(function(p) { return p.id === currentPageId; });
    renderTransitions(page ? page.transitions || [] : []);
    renderPageList();
  }
}

function renderTransitions(transitions) {
  const list = document.getElementById("transitions-list");
  list.innerHTML = "";
  if (!transitions || transitions.length === 0) { list.innerHTML = '<span style="color:#999;font-size:13px;">暂无跳转记录</span>'; return; }
  transitions.forEach((t, i) => {
    const d = document.createElement("div"); d.className = "transition-item";
    d.innerHTML = `<span class="method-tag ${t.method}">${t.method.toUpperCase()}</span><span>"${t.label}" → </span><span class="to-page">${t.target}</span><button class="del-btn" onclick="deleteTransition(${i})">×</button>`;
    list.appendChild(d);
  });
}

async function deleteTransition(index) {
  const page = flow.pages.find(function(p) { return p.id === currentPageId; }); if (!page) return;
  page.transitions.splice(index, 1);
  await api("PUT", "/api/flow", flow);
  await loadFlow();
  // 仅刷新跳转列表
  if (currentPageId) {
    var p = flow.pages.find(function(x) { return x.id === currentPageId; });
    renderTransitions(p ? p.transitions || [] : []);
    renderPageList();
  }
}

async function explorePage() {
  if (!currentPageId || !projectPath) return;
  document.getElementById("btn-explore").disabled = true;
  document.getElementById("btn-explore").textContent = "⏳ 探索中...";
  try {
    await api("POST", "/api/explore", { page_id: currentPageId });
  } catch (e) {
    alert("探索失败: " + e.message);
    document.getElementById("btn-explore").disabled = false;
    document.getElementById("btn-explore").textContent = "🚀 探索";
    return;
  }
  document.getElementById("btn-explore").disabled = false;
  document.getElementById("btn-explore").textContent = "🚀 探索";
  await loadResult(currentPageId);
  renderPageList();
}

async function addPage() {
  const id = prompt("输入页面ID（英文，如 home）："); if (!id) return;
  const name = prompt("输入页面名称（中文，如 主页）：");
  try { await api("POST", "/api/flow/page", { id, name: name || id }); await loadFlow(); renderPageList(); }
  catch (e) { alert(e.message); }
}

async function savePageInfo() {
  if (!currentPageId || !projectPath) return;
  var newId = document.getElementById("edit-page-id").value.trim();
  var newName = document.getElementById("edit-page-name").value.trim();
  if (!newId) { alert("页面ID不能为空"); return; }
  await api("POST", "/api/flow/page/update", { id: currentPageId, new_id: newId, name: newName });
  await loadFlow();
  currentPageId = newId;
  document.getElementById("edit-page-id").value = newId;
  document.getElementById("detail-title").textContent = "📄 " + newName;
  renderPageList();
}