import { useEffect, useMemo, useRef, useState } from "react";
import { ActionIcon, Badge, Checkbox, Group, Stack, Text, Tooltip, useComputedColorScheme } from "@mantine/core";
import { IconAdjustments, IconMinus, IconPlus } from "@tabler/icons-react";
import type { DiagramDto, DiagramLane, DiagramNode } from "@/shared/api";
import { useLang } from "@/shared/i18n";

/**
 * B8: BPMN diagram from the designer coordinates of the model JSON.
 * TWX exports contain no waypoints — edges are drawn as straight lines
 * between the (port-based computed) node borders.
 *
 * Usage: zoom via mouse wheel (toward cursor) or buttons, pan by dragging,
 * double-click = reset view. Lane bands are drawn subtly (border + weak
 * fill only) — earlier, semi-transparent lane surfaces overlaid the
 * diagram with a whitish tint.
 */

const EVENT_KINDS = new Set(["startEvent", "endEvent", "boundaryEvent", "intermediateCatchEvent",
  "intermediateThrowEvent", "event-sub-process-start", "messageStartEvent", "timerStartEvent"]);
const GATEWAY_KINDS = new Set(["exclusiveGateway", "parallelGateway", "inclusiveGateway",
  "eventBasedGateway", "complexGateway"]);

function shape(n: DiagramNode): "ellipse" | "diamond" | "rect" {
  const k = n.kind ?? "";
  if (EVENT_KINDS.has(k) || /Event$/.test(k)) return "ellipse";
  if (GATEWAY_KINDS.has(k) || /Gateway$/.test(k)) return "diamond";
  return "rect";
}

/** Lane center per port name (source: linkVisualInfo); otherwise entry/exit point on the rectangle edge. */
function anchor(n: DiagramNode, port: string | undefined, towards: { x: number; y: number }) {
  const p = (port ?? "").toLowerCase();
  const cx = n.x + n.width / 2, cy = n.y + n.height / 2;
  if (p.includes("top")) return { x: cx, y: n.y };
  if (p.includes("bottom")) return { x: cx, y: n.y + n.height };
  if (p.includes("left")) return { x: n.x, y: cy };
  if (p.includes("right")) return { x: n.x + n.width, y: cy };
  // Port unclear: straight line center->center, intersection with rectangle edge
  const dx = towards.x - cx, dy = towards.y - cy;
  if (dx === 0 && dy === 0) return { x: cx, y: cy };
  const hw = n.width / 2, hh = n.height / 2;
  const t = Math.min(
    dx !== 0 ? hw / Math.abs(dx) : Infinity,
    dy !== 0 ? hh / Math.abs(dy) : Infinity
  );
  return { x: cx + dx * t, y: cy + dy * t };
}

function laneFill(l: DiagramLane): string {
  const c = (l.color ?? "").trim().toLowerCase();
  if (!c || c === "none" || c === "white" || c === "#ffffff" || c === "#fff") {
    return "var(--mantine-color-default-border)";
  }
  return l.color ?? "var(--mantine-color-default-border)";
}

