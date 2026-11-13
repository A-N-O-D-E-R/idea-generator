// The idea canvas: a force layout on an infinite pan/zoom plane.
//
// Linked ideas pull together (springs along the edges), all ideas push apart,
// and collisions are resolved, so families cluster. A ball grows with its jury
// score. Drag an idea onto another to cross them. Hovering lights up the
// idea's whole family line. Everything is drawn on one <canvas>.

import { medal, scoreSum } from './score';
import type { Edge, Idea } from '../types';

export type GraphHandlers = {
  select?: (id: number | null) => void;
  hover?: (id: number | null, e?: PointerEvent) => void;
  combine?: (a: number, b: number) => void;
  expand?: (id: number) => void;
  create?: (x: number, y: number) => void;
  moved?: () => void;
  dropLead?: (id: number, x: number, y: number) => void;
};
export type Point = { x: number; y: number };
type Cam = { x: number; y: number; k: number };
type GNode = {
  id: number; idea: Idea; x: number; y: number; vx: number; vy: number; r: number; tr: number;
  born: number; fx: number | null; fy: number | null; wrapKey?: string; wrapLines?: string[];
};
type Ghost = Point & { born: number };
type Burst = Point & { r: number; color: string; born: number };
type Focus = { id: number | null; set: Set<number> | null; edges: Set<string> | null };
type Drag =
  | { type: 'pinch'; d: number; k: number }
  | { type: 'node'; id: number; sx: number; sy: number; ox: number; oy: number; moved: boolean }
  | { type: 'pan'; sx: number; sy: number; cx: number; cy: number; moved: boolean };

// Validated with the dataviz palette checks against the canvas background (#ffffff).
export const KIND_COLORS: Record<string, string> = { seed: '#3987e5', expand: '#199e70', combine: '#9085e9', guest: '#d55181' };
export const MEDAL_COLORS: (string | null)[] = [null, '#cd7f32', '#c9d1d9', '#f5c542'];
const BG = '#ffffff';
const TEXT = '#eef0f4';
const FONT = 'Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif';

export function radiusFor(idea: Idea) {
  const s = scoreSum(idea);
  if (s != null) return 32 + (s - 3) * 3.2; // 3 → 32, 9 → 51, 13 → 64, 15 → 70
  if (Number.isInteger(idea.medalOverride)) return [44, 52, 60, 68][idea.medalOverride!];
  return 46;
}

const easeOutBack = (t: number) => 1 + 2.2 * (t - 1) ** 3 + 1.2 * (t - 1) ** 2;

export class Graph {
  c: HTMLCanvasElement;
  ctx: CanvasRenderingContext2D;
  h: GraphHandlers;
  nodes = new Map<number, GNode>();
  edges: Edge[] = [];
  edgeKeys = new Set<string>();
  ghosts: Ghost[] = [];
  bursts: Burst[] = [];
  judging = new Set<number>();
  busy = new Set<number>();
  cam: Cam = { x: 0, y: 0, k: 1 };
  camTarget: Cam | null = null;
  alpha = 1;
  hover: number | null = null;
  selected: number | null = null;
  pick: number | null = null;
  dropTarget: GNode | null = null;
  drag: Drag | null = null;
  pointers = new Map<number, Point>();
  lastUser = 0;
  followNewest = false;
  dirty = true;
  wasSettled = true;
  dpr = 1;
  focusCache: Focus = { id: null, set: null, edges: null };
  /** Screen space covered by overlays (seed bar, exhibition title) that fit() should keep clear. */
  insets = { top: 30, right: 30, bottom: 100, left: 30 };
  // React port: lets the component tear the canvas down (StrictMode mounts twice).
  private ac = new AbortController();
  private raf = 0;
  private ro: ResizeObserver;

  constructor(canvas: HTMLCanvasElement, handlers: GraphHandlers) {
    this.c = canvas;
    this.ctx = canvas.getContext('2d')!;
    this.h = handlers; // select, combine, create, expand, moved, dropLead

    this.ro = new ResizeObserver(() => this.resize());
    this.ro.observe(canvas);
    this.resize();
    this.bind();
    const loop = (t: number) => {
      this.frame(t);
      this.raf = requestAnimationFrame(loop);
    };
    this.raf = requestAnimationFrame(loop);
  }

  destroy() {
    cancelAnimationFrame(this.raf);
    this.ro.disconnect();
    this.ac.abort();
  }

