// ─── 状态 ───────────────────────────────────────────
let currentPageId = null;
let ocrData = [];
let dumpData = null;
let selectedComponent = null;
let componentList = [];
let flow = { pages: [] };
let projectRoot = null;

// ─── 初始化 ─────────────────────────────────────────
document.addEventListener("DOMContentLoaded", async () => {
  await checkProject();
});

// ─── API 调用 ───────────────────────────────────────
async function api(method, path, body) {
  const opts = { method, headers: { "Content-Type": "application/json" } };
  if (body) opts.body = JSON.stringify(body);
  const r = await fetch(path, opts);
  if (!r.ok) {
    const err = await r.json().catch(() => ({ error: r.statusText }));
    throw new Error(err.error || r.statusText);
  }
  return r.json();
}

// ─── 项目选择 ───────────────────────────────────────
async function checkProject() {
  try {
    const config = await api("GET", "/api/projects/current");
    if (config.project_root) {
      projectRoot = config.project_root;
      document.getElementById("project-name").textContent = config.project_name;
      await loadFlow();
      renderPageList();
      return;
    }
  } catch {}
  showProjectSelector();
}

async function showProjectSelector() {
  const overlay = document.getElementById("project-selector");
  overlay.style.display = "flex";
  const list = document.getElementById("project-list");
  list.innerHTML = "<p style='color:#999;font-size:13px;'>扫描中...</p>";

  try {
    const data = await api("GET", "/api/projects");
    list.innerHTML = "";
    if (!data.projects || data.projects.length === 0) {
      list.innerHTML = "<p style='color:#999;font-size:13px;'>未找到项目，请手动输入路径</p>";
      return;
    }
    data.projects.forEach(p => {
      const div = document.createElement("div");
      div.className = "project-item";
      const isCurrent = p.path === data.current;
      div.innerHTML = `
        <div>
          <div class="project-item-name">${p.name} ${isCurrent ? '<span class="check">✓</span>' : ''}</div>
          <div class="project-item-path">${p.path}</div>
        </div>
      `;
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
  await api("POST", "/api/projects/select", { path });
  projectRoot = path;
  document.getElementById("project-name").textContent = path.split("/").pop();
  closeProjectSelector();
  await loadFlow();
  renderPageList();
  document.getElementById("detail-title").textContent = "选择一个页面开始探索";
}

async function selectManualPath() {
  const path = document.getElementById("manual-path").value.trim();
  if (!path) return;
  await selectProject(path);
}

// ─── 加载 flow ──────────────────────────────────────
async function loadFlow() {
  try {
    flow = await api("GET", "/api/flow");
  } catch {
    flow = { pages: [] };
  }
}

async function refreshResult() {
  if (!currentPageId) return;
  await loadResult(currentPageId);
}

// ─── 渲染页面列表 ───────────────────────────────────
async function renderPageList() {
  let data = [];
  try {
    data = await api("GET", "/api/explore/pages");
  } catch {
    data = [];
  }
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
    div.innerHTML = `
      <span class="page-name">${p.name}</span>
      <span class="page-badge ${p.explored ? 'done' : 'pending'}">
        ${p.explored ? '✓' : '○'}
      </span>
    `;
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
    if (el.querySelector(".page-name").textContent ===
        (flow.pages.find(p => p.id === pageId)?.name || pageId)) {
      el.classList.add("active");
    }
  });

  document.getElementById("detail-title").textContent =
    `📄 ${flow.pages.find(p => p.id === pageId)?.name || pageId}`;

  await loadResult(pageId);
}

// ─── 加载探索结果 ───────────────────────────────────
async function loadResult(pageId) {
  let data;
  try {
    data = await api("GET", `/api/explore/${pageId}/result`);
  } catch {
    // 页面尚未探索
    document.getElementById("placeholder").style.display = "block";
    document.getElementById("placeholder").innerHTML = "<p>该页面尚未探索</p>";
    document.getElementById("image-wrapper").style.display = "none";
    document.getElementById("toolbar").style.display = "none";
    document.getElementById("transitions-panel").style.display = "none";
    return;
  }

  document.getElementById("placeholder").style.display = "none";
  document.getElementById("image-wrapper").style.display = "none";
  document.getElementById("toolbar").style.display = "none";
  document.getElementById("transitions-panel").style.display = "none";

  if (!data.files || !data.files["screenshot.png"]) {
    document.getElementById("placeholder").style.display = "block";
    document.getElementById("placeholder").innerHTML = "<p>该页面尚未探索</p>";
    return;
  }

  const img = document.getElementById("screenshot-img");
  img.src = `/api/explore/${pageId}/screenshot.png?t=${Date.now()}`;
  img.onload = () => {
    document.getElementById("image-wrapper").style.display = "inline-block";
    document.getElementById("toolbar").style.display = "flex";
    document.getElementById("transitions-panel").style.display = "block";
    setupCanvas();
    renderOverlay();
  };

  ocrData = data.ocr || [];
  dumpData = null;
  if (data.dump) {
    dumpData = parseDumpXml(data.dump);
  }

  renderTransitions(data.transitions || []);
}

// ─── 解析 Dump XML ─────────────────────────────────
function parseDumpXml(xml) {
  const nodes = [];
  const regex = /<node\s+([^>]+)>/g;
  let match;
  while ((match = regex.exec(xml)) !== null) {
    const attrs = {};
    const attrRegex = /(\w+)="([^"]*)"/g;
    let am;
    while ((am = attrRegex.exec(match[1])) !== null) {
      attrs[am[1]] = am[2];
    }
    if (attrs.bounds) {
      const bm = attrs.bounds.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
      if (bm) {
        attrs._left = parseInt(bm[1]);
        attrs._top = parseInt(bm[2]);
        attrs._right = parseInt(bm[3]);
        attrs._bottom = parseInt(bm[4]);
        nodes.push(attrs);
      }
    }
  }
  return nodes;
}

