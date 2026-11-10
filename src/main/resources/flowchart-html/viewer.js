/*
 * 흐름도 HTML 뷰어 — 데이터(FLOW)는 분석기가 모델에서 만든 그래프(Mermaid 흐름도와 같은 중간 표현).
 * 배치: ELK layered (중첩 상자 포함, 소스 순서 유지). 그리기: SVG <text>(foreignObject 없음).
 */
(function () {
  'use strict';
  const SVGNS = 'http://www.w3.org/2000/svg';
  const FONT_SIZE = 12, LINE_H = 16, PAD_X = 10, PAD_Y = 7;
  const FONT = FONT_SIZE + 'px ui-monospace, SFMono-Regular, Menlo, Consolas, "D2Coding", "Malgun Gothic", monospace';
  const TITLE_FONT = '600 ' + FONT;

  document.documentElement.dataset.theme = FLOW.theme === 'dark' ? 'dark' : 'light';
  document.getElementById('ttl').textContent = FLOW.title;
  document.title = FLOW.title;

  // ---- 모델 색인 ----
  const nodes = new Map();   // id → node data
  const groups = new Map();  // id → group data
  const parent = new Map();  // id → 부모 group id (최상위는 null)
  (function index(items, p) {
    for (const it of items) {
      if (it.group) {
        groups.set(it.group, it); parent.set(it.group, p); index(it.children, it.group);
      } else {
        nodes.set(it.id, it); parent.set(it.id, p);
      }
    }
  })(FLOW.root, null);
  const leftAligned = new Set(FLOW.leftAligned);
  // 그린 순서(= 소스 순서) — 뒤에서 앞으로 가는 간선(반복의 ↻ 등)은 배치할 때만 뒤집어 흐름이 위→아래로 유지되게
  const order = new Map();
  (function number(items) { for (const it of items) { order.set(it.group || it.id, order.size); if (it.group) number(it.children); } })(FLOW.root);
  const collapsed = new Set();

  const measureCtx = document.createElement('canvas').getContext('2d');
  function textWidth(s, font) { measureCtx.font = font || FONT; return measureCtx.measureText(s).width; }
  function lineText(segs) { return segs.map(s => s[0]).join(''); }
  function nodeText(n) { return n.lines.map(lineText).join('\n'); }
  function countNodes(gid) {
    let k = 0;
    (function walk(items) { for (const it of items) { if (it.group) walk(it.children); else k++; } })(groups.get(gid).children);
    return k;
  }
  function isMethodGroup(gid) { return /^sg_M\d+$/.test(gid); }

  // 접힌 조상이 있으면 가장 바깥 접힌 상자, 아니면 자신
  function visible(id) {
    let v = id, p = parent.get(id);
    while (p) { if (collapsed.has(p)) v = p; p = parent.get(p); }
    return v;
  }

  // ---- 크기 ----
  function boxSize(lines, font) {
    let w = 0;
    for (const l of lines) w = Math.max(w, textWidth(typeof l === 'string' ? l : lineText(l), font));
    return { w: Math.ceil(w) + PAD_X * 2, h: lines.length * LINE_H + PAD_Y * 2 };
  }
  function nodeSize(n) {
    const s = boxSize(n.lines);
    switch (n.shape) {
      case 'DIAMOND': return { w: s.w * 1.25 + 24, h: s.h * 1.5 + 12 };
      case 'HEX': return { w: s.w + s.h, h: s.h + 4 };
      case 'STADIUM': return { w: s.w + s.h * 0.6, h: s.h };
      case 'SUBROUTINE': return { w: s.w + 16, h: s.h };
      case 'ASYM': return { w: s.w + 14, h: s.h };
      default: return s;
    }
  }
  function collapsedLines(g) {
    return [...g.label, '▸ 펼치기 · 노드 ' + countNodes(g.group) + '개'];
  }

  // ---- ELK 그래프 ----
  function buildElk() {
    const sizes = new Map();
    function items(list) {
      const out = [];
      for (const it of list) {
        if (it.group) {
          if (collapsed.has(it.group)) {
            const s = boxSize(collapsedLines(it), TITLE_FONT);
            out.push({ id: it.group, width: s.w, height: s.h });
          } else {
            const t = boxSize(['▾ ' + it.label[0], ...it.label.slice(1)], TITLE_FONT);
            out.push({
              id: it.group,
              layoutOptions: {
                'elk.padding': '[top=' + (t.h + 6) + ',left=14,bottom=14,right=14]',
                // 제목이 상자보다 길면 상자를 넓힌다
                'elk.nodeSize.constraints': 'MINIMUM_SIZE',
                'elk.nodeSize.minimum': '(' + (t.w + 30) + ',' + (t.h + 30) + ')',
              },
              children: items(it.children),
            });
            sizes.set(it.group, t);
          }
        } else {
          const s = nodeSize(it);
          out.push({ id: it.id, width: s.w, height: s.h });
        }
      }
      return out;
    }
    const children = items(FLOW.root);
    const edges = [], seen = new Set();
    FLOW.edges.forEach((e, i) => {
      const a = visible(e.from), b = visible(e.to);
      // 세부 호출은 선 대신 상자 색 (Mermaid 흐름도와 같은 규칙) — 단, 접힌 상자끼리는 호출 관계가 보이게 선을 긋는다
      if (e.style === 'call' && !FLOW.callEdges && a === e.from && b === e.to) return;
      if (a === b) return;
      const key = a + '>' + b + '>' + e.style + '>' + e.label.join('\n');
      if (seen.has(key)) return;
      seen.add(key);
      const labels = e.label.length ? [{ text: e.label.join('\n'), width: boxSize(e.label).w - 8, height: e.label.length * LINE_H }] : [];
      const rev = order.get(b) < order.get(a);
      edges.push({ id: 'e' + i, sources: [rev ? b : a], targets: [rev ? a : b], labels: labels, data: e, rev: rev });
    });
    return {
      graph: {
        id: 'root',
        layoutOptions: {
          'elk.algorithm': 'layered',
          'elk.direction': FLOW.direction === 'LR' ? 'RIGHT' : FLOW.direction === 'RL' ? 'LEFT' : FLOW.direction === 'BT' ? 'UP' : 'DOWN',
          'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
          'elk.json.edgeCoords': 'ROOT',
          'elk.json.shapeCoords': 'ROOT',
          'elk.edgeRouting': 'ORTHOGONAL',
          'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
          // 반복의 되돌아가는 선(↻)이 순환을 만든다 — 소스 순서(모델 순서)를 거스르는 간선을 뒤집어, 흐름이 위→아래로 유지되게
          'elk.layered.cycleBreaking.strategy': 'MODEL_ORDER',
          'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
          'elk.spacing.nodeNode': '28',
          'elk.layered.spacing.nodeNodeBetweenLayers': '36',
          'elk.spacing.edgeLabel': '4',
          'elk.edgeLabels.inline': 'false',
        },
        children: children,
        edges: edges,
      },
      titles: sizes,
    };
  }

  // ---- 그리기 ----
  const svg = document.getElementById('canvas');
  const vp = document.getElementById('vp');
  const defs = document.getElementById('defs');
  const css = getComputedStyle(document.documentElement);
  const cssVar = n => css.getPropertyValue(n).trim();
  function el(tag, attrs, parentEl) {
    const e = document.createElementNS(SVGNS, tag);
    for (const k in attrs) if (attrs[k] !== undefined && attrs[k] !== null) e.setAttribute(k, attrs[k]);
    if (parentEl) parentEl.appendChild(e);
    return e;
  }
  function marker(id, color) {
    const m = el('marker', { id: id, viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 8, markerHeight: 8, orient: 'auto-start-reverse' }, defs);
    el('path', { d: 'M0,0 L10,5 L0,10 z', fill: color }, m);
  }
  const loopColor = (FLOW.loopEdge && FLOW.loopEdge.stroke) || '#2563eb';
  marker('arrow', cssVar('--edge')); marker('arrowExc', cssVar('--exc')); marker('arrowLoop', loopColor);

  function writeLines(g, lines, x, y, align, fill) {
    const t = el('text', { x: x, y: y, 'font-size': FONT_SIZE, 'text-anchor': align === 'left' ? 'start' : 'middle', fill: fill }, g);
    t.style.font = FONT;
    lines.forEach((l, i) => {
      const segs = typeof l === 'string' ? [[l, '']] : l;
      const row = el('tspan', { x: x, dy: i === 0 ? FONT_SIZE : LINE_H }, t);
      for (const s of segs) {
        const sp = el('tspan', {}, row);
        sp.textContent = s[0].length ? s[0] : ' ';
        if (s[1] && FLOW.textColors[s[1]]) sp.setAttribute('fill', FLOW.textColors[s[1]]);
      }
    });
    return t;
  }

  function shapePath(n, x, y, w, h) {
    switch (n.shape) {
      case 'DIAMOND': return el('polygon', { points: [[x + w / 2, y], [x + w, y + h / 2], [x + w / 2, y + h], [x, y + h / 2]].join(' ') });
      case 'HEX': { const d = Math.min(h / 2, w / 4); return el('polygon', { points: [[x + d, y], [x + w - d, y], [x + w, y + h / 2], [x + w - d, y + h], [x + d, y + h], [x, y + h / 2]].join(' ') }); }
      case 'STADIUM': return el('rect', { x: x, y: y, width: w, height: h, rx: h / 2, ry: h / 2 });
      case 'ROUND': return el('rect', { x: x, y: y, width: w, height: h, rx: 10, ry: 10 });
      case 'ASYM': return el('polygon', { points: [[x, y], [x + w, y], [x + w, y + h], [x, y + h], [x + 12, y + h / 2]].join(' ') });
      default: return el('rect', { x: x, y: y, width: w, height: h, rx: 3, ry: 3 });
    }
  }

  function applyClass(shape, cls) {
    const c = FLOW.classes[cls] || {};
    shape.setAttribute('fill', c.fill || cssVar('--panel'));
    shape.setAttribute('stroke', c.stroke || cssVar('--edge'));
    shape.setAttribute('stroke-width', (c['stroke-width'] || '1px').replace('px', ''));
    if (c['stroke-dasharray']) shape.setAttribute('stroke-dasharray', c['stroke-dasharray']);
    return c.color || cssVar('--fg');
  }

  const drawn = new Map(); // 보이는 id → {x,y,w,h,g}
  let selected = null;

  function draw(layout, titles) {
    vp.textContent = '';
    drawn.clear();
    const gLayer = el('g', {}, vp), eLayer = el('g', {}, vp), nLayer = el('g', {}, vp);
    (function walk(list, depth) {
      for (const c of list) {
        const id = c.id;
        if (groups.has(id) && !collapsed.has(id)) {
          const g = groups.get(id);
          const kind = isMethodGroup(id) ? 'method' : /^sg_(FOR|FOREACH|WHILE|DO)/.test(id) ? 'loop' : /^sg_T\d/.test(id) ? 'try' : 'block';
          const box = el('rect', { x: c.x, y: c.y, width: c.width, height: c.height, rx: 6, ry: 6,
            fill: kind === 'method' ? cssVar('--g-method') : cssVar('--g-block'),
            stroke: kind === 'loop' ? cssVar('--g-loop-stroke') : kind === 'try' ? cssVar('--g-try-stroke') : kind === 'method' ? cssVar('--g-method-stroke') : cssVar('--g-block-stroke'),
            'stroke-width': kind === 'method' ? 1.5 : 1, 'stroke-dasharray': kind === 'try' || kind === 'loop' ? '6 4' : null }, gLayer);
          const tg = el('g', { class: 'gtitle' }, gLayer);
          tg.appendChild(document.createElementNS(SVGNS, 'title')).textContent = '클릭: 접기';
          const t = writeLines(tg, ['▾ ' + g.label[0], ...g.label.slice(1)], c.x + 10, c.y + 5, 'left', cssVar('--g-title'));
          t.style.font = TITLE_FONT;
          el('rect', { x: c.x, y: c.y, width: c.width, height: (titles.get(id) || { h: 20 }).h + 4, fill: 'transparent' }, tg);
          tg.addEventListener('click', ev => { ev.stopPropagation(); toggle(id); });
          drawn.set(id, { x: c.x, y: c.y, w: c.width, h: c.height, el: box });
          walk(c.children || [], depth + 1);
        } else if (groups.has(id)) { // 접힌 상자
          const g = groups.get(id);
          const ng = el('g', { class: 'node' }, nLayer);
          const shape = el('rect', { class: 'shape', x: c.x, y: c.y, width: c.width, height: c.height, rx: 8, ry: 8,
            fill: cssVar('--g-method'), stroke: cssVar('--g-method-stroke'), 'stroke-width': 1.5, 'stroke-dasharray': '4 3' }, ng);
          writeLines(ng, collapsedLines(g), c.x + PAD_X, c.y + PAD_Y, 'left', cssVar('--g-title')).style.font = TITLE_FONT;
          ng.addEventListener('click', ev => { ev.stopPropagation(); toggle(id); });
          drawn.set(id, { x: c.x, y: c.y, w: c.width, h: c.height, el: ng, shape: shape });
        } else {
          const n = nodes.get(id);
          const ng = el('g', { class: 'node' }, nLayer);
          const shape = shapePath(n, c.x, c.y, c.width, c.height);
          shape.setAttribute('class', 'shape');
          ng.appendChild(shape);
          const color = applyClass(shape, n.cls);
          const s = boxSize(n.lines);
          const left = leftAligned.has(n.cls) || n.cls === 'detail';
          const tx = left ? c.x + (c.width - s.w) / 2 + PAD_X : c.x + c.width / 2;
          writeLines(ng, n.lines, tx, c.y + (c.height - s.h) / 2 + PAD_Y, left ? 'left' : 'middle', color);
          ng.addEventListener('click', ev => { ev.stopPropagation(); select(id); });
          if (n.goto) {
            ng.addEventListener('dblclick', ev => { ev.stopPropagation(); go(n.goto); });
            ng.appendChild(document.createElementNS(SVGNS, 'title')).textContent = '더블클릭: 세부 메서드로 이동';
          }
          drawn.set(id, { x: c.x, y: c.y, w: c.width, h: c.height, el: ng, shape: shape });
        }
      }
    })(layout.children || [], 0);

    for (const e of layout.edges || []) {
      const d = e.data;
      const color = d.loop ? loopColor : d.style === 'exception' ? cssVar('--exc') : cssVar('--edge');
      for (const sec of e.sections || []) {
        const pts = [sec.startPoint, ...(sec.bendPoints || []), sec.endPoint];
        if (e.rev) pts.reverse();
        el('path', { d: 'M' + pts.map(p => p.x + ',' + p.y).join(' L'), fill: 'none', stroke: color,
          'stroke-width': d.loop ? 2 : d.style === 'call' ? 2.5 : 1.3, 'stroke-dasharray': d.style === 'exception' ? '5 4' : null,
          'marker-end': 'url(#' + (d.loop ? 'arrowLoop' : d.style === 'exception' ? 'arrowExc' : 'arrow') + ')' }, eLayer);
      }
      for (const l of e.labels || []) {
        el('rect', { x: l.x - 2, y: l.y - 1, width: l.width + 4, height: l.height + 2, fill: cssVar('--label-bg'), opacity: 0.9, rx: 3 }, eLayer);
        writeLines(eLayer, d.label, l.x, l.y, 'left', color);
      }
    }
    if (selected && drawn.has(selected)) drawn.get(selected).el.classList.add('sel');
    else if (selected) select(null); // 접혀 안 보이게 된 노드의 상세는 닫는다
    markHits();
  }

  // ---- 배치 실행 ----
  const elk = new ELK();
  const busy = document.getElementById('busy');
  let layoutRun = 0;
  function relayout(anchorId) {
    const before = anchorId && drawn.has(anchorId) ? screenOf(drawn.get(anchorId)) : null;
    const run = ++layoutRun;
    busy.style.display = 'flex';
    setTimeout(() => {
      const built = buildElk();
      elk.layout(built.graph).then(res => {
        if (run !== layoutRun) return;
        draw(res, built.titles);
        busy.style.display = 'none';
        if (pendingFocus && drawn.has(pendingFocus)) {
          const f = pendingFocus; pendingFocus = null;
          select(f); center(f, true); info();
          return;
        }
        const now = anchorId && drawn.has(anchorId) ? drawn.get(anchorId) : null;
        if (before && now) { // 누른 상자가 화면에서 제자리에 있게
          view.x += before.x - (now.x * view.k + view.x);
          view.y += before.y - (now.y * view.k + view.y);
          apply();
        } else if (!before) {
          fit();
        }
        info();
      }).catch(err => {
        busy.textContent = '배치 실패: ' + (err && err.message ? err.message : err);
        console.error(err);
      });
    }, 20);
  }

  // ---- 확대·이동 ----
  const view = { x: 0, y: 0, k: 1 };
  function apply() { vp.setAttribute('transform', 'translate(' + view.x + ',' + view.y + ') scale(' + view.k + ')'); }
  function screenOf(b) { return { x: b.x * view.k + view.x, y: b.y * view.k + view.y }; }
  function bounds() {
    let x1 = Infinity, y1 = Infinity, x2 = -Infinity, y2 = -Infinity;
    for (const b of drawn.values()) { x1 = Math.min(x1, b.x); y1 = Math.min(y1, b.y); x2 = Math.max(x2, b.x + b.w); y2 = Math.max(y2, b.y + b.h); }
    return { x1, y1, x2, y2 };
  }
  function fit() {
    if (!drawn.size) return;
    const b = bounds(), r = svg.getBoundingClientRect();
    view.k = Math.min(1.2, Math.min(r.width / (b.x2 - b.x1 + 40), r.height / (b.y2 - b.y1 + 40)));
    view.k = Math.max(view.k, 0.02);
    view.x = (r.width - (b.x2 - b.x1) * view.k) / 2 - b.x1 * view.k;
    view.y = 20 - b.y1 * view.k;
    apply();
  }
  function center(id, zoom) {
    const b = drawn.get(id); if (!b) return;
    const r = svg.getBoundingClientRect();
    if (zoom) view.k = Math.max(view.k, 0.9);
    view.x = r.width / 2 - (b.x + b.w / 2) * view.k;
    view.y = r.height / 3 - (b.y + Math.min(b.h, r.height / view.k / 3) / 2) * view.k;
    apply();
  }
  svg.addEventListener('wheel', ev => {
    ev.preventDefault();
    const r = svg.getBoundingClientRect();
    const mx = ev.clientX - r.left, my = ev.clientY - r.top;
    // 휠(트랙패드 핀치 = ctrl+휠 포함) = 확대·축소, Shift+휠·가로 스크롤 = 이동
    if (!ev.shiftKey && Math.abs(ev.deltaX) < Math.abs(ev.deltaY)) {
      const f = Math.exp(-ev.deltaY * (ev.ctrlKey ? 0.01 : 0.0015));
      const k = Math.min(4, Math.max(0.02, view.k * f));
      view.x = mx - (mx - view.x) * (k / view.k);
      view.y = my - (my - view.y) * (k / view.k);
      view.k = k;
    } else {
      view.x -= ev.shiftKey && !ev.deltaX ? ev.deltaY : ev.deltaX;
      view.y -= ev.shiftKey ? 0 : ev.deltaY;
    }
    apply();
  }, { passive: false });
  const stage = document.getElementById('stage');
  let drag = null;
  svg.addEventListener('pointerdown', ev => { drag = { x: ev.clientX, y: ev.clientY, vx: view.x, vy: view.y, moved: false }; });
  window.addEventListener('pointermove', ev => {
    if (!drag) return;
    const dx = ev.clientX - drag.x, dy = ev.clientY - drag.y;
    if (!drag.moved && Math.abs(dx) + Math.abs(dy) < 4) return;
    drag.moved = true; stage.classList.add('drag');
    view.x = drag.vx + dx; view.y = drag.vy + dy; apply();
  });
  window.addEventListener('pointerup', () => { if (drag && drag.moved) suppressClick = true; drag = null; stage.classList.remove('drag'); });
  let suppressClick = false;
  svg.addEventListener('click', ev => { // 끌어 이동한 뒤의 click 은 무시
    if (suppressClick) { suppressClick = false; ev.stopPropagation(); }
  }, true);
  svg.addEventListener('click', () => select(null));

  // ---- 접기·이동 ----
  function toggle(gid) {
    if (collapsed.has(gid)) collapsed.delete(gid); else collapsed.add(gid);
    relayout(gid);
  }
  function reveal(id) { // 접힌 조상 펴기
    let p = parent.get(id), changed = false;
    while (p) { if (collapsed.delete(p)) changed = true; p = parent.get(p); }
    return changed;
  }
  let pendingFocus = null; // 배치가 끝난 뒤 이동할 곳 (접힌 상자를 편 경우)
  function go(id) {
    if (reveal(id)) {
      pendingFocus = id; relayout(id);
    } else {
      select(id); center(id, true);
    }
  }

  // ---- 상세 패널 ----
  const panel = document.getElementById('panel');
  function select(id) {
    if (selected && drawn.has(selected)) drawn.get(selected).el.classList.remove('sel');
    selected = id;
    if (!id || !nodes.has(id)) { panel.style.display = 'none'; return; }
    if (drawn.has(id)) drawn.get(id).el.classList.add('sel');
    const n = nodes.get(id);
    panel.textContent = '';
    const row = document.createElement('div'); row.className = 'row';
    const meta = document.createElement('div'); meta.className = 'meta';
    meta.textContent = '노드 ' + id + (n.ids.length > 1 ? ' · 모델 ' + n.ids.join(', ') : '') + ' · ' + (n.cls || '');
    row.appendChild(meta);
    const close = document.createElement('button'); close.textContent = '닫기'; close.onclick = () => select(null);
    row.appendChild(close);
    panel.appendChild(row);
    const pre = document.createElement('pre'); pre.textContent = nodeText(n); panel.appendChild(pre);
    if (n.locs.length) {
      const loc = document.createElement('div'); loc.className = 'meta'; loc.textContent = '위치: ' + n.locs.join(' · ');
      panel.appendChild(loc);
    }
    const p = parent.get(id);
    if (p) {
      let m = p; while (m && !isMethodGroup(m)) m = parent.get(m);
      if (m) { const d = document.createElement('div'); d.className = 'meta'; d.textContent = '메서드: ' + groups.get(m).label.join(' '); panel.appendChild(d); }
    }
    if (n.goto) {
      const b = document.createElement('button'); b.style.marginTop = '6px';
      const tg = groups.get('sg_' + n.goto);
      b.textContent = '세부 메서드로 이동 → ' + (tg ? tg.label.join(' ') : n.goto);
      b.onclick = () => go(n.goto);
      panel.appendChild(b);
    }
    panel.style.display = 'block';
  }

  // ---- 검색 ----
  const q = document.getElementById('q');
  let hits = [], hitAt = -1;
  function search() {
    const s = q.value.trim().toLowerCase();
    hits = [];
    if (s) {
      for (const [id, n] of nodes) if (nodeText(n).toLowerCase().includes(s) || n.ids.some(x => x.toLowerCase() === s)) hits.push(id);
      for (const [gid, g] of groups) if (g.label.join(' ').toLowerCase().includes(s)) { const first = firstNode(gid); if (first && !hits.includes(first)) hits.push(first); }
    }
    hitAt = -1;
    markHits(); info();
  }
  function firstNode(gid) {
    for (const it of groups.get(gid).children) { if (!it.group) return it.id; const f = firstNode(it.group); if (f) return f; }
    return null;
  }
  function markHits() {
    for (const [id, b] of drawn) b.el.classList && b.el.classList.remove('hit');
    const set = new Set(hits.map(visible));
    for (const id of set) { const b = drawn.get(id); if (b && b.el.classList) b.el.classList.add('hit'); }
  }
  function step(dir) {
    if (!hits.length) return;
    hitAt = (hitAt + dir + hits.length) % hits.length;
    go(hits[hitAt]);
    info();
  }
  q.addEventListener('input', search);
  q.addEventListener('keydown', ev => { if (ev.key === 'Enter') { ev.preventDefault(); step(ev.shiftKey ? -1 : 1); } });
  document.getElementById('next').onclick = () => step(1);
  document.getElementById('prev').onclick = () => step(-1);
  document.getElementById('fit').onclick = fit;
  document.getElementById('collapseAll').onclick = () => {
    for (const gid of groups.keys()) if (isMethodGroup(gid) && !(nodes.get(gid.substring(3)) || {}).cls?.startsWith('entry')) collapsed.add(gid);
    relayout(null);
  };
  document.getElementById('expandAll').onclick = () => { collapsed.clear(); relayout(null); };
  function info() {
    const s = q.value.trim();
    document.getElementById('info').textContent = (s ? '검색 ' + (hits.length ? (hitAt + 1) + '/' + hits.length : '0건') + ' · ' : '')
      + '노드 ' + nodes.size + ' · 상자 ' + groups.size + (collapsed.size ? ' · 접힘 ' + collapsed.size : '');
  }

  window.addEventListener('keydown', ev => {
    if (ev.target === q) return;
    if (ev.key === '/') { ev.preventDefault(); q.focus(); }
    else if (ev.key === 'Escape') select(null);
    else if (ev.key === 'f') fit();
  });

  relayout(null);
})();
