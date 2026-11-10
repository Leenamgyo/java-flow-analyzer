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

  const elk = new ELK();

  // ---- 배치 단위: 메서드 상자 ----
  // 메서드마다 따로 ELK 로 배치하고, 진입 메서드를 가운데 두고 호출 깊이마다 좌우 바깥 열로 —
  // 불린 메서드 상자는 부른 노드와 같은 높이에 붙여 연결선으로 잇는다 (사용자: 링크로 찾아가지 말고 주변에 붙이기).
  const unitList = [];      // 단위(메서드 상자, 메서드 밖 노드)
  const unitOf = new Map(); // id → 단위 id
  (function units(items) {
    for (const it of items) {
      if (it.group && !isMethodGroup(it.group)) { units(it.children); continue; } // 단계 보기 레이어 등은 펼쳐서
      const uid = it.group || it.id;
      unitList.push(it);
      (function mark(x) { unitOf.set(x.group || x.id, uid); if (x.group) x.children.forEach(mark); })(it);
    }
  })(FLOW.root);
  const entryUnit = (() => {
    for (const [id, n] of nodes) if (n.cls === 'entry') return unitOf.get(id);
    return unitList.length ? (unitList[0].group || unitList[0].id) : null;
  })();

  // ---- VO 연결: Service·DAO 호출 노드 ← 넘기는 VO 를 set 하는 기존 노드. 평소엔 안 보이고, 상세 패널 'VO 연결' 체크로
  //      강조할 때만 선이 나타난다 (사용자: 선으로 하이라이트만 — 배치·노드·색은 그대로) ----
  const voEdges = new Map(); // 호출 노드 → [{style:'vo', from, to}] (강조에서 같은 객체로 찾음)
  for (const [id, n] of nodes) {
    if (!n.voIn) continue;
    const list = [];
    for (const v of n.voIn) for (const f of v.from || []) {
      if (nodes.has(f)) list.push({ style: 'vo', label: [], loop: false, from: f, to: id, rows: voRows(nodes.get(f), v.lines) });
    }
    if (list.length) voEdges.set(id, list);
  }
  /**
   * 노드 안에서 VO 에 set 하는 줄 (노드 전체가 아니라 그 줄만 강조 — 사용자: 주변 줄까지 켜져 이상함).
   * 노드 줄은 '#n '·'│ '·'▸ ' 머리가 붙고 긴 줄은 '> ' 로 이어지므로 머리를 떼고 set 코드와 비교한다.
   */
  function voRows(node, wanted) {
    const want = wanted.map(w => w.replace(/  \(조건부\)$/, ''));
    const out = [];
    for (let i = 0; i < node.lines.length; i++) {
      const text = lineText(node.lines[i]);
      if (text.startsWith('> ')) continue;
      const norm = text.replace(/^[│ ]*/, '').replace(/^▸ /, '').replace(/^#\d+(–\d+)? /, '');
      if (want.some(w => norm === w || norm.startsWith(w + ' ') || norm.length > 8 && w.startsWith(norm))) {
        out.push(i);
        for (let k = i + 1; k < node.lines.length && lineText(node.lines[k]).startsWith('> '); k++) out.push(k); // 이어지는 줄
      }
    }
    return out;
  }
  function voText(n) {
    const out = [];
    for (const v of n.voIn) {
      out.push(v.title + (v.via ? '  ← ' + v.via : ''));
      for (const l of v.lines) out.push('  ' + l);
    }
    return out.join('\n');
  }

  // ---- 작은 세부 메서드 생략 (노드 수 ≤ 기준, 설정 output.flowchart-html-hide-nodes, 뷰어에서 바꿈) ----
  const unitSize = new Map();
  for (const [id] of nodes) unitSize.set(unitOf.get(id), (unitSize.get(unitOf.get(id)) || 0) + 1);
  const forceShown = new Set(); // 생략 대상이지만 검색·이동으로 다시 보인 메서드
  let hideNodes = FLOW.hideNodes || 0;
  try { const v = localStorage.getItem('flowchart.hideNodes'); if (v !== null && v !== '') hideNodes = Math.max(0, parseInt(v, 10) || 0); } catch (e) { /* 저장소 없음 */ }
  function hiddenUnit(uid) {
    return hideNodes > 0 && uid !== entryUnit && groups.has(uid) && (unitSize.get(uid) || 0) <= hideNodes && !forceShown.has(uid);
  }

  const ELK_OPTIONS = {
    'elk.algorithm': 'layered',
    'elk.direction': FLOW.direction === 'LR' ? 'RIGHT' : FLOW.direction === 'RL' ? 'LEFT' : FLOW.direction === 'BT' ? 'UP' : 'DOWN',
    'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
    'elk.json.edgeCoords': 'ROOT',
    'elk.json.shapeCoords': 'ROOT',
    'elk.edgeRouting': 'ORTHOGONAL',
    'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
    'elk.layered.cycleBreaking.strategy': 'MODEL_ORDER',
    'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
    'elk.spacing.nodeNode': '28',
    'elk.layered.spacing.nodeNodeBetweenLayers': '36',
    'elk.spacing.edgeLabel': '4',
    'elk.edgeLabels.inline': 'false',
  };

  function elkItems(list, sizes) {
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
            children: elkItems(it.children, sizes),
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

  function labelBox(lines) { return { text: lines.join('\n'), width: boxSize(lines).w - 8, height: lines.length * LINE_H }; }

  /** 보이는 간선: 같은 단위 안(ELK 가 배치) / 단위 사이(연결선, 직접 그림) */
  function visibleEdges() {
    const inner = new Map(), raw = [], seen = new Set();
    FLOW.edges.forEach((e, i) => {
      const a = visible(e.from), b = visible(e.to);
      if (a === b) return;
      const key = a + '>' + b + '>' + e.style + '>' + e.label.join('\n');
      if (seen.has(key)) return;
      seen.add(key);
      const ua = unitOf.get(a), ub = unitOf.get(b);
      if (ua !== ub) { raw.push({ a: a, b: b, ua: ua, ub: ub, data: e }); return; }
      if (e.style === 'call') return; // 같은 메서드 안 재귀 호출 — 상자 색으로
      // 뒤에서 앞으로 가는 간선(반복 ↻ 등)은 배치할 때만 뒤집어 흐름이 위→아래로 유지되게
      const rev = order.get(b) < order.get(a);
      if (!inner.has(ua)) inner.set(ua, []);
      inner.get(ua).push({ id: 'e' + i, sources: [rev ? b : a], targets: [rev ? a : b],
        labels: e.label.length ? [labelBox(e.label)] : [], data: e, rev: rev });
    });
    // 생략한 메서드로 가는 선은 빼고, 생략한 메서드에서 나가는 선은 그 메서드를 부른 노드에서 나가게 (호출 줄기 유지)
    const cross = [], seenCross = new Set();
    for (const c of raw) {
      if (hiddenUnit(c.ub)) continue;
      let a = c.a, ua = c.ua, guard = 0;
      while (hiddenUnit(ua) && guard++ < 50) {
        const up = raw.find(x => x.ub === ua);
        if (!up) { a = null; break; }
        a = up.a; ua = up.ua;
      }
      if (!a || ua === c.ub) continue;
      const key = a + '>' + c.b + '>' + c.data.style;
      if (seenCross.has(key)) continue;
      seenCross.add(key);
      cross.push({ a: a, b: c.b, ua: ua, ub: c.ub, data: c.data });
    }
    return { inner, cross };
  }

  function shift(n, dx, dy) {
    n.x += dx; n.y += dy;
    (n.children || []).forEach(c => shift(c, dx, dy));
  }
  function shiftEdge(e, dx, dy) {
    for (const sec of e.sections || []) {
      for (const p of [sec.startPoint, ...(sec.bendPoints || []), sec.endPoint]) { p.x += dx; p.y += dy; }
    }
    for (const l of e.labels || []) { l.x += dx; l.y += dy; }
  }

  const COL_GAP = 140, ROW_GAP = 36;

  /** 메서드별 배치 → 열·높이 맞춤 → 하나의 배치 결과 (draw 가 그대로 그림) */
  function layoutAll() {
    const sizes = new Map();
    const { inner, cross } = visibleEdges();
    const jobs = unitList.filter(u => !hiddenUnit(u.group || u.id)).map(u => {
      const uid = u.group || u.id;
      return elk.layout({ id: 'root', layoutOptions: ELK_OPTIONS, children: elkItems([u], sizes), edges: inner.get(uid) || [] })
        .then(res => ({ uid: uid, node: res.children[0], edges: res.edges || [] }));
    });
    // ELK 가 단독 배치에서 상자를 내용보다 크게 잡는 경우가 있어 내용(자식·간선)에 맞춰 줄인다
    function trim(n, edges) {
      if (!n.children || !n.children.length) return;
      n.children.forEach(c => trim(c, null));
      let r = -Infinity, b = -Infinity;
      for (const c of n.children) { r = Math.max(r, c.x + c.width); b = Math.max(b, c.y + c.height); }
      for (const e of edges || []) {
        for (const sec of e.sections || []) for (const p of [sec.startPoint, ...(sec.bendPoints || []), sec.endPoint]) { r = Math.max(r, p.x); b = Math.max(b, p.y); }
        for (const l of e.labels || []) { r = Math.max(r, l.x + l.width); b = Math.max(b, l.y + l.height); }
      }
      const t = sizes.get(n.id);
      n.width = Math.min(n.width, Math.max(r + 14 - n.x, t ? t.w + 30 : 0));
      n.height = Math.min(n.height, b + 14 - n.y);
    }
    return Promise.all(jobs).then(results => {
      results.forEach(r => trim(r.node, r.edges));
      const byId = new Map(results.map(r => [r.uid, r]));
      // 호출 깊이 (진입 메서드에서 BFS) — 처음 찾은 호출자(via)를 기억해 방향을 물려준다
      const next = new Map();
      for (const c of cross) { if (!next.has(c.ua)) next.set(c.ua, []); next.get(c.ua).push(c.ub); }
      const depth = new Map(), via = new Map();
      if (entryUnit) depth.set(entryUnit, 0);
      const queue = entryUnit ? [entryUnit] : [];
      while (queue.length) {
        const u = queue.shift();
        for (const v of next.get(u) || []) if (!depth.has(v)) { depth.set(v, depth.get(u) + 1); via.set(v, u); queue.push(v); }
      }
      let maxDepth = 0;
      depth.forEach(d => { maxDepth = Math.max(maxDepth, d); });
      for (const r of results) if (!depth.has(r.uid)) depth.set(r.uid, maxDepth + 1); // 어디서도 안 불린 것은 맨 끝

      const pos = new Map(); // 배치를 마친 id → 절대 상자
      function record(n) { pos.set(n.id, n); (n.children || []).forEach(record); }
      function place(r, x, y) {
        const n = r.node, dx = x - n.x, dy = y - n.y;
        shift(n, dx, dy);
        r.edges.forEach(e => shiftEdge(e, dx, dy));
        record(n);
      }
      // 진입 메서드 (깊이 0) 를 가운데에
      const entry = byId.get(entryUnit);
      if (entry) place(entry, 0, 0);

      // 방향: 진입 메서드가 직접 부른 메서드는 부른 노드가 진입 상자의 왼쪽 절반이면 왼쪽(-1), 아니면 오른쪽(+1).
      // 더 깊은 메서드는 처음 부른 메서드의 방향을 따라 계속 바깥쪽으로 (사용자: 좌우 가까운 쪽에)
      const side = new Map();
      function sideOf(uid) {
        if (side.has(uid)) return side.get(uid);
        let sd = 1;
        const p = via.get(uid);
        if (p === entryUnit && entry) {
          let top = null;
          for (const c of cross) if (c.ub === uid && c.ua === entryUnit && pos.has(c.a)) { const n = pos.get(c.a); if (!top || n.y < top.y) top = n; }
          if (top) sd = top.x + top.width / 2 < entry.node.x + entry.node.width / 2 ? -1 : 1;
        } else if (p) {
          sd = sideOf(p);
        }
        side.set(uid, sd);
        return sd;
      }
      let rightEdge = entry ? entry.node.x + entry.node.width : 0;
      let leftEdge = entry ? entry.node.x : 0;
      const anchor = r => { // 부른 노드의 높이 (이미 놓인 안쪽 열 기준)
        let y = Infinity;
        for (const c of cross) if (c.ub === r.uid && pos.has(c.a)) { const sn = pos.get(c.a); y = Math.min(y, sn.y + sn.height / 2 - 20); }
        return y;
      };
      for (let d = 1; d <= maxDepth + 1; d++) {
        for (const sd of [1, -1]) {
          const col = results.filter(r => r.uid !== entryUnit && depth.get(r.uid) === d && sideOf(r.uid) === sd);
          if (!col.length) continue;
          const rows = col.map((r, i) => ({ r: r, y: anchor(r), i: i }))
            .sort((p, q) => (p.y === q.y ? p.i - q.i : p.y - q.y));
          let bottom = -Infinity, edge = sd > 0 ? rightEdge : leftEdge;
          for (const row of rows) {
            const n = row.r.node;
            const y = Math.max(row.y === Infinity ? (bottom === -Infinity ? 0 : bottom + ROW_GAP) : row.y, bottom + ROW_GAP);
            // 오른쪽 열은 왼쪽 끝을, 왼쪽 열은 오른쪽 끝을 맞춘다 (진입 메서드 쪽으로 붙게)
            place(row.r, sd > 0 ? rightEdge + COL_GAP : leftEdge - COL_GAP - n.width, y);
            bottom = n.y + n.height;
            edge = sd > 0 ? Math.max(edge, n.x + n.width) : Math.min(edge, n.x);
          }
          if (sd > 0) rightEdge = edge; else leftEdge = edge;
        }
      }

      // 단위 사이 연결선: 부른 노드 → 불린 메서드 상자 (오른쪽이면 상자 왼쪽 위, 왼쪽이면 상자 오른쪽 위; 열 사이 빈 곳에서 꺾음)
      const edges = results.flatMap(r => r.edges);
      for (const c of cross) {
        const sn = pos.get(c.a), t = pos.get(c.ub);
        if (!sn || !t) continue;
        const ty = t.y + 16, sy = sn.y + sn.height / 2;
        let pts;
        if (t.x >= sn.x + sn.width) {
          const mx = t.x - 24;
          pts = [{ x: sn.x + sn.width, y: sy }, { x: mx, y: sy }, { x: mx, y: ty }, { x: t.x, y: ty }];
        } else if (t.x + t.width <= sn.x) {
          const mx = t.x + t.width + 24;
          pts = [{ x: sn.x, y: sy }, { x: mx, y: sy }, { x: mx, y: ty }, { x: t.x + t.width, y: ty }];
        } else { // 같은 열(되돌아가는 호출 등) — 바깥으로 돌아서
          const mx = Math.min(sn.x, t.x) - 24;
          pts = [{ x: sn.x, y: sy }, { x: mx, y: sy }, { x: mx, y: ty }, { x: t.x, y: ty }];
        }
        const label = c.data.label.length ? labelBox(c.data.label) : null;
        if (label) { label.x = pts[1].x > pts[0].x ? pts[0].x + 6 : pts[0].x - label.width - 6; label.y = pts[0].y - label.height - 2; }
        edges.push({ id: 'x' + edges.length, sections: [{ startPoint: pts[0], bendPoints: pts.slice(1, -1), endPoint: pts[pts.length - 1] }],
          labels: label ? [label] : [], data: c.data, cross: true });
      }
      // VO 연결선: 배치가 끝난 위치에 덧그림 (VO 를 set 하는 노드 오른쪽 → 호출 노드 오른쪽, 바깥으로 돌아서)
      {
        for (const [id, list] of voEdges) {
          const t = pos.get(visible(id));
          if (!t) continue;
          for (const d of list) {
            const f = pos.get(visible(d.from));
            if (!f || f === t) continue;
            const mx = Math.max(f.x + f.width, t.x + t.width) + 18;
            const fn = nodes.get(d.from);
            const fy = visible(d.from) === d.from && d.rows.length
              ? f.y + (f.height - boxSize(fn.lines).h) / 2 + PAD_Y + d.rows[0] * LINE_H + LINE_H / 2 // set 하는 줄 높이에서
              : f.y + f.height / 2;
            const ty = t.y + t.height / 2;
            edges.push({ id: 'vo' + edges.length, sections: [{ startPoint: { x: f.x + f.width, y: fy },
              bendPoints: [{ x: mx, y: fy }, { x: mx, y: ty }], endPoint: { x: t.x + t.width, y: ty } }], labels: [], data: d });
          }
        }
      }
      return { layout: { children: results.map(r => r.node), edges: edges }, titles: sizes };
    });
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
  marker('arrowCall', (FLOW.classes.detail && FLOW.classes.detail.stroke) || '#b45309');

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
    groupEls.clear();
    edgeEls.length = 0;
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
          groupEls.set(id, { box: box, title: tg });
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
          drawn.set(id, { x: c.x, y: c.y, w: c.width, h: c.height, el: ng, shape: shape, textTop: c.y + (c.height - s.h) / 2 + PAD_Y });
        }
      }
    })(layout.children || [], 0);

    for (const e of layout.edges || []) {
      const d = e.data;
      const callColor = (FLOW.classes.detail && FLOW.classes.detail.stroke) || '#b45309';
      const color = d.loop ? loopColor : d.style === 'exception' ? cssVar('--exc') : d.style === 'call' ? callColor : cssVar('--edge');
      const els = [];
      edgeEls.push({ data: d, cross: !!e.cross, els: els });
      for (const sec of e.sections || []) {
        const pts = [sec.startPoint, ...(sec.bendPoints || []), sec.endPoint];
        if (e.rev) pts.reverse();
        els[els.length] = el('path', { d: 'M' + pts.map(p => p.x + ',' + p.y).join(' L'), fill: 'none', stroke: color,
          'stroke-width': d.loop ? 2 : d.style === 'call' ? 1.6 : 1.3, 'stroke-dasharray': d.style === 'exception' ? '5 4' : d.style === 'call' ? '7 4' : d.style === 'vo' ? '2 3' : null,
          'stroke-opacity': e.cross ? 0.85 : 1,
          'marker-end': 'url(#' + (d.loop ? 'arrowLoop' : d.style === 'exception' ? 'arrowExc' : d.style === 'call' ? 'arrowCall' : 'arrow') + ')' }, eLayer);
      }
      for (const l of e.labels || []) {
        els.push(el('rect', { x: l.x - 2, y: l.y - 1, width: l.width + 4, height: l.height + 2, fill: cssVar('--label-bg'), opacity: 0.9, rx: 3 }, eLayer));
        els.push(writeLines(eLayer, d.label, l.x, l.y, 'left', color));
      }
    }
    if (selected && drawn.has(selected)) drawn.get(selected).el.classList.add('sel');
    else if (selected) select(null); // 접혀 안 보이게 된 노드의 상세는 닫는다
    markHits();
    applyHighlight();
  }

  // ---- 연결 강조: 고른 노드·메서드에서 호출(==>)을 따라 위(부른 쪽, 진입 메서드까지)·아래(부르는 Service·DAO …)로 ----
  const groupEls = new Map(); // 펼친 상자 id → {box, title}
  const edgeEls = [];         // {data(원래 간선), cross, els}
  const callIn = new Map(), callOut = new Map(), unitCalls = new Map();
  for (const e of FLOW.edges) {
    if (e.style !== 'call') continue;
    const tu = unitOf.get(e.to), fu = unitOf.get(e.from);
    if (!tu || !fu) continue;
    if (!callIn.has(tu)) callIn.set(tu, []);
    callIn.get(tu).push(e);
    if (!callOut.has(e.from)) callOut.set(e.from, []);
    callOut.get(e.from).push(e);
    if (!unitCalls.has(fu)) unitCalls.set(fu, new Set());
    unitCalls.get(fu).add(e.from);
  }
  let hl = null; // 강조 합집합 {nodes, units, edges}
  const hlStarts = new Map(); // 체크한 시작 노드 → {up, down} (여러 개 체크하면 경로를 함께)
  function headerOf(unit) { const id = unit && unit.startsWith('sg_') ? unit.substring(3) : null; return id && nodes.has(id) ? id : null; }
  function pathOf(start, upOn, downOn, h) {
    h.nodes.add(start);
    const u0 = unitOf.get(start);
    h.units.add(u0);
    if (headerOf(u0)) h.nodes.add(headerOf(u0));
    const isHeader = headerOf(u0) === start;
    if (upOn) { // 위로: 이 메서드를 부른 노드 → 그 메서드를 부른 노드 … (진입 메서드까지)
      const seen = new Set();
      (function up(u) {
        if (seen.has(u)) return;
        seen.add(u);
        for (const e of callIn.get(u) || []) {
          h.edges.add(e); h.nodes.add(e.from);
          const pu = unitOf.get(e.from);
          h.units.add(pu);
          if (headerOf(pu)) h.nodes.add(headerOf(pu));
          up(pu);
        }
      })(u0);
    }
    if (downOn) { // 아래로: 이 노드(메서드 머리면 메서드 안 모든 호출)가 부르는 메서드 → 그 안의 호출 …
      const seen = new Set();
      const down = n => {
        for (const e of callOut.get(n) || []) {
          h.edges.add(e); h.nodes.add(n);
          const tu = unitOf.get(e.to);
          h.units.add(tu);
          if (headerOf(tu)) h.nodes.add(headerOf(tu));
          if (seen.has(tu)) continue;
          seen.add(tu);
          for (const c of unitCalls.get(tu) || []) down(c);
        }
      };
      if (isHeader) { seen.add(u0); for (const c of unitCalls.get(u0) || []) down(c); } else down(start);
    }
  }
  const voStarts = new Set(); // 'VO 연결' 체크한 호출 노드
  function recomputeHighlight() {
    if (!hlStarts.size) { hl = null; } else {
      hl = { nodes: new Set(), units: new Set(), edges: new Set() };
      for (const [start, d] of hlStarts) pathOf(start, d.up, d.down, hl);
    }
    if (voStarts.size) { // VO 연결 체크: 호출 노드 + VO 를 set 하는 노드 + 그 사이 선
      if (!hl) hl = { nodes: new Set(), units: new Set(), edges: new Set() };
      for (const id of voStarts) {
        hl.nodes.add(id); hl.units.add(unitOf.get(id));
        for (const d of voEdges.get(id) || []) {
          hl.edges.add(d); hl.nodes.add(d.from);
          if (d.rows.length) { hl.rows = hl.rows || new Map(); hl.rows.set(d.from, [...(hl.rows.get(d.from) || []), ...d.rows]); }
        }
      }
    }
    applyHighlight();
    info();
  }
  function setHighlight(id, d) { // d = null 이면 그 노드 강조 끔
    if (d && (d.up || d.down)) hlStarts.set(id, d); else hlStarts.delete(id);
    recomputeHighlight();
  }
  function clearHighlight() { if (hlStarts.size || voStarts.size) { hlStarts.clear(); voStarts.clear(); recomputeHighlight(); if (selected) select(selected); } }
  function applyHighlight() {
    const on = !!hl;
    const vis = on ? new Set([...hl.nodes, ...hl.units].map(visible)) : null;
    // 연결 강조(경로)에서 온 노드 — VO 연결로만 켜진 노드는 테두리 없이 그 줄만 띠로
    const pathNodes = on ? new Set() : null;
    if (on) for (const [start, d] of hlStarts) { const h = { nodes: new Set(), units: new Set(), edges: new Set() }; pathOf(start, d.up, d.down, h); h.nodes.forEach(x => pathNodes.add(visible(x))); }
    if (on) for (const id of voStarts) pathNodes.add(visible(id));
    for (const [id, b] of drawn) {
      if (groupEls.has(id)) continue;
      const lit = on && vis.has(id);
      b.el.classList.toggle('dim', on && !lit);
      b.el.classList.toggle('path', lit && (pathNodes.has(id) || !(hl.rows && hl.rows.has(id))));
      b.el.querySelectorAll('.rowhl').forEach(x => x.remove());
      const rows = on && hl.rows ? hl.rows.get(id) : null;
      if (rows && b.textTop !== undefined) {
        for (const i of rows) {
          const band = el('rect', { class: 'rowhl', x: b.x + 2, y: b.textTop + i * LINE_H - 1, width: b.w - 4, height: LINE_H + 1, rx: 2 });
          b.el.insertBefore(band, b.shape.nextSibling); // 글자 아래, 상자 위
        }
      }
    }
    for (const [id, gEl] of groupEls) {
      const lit = on && hl.units.has(id);
      for (const x of [gEl.box, gEl.title]) x.classList.toggle('dim', on && !lit && isMethodGroup(id));
      gEl.box.classList.toggle('pathbox', lit && isMethodGroup(id));
    }
    for (const e of edgeEls) {
      const lit = on && hl.edges.has(e.data);
      if (e.data.style === 'vo') { for (const x of e.els) { x.style.display = lit ? '' : 'none'; x.classList.toggle('pathedge', lit); } continue; }
      for (const x of e.els) { x.classList.toggle('dim', on && !lit); x.classList.toggle('pathedge', lit); }
    }
    document.getElementById('clearHl').style.display = on ? '' : 'none';
  }

  // ---- 배치 실행 ----
  const busy = document.getElementById('busy');
  let layoutRun = 0;
  function relayout(anchorId) {
    const before = anchorId && drawn.has(anchorId) ? screenOf(drawn.get(anchorId)) : null;
    const run = ++layoutRun;
    busy.style.display = 'flex';
    setTimeout(() => {
      layoutAll().then(built => {
        if (run !== layoutRun) return;
        draw(built.layout, built.titles);
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
  function reveal(id) { // 접힌 조상 펴기 · 생략한 메서드 다시 보이기
    let p = parent.get(id), changed = false;
    const u = unitOf.get(id);
    if (hiddenUnit(u)) { forceShown.add(u); changed = true; }
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
    if (n.voIn) { // 넘기는 VO 와 set 코드
      const t = document.createElement('div'); t.className = 'meta'; t.style.marginTop = '6px'; t.textContent = '넘기는 VO (set 코드 그대로)';
      panel.appendChild(t);
      const pv = document.createElement('pre'); pv.textContent = voText(n); panel.appendChild(pv);
    }
    const linkable = callIn.has(unitOf.get(id)) || callOut.has(id) || headerOf(unitOf.get(id)) === id && unitCalls.has(unitOf.get(id));
    if (linkable || voEdges.has(id)) {
      const cur = hlStarts.get(id);
      const row2 = document.createElement('div'); row2.className = 'row'; row2.style.marginTop = '6px'; row2.style.justifyContent = 'flex-start';
      const box = (text, checked, disabled, onChange) => {
        const l = document.createElement('label'); l.className = 'chk';
        const c = document.createElement('input'); c.type = 'checkbox'; c.checked = checked; c.disabled = disabled;
        c.onchange = () => onChange(c.checked);
        l.appendChild(c); l.appendChild(document.createTextNode(' ' + text));
        row2.appendChild(l);
        return c;
      };
      if (linkable) {
        box('연결 강조', !!cur, false, on => { setHighlight(id, on ? { up: true, down: true } : null); select(id); });
        box('↑ 부른 쪽', cur ? cur.up : true, !cur, on => { setHighlight(id, { up: on, down: hlStarts.get(id).down }); select(id); });
        box('↓ 부르는 쪽', cur ? cur.down : true, !cur, on => { setHighlight(id, { up: hlStarts.get(id).up, down: on }); select(id); });
      }
      if (voEdges.has(id)) {
        box('VO 연결', voStarts.has(id), false, on => { if (on) voStarts.add(id); else voStarts.delete(id); recomputeHighlight(); select(id); });
      }
      panel.appendChild(row2);
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
      for (const [id, n] of nodes) {
        const text = nodeText(n) + (n.voIn ? '\n' + voText(n) : '');
        if (text.toLowerCase().includes(s) || n.ids.some(x => x.toLowerCase() === s)) hits.push(id);
      }
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
  document.getElementById('clearHl').onclick = clearHighlight;
  document.getElementById('collapseAll').onclick = () => {
    for (const gid of groups.keys()) if (isMethodGroup(gid) && !(nodes.get(gid.substring(3)) || {}).cls?.startsWith('entry')) collapsed.add(gid);
    relayout(null);
  };
  document.getElementById('expandAll').onclick = () => { collapsed.clear(); relayout(null); };
  function info() {
    const s = q.value.trim();
    document.getElementById('info').textContent = (s ? '검색 ' + (hits.length ? (hitAt + 1) + '/' + hits.length : '0건') + ' · ' : '')
      + '노드 ' + nodes.size + ' · 상자 ' + groups.size + (collapsed.size ? ' · 접힘 ' + collapsed.size : '')
      + (hiddenCount() ? ' · 생략 ' + hiddenCount() : '')
      + (hl ? ' · 강조 ' + (hlStarts.size + voStarts.size) + '곳' : '');
  }

  function hiddenCount() { return unitList.filter(u => hiddenUnit(u.group || u.id)).length; }
  const hideBox = document.getElementById('hideOn'), hideNum = document.getElementById('hideNum');
  let lastHide = hideNodes > 0 ? hideNodes : (FLOW.hideNodes > 0 ? FLOW.hideNodes : 10);
  hideBox.checked = hideNodes > 0;
  hideNum.value = lastHide;
  function setHide() {
    lastHide = Math.max(1, parseInt(hideNum.value, 10) || 1);
    hideNodes = hideBox.checked ? lastHide : 0;
    try { localStorage.setItem('flowchart.hideNodes', String(hideNodes)); } catch (e) { /* 저장소 없음 */ }
    forceShown.clear();
    relayout(entryUnit);
  }
  hideBox.addEventListener('change', setHide);
  hideNum.addEventListener('change', () => { if (hideBox.checked) setHide(); });

  window.addEventListener('keydown', ev => {
    if (ev.target === q) return;
    if (ev.key === '/') { ev.preventDefault(); q.focus(); }
    else if (ev.key === 'Escape') { if (hl) clearHighlight(); else select(null); }
    else if (ev.key === 'f') fit();
  });

  relayout(null);
})();
