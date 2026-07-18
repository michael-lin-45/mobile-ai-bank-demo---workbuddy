const pptxgen = require("pptxgenjs");
const path = require("path");
const fs = require("fs");

const ASSETS = path.resolve(__dirname, "assets");
const OUTPUT = path.resolve(__dirname, "AI可观测设计方案.pptx");

// --- Color Palette (Banking Professional) ---
const C = {
  navy:       "0C1445",
  darkBlue:   "1A2A5E",
  midBlue:    "2E4A7A",
  gold:       "C9A84C",
  lightGold:  "E8D48B",
  white:      "FFFFFF",
  offWhite:   "F4F6F9",
  textDark:   "1E293B",
  textBody:   "475569",
  textMuted:  "94A3B8",
  redAccent:  "DC2626",
  greenOk:    "16A34A",
  border:     "CBD5E1",
  cardBg:     "FFFFFF",
  tableHead:  "1A2A5E",
  tableStripe:"F1F5F9",
};

// --- Helper functions ---
const readB64 = (filename) => {
  const f = path.join(ASSETS, filename);
  const buf = fs.readFileSync(f);
  return `image/png;base64,${buf.toString("base64")}`;
};

// Preload all assets
const IMG = {
  v20_overview:    readB64("v20_overview.png"),
  v20_session:     readB64("v20_session.png"),
  v20_trace:       readB64("v20_trace.png"),
  v20_insight:     readB64("v20_insight.png"),
  v20_intelligence:readB64("v20_intelligence.png"),
  v20_logs:        readB64("v20_logs.png"),
  v20_alerts:      readB64("v20_alerts.png"),
  v16_overview:    readB64("v16_overview.png"),
};

// Factory to avoid object reuse bug
const mkShadow = () => ({ type: "outer", color: "000000", blur: 4, offset: 1, angle: 135, opacity: 0.10 });

function addFooter(slide, pageNum) {
  slide.addText([
    { text: "AI可观测设计方案", options: { fontSize: 8, color: C.textMuted } },
  ], { x: 0.5, y: 5.15, w: 4, h: 0.3, margin: 0 });
  slide.addText([
    { text: `${pageNum}`, options: { fontSize: 8, color: C.textMuted, align: "right" } },
  ], { x: 9.0, y: 5.15, w: 0.5, h: 0.3, align: "right", margin: 0 });
}

function addPageTitle(slide, title, subtitle) {
  // Gold accent bar
  slide.addShape("rect", { x: 0.5, y: 0.35, w: 0.06, h: 0.4, fill: { color: C.gold } });
  slide.addText(title, {
    x: 0.72, y: 0.28, w: 8.5, h: 0.45, fontSize: 22, fontFace: "Calibri",
    color: C.navy, bold: true, margin: 0,
  });
  if (subtitle) {
    slide.addText(subtitle, {
      x: 0.72, y: 0.72, w: 8.5, h: 0.25, fontSize: 10, fontFace: "Calibri",
      color: C.textMuted, margin: 0,
    });
  }
}

// --- Create Presentation ---
const pres = new pptxgen();
pres.layout = "LAYOUT_16x9";
pres.author = "AI可观测团队";
pres.title = "AI可观测设计方案";

// ==================== P1: Cover ====================
(() => {
  const s = pres.addSlide();
  s.background = { color: C.navy };

  // Top gold line
  s.addShape("rect", { x: 0.5, y: 0.8, w: 3.0, h: 0.04, fill: { color: C.gold } });

  // Main title
  s.addText("AI可观测设计方案", {
    x: 0.5, y: 1.1, w: 8, h: 0.8, fontSize: 38, fontFace: "Calibri",
    color: C.white, bold: true, margin: 0,
  });
  // Subtitle
  s.addText("移动银行AI智能助手 · 全链路可观测性平台", {
    x: 0.5, y: 1.9, w: 8, h: 0.4, fontSize: 16, fontFace: "Calibri",
    color: C.lightGold, margin: 0,
  });

  // Divider
  s.addShape("rect", { x: 0.5, y: 2.55, w: 9.0, h: 0.005, fill: { color: C.gold, transparency: 60 } });

  // Features row
  const features = [
    { icon: "●", text: "指标监控" },
    { icon: "●", text: "会话回放" },
    { icon: "●", text: "链路追踪" },
    { icon: "●", text: "AI洞察" },
    { icon: "●", text: "日志告警" },
  ];
  features.forEach((f, i) => {
    s.addText([
      { text: `${f.icon} `, options: { color: C.gold, fontSize: 11 } },
      { text: f.text, options: { color: C.white, fontSize: 12, fontFace: "Calibri" } },
    ], { x: 0.5 + i * 1.82, y: 2.8, w: 1.7, h: 0.3, margin: 0 });
  });

  // Bottom info
  s.addText("面向银行金融行业的AI应用观测 · 方案汇报", {
    x: 0.5, y: 4.5, w: 8, h: 0.3, fontSize: 11, fontFace: "Calibri",
    color: C.textMuted, margin: 0,
  });

  // Right-side decorative rectangle
  s.addShape("rect", { x: 8.2, y: 0, w: 1.8, h: 5.625, fill: { color: C.darkBlue } });
  s.addShape("rect", { x: 8.2, y: 0, w: 0.06, h: 5.625, fill: { color: C.gold } });
})();

