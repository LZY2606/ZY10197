let stateData = null;
let latest = null;
let currentBranch = 0;

const $ = (id) => document.getElementById(id);

async function api(url, opts) {
  const res = await fetch(url, opts);
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt);
  }
  return res.json();
}

async function loadState() {
  stateData = await api('/api/state');
  renderDates();
  renderHiatuses();
  loadRuns();
}

function renderDates() {
  const box = $('dates');
  box.innerHTML = '';
  for (const d of stateData.dates) {
    const div = document.createElement('div');
    div.className = 'date-item' + (d.excluded ? ' excluded' : '');
    div.innerHTML =
      `<div><span class="id">${d.id}</span> · 深度 ${d.depthMm} mm · 年龄 ${d.ageKa} ka · σ ${d.ageSigmaKa}
       ${d.corrGroup ? `<span class="grp">相关组 ${d.corrGroup}（共享σ ${d.sharedSigmaKa}）</span>` : '独立'}</div>
       <div class="excluded"><label><input type="checkbox" ${d.excluded ? 'checked' : ''}
         onchange="toggleExclude('${d.id}', this.checked)"> 排除此年代锦标</label></div>`;
    box.appendChild(div);
  }
}

function dAge(d){ return d.ageKa; }

function renderHiatuses() {
  const box = $('hiatuses');
  box.innerHTML = '';
  for (const h of stateData.hiatuses) {
    const div = document.createElement('div');
    div.className = 'hiatus-item';
    div.innerHTML =
      `<div><b>${h.id}</b>：${h.topDepthMm} – ${h.bottomDepthMm} mm</div>
       <div class="row" style="margin:6px 0 0">
         <label><input type="checkbox" ${h.topOpen ? 'checked' : ''} id="topopen-${h.id}"> 上侧开放</label>
         <label><input type="checkbox" ${h.bottomOpen ? 'checked' : ''} id="botopen-${h.id}"> 下侧开放</label>
       </div>
       <div class="row" style="margin:6px 0 0">
         <label>顶深 <input type="number" value="${h.topDepthMm}" step="1" style="width:70px" id="top-${h.id}"></label>
         <label>底深 <input type="number" value="${h.bottomDepthMm}" step="1" style="width:70px" id="bot-${h.id}"></label>
         <button onclick="saveHiatusEndpoints('${h.id}')">调整端点</button>
       </div>`;
    box.appendChild(div);
  }
}

async function toggleExclude(id, excluded) {
  await api(`/api/dates/${id}/excluded?excluded=${excluded}`, {method: 'PUT'});
  await loadState();
}

async function saveHiatusEndpoints(id) {
  const top = parseFloat($('top-' + id).value);
  const bot = parseFloat($('bot-' + id).value);
  const topOpen = $('topopen-' + id).checked;
  const bottomOpen = $('botopen-' + id).checked;
  try {
    await api(`/api/hiatuses/${id}`, {
      method: 'PUT',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({topDepthMm: top, bottomDepthMm: bot, topOpen, bottomOpen})
    });
    await loadState();
  } catch (e) { alert('调整失败：' + prettyError(e)); }
}

async function resetFixtures() {
  await api('/api/fixtures/reset', {method: 'POST'});
  latest = null;
  await loadState();
  alert('已恢复固定 fixture');
}

async function clearData() {
  if (!confirm('确认清空全部数据（含运行记录）？之后可用“重置 fixture”重新导入复核。')) return;
  await api('/api/data/clear', {method: 'POST'});
  latest = null;
  $('corridorChart').innerHTML = '';
  $('conflicts').innerHTML = '';
  await loadState();
}

async function runModel() {
  const body = {
    draws: parseInt($('draws').value, 10),
    seed: parseInt($('seed').value, 10),
    gridStepKa: parseFloat($('gridStep').value),
    corridorStepMm: parseFloat($('corridorStep').value),
    label: new Date().toISOString()
  };
  latest = await api('/api/run', {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
  });
  currentBranch = 0;
  renderAll();
  loadRuns();
}

async function conflictDemo() {
  const body = {atDateId: 'D2', ageKa: 9.5, ageSigmaKa: 0.22};
  const data = await api('/api/demo/conflict', {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
  });
  latest = data.result;
  currentBranch = 0;
  renderAll();
}