function diagramTextColor(fill: string, colorScheme: "light" | "dark"): string {
  const hex = /^#([\da-f]{3}|[\da-f]{6})$/i.exec(fill.trim());
  const rgb = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i.exec(fill.trim());
  let channels: number[] | null = null;
  if (hex) {
    const value = hex[1].length === 3 ? [...hex[1]].map((v) => v + v).join("") : hex[1];
    channels = [0, 2, 4].map((i) => parseInt(value.slice(i, i + 2), 16));
  } else if (rgb) {
    channels = rgb.slice(1, 4).map(Number);
  }
  if (!channels) return "#111311";

  const canvas = colorScheme === "dark" ? [17, 19, 17] : [243, 241, 236];
  const composited = channels.map((value, i) => value * 0.85 + canvas[i] * 0.15);
  const linear = composited.map((value) => {
    const channel = value / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  const luminance = 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
  return luminance > 0.179 ? "#111311" : "#FFFFFF";
}

interface View { x: number; y: number; w: number; h: number }

export default function DiagramSvg({ diagram, hotLocations, onNodeClick }: {
  diagram: DiagramDto;
  /** Finding texts (location) of the object — nodes whose name appears in them turn red. */
  hotLocations?: string[];
  onNodeClick?: (node: DiagramNode) => void;
}) {
  const { tr } = useLang();
  const colorScheme = useComputedColorScheme("light", { getInitialValueInEffect: true });
  const byId = useMemo(() => new Map(diagram.nodes.map((n) => [n.id, n])), [diagram]);
  const [showLanes, setShowLanes] = useState(true);
  const pad = 16;
  const lanes = useMemo(() => diagram.lanes ?? [], [diagram]);

  // Pan/zoom: viewBox state; base = everything visible (fit)
  const base = useMemo<View>(() => ({
    x: diagram.minX - pad, y: diagram.minY - pad,
    w: diagram.width + 2 * pad, h: diagram.height + 2 * pad,
  }), [diagram]);
  const [view, setView] = useState<View>(base);
  const viewRef = useRef(view);
  viewRef.current = view;
  useEffect(() => { setView(base); }, [base]);

  const svgRef = useRef<SVGSVGElement>(null);
  const dragRef = useRef<{ cx: number; cy: number; v: View; moved: boolean } | null>(null);
  const movedRef = useRef(false);

  const zoomLimits = () => ({ min: base.w / 30, max: base.w * 2 });
  const userPoint = (clientX: number, clientY: number): { x: number; y: number; s: number } => {
    const el = svgRef.current;
    const v = viewRef.current;
    if (!el) return { x: v.x, y: v.y, s: 1 };
    const r = el.getBoundingClientRect();
    // preserveAspectRatio="xMidYMid meet": uniform scale + centering
    const s = Math.max(1e-6, Math.min(r.width / v.w, r.height / v.h));
    return { x: v.x + (clientX - r.left - (r.width - v.w * s) / 2) / s,
             y: v.y + (clientY - r.top - (r.height - v.h * s) / 2) / s, s };
  };
  const zoomAt = (factor: number, ux: number, uy: number) => {
    const v = viewRef.current;
    const { min, max } = zoomLimits();
    const nw = Math.min(max, Math.max(min, v.w * factor));
    const ratio = nw / v.w;
    setView({ x: ux - (ux - v.x) * ratio, y: uy - (uy - v.y) * ratio, w: nw, h: v.h * ratio });
  };

  // Wheel detection as a native listener (React onWheel is passive -> preventDefault has no effect)
  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const p = userPoint(e.clientX, e.clientY);
      zoomAt(e.deltaY > 0 ? 1.18 : 1 / 1.18, p.x, p.y);
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [base]);

  const onPointerDown = (e: React.PointerEvent<SVGSVGElement>) => {
    if (e.button !== 0) return;
    dragRef.current = { cx: e.clientX, cy: e.clientY, v: viewRef.current, moved: false };
    movedRef.current = false;
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const onPointerMove = (e: React.PointerEvent<SVGSVGElement>) => {
    const d = dragRef.current;
    if (!d) return;
    const el = svgRef.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const s = Math.max(1e-6, Math.min(r.width / d.v.w, r.height / d.v.h));
    const dx = (e.clientX - d.cx) / s, dy = (e.clientY - d.cy) / s;
    if (!d.moved && Math.hypot(dx * s, dy * s) < 4) return; // click, not a drag
    d.moved = true;
    movedRef.current = true;
    setView({ ...d.v, x: d.v.x - dx, y: d.v.y - dy });
  };
  const onPointerUp = (e: React.PointerEvent<SVGSVGElement>) => {
    dragRef.current = null;
    try { e.currentTarget.releasePointerCapture(e.pointerId); } catch { /* already released during blur */ }
  };

  // Lane bands: identical bounding box (parent/child lane dialogs) only once,
  // large first, so small bands stay on top.
  const laneShapes = useMemo(() => {
    const seen = new Set<string>();
    const out: DiagramLane[] = [];
    for (const l of [...lanes].sort((a, b) => b.width * b.height - a.width * a.height)) {
      const k = `${Math.round(l.x)}|${Math.round(l.y)}|${Math.round(l.width)}|${Math.round(l.height)}`;
      if (seen.has(k)) continue;
      seen.add(k);
      out.push(l);
    }
    return out;
  }, [lanes]);

  if (diagram.nodes.length === 0) {
    return <Text c="dimmed" size="sm">{tr("Kein Diagramm: Objekt enthält keine Designer-Koordinaten (nur Prozesse/BPDs mit Modell-JSON haben ein Diagramm).", "No diagram: the object contains no designer coordinates (only processes/BPDs with model JSON have a diagram).")}</Text>;
  }

  const isHot = (n: DiagramNode) => {
    const nm = n.name;
    return !!nm && !!hotLocations?.some((h) => h.includes(nm));
  };
  const stroke = (n: DiagramNode) =>
    isHot(n) ? "var(--twx-health-bad)" : "var(--twx-diagram-edge)";
  const zoomPct = Math.round((base.w / view.w) * 100);
  const nodeClick = (n: DiagramNode) => {
    if (movedRef.current || !onNodeClick) return; // not a pan gesture
    onNodeClick(n);
  };

  return (
    <Stack gap="xs">
      <Group gap="xs" justify="apart">
        <Group gap="xs">
          <Badge size="xs" color="gray">{tr("□ Aktivität", "□ Activity")}</Badge>
          <Badge size="xs" color="gray">{tr("◯ Ereignis", "◯ Event")}</Badge>
          <Badge size="xs" color="gray">◇ Gateway</Badge>
          {lanes.length > 0 && (
            <Checkbox
              size="xs"
              label={`Lanes (${lanes.length})`}
              checked={showLanes}
              onChange={(e) => setShowLanes(e.currentTarget.checked)}
            />
          )}
          {hotLocations && hotLocations.length > 0 && (
            <Badge size="xs" color="red">{tr("rot = Fund an diesem Knoten", "red = finding on this node")}</Badge>
          )}
        </Group>
        <Group gap={4}>
          <Text size="xs" c="dimmed">{zoomPct}%</Text>
          <Tooltip label={tr("Kleiner", "Zoom out")}>
            <ActionIcon size="xs" variant="light" onClick={() => zoomAt(1 / 1.35, view.x + view.w / 2, view.y + view.h / 2)}>
              <IconMinus size={14} />
            </ActionIcon>
          </Tooltip>
          <Tooltip label={tr("Grösser", "Zoom in")}>
            <ActionIcon size="xs" variant="light" onClick={() => zoomAt(1.35, view.x + view.w / 2, view.y + view.h / 2)}>
              <IconPlus size={14} />
            </ActionIcon>
          </Tooltip>
          <Tooltip label={tr("Ganze Ansicht", "Fit to view")}>
            <ActionIcon size="xs" variant="light" onClick={() => setView(base)}>
              <IconAdjustments size={14} />
            </ActionIcon>
          </Tooltip>
        </Group>
      </Group>
      <div style={{ border: "1px solid var(--mantine-color-default-border)", borderRadius: 4, background: "var(--mantine-color-body)", height: "62vh" }}>
        <svg
          ref={svgRef}
          viewBox={`${view.x} ${view.y} ${view.w} ${view.h}`}
          width="100%"
          height="100%"
          preserveAspectRatio="xMidYMid meet"
          style={{ display: "block", fontFamily: "inherit", cursor: "grab", touchAction: "none", userSelect: "none" }}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerLeave={onPointerUp}
          onDoubleClick={() => setView(base)}
        >
          <defs>
            <marker id="dgm-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--mantine-color-dimmed)" />
            </marker>
          </defs>
          {/* Lane bands subtle: dashed border, fill nearly transparent —
              thick semi-transparent surfaces used to overlay the model. */}
          {showLanes && laneShapes.map((l, i) => (
            <g key={l.id ?? `lane-${i}`}>
              <rect x={l.x} y={l.y} width={l.width} height={l.height}
                fill={laneFill(l)} fillOpacity={0.07}
                stroke="var(--mantine-color-default-border)" strokeWidth={1} strokeDasharray="6 3" />
              <text x={l.x + 6} y={l.y + 14} fontSize={11} fontWeight={700}
                fill="var(--mantine-color-dimmed)" style={{ pointerEvents: "none" }}>
                {l.name ?? tr("(Bahn)", "(lane)")}{l.systemLane ? " · System" : ""}
              </text>
            </g>
          ))}
          {diagram.edges.map((e) => {
            const s = byId.get(e.source), t = byId.get(e.target);
            if (!s || !t) return null;
            const sc = { x: s.x + s.width / 2, y: s.y + s.height / 2 };
            const tc = { x: t.x + t.width / 2, y: t.y + t.height / 2 };
            const a = anchor(s, e.sourcePort, tc);
            const b = anchor(t, e.targetPort, sc);
            const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
            const label = e.condition || e.name;
            return (
              <g key={e.id} style={{ pointerEvents: "none" }}>
                <line x1={a.x} y1={a.y} x2={b.x} y2={b.y}
                  stroke="var(--twx-diagram-edge)" strokeWidth={1} markerEnd="url(#dgm-arrow)" />
                {label && (
                  <text x={mx} y={my - 3} fontSize={9} textAnchor="middle"
                    fill="var(--mantine-color-dimmed)">
                    {label.length > 40 ? label.slice(0, 40) + "…" : label}
                  </text>
                )}
              </g>
            );
          })}
          {diagram.nodes.map((n) => {
            const shp = shape(n);
            const hot = isHot(n);
            const fillColor = n.color && n.color !== "none" ? n.color : "#e7f5ff";
            const common = {
              stroke: stroke(n),
              strokeWidth: hot ? 2.5 : 1,
              fill: fillColor,
              fillOpacity: 0.85,
              style: { cursor: onNodeClick ? "pointer" : undefined } as React.CSSProperties,
              onClick: () => nodeClick(n),
            };
            const name = n.name ?? "";
            const maxChars = Math.max(4, Math.floor(n.width / 6.2));
            const lines: string[] = [];
            for (let i = 0; i < name.length && lines.length < 3; i += maxChars) {
              lines.push(name.slice(i, i + maxChars) + (i + maxChars < name.length && lines.length === 2 ? "…" : ""));
            }
            return (
              <g key={n.id}>
                {shp === "ellipse" && (
                  <ellipse cx={n.x + n.width / 2} cy={n.y + n.height / 2}
                    rx={n.width / 2} ry={n.height / 2} {...common} />
                )}
                {shp === "diamond" && (
                  <polygon
                    points={`${n.x + n.width / 2},${n.y} ${n.x + n.width},${n.y + n.height / 2} ${n.x + n.width / 2},${n.y + n.height} ${n.x},${n.y + n.height / 2}`}
                    {...common} />
                )}
                {shp === "rect" && (
                  <rect x={n.x} y={n.y} width={n.width} height={n.height} rx={6} {...common} />
                )}
                <text x={n.x + n.width / 2} y={n.y + n.height / 2 - (lines.length - 1) * 5.5}
                  fontSize={10} textAnchor="middle" fill={diagramTextColor(fillColor, colorScheme)}
                  style={{ pointerEvents: "none" }}>
                  {lines.map((l, i) => (
                    <tspan key={i} x={n.x + n.width / 2} dy={i === 0 ? 0 : 11}>{l}</tspan>
                  ))}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      <Text size="xs" c="dimmed">
        {tr("Mausrad = Zoom · Ziehen = Verschieben · Doppelklick = Ganze Ansicht", "Mouse wheel = zoom · drag = pan · double-click = fit")}
      </Text>
    </Stack>
  );
}