// ==================== P2: Necessity ====================
(() => {
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  addPageTitle(s, "AI可观测的必要性", "传统APM已无法满足AI应用的可观测需求");
  addFooter(s, 2);

  // Left card - Traditional APM Blind Spots
  s.addShape("rect", { x: 0.5, y: 1.15, w: 4.2, h: 3.4, fill: { color: C.white }, shadow: mkShadow() });
  s.addShape("rect", { x: 0.5, y: 1.15, w: 4.2, h: 0.06, fill: { color: C.redAccent } });

  s.addText("传统APM的三大盲区", {
    x: 0.75, y: 1.3, w: 3.7, h: 0.35, fontSize: 14, fontFace: "Calibri",
    color: C.redAccent, bold: true, margin: 0,
  });

  const blindSpots = [
    { title: "LLM调用黑盒", desc: "无法追踪模型推理耗时、Token消耗、提示词内容" },
    { title: "Agent决策不可见", desc: "多步推理链路断裂，中间过程无法回溯" },
    { title: "用户体验盲区", desc: "缺乏会话级观测，无法重现用户真实交互路径" },
  ];
  blindSpots.forEach((b, i) => {
    s.addShape("rect", { x: 0.75, y: 1.85 + i * 0.85, w: 0.06, h: 0.6, fill: { color: C.redAccent, transparency: 40 } });
    s.addText(b.title, {
      x: 1.0, y: 1.82 + i * 0.85, w: 3.4, h: 0.25, fontSize: 11, fontFace: "Calibri",
      color: C.textDark, bold: true, margin: 0,
    });
    s.addText(b.desc, {
      x: 1.0, y: 2.07 + i * 0.85, w: 3.4, h: 0.35, fontSize: 9, fontFace: "Calibri",
      color: C.textBody, margin: 0,
    });
  });

  // Right card - Bank Requirements
  s.addShape("rect", { x: 5.3, y: 1.15, w: 4.2, h: 3.4, fill: { color: C.white }, shadow: mkShadow() });
  s.addShape("rect", { x: 5.3, y: 1.15, w: 4.2, h: 0.06, fill: { color: C.greenOk } });

  s.addText("银行AI可观测四大核心需求", {
    x: 5.55, y: 1.3, w: 3.7, h: 0.35, fontSize: 14, fontFace: "Calibri",
    color: C.greenOk, bold: true, margin: 0,
  });

  const needs = [
    { title: "全链路可见", desc: "从用户请求到模型响应，每一跳都可追踪" },
    { title: "合规审计", desc: "交互记录完整留存，满足金融监管追溯要求" },
    { title: "智能告警", desc: "异常检测 & 根因分析，不等用户投诉先发现问题" },
    { title: "性能优化", desc: "Token消耗、延迟瓶颈实时可见，驱动降本增效" },
  ];
  needs.forEach((n, i) => {
    s.addShape("rect", { x: 5.55, y: 1.85 + i * 0.65, w: 0.06, h: 0.48, fill: { color: C.greenOk, transparency: 40 } });
    s.addText(n.title, {
      x: 5.8, y: 1.82 + i * 0.65, w: 3.4, h: 0.22, fontSize: 11, fontFace: "Calibri",
      color: C.textDark, bold: true, margin: 0,
    });
    s.addText(n.desc, {
      x: 5.8, y: 2.04 + i * 0.65, w: 3.4, h: 0.30, fontSize: 9, fontFace: "Calibri",
      color: C.textBody, margin: 0,
    });
  });

  // Bottom comparison table
  const tblRows = [
    [
      { text: "对比维度", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 10, align: "center" } },
      { text: "传统APM", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 10, align: "center" } },
      { text: "AI可观测平台", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 10, align: "center" } },
    ],
    [
      { text: "观测粒度", options: { fontSize: 9, align: "center" } },
      { text: "服务级 (HTTP/gRPC)", options: { fontSize: 9, align: "center", color: C.textBody } },
      { text: "会话级 + 轮次级 + Span级", options: { fontSize: 9, align: "center", color: C.greenOk, bold: true } },
    ],
    [
      { text: "LLM可见性", options: { fontSize: 9, align: "center" } },
      { text: "无", options: { fontSize: 9, align: "center", color: C.redAccent } },
      { text: "Token/延迟/Prompt全量记录", options: { fontSize: 9, align: "center", color: C.greenOk, bold: true } },
    ],
    [
      { text: "回放能力", options: { fontSize: 9, align: "center" } },
      { text: "不支持", options: { fontSize: 9, align: "center", color: C.redAccent } },
      { text: "逐轮会话回放 & 重放", options: { fontSize: 9, align: "center", color: C.greenOk, bold: true } },
    ],
    [
      { text: "根因分析", options: { fontSize: 9, align: "center" } },
      { text: "手动排查", options: { fontSize: 9, align: "center", color: C.redAccent } },
      { text: "AI驱动的智能洞察", options: { fontSize: 9, align: "center", color: C.greenOk, bold: true } },
    ],
  ];
  s.addTable(tblRows, {
    x: 0.5, y: 4.65, w: 9.0,
    colW: [2, 3.5, 3.5],
    border: { pt: 0.5, color: C.border },
    rowH: [0.28, 0.28, 0.28, 0.28, 0.28],
  });
})();

