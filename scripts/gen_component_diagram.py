# -*- coding: utf-8 -*-
"""生成组件引用关系图 SVG（无外部依赖）。"""
import xml.sax.saxutils as su

W, H = 1120, 760
groups = {
    "Core 8080":      (30, 55, 340, 470),
    "Backend 9090":   (700, 55, 360, 430),
}
# node: (x, y, w, h, label)
nodes = {
    "BC":   (48, 80, 300, 38, "BankController.chat()"),
    "DR":   (48, 128, 300, 38, "DomainRouter.route()  L0 span"),
    "AS":   (48, 176, 300, 38, "L1/L2 DomainService / SubGraph"),
    "OCM":  (48, 224, 300, 38, "ObsChatModel (ChatModel)"),
    "ASC":  (48, 272, 300, 38, "AgentSpanContext (held-span)"),
    "OM":   (48, 344, 300, 38, "ObservabilityMetrics (agent.*)"),
    "MR":   (48, 392, 300, 38, "MetricsRegistry (deepflux.*)"),
    "SB":   (48, 464, 300, 38, "SessionBridge.reportSession()"),
    "AGENT":(400, 150, 250, 44, "OTel Java Agent -javaagent"),
    "COLL": (400, 360, 250, 44, "Collector 4318"),
    "RC":   (718, 80, 320, 38, "OtlpV1Receiver /v1/*"),
    "PS":   (718, 128, 320, 38, "OtlpParserService"),
    "SD":   (718, 176, 320, 38, "SpanDurationNormalizer"),
    "SRV":  (718, 224, 320, 38, "SessionService"),
    "RED":  (718, 272, 320, 38, "Redis hot layer"),
    "H2":   (718, 320, 320, 38, "H2 warm layer"),
    "FE":   (718, 500, 320, 44, "Frontend 3000"),
}
# edges: (src, dst, label, dotted)
edges = [
    ("BC", "DR", "", False),
    ("DR", "AS", "", False),
    ("DR", "ASC", "setWithHeldSpan", True),
    ("AS", "OCM", "ChatClient", False),
    ("OCM", "AGENT", "llm.* + span", False),
    ("OCM", "ASC", "attach/commit", False),
    ("OM", "MR", "", False),
    ("OM", "AGENT", "agent.*", False),
    ("MR", "AGENT", "deepflux.*", False),
    ("BC", "SB", "doOnTerminate", False),
    ("SB", "SRV", "POST /api/v1/sessions", False),
    ("AGENT", "COLL", "OTLP", False),
    ("COLL", "RC", "OTLP json", False),
    ("RC", "PS", "", False),
    ("PS", "SD", "", False),
    ("PS", "H2", "spans/metrics_agg", False),
    ("PS", "RED", "hot keys", False),
    ("SRV", "H2", "sessions/turns", False),
    ("FE", "RC", "/api", False),
]


def esc(s):
    return su.escape(s)


def center(n):
    x, y, w, h, _ = nodes[n]
    return x + w / 2, y + h / 2


def conn(s, d):
    """exit/entry points based on dominant axis."""
    sx, sy, sw, sh, _ = nodes[s]
    dx0, dy0, dw, dh, _ = nodes[d]
    scx, scy = sx + sw / 2, sy + sh / 2
    dcx, dcy = dx0 + dw / 2, dy0 + dh / 2
    dx, dy = dcx - scx, dcy - scy
    if abs(dx) >= abs(dy):
        if dx >= 0:
            return (sx + sw, scy), (dx0, dcy)   # right -> left
        else:
            return (sx, scy), (dx0 + dw, dcy)     # left -> right
    else:
        if dy >= 0:
            return (scx, sy + sh), (dcx, dy0)     # bottom -> top
        else:
            return (scx, sy), (dcx, dy0 + dh)      # top -> bottom


parts = []
parts.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
             f'viewBox="0 0 {W} {H}" font-family="Segoe UI, Arial, sans-serif" font-size="13">')
parts.append('<defs>'
             '<marker id="arw" markerWidth="10" markerHeight="10" refX="8" refY="3" '
             'orient="auto" markerUnits="strokeWidth">'
             '<path d="M0,0 L8,3 L0,6 Z" fill="#555"/></marker>'
             '<marker id="arwdot" markerWidth="10" markerHeight="10" refX="8" refY="3" '
             'orient="auto" markerUnits="strokeWidth">'
             '<path d="M0,0 L8,3 L0,6 Z" fill="#c0392b"/></marker>'
             '</defs>')
parts.append(f'<rect x="0" y="0" width="{W}" height="{H}" fill="#ffffff"/>')

# groups
gcolor = {"Core 8080": "#eaf2fb", "Backend 9090": "#fdeef0"}
for name, (x, y, w, h) in groups.items():
    parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" '
                 f'fill="{gcolor.get(name, "#f5f5f5")}" stroke="#9bbce0" stroke-width="1.5"/>')
    parts.append(f'<text x="{x+12}" y="{y+22}" font-weight="bold" fill="#234">{esc(name)}</text>')

# edges
for s, d, label, dotted in edges:
    (x1, y1), (x2, y2) = conn(s, d)
    dash = 'stroke-dasharray="6 4" ' if dotted else ''
    col = '#c0392b' if dotted else '#555'
    mk = 'arwdot' if dotted else 'arw'
    parts.append(f'<line x1="{x1:.0f}" y1="{y1:.0f}" x2="{x2:.0f}" y2="{y2:.0f}" '
                 f'{dash}stroke="{col}" stroke-width="1.5" marker-end="url(#{mk})"/>')
    if label:
        mx, my = (x1 + x2) / 2, (y1 + y2) / 2
        parts.append(f'<rect x="{mx-46:.0f}" y="{my-9:.0f}" width="{len(label)*7+12:.0f}" '
                     f'height="18" rx="4" fill="#fff" stroke="#ddd"/>')
        parts.append(f'<text x="{mx:.0f}" y="{my+4:.0f}" text-anchor="middle" '
                     f'font-size="11" fill="{col}">{esc(label)}</text>')

# nodes
for n, (x, y, w, h, label) in nodes.items():
    fill = "#fff" if n not in ("AGENT", "COLL", "FE") else "#fff7e6"
    stroke = "#888" if n not in ("AGENT", "COLL", "FE") else "#d9a441"
    parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="6" '
                 f'fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>')
    parts.append(f'<text x="{x+w/2:.0f}" y="{y+h/2+4:.0f}" text-anchor="middle" '
                 f'fill="#222">{esc(label)}</text>')

# legend
parts.append('<line x1="40" y1="720" x2="70" y2="720" stroke="#555" stroke-width="1.5" '
             'marker-end="url(#arw)"/>')
parts.append('<text x="78" y="724" fill="#333">调用 / 上报</text>')
parts.append('<line x1="180" y1="720" x2="210" y2="720" stroke="#c0392b" stroke-width="1.5" '
             'stroke-dasharray="6 4" marker-end="url(#arwdot)"/>')
parts.append('<text x="218" y="724" fill="#c0392b">held-span 托管回写（虚线）</text>')

parts.append('</svg>')

with open("docs/component-reference.svg", "w", encoding="utf-8") as f:
    f.write("\n".join(parts))
print("written docs/component-reference.svg")