function showTab(name, btn) {
  for (const t of ['corridor','rate','gap','proxy','runs']) {
    $('tab-' + t).style.display = t === name ? '' : 'none';
  }
  document.querySelectorAll('.tabs button').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
}

function prettyError(e) {
  try { const j = JSON.parse(e.message); return j.error || e.message; } catch (_) { return e.message; }
}

function branches() { return latest ? latest.result.branches : []; }

function renderAll() {
  renderConflicts();
  renderBranchTabs();
  renderCorridor();
  renderRates();
  renderGaps();
  renderProxy();
  renderRunMeta();
}

function renderConflicts() {
  const box = $('conflicts');
  box.innerHTML = '';
  const conflicts = branches().flatMap(b => b.conflicts || []);
  const seen = new Set();
  for (const c of conflicts) {
    const key = c.depthMm + '|' + c.incompatiblePairs.map(p => p.dateIdA + p.dateIdB).join();
    if (seen.has(key)) continue;
    seen.add(key);
    const div = document.createElement('div');
    div.className = 'conflict';
    let html = `<b>⚠ 同深度（${c.depthMm} mm）年龄区间不相容 —— 返回冲突证据，不统一放大误差</b>`;
    html += '<table><tr><th>锦标对</th><th>年龄A</th><th>年龄B</th><th>差值(ka)</th><th>σ差(ka)</th><th>z</th></tr>';
    for (const p of c.incompatiblePairs) {
      html += `<tr class="${p.incompatible ? 'z' : ''}"><td>${p.dateIdA} / ${p.dateIdB}</td>
        <td>${p.ageA}</td><td>${p.ageB}</td><td>${signed(p.diffKa)}</td>
        <td>${p.sigmaDiffKa.toFixed(3)}</td><td>${p.zScore.toFixed(2)}${p.incompatible ? ' 不相容' : ' 相容'}</td></tr>`;
    }
    html += '</table>';
    div.innerHTML = html;
    box.appendChild(div);
  }
}

function renderBranchTabs() {
  const box = $('branchTabs');
  box.innerHTML = '';
  const bs = branches();
  bs.forEach((b, i) => {
    const btn = document.createElement('button');
    btn.textContent = b.label + ' [' + b.dateIds.join(',') + ']';
    btn.style.marginRight = '6px';
    if (i === currentBranch) btn.className = 'primary';
    btn.onclick = () => { currentBranch = i; renderAll(); };
    box.appendChild(btn);
  });
}

function signed(x){ return (x >= 0 ? '+' : '') + x.toFixed(2); }

// ----------------------------- SVG 通用工具
function svgEl(tag, attrs) {
  const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const k in attrs) el.setAttribute(k, attrs[k]);
  return el;
}
function makeSvg(width, height) {
  const svg = svgEl('svg', {viewBox: `0 0 ${width} ${height}`, role: 'img'});
  return svg;
}
function extent(values) {
  const v = values.filter(x => x !== null && x !== undefined && !Number.isNaN(x));
  return [Math.min(...v), Math.max(...v)];
}
function scale(v, lo, hi, a, b) { return a + (v - lo) / (hi - lo) * (b - a); }
function fmt(x, n=2){ return Number.isNaN(x) || x === null ? '缺失' : x.toFixed(n); }