// ==================== P3: Architecture ====================
(() => {
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  addPageTitle(s, "AI可观测整体架构", "端到端数据流：埋点 → 采集 → 处理 → 存储 → 呈现");
  addFooter(s, 3);

  // 5 layer boxes - horizontally arranged in two rows
  const layers = [
    { x: 0.5,  y: 1.4, label: "业务埋点层", items: ["Core服务OTel SDK", "javaagent自动注入", "Span/指标/日志导出"] },
    { x: 2.5,  y: 1.4, label: "数据采集层", items: ["OTel Collector", "OTLP协议接收", "批量压缩&转发"] },
    { x: 5.5,  y: 1.4, label: "数据处理层", items: ["Backend API服务", "Span解析&关联", "汇聚持久化"] },
    { x: 0.5,  y: 3.3, label: "数据存储层", items: ["H2 / PostgreSQL", "时序指标库", "日志: Loki"] },
    { x: 5.0,  y: 3.3, label: "前端呈现层", items: ["总览大屏 / 指标看板", "会话回放 / Trace链路", "AI洞察 / 日志告警"] },
  ];

  layers.forEach((l) => {
    // Card background
    s.addShape("rect", { x: l.x, y: l.y, w: (l.x === 0.5 && l.y === 3.3) ? 4.3 : 4.3, h: 1.5, fill: { color: C.white }, shadow: mkShadow() });
    // Top accent
    s.addShape("rect", { x: l.x, y: l.y, w: (l.x === 0.5 && l.y === 3.3) ? 4.3 : 4.3, h: 0.04, fill: { color: C.midBlue } });
    // Layer label
    s.addText(l.label, {
      x: l.x + 0.15, y: l.y + 0.1, w: (l.x === 0.5 && l.y === 3.3) ? 4.0 : 4.0, h: 0.25,
      fontSize: 11, fontFace: "Calibri", color: C.midBlue, bold: true, margin: 0,
    });
    // Items
    l.items.forEach((it, idx) => {
      s.addText([
        { text: `  ${it}`, options: { fontSize: 9, color: C.textBody } },
      ], {
        x: l.x + 0.15, y: l.y + 0.44 + idx * 0.28, w: 4.0, h: 0.25,
        fontFace: "Calibri", margin: 0,
      });
    });
  });

  // Arrows between layers (row 1: 1→2→3; row 1→row 2: 3→4; row 2: 4→5)
  const arrows = [
    { x: 2.35, y: 2.1, w: 0.2 }, // 1→2
    { x: 4.8,  y: 2.1, w: 0.2 }, // 2→3
    { x: 2.85, y: 2.95, w: 1.2 }, // 3→4 (down)
    { x: 4.8,  y: 4.0, w: 0.2 }, // 4→5
  ];

  // Just use simple arrow text
  s.addText("→", { x: 2.0, y: 2.0, w: 0.4, h: 0.3, fontSize: 18, color: C.gold, align: "center", margin: 0 });
  s.addText("→", { x: 4.65, y: 2.0, w: 0.4, h: 0.3, fontSize: 18, color: C.gold, align: "center", margin: 0 });
  s.addText("↓", { x: 2.65, y: 2.75, w: 0.3, h: 0.3, fontSize: 18, color: C.gold, align: "center", margin: 0 });
  s.addText("→", { x: 4.5, y: 3.9, w: 0.4, h: 0.3, fontSize: 18, color: C.gold, align: "center", margin: 0 });

  // Bottom integration note
  s.addText("标准技术栈：Spring Boot 3 + Spring AI + OpenTelemetry JavaAgent + OTLP Collector + Loki + Grafana", {
    x: 0.5, y: 5.0, w: 9.0, h: 0.2, fontSize: 8, fontFace: "Calibri", color: C.textMuted, margin: 0,
  });
})();

