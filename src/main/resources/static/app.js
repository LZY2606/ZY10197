let scenario = null;
let result = null;

const $ = (id) => document.getElementById(id);
const fmt = (x, d = 1) => Number.isFinite(x) ? x.toFixed(d) : '缺失';

async function init() {
  $('runBtn').onclick = () => compute(true);
  $('resetBtn').onclick = reset;
  $('exportBtn').onclick = exportSnapshot;
  $('clearBtn').onclick = clearDatabase;
  $('importConfirm').onclick = importSnapshot;
  $('clearBtn').insertAdjacentHTML('afterend', '<button id="importBtn">导入快照</button>');
  $('importBtn').onclick = () => $('importDialog').showModal();
  await load();
  await compute(false);
}

async function load() {
  scenario = await fetch('/api/scenario').then(r => r.json());
  fillForm();
  await refreshRuns();
}

function fillForm() {
  for (const key of ['proxyName', 'gridStart', 'gridStop', 'gridStep', 'branchCount', 'sharedCorrectionSigma']) {
    $(key).value = scenario[key];
  }
  $('youngDepth').value = scenario.hiatus.youngDepth;
  $('oldDepth').value = scenario.hiatus.oldDepth;
  $('youngSideOpen').checked = scenario.hiatus.youngSideOpen;
  $('oldSideOpen').checked = scenario.hiatus.oldSideOpen;
  const tbody = document.querySelector('#dateTable tbody');
  tbody.innerHTML = '';
  scenario.dates.forEach((d, i) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><input data-i="${i}" data-k="id" value="${d.id}"></td>
      <td><input data-i="${i}" data-k="depth" type="number" value="${d.depth}"></td>
      <td><input data-i="${i}" data-k="ageBP" type="number" value="${d.ageBP}"></td>
      <td><input data-i="${i}" data-k="ageSigma" type="number" value="${d.ageSigma}"></td>
      <td><input data-i="${i}" data-k="correctionGroup" value="${d.correctionGroup ?? ''}"></td>
      <td><select data-i="${i}" data-k="kind"><option>u-th</option><option>fixed</option></select></td>
      <td><input data-i="${i}" data-k="active" type="checkbox" ${d.active ? 'checked' : ''}></td>`;
    tbody.appendChild(tr);
    tr.querySelector('select').value = d.kind;
  });
  tbody.querySelectorAll('input,select').forEach(el => el.onchange = readForm);
}

function readForm() {
  for (const key of ['proxyName', 'gridStart', 'gridStop', 'gridStep', 'branchCount', 'sharedCorrectionSigma']) {
    scenario[key] = key === 'proxyName' ? $(key).value : Number($(key).value);
  }
  scenario.hiatus.youngDepth = Number($('youngDepth').value);
  scenario.hiatus.oldDepth = Number($('oldDepth').value);
  scenario.hiatus.youngSideOpen = $('youngSideOpen').checked;
  scenario.hiatus.oldSideOpen = $('oldSideOpen').checked;
  document.querySelectorAll('#dateTable [data-i]').forEach(el => {
    const i = Number(el.dataset.i), k = el.dataset.k;
    if (k === 'id' || k === 'correctionGroup') scenario.dates[i][k] = el.value || null;
    else if (k === 'active') scenario.dates[i][k] = el.checked;
    else if (k === 'kind') scenario.dates[i][k] = el.value;
    else scenario.dates[i][k] = Number(el.value);
  });
}

async function compute(persist) {
  readForm();
  $('runBtn').disabled = true;
  try {
    const r = await fetch('/api/runs', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({scenario, persistScenario: persist})
    });
    result = await r.json();
    render();
    await refreshRuns();
    if (!r.ok) alert(result.message || '计算失败');
  } finally {
    $('runBtn').disabled = false;
  }
}

async function reset() {
  scenario = await fetch('/api/scenario/reset', {method: 'POST'}).then(r => r.json());
  fillForm();
  result = null;
  render();
  await refreshRuns();
}

async function clearDatabase() {
  if (!confirm('确认清空 scenario 与全部运行记录？')) return;
  await fetch('/api/admin/clear', {method: 'POST'});
  await load();
  result = null;
  render();
}

async function exportSnapshot() {
  const blob = await fetch('/api/export').then(r => r.blob());
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = `rocklayer-snapshot-${new Date().toISOString().slice(0,19)}.json`;
  a.click(); URL.revokeObjectURL(url);
}

async function importSnapshot() {
  const file = $('importFile').files[0];
  if (!file) return;
  const text = await file.text();
  const r = await fetch('/api/import?resetBeforeImport=true', {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: text
  });
  const body = await r.json();
  if (!r.ok) alert(body.message || '导入失败');
  await load();
  await compute(false);
}

async function refreshRuns() {
  const runs = await fetch('/api/runs').then(r => r.json());
  const tbody = document.querySelector('#runsTable tbody');
  tbody.innerHTML = runs.map(r => `<tr><td>${r.created_at}</td><td>${r.id}</td><td>${r.status}</td>
    <td><a href="/api/runs/${r.id}" target="_blank">查看 / 另存 JSON</a></td></tr>`).join('');
}

function render() {
  if (!result) return;
  renderConflicts();
  drawAge();
  drawRates();
  drawProxy();
  renderHiatus();
}

function renderConflicts() {
  const conflicts = result.conflicts || [];
  $('conflictPanel').classList.toggle('hidden', conflicts.length === 0);
  $('conflicts').innerHTML = conflicts.map(c => `<div class="conflict-item">
    <strong>${c.type}</strong><br>${c.dateA} 与 ${c.dateB} 在深度 ${c.depth}：年龄 ${fmt(c.ageA)} / ${fmt(c.ageB)} yr BP，
    差异 ${fmt(c.difference)}，σ=${fmt(c.differenceSigma)}，z=${fmt(c.z, 2)}。<br>
    2σ 区间：${c.intervalA} vs ${c.intervalB}<br><small>${c.reason} errorInflationApplied=${c.errorInflationApplied}</small>
  </div>`).join('');
}

function svgEl(name, attrs = {}) {
  const el = document.createElementNS('http://www.w3.org/2000/svg', name);
  Object.entries(attrs).forEach(([k, v]) => el.setAttribute(k, v));
  return el;
}

function scale(v, min, max, a, b) { return a + (v - min) / (max - min) * (b - a); }

function drawAxes(svg, xLabel, yLabel, x0 = 58, y0 = 360, w = 620, h = 300) {
  svg.innerHTML = '';
  svg.append(svgEl('line', {x1:x0,y1:y0,x2:x0+w,y2:y0,stroke:'#334'}));
  svg.append(svgEl('line', {x1:x0,y1:y0-h,x2:x0,y2:y0,stroke:'#334'}));
  for (let i=0;i<=5;i++) {
    const x=x0+w*i/5, y=y0-h*i/5;
    svg.append(svgEl('line',{x1:x,y1:y0,x2:x,y2:y0+5,stroke:'#334'}));
    svg.append(svgEl('line',{x1:x0-5,y1:y,x2:x0,y2:y,stroke:'#334'}));
  }
  text(svg, xLabel, x0+w/2, 398, 13, '#444', 'middle');
  text(svg, yLabel, 18, y0-h/2, 13, '#444', 'middle', -90);
}
function text(svg, s, x, y, size=12, fill='#333', anchor='start', rotate=0, weight=500) {
  const t=svgEl('text',{x,y,'font-size':size,fill,'text-anchor':anchor,'font-weight':weight,
    transform:rotate?`rotate(${rotate} ${x} ${y})`:''});
  t.textContent=s; svg.append(t);
}

function drawAge() {
  const svg = $('ageSvg');
  if (!result.ageCorridor) return;
  const rows = result.ageCorridor;
  const dMin = Math.min(...rows.map(r=>r.depth)), dMax=Math.max(...rows.map(r=>r.depth));
  const aMin = Math.min(...rows.map(r=>r.q025)), aMax=Math.max(...rows.map(r=>r.q975));
  drawAxes(svg, '深度 (mm)', '年龄 (yr BP)');
  const X=d=>scale(d,dMin,dMax,58,678), Y=a=>scale(a,aMin,aMax,360,60);
  const colors=['#2e7d62','#9b6335'];
  [0,1].forEach(seg => {
    const rs=rows.filter(r=>r.segmentIndex===seg).sort((a,b)=>a.depth-b.depth);
    if (!rs.length) return;
    const poly=[...rs.map(r=>`${X(r.depth)},${Y(r.q975)}`), ...rs.reverse().map(r=>`${X(r.depth)},${Y(r.q025)}`)].join(' ');
    svg.append(svgEl('polygon',{points:poly,fill:colors[seg],opacity:.17}));
    rs.sort((a,b)=>a.depth-b.depth).forEach(r=>svg.append(svgEl('circle',{cx:X(r.depth),cy:Y(r.median),r:2.5,fill:colors[seg]})));
  });
  const h=result.hiatus;
  [h.youngDepth,h.oldDepth].forEach(d=>{
    if(d>=dMin&&d<=dMax) svg.append(svgEl('line',{x1:X(d),y1:60,x2:X(d),y2:360,stroke:'#b3372f','stroke-dasharray':'5 4'}));
  });
  text(svg,'绿色=年轻段；棕色=较老段；红虚线间断，不连线',58,42,12,'#666','start',0,650);
}

function drawRates() {
  const svg=$('rateSvg');
  if(!result.growthRates) return;
  const rows=result.growthRates;
  const xMin=Math.min(...rows.map(r=>r.depthStart)), xMax=Math.max(...rows.map(r=>r.depthEnd));
  const yMin=0, yMax=Math.max(...rows.map(r=>r.q975))*1.15;
  drawAxes(svg,'深度 (mm)','生长率 (mm/yr)');
  const X=d=>scale(d,xMin,xMax,58,678), Y=r=>scale(r,yMin,yMax,360,60);
  rows.forEach(r=>{
    const color=r.segmentIndex===0?'#2e7d62':'#9b6335';
    const x=(X(r.depthStart)+X(r.depthEnd))/2;
    svg.append(svgEl('line',{x1:x,y1:Y(r.q025),x2:x,y2:Y(r.q975),stroke:color,'stroke-width':5,opacity:.45}));
    svg.append(svgEl('circle',{cx:x,cy:Y(r.median),r:4,fill:color}));
  });
  text(svg,'间断内部不计算为极慢生长率',58,42,12,'#b3372f','start',0,650);
}

function drawProxy() {
  const svg=$('proxySvg');
  if(!result.proxyGrid) return;
  const rows=result.proxyGrid;
  const xMin=rows[0].age, xMax=rows[rows.length-1].age;
  const supported=rows.filter(r=>r.supported);
  const yMin=Math.min(...supported.map(r=>r.q025))-.2;
  const yMax=Math.max(...supported.map(r=>r.q975))+.2;
  drawAxes(svg,'规则年龄网格 (yr BP)', scenario.proxyName + (scenario.proxyUnit ? ` (${scenario.proxyUnit})` : ''));
  const X=a=>scale(a,xMin,xMax,58,678), Y=v=>scale(v,yMin,yMax,360,60);
  rows.forEach(r=>{
    const x=X(r.age);
    if(!r.supported) {
      svg.append(svgEl('rect',{x:x-3,y:60,width:6,height:300,fill:'#d9d9d9',opacity:.85}));
      return;
    }
    svg.append(svgEl('line',{x1:x,y1:Y(r.q025),x2:x,y2:Y(r.q975),stroke:r.segment==='segment-0'?'#2e7d62':'#9b6335','stroke-width':3,opacity:.45}));
    svg.append(svgEl('circle',{cx:x,cy:Y(r.median),r:3.5,fill:r.segment==='segment-0'?'#2e7d62':'#9b6335'}));
  });
  const low=Math.min(...rows.map(r=>r.coverageRate));
  text(svg,`最低格点可覆盖率 ${(low*100).toFixed(1)}%；缺口保留为缺失`,58,42,12,'#666','start',0,650);
}

function renderHiatus() {
  const h=result.hiatusDuration;
  if(!h) return;
  $('hiatusDuration').innerHTML = h.estimable
    ? `<div class="big">${fmt(h.median)} yr</div>
       <div>95%：${fmt(h.q025)} – ${fmt(h.q975)} yr，分支可覆盖率 ${(h.coverageRate*100).toFixed(1)}%</div>
       <small>${h.status}</small>`
    : `<div class="big">不可估</div><div>${h.status}</div><div>不跨间断线性插值，也不以极慢生长率填充。</div>`;
}

init();