// ─── Canvas ─────────────────────────────────────────
function setupCanvas() {
  const img = document.getElementById("screenshot-img");
  const canvas = document.getElementById("overlay-canvas");
  canvas.width = img.naturalWidth;
  canvas.height = img.naturalHeight;
  canvas.style.width = "100%";
  canvas.style.height = "100%";
}

function toggleOverlay() {
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
      const b = item.bounds;
      if (!b) return;
      const x = b.left, y = b.top, w = b.right - b.left, h = b.bottom - b.top;
      if (w <= 0 || h <= 0) return;
      const id = `ocr_${item.label}_${x}_${y}`;
      componentList.push({ id, type: "ocr", label: item.label, bounds: { left: x, top: y, right: b.right, bottom: b.bottom } });

      ctx.strokeStyle = "#f44336";
      ctx.lineWidth = 2;
      ctx.strokeRect(x, y, w, h);

      ctx.fillStyle = "rgba(244, 67, 54, 0.7)";
      ctx.font = "12px sans-serif";
      const tw = ctx.measureText(item.label).width;
      ctx.fillRect(x, y - 16, Math.min(tw + 6, w), 16);
      ctx.fillStyle = "#fff";
      ctx.fillText(item.label, x + 3, y - 4);
    });
  }

  if (showDump && dumpData) {
    dumpData.forEach(node => {
      const x = node._left, y = node._top, w = node._right - node._left, h = node._bottom - node._top;
      if (w <= 0 || h <= 0) return;
      const label = node.text || node.desc || node.className || "";
      if (!label) return;
      const id = `dump_${label}_${x}_${y}`;
      componentList.push({ id, type: "dump", label, bounds: { left: x, top: y, right: node._right, bottom: node._bottom } });

      ctx.strokeStyle = "#9c27b0";
      ctx.lineWidth = 1.5;
      ctx.setLineDash([4, 2]);
      ctx.strokeRect(x, y, w, h);
      ctx.setLineDash([]);

      ctx.fillStyle = "rgba(156, 39, 176, 0.7)";
      ctx.font = "11px sans-serif";
      const tw = ctx.measureText(label).width;
      ctx.fillRect(x, y - 15, Math.min(tw + 4, w), 15);
      ctx.fillStyle = "#fff";
      ctx.fillText(label, x + 2, y - 3);
    });
  }
}

// ─── Canvas 点击 ────────────────────────────────────
document.getElementById("overlay-canvas").addEventListener("click", (e) => {
  if (componentList.length === 0) return;
  const canvas = e.target;
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const scaleY = canvas.height / rect.height;
  const mx = (e.clientX - rect.left) * scaleX;
  const my = (e.clientY - rect.top) * scaleY;

  let clicked = null;
  for (const c of componentList) {
    const b = c.bounds;
    if (mx >= b.left && mx <= b.right && my >= b.top && my <= b.bottom) {
      clicked = c;
      break;
    }
  }
  if (!clicked) return;

  selectedComponent = clicked;
  showTransitionModal(clicked);
});