// ==================== P4: Metrics ====================
(() => {
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  addPageTitle(s, "观测指标体系", "五大模块分类，覆盖性能、质量、业务全维度");
  addFooter(s, 4);

  // Left: Metrics table
  const tblRows = [
    [
      { text: "模块", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 9, align: "center" } },
      { text: "指标名称", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 9, align: "center" } },
      { text: "说明", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 9, align: "center" } },
    ],
    [
      { text: "A.性能", options: { fontSize: 8, align: "center", bold: true } },
      { text: "TTFT / TTAT / E2E延迟", options: { fontSize: 8, align: "center", color: C.textBody } },
      { text: "首Token / 总耗时 / 端到端", options: { fontSize: 8, align: "center", color: C.textBody } },
    ],
    [
      { text: "B.质量", options: { fontSize: 8, align: "center", bold: true } },
      { text: "成功率 / 意图识别准确率", options: { fontSize: 8, align: "center", color: C.textBody } },
      { text: "回复质量、Routing正确率", options: { fontSize: 8, align: "center", color: C.textBody } },
    ],
    [
      { text: "C.成本", options: { fontSize: 8, align: "center", bold: true } },
      { text: "Token消耗 / 调用次数", options: { fontSize: 8, align: "center", color: C.textBody } },
      { text: "输入/输出Token、模型成本", options: { fontSize: 8, align: "center", color: C.textBody } },
    ],
    [
      { text: "D.业务", options: { fontSize: 8, align: "center", bold: true } },
      { text: "会话数/用户数/意图分布", options: { fontSize: 8, align: "center", color: C.textBody } },
      { text: "活跃度、业务转化趋势", options: { fontSize: 8, align: "center", color: C.textBody } },
    ],
    [
      { text: "E.安全", options: { fontSize: 8, align: "center", bold: true } },
      { text: "异常频率 / 敏感词命中", options: { fontSize: 8, align: "center", color: C.textBody } },
      { text: "注入攻击、信息泄露预警", options: { fontSize: 8, align: "center", color: C.textBody } },
    ],
  ];
  s.addTable(tblRows, {
    x: 0.5, y: 1.2, w: 4.5,
    colW: [0.9, 1.7, 1.9],
    border: { pt: 0.5, color: C.border },
    rowH: [0.28, 0.38, 0.38, 0.38, 0.38, 0.38],
  });

  // Right: Screenshot
  s.addText("UI界面截图 — 总览大屏", {
    x: 5.35, y: 1.15, w: 4.3, h: 0.25, fontSize: 9, color: C.textMuted, align: "center", margin: 0,
  });
  s.addImage({ data: IMG.v20_overview, x: 5.35, y: 1.45, w: 4.15, h: 3.0, sizing: { type: "contain", w: 4.15, h: 3.0 } });

  // Key takeaway
  s.addText("💡 五大模块覆盖AI应用全生命周期，从性能诊断到业务决策，一个平台全部搞定", {
    x: 0.5, y: 5.0, w: 9.0, h: 0.2, fontSize: 9, fontFace: "Calibri", color: C.navy, margin: 0,
  });
})();