  // ------------------------------------------------------------ data

  setData(ideas: Idea[], edges: Edge[], judging: number[] = []) {
    this.nodes.clear();
    this.edges = [];
    this.edgeKeys.clear();
    this.ghosts = [];
    this.judging = new Set(judging);
    this.busy.clear();
    this.selected = this.hover = this.pick = null;
    for (const i of ideas) this.upsert(i, false);
    this.addEdges(edges);
    this.alpha = 0.6;
    this.fit(true);
  }

  upsert(idea: Idea, animate = true) {
    const n = this.nodes.get(idea.id);
    if (n) {
      n.idea = idea;
      n.tr = radiusFor(idea);
    } else {
      const r = radiusFor(idea);
      this.nodes.set(idea.id, {
        id: idea.id, idea, x: idea.x, y: idea.y, vx: 0, vy: 0, r: animate ? r * 0.3 : r, tr: r,
        born: animate ? performance.now() : 0, fx: null, fy: null,
      });
      this.alpha = Math.max(this.alpha, 0.5);
      if (animate && this.followNewest && performance.now() - this.lastUser > 8000) this.focusOn(idea.id, null);
    }
    this.focusCache.id = null;
    this.dirty = true;
  }

  remove(id: number) {
    this.nodes.delete(id);
    this.edges = this.edges.filter((e) => e.from !== id && e.to !== id);
    this.edgeKeys = new Set(this.edges.map((e) => `${e.from}-${e.to}`));
    if (this.selected === id) this.selected = null;
    this.focusCache.id = null;
    this.alpha = Math.max(this.alpha, 0.3);
    this.dirty = true;
  }

  addEdges(edges: Edge[]) {
    for (const e of edges) {
      const k = `${e.from}-${e.to}`;
      if (this.edgeKeys.has(k)) continue;
      this.edgeKeys.add(k);
      this.edges.push({ from: e.from, to: e.to });
    }
    this.focusCache.id = null;
    this.alpha = Math.max(this.alpha, 0.4);
    this.dirty = true;
  }

  setJudging(ids: number[], on: boolean) {
    for (const id of ids) if (on) this.judging.add(id); else this.judging.delete(id);
    this.dirty = true;
  }

  setBusy(ids: number[], on: boolean) {
    for (const id of ids) if (on) this.busy.add(id); else this.busy.delete(id);
    this.dirty = true;
  }

  /** Placeholders where new ideas will appear while the model works. */
  addGhosts(points: Point[]) {
    const now = performance.now();
    const ghosts = points.map((p) => ({ ...p, born: now }));
    this.ghosts.push(...ghosts);
    this.dirty = true;
    return () => {
      this.ghosts = this.ghosts.filter((g) => !ghosts.includes(g));
      this.dirty = true;
    };
  }

  ghostsAround(id: number, n = 3): Point[] {
    const node = this.nodes.get(id);
    if (!node) return [];
    return Array.from({ length: n }, (_, i) => {
      const a = (Math.PI * 2 * i) / n - Math.PI / 2;
      return { x: node.x + Math.cos(a) * 170, y: node.y + Math.sin(a) * 170 };
    });
  }

  burst(id: number, color = MEDAL_COLORS[3]!) {
    const n = this.nodes.get(id);
    if (n) this.bursts.push({ x: n.x, y: n.y, r: n.r, color, born: performance.now() });
    this.dirty = true;
  }

  select(id: number | null) {
    this.selected = id;
    this.focusCache.id = null;
    this.dirty = true;
  }

  setPick(id: number | null) {
    this.pick = id;
    this.c.style.cursor = id ? 'crosshair' : '';
    this.dirty = true;
  }

  positions() {
    return [...this.nodes.values()].map((n) => ({ id: n.id, x: n.x, y: n.y }));
  }

  viewCenter() {
    return { x: this.cam.x + (Math.random() - 0.5) * 80, y: this.cam.y + (Math.random() - 0.5) * 80 };
  }

  focusOn(id: number, k: number | null = 1) {
    const n = this.nodes.get(id);
    if (n) this.camTarget = { x: n.x, y: n.y, k: k ?? this.cam.k };
    this.dirty = true;
  }