// ─── 跳转弹窗 ───────────────────────────────────────
function showTransitionModal(component) {
  document.getElementById("modal-label").textContent = component.label;
  document.getElementById("modal-bounds").textContent =
    `[${component.bounds.left},${component.bounds.top} - ${component.bounds.right},${component.bounds.bottom}]`;

  const select = document.getElementById("modal-target");
  select.innerHTML = "";
  flow.pages.forEach(p => {
    if (p.id !== currentPageId) {
      const opt = document.createElement("option");
      opt.value = p.id;
      opt.textContent = p.name;
      select.appendChild(opt);
    }
  });
  const custom = document.createElement("option");
  custom.value = "__custom__";
  custom.textContent = "手动输入...";
  select.appendChild(custom);

  document.getElementById("modal-method").value = component.type;
  document.getElementById("modal-overlay").style.display = "flex";
}

function closeModal(e) {
  if (e && e.target !== e.currentTarget) return;
  document.getElementById("modal-overlay").style.display = "none";
  selectedComponent = null;
}

async function confirmTransition() {
  const target = document.getElementById("modal-target").value;
  let targetId = target;
  if (target === "__custom__") {
    targetId = prompt("输入目标页面ID:");
    if (!targetId) return;
    await api("POST", "/api/flow/page", { id: targetId, name: targetId });
    await loadFlow();
  }

  await api("POST", "/api/flow/transition", {
    page_id: currentPageId,
    target_id: targetId,
    method: document.getElementById("modal-method").value,
    label: selectedComponent.label,
    bounds: [selectedComponent.bounds.left, selectedComponent.bounds.top,
             selectedComponent.bounds.right, selectedComponent.bounds.bottom],
  });

  closeModal();
  await loadResult(currentPageId);
  renderPageList();
}

// ─── 渲染跳转 ───────────────────────────────────────
function renderTransitions(transitions) {
  const list = document.getElementById("transitions-list");
  list.innerHTML = "";
  if (!transitions || transitions.length === 0) {
    list.innerHTML = '<span style="color:#999;font-size:13px;">暂无跳转记录</span>';
    return;
  }
  transitions.forEach((t, i) => {
    const div = document.createElement("div");
    div.className = "transition-item";
    div.innerHTML = `
      <span class="method-tag ${t.method}">${t.method.toUpperCase()}</span>
      <span>"${t.label}" → </span>
      <span class="to-page">${t.target}</span>
      <button class="del-btn" onclick="deleteTransition(${i})">×</button>
    `;
    list.appendChild(div);
  });
}

async function deleteTransition(index) {
  const page = flow.pages.find(p => p.id === currentPageId);
  if (!page) return;
  page.transitions.splice(index, 1);
  await api("PUT", "/api/flow", flow);
  await loadResult(currentPageId);
  renderPageList();
}

// ─── 探索操作 ───────────────────────────────────────
async function explorePage() {
  if (!currentPageId) return;
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

  let attempts = 0;
  const poll = setInterval(async () => {
    attempts++;
    try {
      const status = await api("POST", "/api/explore/poll", { page_id: currentPageId });
      if (status.status === "ok" || status.status === "error" || status.status === "shizuku_failed" || attempts > 30) {
        clearInterval(poll);
        document.getElementById("btn-explore").disabled = false;
        document.getElementById("btn-explore").textContent = "🚀 探索";
        if (status.status === "shizuku_failed") {
          alert("Shizuku 未运行，请检查手机");
        }
        await loadResult(currentPageId);
        renderPageList();
      }
    } catch {
      clearInterval(poll);
      document.getElementById("btn-explore").disabled = false;
      document.getElementById("btn-explore").textContent = "🚀 探索";
    }
  }, 2000);
}

// ─── 添加页面 ───────────────────────────────────────
async function addPage() {
  const id = prompt("输入页面ID（英文，如 home）：");
  if (!id) return;
  const name = prompt("输入页面名称（中文，如 主页）：");
  try {
    await api("POST", "/api/flow/page", { id, name: name || id });
    await loadFlow();
    renderPageList();
  } catch (e) {
    alert(e.message);
  }
}

// ─── 导出 ───────────────────────────────────────────
async function exportFlow() {
  await loadFlow();
  const blob = new Blob([JSON.stringify(flow, null, 2)], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "flow.json";
  a.click();
}