// ==================== P5: Session & Trace ====================
(() => {
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  addPageTitle(s, "会话回放 & 链路追踪", "逐轮回溯用户交互 + 端到端Trace瀑布图");
  addFooter(s, 5);

  // Intro text
  s.addText("会话回放完整记录用户每一轮对话的输入、Agent响应、意图路由全过程；链路追踪以L0→L1→L2瀑布图展示请求在各层的耗时分布，快速定位瓶颈。", {
    x: 0.5, y: 1.15, w: 9.0, h: 0.35, fontSize: 10, fontFace: "Calibri", color: C.textBody, margin: 0,
  });

  // Session screenshot (left)
  s.addText("会话回放界面", {
    x: 0.5, y: 1.6, w: 4.3, h: 0.2, fontSize: 9, color: C.textMuted, align: "center", margin: 0,
  });
  s.addImage({ data: IMG.v20_session, x: 0.5, y: 1.82, w: 4.3, h: 2.2, sizing: { type: "contain", w: 4.3, h: 2.2 } });

  // Trace screenshot (right)
  s.addText("链路追踪界面", {
    x: 5.2, y: 1.6, w: 4.3, h: 0.2, fontSize: 9, color: C.textMuted, align: "center", margin: 0,
  });
  s.addImage({ data: IMG.v20_trace, x: 5.2, y: 1.82, w: 4.3, h: 2.2, sizing: { type: "contain", w: 4.3, h: 2.2 } });

  // Bottom feature highlights
  const features = [
    { title: "逐轮回放", desc: "支持按sessionId查询，完整还原用户多轮对话上下文" },
    { title: "L0/L1/L2分级", desc: "DomainRouter→Agent Pipeline→LLM Call 三级Span瀑布展示" },
    { title: "三件套联动", desc: "会话回放 ↔ 链路追踪 ↔ 日志详情无缝跳转" },
  ];
  features.forEach((f, i) => {
    s.addShape("rect", { x: 0.5 + i * 3.1, y: 4.2, w: 2.9, h: 0.85, fill: { color: C.white }, shadow: mkShadow() });
    s.addShape("rect", { x: 0.5 + i * 3.1, y: 4.2, w: 0.04, h: 0.85, fill: { color: C.gold } });
    s.addText(f.title, {
      x: 0.7 + i * 3.1, y: 4.25, w: 2.5, h: 0.22, fontSize: 10, fontFace: "Calibri",
      color: C.navy, bold: true, margin: 0,
    });
    s.addText(f.desc, {
      x: 0.7 + i * 3.1, y: 4.50, w: 2.5, h: 0.45, fontSize: 9, fontFace: "Calibri",
      color: C.textBody, margin: 0,
    });
  });
})();