// ----------------------------- 年龄走廊
function renderCorridor() {
  const box = $('corridorChart');
  box.innerHTML = '';
  const b = branches()[currentBranch];
  if (!b) return;
  const W = 760, H = 520, M = {l:56, r:16, t:14, b:40};
  const xsAll = b.corridor.flatMap(p => [p.q025, p.q975]).filter(x => !Number.isNaN(x));
  const ysAll = b.corridor.map(p => p.depthMm);
  const [xmin, xmax] = extent(xsAll), [ymin, ymax] = extent(ysAll);
  const X = v => scale(v, xmin, xmax, M.l, W - M.r);
  const Y = v => scale(v, ymin, ymax, M.t, H - M.b);
  const svg = makeSvg(W, H);

  // 间断阴影带（依据 corridor 段切换处与 gap 信息）
  for (const g of b.gaps) {
    if (g.topDepthMm >= ymin && g.bottomDepthMm <= ymax) {
      svg.appendChild(svgEl('rect', {x: M.l, y: Y(g.bottomDepthMm), width: W-M.l-M.r,
        height: Y(g.topDepthMm)-Y(g.bottomDepthMm), fill: '#3a2b20', opacity: 0.7}));
      const t = svgEl('text', {x: M.l + 6, y: (Y(g.topDepthMm)+Y(g.bottomDepthMm))/2 + 4,
        fill: '#e2b04f', 'font-size': 11});
      t.textContent = '间断（不插值）';
      svg.appendChild(t);
    }
  }

  // 95% 带
  const bandPts = [];
  for (const p of b.corridor) if (!p.unsupported) bandPts.push([X(p.q975), Y(p.depthMm)]);
  for (let i = b.corridor.length - 1; i >= 0; i--) {
    const p = b.corridor[i];
    if (!p.unsupported) bandPts.push([X(p.q025), Y(p.depthMm)]);
  }
  if (bandPts.length > 2) {
    svg.appendChild(svgEl('polygon', {points: bandPts.map(p => p.join(',')).join(' '),
      fill: '#5fd08a', opacity: 0.25}));
  }
  // 中线（缺失处断开）
  let path = '';
  let pen = false;
  for (const p of b.corridor) {
    if (p.unsupported || Number.isNaN(p.q500)) { pen = false; continue; }
    path += (pen ? 'L' : 'M') + X(p.q500) + ',' + Y(p.depthMm);
    pen = true;
  }
  svg.appendChild(svgEl('path', {d: path, stroke: '#5fd08a', 'stroke-width': 2, fill: 'none'}));

  // 缺失/无支持节点
  for (const p of b.corridor) {
    if (p.unsupported) {
      svg.appendChild(svgEl('circle', {cx: M.l + 4, cy: Y(p.depthMm), r: 3, fill: '#93a698'}));
    }
  }
  // 坐标轴
  addAxes(svg, W, H, M, xmin, xmax, ymax, ymin, '年龄 (ka)', '深度 (mm)');
  box.appendChild(svg);
}

function addAxes(svg, W, H, M, xmin, xmax, dmin, dmax, xlabel, ylabel) {
  svg.appendChild(svgEl('line', {x1:M.l,y1:M.t,x2:M.l,y2:H-M.b,stroke:'#4b5c51'}));
  svg.appendChild(svgEl('line', {x1:M.l,y1:H-M.b,x2:W-M.r,y2:H-M.b,stroke:'#4b5c51'}));
  const xticks = 5, yticks = 6;
  for (let i=0;i<=xticks;i++){
    const v = xmin + (xmax-xmin)*i/xticks, x = scale(v,xmin,xmax,M.l,W-M.r);
    svg.appendChild(svgEl('line',{x1:x,y1:H-M.b,x2:x,y2:H-M.b+4,stroke:'#4b5c51'}));
    const t = svgEl('text',{x,y:H-M.b+16,fill:'#93a698','font-size':10,'text-anchor':'middle'});
    t.textContent = v.toFixed(1); svg.appendChild(t);
  }
  for (let i=0;i<=yticks;i++){
    const v = dmin + (dmax-dmin)*i/yticks;
    const y = scale(v,dmin,dmax,H-M.b,M.t);
    svg.appendChild(svgEl('line',{x1:M.l-4,y1:y,x2:M.l,y2:y,stroke:'#4b5c51'}));
    const t = svgEl('text',{x:M.l-7,y:y+3,fill:'#93a698','font-size':10,'text-anchor':'end'});
    t.textContent = v.toFixed(0); svg.appendChild(t);
  }
  const tx = svgEl('text',{x:(M.l+W-M.r)/2,y:H-6,fill:'#cfe0d4','font-size':12,'text-anchor':'middle'});
  tx.textContent=xlabel; svg.appendChild(tx);
  const ty = svgEl('text',{x:14,y:(M.t+H-M.b)/2,fill:'#cfe0d4','font-size':12,
    'text-anchor':'middle',transform:`rotate(-90 14 ${(M.t+H-M.b)/2})`});
  ty.textContent=ylabel; svg.appendChild(ty);
}