  fit(instant = false) {
    if (!this.nodes.size) {
      this.cam = { x: 0, y: 0, k: 1 };
      this.camTarget = null;
      return;
    }
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const n of this.nodes.values()) {
      x0 = Math.min(x0, n.x - n.tr); y0 = Math.min(y0, n.y - n.tr);
      x1 = Math.max(x1, n.x + n.tr); y1 = Math.max(y1, n.y + n.tr);
    }
    const { top, right, bottom, left } = this.insets;
    const w = Math.max(100, this.c.clientWidth - left - right), h = Math.max(100, this.c.clientHeight - top - bottom);
    const k = Math.min(1.4, Math.max(0.15, Math.min(w / (x1 - x0 + 80), h / (y1 - y0 + 80))));
    // Center the board in the free area, not behind the overlays.
    const target = { x: (x0 + x1) / 2 - (left - right) / (2 * k), y: (y0 + y1) / 2 - (top - bottom) / (2 * k), k };
    if (instant) this.cam = target;
    else this.camTarget = target;
    this.dirty = true;
  }

  zoomBy(f: number) {
    this.camTarget = { ...this.cam, k: Math.min(3, Math.max(0.1, this.cam.k * f)) };
  }

  // ------------------------------------------------------------ geometry

  resize() {
    this.dpr = window.devicePixelRatio || 1;
    this.c.width = Math.round(this.c.clientWidth * this.dpr);
    this.c.height = Math.round(this.c.clientHeight * this.dpr);
    this.dirty = true;
  }

  toWorld(sx: number, sy: number): Point {
    const r = this.c.getBoundingClientRect();
    return {
      x: (sx - r.left - this.c.clientWidth / 2) / this.cam.k + this.cam.x,
      y: (sy - r.top - this.c.clientHeight / 2) / this.cam.k + this.cam.y,
    };
  }

  nodeAt(wx: number, wy: number, except: number | null = null): GNode | null {
    const list = [...this.nodes.values()];
    for (let i = list.length - 1; i >= 0; i--) {
      const n = list[i];
      if (n.id !== except && Math.hypot(n.x - wx, n.y - wy) <= n.r + 4) return n;
    }
    return null;
  }

  /** The idea's whole family line: ancestors and descendants. */
  focus(): Focus | null {
    const id = this.hover ?? this.selected;
    if (id == null) return null;
    if (this.focusCache.id === id) return this.focusCache;
    const up = new Map<number, number[]>(), down = new Map<number, number[]>();
    for (const e of this.edges) {
      (up.get(e.to) ?? up.set(e.to, []).get(e.to)!).push(e.from);
      (down.get(e.from) ?? down.set(e.from, []).get(e.from)!).push(e.to);
    }
    const set = new Set([id]);
    for (const map of [up, down]) {
      const q = [id];
      while (q.length) for (const n of map.get(q.pop()!) ?? []) if (!set.has(n)) { set.add(n); q.push(n); }
    }
    const edges = new Set(this.edges.filter((e) => set.has(e.from) && set.has(e.to)).map((e) => `${e.from}-${e.to}`));
    this.focusCache = { id, set, edges };
    return this.focusCache;
  }

  // ------------------------------------------------------------ simulation

  tick() {
    const nodes = [...this.nodes.values()];
    const a = this.alpha;
    for (const e of this.edges) {
      const s = this.nodes.get(e.from), t = this.nodes.get(e.to);
      if (!s || !t) continue;
      let dx = t.x + t.vx - s.x - s.vx, dy = t.y + t.vy - s.y - s.vy;
      const l = Math.hypot(dx, dy) || 1;
      const f = ((l - (s.r + t.r + 90)) / l) * a * 0.22;
      dx *= f; dy *= f;
      t.vx -= dx * 0.5; t.vy -= dy * 0.5; s.vx += dx * 0.5; s.vy += dy * 0.5;
    }
    for (let i = 0; i < nodes.length; i++) {
      const p = nodes[i];
      for (let j = i + 1; j < nodes.length; j++) {
        const q = nodes[j];
        const dx = q.x - p.x, dy = q.y - p.y;
        let d2 = dx * dx + dy * dy;
        if (d2 > 640_000) continue;
        d2 = Math.max(d2, 400);
        const f = (-1400 * a) / d2;
        p.vx += dx * f; p.vy += dy * f; q.vx -= dx * f; q.vy -= dy * f;
      }
      p.vx -= p.x * 0.0025 * a;
      p.vy -= p.y * 0.0025 * a;
    }
    for (const n of nodes) {
      n.vx *= 0.58; n.vy *= 0.58;
      if (n.fx != null) { n.x = n.fx; n.y = n.fy!; n.vx = n.vy = 0; } else { n.x += n.vx; n.y += n.vy; }
    }
    for (let pass = 0; pass < 2; pass++) {
      for (let i = 0; i < nodes.length; i++) {
        for (let j = i + 1; j < nodes.length; j++) {
          const p = nodes[i], q = nodes[j];
          const min = p.r + q.r + 14;
          let dx = q.x - p.x, dy = q.y - p.y;
          const d = Math.hypot(dx, dy);
          if (d >= min) continue;
          if (d < 0.01) { dx = Math.random() - 0.5; dy = Math.random() - 0.5; }
          const push = (min - d) / (d || 1) * 0.5;
          if (p.fx == null) { p.x -= dx * push; p.y -= dy * push; }
          if (q.fx == null) { q.x += dx * push; q.y += dy * push; }
        }
      }
    }
    this.alpha += (0 - this.alpha) * 0.02;
  }

  // ------------------------------------------------------------ frame

  frame(now: number) {
    let animating = this.alpha > 0.004 || !!this.camTarget || this.judging.size || this.busy.size || this.ghosts.length || this.bursts.length;
    for (const n of this.nodes.values()) {
      if (Math.abs(n.r - n.tr) > 0.2) { n.r += (n.tr - n.r) * 0.12; animating = true; }
      if (n.born && now - n.born < 600) animating = true;
    }
    if (this.alpha > 0.004) {
      this.tick();
      this.wasSettled = false;
    } else if (!this.wasSettled) {
      this.wasSettled = true;
      this.h.moved?.();
    }
    if (this.camTarget) {
      const c = this.cam, tg = this.camTarget;
      c.x += (tg.x - c.x) * 0.12; c.y += (tg.y - c.y) * 0.12; c.k += (tg.k - c.k) * 0.12;
      if (Math.abs(tg.x - c.x) < 0.5 && Math.abs(tg.y - c.y) < 0.5 && Math.abs(tg.k - c.k) < 0.002) this.camTarget = null;
    }
    if (!animating && !this.dirty) return;
    this.dirty = false;
    this.draw(now);
  }

  draw(now: number) {
    const { ctx, cam } = this;
    const w = this.c.clientWidth, h = this.c.clientHeight, k = cam.k;
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.fillStyle = BG;
    ctx.fillRect(0, 0, w, h);

    // Dot grid, in world space, fading out when zoomed far out.
    const step = 48;
    if (step * k > 12) {
      ctx.fillStyle = `rgba(60,10,40,${Math.min(0.07, (step * k - 12) / 300)})`;
      const x0 = Math.floor((cam.x - w / 2 / k) / step) * step, x1 = cam.x + w / 2 / k;
      const y0 = Math.floor((cam.y - h / 2 / k) / step) * step, y1 = cam.y + h / 2 / k;
      for (let x = x0; x < x1; x += step) for (let y = y0; y < y1; y += step) {
        ctx.fillRect((x - cam.x) * k + w / 2 - 1, (y - cam.y) * k + h / 2 - 1, 2, 2);
      }
    }

    ctx.save();
    ctx.translate(w / 2, h / 2);
    ctx.scale(k, k);
    ctx.translate(-cam.x, -cam.y);
    const focus = this.focus();

    // Edges: parent → child, gently curved.
    for (const e of this.edges) {
      const s = this.nodes.get(e.from), t = this.nodes.get(e.to);
      if (!s || !t) continue;
      const lit = focus?.edges!.has(`${e.from}-${e.to}`);
      const mx = (s.x + t.x) / 2, my = (s.y + t.y) / 2;
      const cx = mx + (t.y - s.y) * 0.08, cy = my - (t.x - s.x) * 0.08;
      ctx.beginPath();
      ctx.moveTo(s.x, s.y);
      ctx.quadraticCurveTo(cx, cy, t.x, t.y);
      ctx.strokeStyle = lit ? KIND_COLORS[t.idea.kind] ?? '#fff' : focus ? 'rgba(60,10,40,0.06)' : 'rgba(60,10,40,0.2)';
      ctx.lineWidth = (lit ? 2.5 : 1.5) / Math.max(k, 0.4);
      ctx.stroke();
    }

    // Ghosts: where new ideas are on their way.
    for (const g of this.ghosts) {
      const pulse = 0.5 + 0.5 * Math.sin((now - g.born) / 260);
      ctx.beginPath();
      ctx.arc(g.x, g.y, 34 + pulse * 6, 0, Math.PI * 2);
      ctx.setLineDash([6, 8]);
      ctx.strokeStyle = `rgba(60,10,40,${0.25 + pulse * 0.2})`;
      ctx.lineWidth = 2;
      ctx.stroke();
      ctx.setLineDash([]);
    }

    for (const n of this.nodes.values()) this.drawNode(n, now, focus, k);

    // Gold bursts.
    this.bursts = this.bursts.filter((b) => now - b.born < 1400);
    for (const b of this.bursts) {
      const p = (now - b.born) / 1400;
      ctx.beginPath();
      ctx.arc(b.x, b.y, b.r + p * 120, 0, Math.PI * 2);
      ctx.strokeStyle = b.color;
      ctx.globalAlpha = 1 - p;
      ctx.lineWidth = 6 * (1 - p) + 1;
      ctx.stroke();
      ctx.globalAlpha = 1;
    }

    // While dragging onto another idea: the line that will become a cross.
    if (this.drag?.type === 'node' && this.dropTarget) {
      const a = this.nodes.get(this.drag.id)!, b = this.dropTarget;
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.setLineDash([8, 6]);
      ctx.strokeStyle = KIND_COLORS.combine;
      ctx.lineWidth = 3 / k;
      ctx.stroke();
      ctx.setLineDash([]);
    }
    ctx.restore();
  }

  drawNode(n: GNode, now: number, focus: Focus | null, k: number) {
    const { ctx } = this;
    const idea = n.idea;
    const age = n.born ? (now - n.born) / 500 : 1;
    const scale = age >= 1 ? 1 : Math.max(0.05, easeOutBack(Math.min(1, age)));
    const r = n.r * scale;
    const color = KIND_COLORS[idea.kind] ?? '#888';
    const m = medal(idea);
    ctx.globalAlpha = focus && !focus.set!.has(n.id) ? 0.28 : 1;

    if (m === 3) {
      ctx.shadowColor = 'rgba(245,197,66,0.55)';
      ctx.shadowBlur = 28;
    }
    const g = ctx.createRadialGradient(n.x - r * 0.35, n.y - r * 0.4, r * 0.1, n.x, n.y, r);
    g.addColorStop(0, color + 'cc');
    g.addColorStop(1, '#1b0c16');
    ctx.beginPath();
    ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
    ctx.fillStyle = g;
    ctx.fill();
    ctx.shadowBlur = 0;

    ctx.lineWidth = m ? 3.5 : 1.5;
    ctx.strokeStyle = m ? MEDAL_COLORS[m]! : color + '99';
    ctx.stroke();

    if (idea.kind === 'guest') {
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 6, 0, Math.PI * 2);
      ctx.setLineDash([4, 6]);
      ctx.strokeStyle = KIND_COLORS.guest;
      ctx.lineWidth = 1.5;
      ctx.stroke();
      ctx.setLineDash([]);
    }
    if (this.selected === n.id || this.pick === n.id) {
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 9, 0, Math.PI * 2);
      ctx.strokeStyle = this.pick === n.id ? KIND_COLORS.combine : '#3b0d2a';
      ctx.lineWidth = 2.5;
      ctx.stroke();
    }
    if (this.dropTarget === n) {
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 12, 0, Math.PI * 2);
      ctx.strokeStyle = KIND_COLORS.combine;
      ctx.lineWidth = 4;
      ctx.stroke();
    }
    if (this.judging.has(n.id)) {
      const a0 = now / 300;
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 5, a0, a0 + Math.PI * 0.6);
      ctx.strokeStyle = '#f5c542';
      ctx.lineWidth = 3;
      ctx.stroke();
    }
    if (this.busy.has(n.id)) {
      const p = 0.5 + 0.5 * Math.sin(now / 180);
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 4 + p * 8, 0, Math.PI * 2);
      ctx.strokeStyle = `rgba(60,10,40,${0.2 + p * 0.35})`;
      ctx.lineWidth = 2;
      ctx.stroke();
    }

    // Text only when it would be legible on screen.
    if (r * k >= 18) {
      const fs = Math.max(10, Math.min(17, r * 0.24));
      const lines = this.wrap(n, idea.title, fs, r * 1.5, r < 45 ? 3 : 4);
      ctx.fillStyle = TEXT;
      ctx.font = `600 ${fs}px ${FONT}`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      const lh = fs * 1.18;
      const top = n.y - ((lines.length - 1) * lh) / 2 - (scoreSum(idea) != null ? fs * 0.35 : 0);
      lines.forEach((line, i) => ctx.fillText(line, n.x, top + i * lh));

      const sum = scoreSum(idea);
      if (sum != null) {
        const label = String(sum);
        ctx.font = `700 ${fs * 0.78}px ${FONT}`;
        const pw = ctx.measureText(label).width + fs * 0.9, ph = fs * 1.15;
        const py = n.y + r - ph * 0.95;
        ctx.beginPath();
        ctx.roundRect(n.x - pw / 2, py - ph / 2, pw, ph, ph / 2);
        ctx.fillStyle = m ? MEDAL_COLORS[m]! : '#3a2230';
        ctx.fill();
        ctx.fillStyle = m ? '#111' : '#d7dbe3';
        ctx.fillText(label, n.x, py + 0.5);
      }
      if (idea.projectPath) {
        const bx = n.x + r * 0.72, by = n.y - r * 0.72, br = Math.max(9, fs * 0.75);
        ctx.beginPath();
        ctx.arc(bx, by, br, 0, Math.PI * 2);
        ctx.fillStyle = '#e8e3d9';
        ctx.fill();
        ctx.fillStyle = '#111';
        ctx.font = `800 ${br * 0.95}px ${FONT}`;
        ctx.fillText('CC', bx, by + 0.5);
      }
    }
    ctx.globalAlpha = 1;
  }

  wrap(n: GNode, text: string, fs: number, maxW: number, maxLines: number) {
    const key = `${text}|${fs.toFixed(1)}|${maxW.toFixed(0)}`;
    if (n.wrapKey === key) return n.wrapLines!;
    const ctx = this.ctx;
    ctx.font = `600 ${fs}px ${FONT}`;
    const words = text.split(/\s+/);
    const lines: string[] = [];
    let cur = '';
    for (const w of words) {
      const next = cur ? `${cur} ${w}` : w;
      if (ctx.measureText(next).width <= maxW || !cur) cur = next;
      else { lines.push(cur); cur = w; }
    }
    if (cur) lines.push(cur);
    if (lines.length > maxLines) {
      lines.length = maxLines;
      let last = lines[maxLines - 1];
      while (last.length > 1 && ctx.measureText(last + '…').width > maxW) last = last.slice(0, -1);
      lines[maxLines - 1] = last.trimEnd() + '…';
    }
    n.wrapKey = key;
    n.wrapLines = lines;
    return lines;
  }

  // ------------------------------------------------------------ input

  bind() {
    const c = this.c;
    const opts = { signal: this.ac.signal };
    c.addEventListener('pointerdown', (e) => {
      c.setPointerCapture(e.pointerId);
      this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      this.lastUser = performance.now();
      this.camTarget = null;
      if (this.pointers.size === 2) {
        const [a, b] = [...this.pointers.values()];
        this.drag = { type: 'pinch', d: Math.hypot(a.x - b.x, a.y - b.y), k: this.cam.k };
        return;
      }
      const w = this.toWorld(e.clientX, e.clientY);
      const n = this.nodeAt(w.x, w.y);
      if (n) {
        this.drag = { type: 'node', id: n.id, sx: e.clientX, sy: e.clientY, ox: n.x - w.x, oy: n.y - w.y, moved: false };
      } else {
        this.drag = { type: 'pan', sx: e.clientX, sy: e.clientY, cx: this.cam.x, cy: this.cam.y, moved: false };
      }
    }, opts);

    c.addEventListener('pointermove', (e) => {
      if (this.pointers.has(e.pointerId)) this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      const d = this.drag;
      if (d?.type === 'pinch' && this.pointers.size === 2) {
        const [a, b] = [...this.pointers.values()];
        this.cam.k = Math.min(3, Math.max(0.1, d.k * (Math.hypot(a.x - b.x, a.y - b.y) / d.d)));
        this.dirty = true;
        return;
      }
      if (d?.type === 'node') {
        if (!d.moved && Math.hypot(e.clientX - d.sx, e.clientY - d.sy) > 5) {
          d.moved = true;
          if (this.hover != null) {
            this.hover = null; // the hover card must not cover the drop target
            this.h.hover?.(null);
          }
        }
        if (!d.moved) return;
        const w = this.toWorld(e.clientX, e.clientY);
        const n = this.nodes.get(d.id);
        if (!n) return;
        n.fx = w.x + d.ox;
        n.fy = w.y + d.oy;
        this.dropTarget = this.nodeAt(w.x, w.y, d.id);
        this.alpha = Math.max(this.alpha, 0.15);
        this.dirty = true;
        return;
      }
      if (d?.type === 'pan') {
        if (!d.moved && Math.hypot(e.clientX - d.sx, e.clientY - d.sy) > 4) d.moved = true;
        this.cam.x = d.cx - (e.clientX - d.sx) / this.cam.k;
        this.cam.y = d.cy - (e.clientY - d.sy) / this.cam.k;
        this.dirty = true;
        return;
      }
      const w = this.toWorld(e.clientX, e.clientY);
      const n = this.nodeAt(w.x, w.y);
      const id = n?.id ?? null;
      if (id !== this.hover) {
        this.hover = id;
        this.dirty = true;
        c.style.cursor = this.pick ? 'crosshair' : id ? 'pointer' : 'grab';
        this.h.hover?.(id, e);
      }
    }, opts);

    const end = (e: PointerEvent) => {
      this.pointers.delete(e.pointerId);
      const d = this.drag;
      this.drag = null;
      if (!d || d.type === 'pinch') return;
      if (d.type === 'node') {
        const n = this.nodes.get(d.id);
        if (n) { n.fx = n.fy = null; }
        const target = this.dropTarget;
        this.dropTarget = null;
        this.dirty = true;
        if (!d.moved) {
          if (this.pick && this.pick !== d.id) {
            const a = this.pick;
            this.setPick(null);
            this.h.combine?.(a, d.id);
          } else {
            this.h.select?.(d.id);
          }
        } else if (target) {
          this.h.combine?.(d.id, target.id);
        } else {
          this.h.moved?.();
        }
      } else if (d.type === 'pan' && !d.moved) {
        if (this.pick) this.setPick(null);
        this.h.select?.(null);
      }
    };
    c.addEventListener('pointerup', end, opts);
    c.addEventListener('pointercancel', end, opts);
    c.addEventListener('pointerleave', () => {
      if (this.hover != null && !this.drag) {
        this.hover = null;
        this.dirty = true;
        this.h.hover?.(null);
      }
    }, opts);

    c.addEventListener('dblclick', (e) => {
      const w = this.toWorld(e.clientX, e.clientY);
      const n = this.nodeAt(w.x, w.y);
      if (n) this.h.expand?.(n.id);
      else this.h.create?.(w.x, w.y);
    }, opts);

    c.addEventListener(
      'wheel',
      (e: WheelEvent) => {
        e.preventDefault();
        this.lastUser = performance.now();
        this.camTarget = null;
        // Pinch (ctrlKey) and mouse wheels zoom; two-finger trackpad scroll pans.
        const mouseWheel = e.deltaMode !== 0 || (e.deltaX === 0 && Number.isInteger(e.deltaY) && Math.abs(e.deltaY) >= 40);
        if (e.ctrlKey || e.metaKey || mouseWheel) {
          const before = this.toWorld(e.clientX, e.clientY);
          const f = Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0015));
          this.cam.k = Math.min(3, Math.max(0.1, this.cam.k * f));
          const after = this.toWorld(e.clientX, e.clientY);
          this.cam.x += before.x - after.x;
          this.cam.y += before.y - after.y;
        } else {
          this.cam.x += e.deltaX / this.cam.k;
          this.cam.y += e.deltaY / this.cam.k;
        }
        this.dirty = true;
      },
      { passive: false, signal: this.ac.signal },
    );

    // Leads dragged in from the scouting panel.
    c.addEventListener('dragover', (e) => {
      if (e.dataTransfer?.types.includes('text/x-lead')) e.preventDefault();
    }, opts);
    c.addEventListener('drop', (e) => {
      const id = Number(e.dataTransfer?.getData('text/x-lead'));
      if (!id) return;
      e.preventDefault();
      const w = this.toWorld(e.clientX, e.clientY);
      this.h.dropLead?.(id, w.x, w.y);
    }, opts);
  }
}