// ==================== P6: AI Insight ====================
(() => {
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  addPageTitle(s, "AI洞察 & 智能洞察", "从表面指标到深层根因，AI驱动的问题诊断与对策建议");
  addFooter(s, 6);

  // Left content area - 3 diagnostic domains
  s.addText("三大诊断域", {
    x: 0.5, y: 1.15, w: 4.0, h: 0.3, fontSize: 12, fontFace: "Calibri",
    color: C.navy, bold: true, margin: 0,
  });

  const domains = [
    { title: "性能诊断", desc: "TTFT飙升？Token消耗异常？自动识别最长Span，定位瓶颈节点", color: C.redAccent },
    { title: "质量诊断", desc: "成功率下降？意图识别偏差？关联失败会话分析共性问题", color: C.midBlue },
    { title: "业务诊断", desc: "回退率/转人工率突增？检测知识库覆盖盲区与模型幻觉", color: C.gold },
  ];
  domains.forEach((d, i) => {
    s.addShape("rect", { x: 0.5, y: 1.55 + i * 0.9, w: 4.0, h: 0.78, fill: { color: C.white }, shadow: mkShadow() });
    s.addShape("rect", { x: 0.5, y: 1.55 + i * 0.9, w: 0.04, h: 0.78, fill: { color: d.color } });
    s.addText(d.title, {
      x: 0.7, y: 1.57 + i * 0.9, w: 3.6, h: 0.22, fontSize: 10, fontFace: "Calibri",
      color: d.color, bold: true, margin: 0,
    });
    s.addText(d.desc, {
      x: 0.7, y: 1.80 + i * 0.9, w: 3.6, h: 0.45, fontSize: 9, fontFace: "Calibri",
      color: C.textBody, margin: 0,
    });
  });

  // Example: root cause & action
  s.addShape("rect", { x: 0.5, y: 4.05, w: 9.0, h: 0.95, fill: { color: C.white }, shadow: mkShadow() });
  s.addShape("rect", { x: 0.5, y: 4.05, w: 9.0, h: 0.04, fill: { color: C.gold } });

  s.addText("智能诊断示例", {
    x: 0.7, y: 4.12, w: 3.0, h: 0.22, fontSize: 10, fontFace: "Calibri", color: C.gold, bold: true, margin: 0,
  });
  s.addText([
    { text: "指标异常：", options: { bold: true, color: C.redAccent, fontSize: 9 } },
    { text: `用户投诉\u201C转账失败\u201D增多 \u2192 异常会话占比达15%`, options: { fontSize: 9, color: C.textBody } },
  ], { x: 0.7, y: 4.35, w: 8.5, h: 0.2, margin: 0 });
  s.addText([
    { text: "根因定位：", options: { bold: true, color: C.midBlue, fontSize: 9 } },
    { text: "DomainRouter路由超时 → 后端服务响应>3s → 触发超时回退", options: { fontSize: 9, color: C.textBody } },
  ], { x: 0.7, y: 4.55, w: 8.5, h: 0.2, margin: 0 });
  s.addText([
    { text: "行动建议：", options: { bold: true, color: C.greenOk, fontSize: 9 } },
    { text: "1.调整路由超时阈值  2.扩容后端推理服务  3.增加降级兜底策略", options: { fontSize: 9, color: C.textBody } },
  ], { x: 0.7, y: 4.75, w: 8.5, h: 0.2, margin: 0 });

  // Right: Screenshot
  s.addText("智能洞察 UI 界面", {
    x: 5.0, y: 1.15, w: 4.5, h: 0.2, fontSize: 9, color: C.textMuted, align: "center", margin: 0,
  });
  s.addImage({ data: IMG.v20_intelligence, x: 5.0, y: 1.4, w: 4.5, h: 2.55, sizing: { type: "contain", w: 4.5, h: 2.55 } });
})();

// ==================== P7: Logs & Alerts ====================
(() => {
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  addPageTitle(s, "日志 & 告警", "集中日志查询 + 多级告警规则，异常秒级响应");
  addFooter(s, 7);

  // Left features
  s.addText("日志管理", {
    x: 0.5, y: 1.15, w: 4.5, h: 0.3, fontSize: 12, fontFace: "Calibri",
    color: C.navy, bold: true, margin: 0,
  });

  const logFeatures = [
    "🔍 全文搜索：按级别/关键词/时间范围快速检索",
    "📊 统计分析：ERROR/WARN/INFO/DEBUG级别分布",
    "🔗 关联跳转：日志条目关联到对应Trace/Session",
    "💾 持久存储：底层Loki支持海量日志集中管理",
  ];
  logFeatures.forEach((lf, i) => {
    s.addText(lf, {
      x: 0.5, y: 1.5 + i * 0.28, w: 4.5, h: 0.25, fontSize: 9, fontFace: "Calibri",
      color: C.textBody, margin: 0,
    });
  });

  s.addText("告警规则", {
    x: 0.5, y: 2.8, w: 4.5, h: 0.3, fontSize: 12, fontFace: "Calibri",
    color: C.navy, bold: true, margin: 0,
  });

  const alertTbl = [
    [
      { text: "规则", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 8, align: "center" } },
      { text: "条件", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 8, align: "center" } },
      { text: "通知", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 8, align: "center" } },
    ],
    [
      { text: "成功率<95%", options: { fontSize: 8, align: "center" } },
      { text: "连续5分钟", options: { fontSize: 8, align: "center" } },
      { text: "飞书/邮件/短信", options: { fontSize: 8, align: "center" } },
    ],
    [
      { text: "TTFT>3s", options: { fontSize: 8, align: "center" } },
      { text: "P95延迟超阈值", options: { fontSize: 8, align: "center" } },
      { text: "飞书/邮件", options: { fontSize: 8, align: "center" } },
    ],
    [
      { text: "Token消耗异常", options: { fontSize: 8, align: "center" } },
      { text: "日环比>50%", options: { fontSize: 8, align: "center" } },
      { text: "飞书通知", options: { fontSize: 8, align: "center" } },
    ],
    [
      { text: "错误率>1%", options: { fontSize: 8, align: "center" } },
      { text: "连续3分钟", options: { fontSize: 8, align: "center" } },
      { text: "电话/飞书/邮件", options: { fontSize: 8, align: "center" } },
    ],
  ];
  s.addTable(alertTbl, {
    x: 0.5, y: 3.15, w: 4.5,
    colW: [1.2, 1.5, 1.8],
    border: { pt: 0.5, color: C.border },
    rowH: [0.24, 0.24, 0.24, 0.24, 0.24],
  });

  // Right screenshots
  s.addText("日志查询界面", {
    x: 5.3, y: 1.15, w: 4.2, h: 0.2, fontSize: 9, color: C.textMuted, align: "center", margin: 0,
  });
  s.addImage({ data: IMG.v20_logs, x: 5.3, y: 1.4, w: 4.2, h: 1.55, sizing: { type: "contain", w: 4.2, h: 1.55 } });

  s.addText("告警规则界面", {
    x: 5.3, y: 3.1, w: 4.2, h: 0.2, fontSize: 9, color: C.textMuted, align: "center", margin: 0,
  });
  s.addImage({ data: IMG.v20_alerts, x: 5.3, y: 3.35, w: 4.2, h: 1.65, sizing: { type: "contain", w: 4.2, h: 1.65 } });
})();