// ----------------------------- 局部生长率
function renderRates() {
  const box = $('rateChart');
  box.innerHTML = '';
  const b = branches()[currentBranch];
  if (!b || b.growthRates.length === 0) {
    box.innerHTML = '<p class="small">该分支无可用生长率（每段至少需要两枚锦标）。</p>';
    return;
  }
  const W=760,H=360,M={l:60,r:16,t:14,b:40};
  const xs=b.growthRates.map(p=>p.depthMm);
  const ys=b.growthRates.flatMap(p=>[p.q025,p.q975]);
  const [xmin,xmax]=extent(xs), [ymin,ymax]=extent(ys);
  const X=v=>scale(v,xmin,xmax,M.l,W-M.r), Y=v=>scale(v,ymin,ymax,H-M.b,M.t);
  const svg=makeSvg(W,H);
  for (const p of b.growthRates){
    svg.appendChild(svgEl('line',{x1:X(p.depthMm),y1:Y(p.q025),x2:X(p.depthMm),y2:Y(p.q975),
      stroke:'#5fd08a','stroke-width':3,opacity:0.5}));
    svg.appendChild(svgEl('circle',{cx:X(p.depthMm),cy:Y(p.q500),r:3.5,fill:'#5fd08a'}));
    const seg=svgEl('text',{x:X(p.depthMm),y:M.t+2,fill:'#93a698','font-size':10,'text-anchor':'middle'});
    seg.textContent=p.segmentId; svg.appendChild(seg);
  }
  addAxes(svg,W,H,M,xmin,xmax,ymin,ymax,'深度 (mm)','生长率 (mm/yr)');
  box.appendChild(svg);
}

// ----------------------------- 间断时长
function renderGaps() {
  const box = $('gapBoxes');
  box.innerHTML = '';
  const b = branches()[currentBranch];
  if (!b) return;
  for (const g of b.gaps) {
    const div=document.createElement('div');
    div.className='gapbox';
    div.innerHTML = `<b>${g.hiatusId}</b>：${g.topDepthMm}–${g.bottomDepthMm} mm
      （上侧${g.topOpen?'开放':'闭合'} / 下侧${g.bottomOpen?'开放':'闭合'}）<br>
      投影间断时长：${g.projectedQ500==null?'<span class="missing">缺失（开放侧不投影，未强行连线）</span>'
        : `${fmt(g.projectedQ500)} ka（90% 区间 ${fmt(g.projectedQ025)} – ${fmt(g.projectedQ975)}）`}<br>
      最近邻锦标最小间断时长：${fmt(g.nearestNeighborQ500)} ka
      （90% 区间 ${fmt(g.nearestNeighborQ025)} – ${fmt(g.nearestNeighborQ975)}）<br>
      <span class="small">覆盖率 ${(g.coverage*100).toFixed(0)}% · ${g.interpretation}</span>`;
    box.appendChild(div);
  }
}