// ==================== P8: POC vs Production ====================
(() => {
  const s = pres.addSlide();
  s.background = { color: C.offWhite };
  addPageTitle(s, "POC-DEMO vs 生产方案对比", "我们的方案已充分考虑千万/亿级用户的金融级要求");
  addFooter(s, 8);

  // Intro
  s.addText("DEMO方案快速验证核心观测能力，生产方案在此基础上全面升级至金融级标准，满足合规、安全、高可用要求。", {
    x: 0.5, y: 1.1, w: 9.0, h: 0.25, fontSize: 9, fontFace: "Calibri", color: C.textBody, margin: 0,
  });

  const tblRows = [
    [
      { text: "维度", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 9, align: "center" } },
      { text: "POC-DEMO方案", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 9, align: "center" } },
      { text: "生产方案", options: { bold: true, color: C.white, fill: { color: C.tableHead }, fontSize: 9, align: "center" } },
    ],
    ["数据库",      "H2内嵌数据库（2GB）",   "PostgreSQL集群（TB级）"],
    ["Span存储",    "H2单表",                "时序数据库（ClickHouse/TimescaleDB）"],
    ["日志存储",    "本地文件 / 控制台",      "Loki分布式集群"],
    ["数据脱敏",    "未实施",                 "字段级脱敏（手机号/身份证/AUM）"],
    ["访问控制",    "无认证",                 "RBAC + SSO + 审计日志"],
    ["高可用",      "单节点",                 "多AZ部署 + 主从切换 + 熔断限流"],
    ["网络传输",    "HTTP明文",               "mTLS加密 + VPC隔离"],
    ["数据留存",    "无策略",                 "满足监管：会话≥3年 / 日志≥1年"],
    ["性能基线",    "单机<100 QPS",           "集群≥10K QPS，水平可扩展"],
    ["合规认证",    "无",                     "等保三级 / 数据出境合规评估"],
  ];
  s.addTable(tblRows, {
    x: 0.5, y: 1.45, w: 9.0,
    colW: [1.5, 3.5, 4.0],
    border: { pt: 0.5, color: C.border },
    rowH: [0.26, 0.26, 0.26, 0.26, 0.26, 0.26, 0.26, 0.26, 0.26, 0.26, 0.26],
  });

  // Bottom summary
  s.addShape("rect", { x: 0.5, y: 4.45, w: 9.0, h: 0.65, fill: { color: C.navy } });
  s.addShape("rect", { x: 0.5, y: 4.45, w: 0.04, h: 0.65, fill: { color: C.gold } });
  s.addText("从DEMO到生产：零架构重构，平滑升级。技术栈完全统一（Spring Boot + OpenTelemetry + Loki），仅需将单机组件替换为分布式高可用版本，业务代码无需改动。", {
    x: 0.7, y: 4.5, w: 8.6, h: 0.55, fontSize: 10, fontFace: "Calibri", color: C.white, margin: 0,
  });
})();

// --- Write File ---
pres.writeFile({ fileName: OUTPUT }).then(() => {
  console.log("PPTX generated: " + OUTPUT);
}).catch(err => {
  console.error("Error:", err.message);
  process.exit(1);
});