// ----------------------------- 代理时间网格
function renderProxy() {
  const box=$('proxyChart'); box.innerHTML='';
  const table=$('proxyTable'); table.innerHTML='';
  const b=branches()[currentBranch];
  if (!b || b.proxyGrid.length===0){ box.innerHTML='<p class="small">无代理网格数据。</p>'; return; }
  const W=760,H=360,M={l:56,r:16,t:14,b:40};
  const xs=b.proxyGrid.map(p=>p.ageKa);
  const ys=b.proxyGrid.flatMap(p=>p.missing?[]:[p.q025,p.q975]);
  const [xmin,xmax]=extent(xs);
  const [ymin,ymax]= ys.length? extent(ys):[0,1];
  const X=v=>scale(v,xmin,xmax,M.l,W-M.r), Y=v=>scale(v,ymin,ymax,H-M.b,M.t);
  const svg=makeSvg(W,H);
  // 覆盖率底色
  for (const p of b.proxyGrid){
    const c = p.coverage>=0.995 ? '#1d3328' : p.coverage>0 ? '#3a3622' : '#2a201f';
    const w=(W-M.l-M.r)/Math.max(b.proxyGrid.length-1,1);
    svg.appendChild(svgEl('rect',{x:X(p.ageKa)-w/2,y:M.t,width:Math.max(w,1),height:H-M.t-M.b,fill:c}));
  }
  // 中线与区间（缺失跳过 -> 间断在时间轴上自然断裂）
  let medPath='', qPathTop='', qPathBot='', pen=false;
  for (const p of b.proxyGrid){
    if (p.missing){ pen=false; continue; }
    medPath += (pen?'L':'M')+X(p.ageKa)+','+Y(p.q500);
    qPathTop += (pen?'L':'M')+X(p.ageKa)+','+Y(p.q975);
    qPathBot += (pen?'L':'M')+X(p.ageKa)+','+Y(p.q025);
    pen=true;
  }
  svg.appendChild(svgEl('path',{d:qPathTop,stroke:'#5fd08a',opacity:0.35,'stroke-width':1,fill:'none'}));
  svg.appendChild(svgEl('path',{d:qPathBot,stroke:'#5fd08a',opacity:0.35,'stroke-width':1,fill:'none'}));
  svg.appendChild(svgEl('path',{d:medPath,stroke:'#5fd08a','stroke-width':2,fill:'none'}));
  for (const p of b.proxyGrid){
    if (p.missing){
      svg.appendChild(svgEl('circle',{cx:X(p.ageKa),cy:H-M.b-6,r:2.5,fill:'#93a698'}));
    }
  }
  addAxes(svg,W,H,M,xmin,xmax,ymin,ymax,'年龄 (ka)','代理值');
  box.appendChild(svg);

  let html='<table><tr><th>时间格点(ka)</th><th>均值</th><th>中位</th><th>2.5%</th><th>97.5%</th><th>覆盖率</th><th>状态</th></tr>';
  for (const p of b.proxyGrid){
    html += `<tr class="${p.missing?'missing':''}"><td>${p.ageKa.toFixed(2)}</td>
      <td>${p.missing?'缺失':p.mean.toFixed(3)}</td>
      <td>${p.missing?'缺失':p.q500.toFixed(3)}</td>
      <td>${p.missing?'—':p.q025.toFixed(3)}</td>
      <td>${p.missing?'—':p.q975.toFixed(3)}</td>
      <td>${(p.coverage*100).toFixed(0)}%</td>
      <td>${p.missing?'无年代支持，保留缺失':'ok'}</td></tr>`;
  }
  html+='</table>';
  table.innerHTML=html;
}

function renderRunMeta(){
  const box=$('runDetail');
  if (!latest) { box.innerHTML='<p class="small">尚未运行。</p>'; return; }
  const meta = latest.result.meta || {};
  box.innerHTML = `<p>运行ID：${latest.id} · 指纹：<code>${latest.fingerprint}</code></p>
    <p class="small">活动锦标 ${meta.activeDateCount} · 分支 ${meta.branchCount} · 间断 ${meta.hiatusCount}
    · 生长段 ${meta.segmentCount} · 冲突 ${meta.conflictCount}</p>`;
}

// ----------------------------- 运行记录 / 导入导出
async function loadRuns(){
  const runs = await api('/api/runs');
  const box=$('runs'); box.innerHTML='';
  if (runs.length===0){ box.textContent='暂无记录'; return; }
  for (const r of runs.slice().reverse()){
    const a=document.createElement('a');
    a.href='#'; a.style.marginRight='10px';
    a.textContent=`#${r.id} ${r.createdAt}`;
    a.onclick=(e)=>{e.preventDefault(); loadRun(r.id);};
    box.appendChild(a);
  }
}

async function loadRun(id){
  const bundle = await api('/api/runs/'+id);
  latest = {id: bundle.id, fingerprint: bundle.fingerprint, result: bundle.result};
  currentBranch=0; renderAll();
  showTab('runs', document.querySelector('.tabs button[data-tab="runs"]'));
}

function exportLatest(){
  if (!latest){ alert('请先运行模型'); return; }
  window.location = '/api/runs/'+latest.id+'/export';
}

async function importRun(replaceData){
  const f=$('importFile').files[0];
  if (!f){ alert('请选择导出的 JSON 文件'); return; }
  const text = await f.text();
  try {
    const res = await api(`/api/runs/import?replaceData=${replaceData}`, {
      method:'POST', headers:{'Content-Type':'application/json'}, body:text});
    alert(`复核通过（指纹一致），已导入为运行 #${res.importedRunId}`);
    await loadState();
    await loadRun(res.importedRunId);
  } catch(e){ alert('导入被拒绝：' + prettyError(e)); }
}

loadState();
