const http = require('http');
const PORT = 9092;

// 存储结构
let metricsStore = {
    httpRequests: [],
    aiModelCalls: []
};
let tracesStore = [];
let logsStore = [];
let requestCount = 0;
let errorCount = 0;
let totalResponseTime = 0;

// 前端HTML完整代码，包含链路和日志功能
const frontendHtml = `
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>银行AI智能体可观测平台</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { font-family: system-ui, -apple-system, sans-serif; background: #f0f2f5; }
    .header { height: 64px; line-height: 64px; background: #001529; color: white; padding: 0 24px; font-size: 20px; font-weight: 600; }
    .container { padding: 24px; max-width: 1600px; margin: 0 auto; }
    .metrics-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 24px; }
    .metric-card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }
    .metric-title { font-size: 14px; color: #666; margin-bottom: 8px; }
    .metric-value { font-size: 32px; font-weight: 600; }
    .success { color: #3f8600; }
    .warning { color: #faad14; }
    .error { color: #cf1322; }
    .card { background: white; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); padding: 24px; margin-bottom: 24px; }
    .tabs { display: flex; gap: 24px; margin-bottom: 24px; border-bottom: 1px solid #f0f0f0; }
    .tab-btn { padding: 8px 0; border: none; background: none; font-size: 16px; cursor: pointer; border-bottom: 2px solid transparent; }
    .tab-btn.active { color: #1890ff; border-bottom-color: #1890ff; font-weight: 500; }
    .tab-content { display: none; }
    .tab-content.active { display: block; }
    .search-bar { margin-bottom: 16px; display: flex; gap: 8px; }
    .search-input { padding: 8px 12px; border: 1px solid #d9d9d9; border-radius: 4px; flex: 1; max-width: 400px; }
    table { width: 100%; border-collapse: collapse; margin-top: 16px; }
    th { text-align: left; padding: 12px 8px; background: #fafafa; border-bottom: 1px solid #f0f0f0; font-weight: 500; }
    td { padding: 12px 8px; border-bottom: 1px solid #f0f0f0; }
    .tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; cursor: pointer; }
    .tag-green { background: #f6ffed; color: #3f8600; border: 1px solid #b7eb8f; }
    .tag-red { background: #fff2f0; color: #cf1322; border: 1px solid #ffccc7; }
    .tag-gold { background: #fffbe6; color: #faad14; border: 1px solid #ffe58f; }
    .tag-blue { background: #e6f7ff; color: #1890ff; border: 1px solid #91d5ff; }
    .trace-detail { background: #fafafa; padding: 16px; border-radius: 8px; margin: 8px 0; display: none; }
    .trace-detail.active { display: block; }
    .timeline { margin: 16px 0; padding-left: 24px; border-left: 2px solid #f0f0f0; }
    .timeline-item { position: relative; margin-bottom: 16px; }
    .timeline-dot { position: absolute; left: -29px; width: 10px; height: 10px; border-radius: 50%; background: #52c41a; }
    .timeline-dot.error { background: #ff4d4f; }
    .expand-btn { color: #1890ff; cursor: pointer; font-size: 12px; }
  </style>
</head>
<body>
  <div class="header">银行AI智能体可观测平台</div>
  <div class="container">
    <!-- 指标卡片 -->
    <div class="metrics-grid">
      <div class="metric-card">
        <div class="metric-title">总请求数</div>
        <div class="metric-value success" id="totalReq">0</div>
      </div>
      <div class="metric-card">
        <div class="metric-title">请求成功率</div>
        <div class="metric-value success" id="successRate">100%</div>
      </div>
      <div class="metric-card">
        <div class="metric-title">平均响应时间</div>
        <div class="metric-value success" id="avgTime">0 ms</div>
      </div>
      <div class="metric-card">
        <div class="metric-title">异常请求数</div>
        <div class="metric-value error" id="errorCount">0</div>
      </div>
    </div>
    <!-- 标签页 -->
    <div class="card">
      <div class="tabs">
        <button class="tab-btn active" onclick="switchTab('logs')">日志检索</button>
        <button class="tab-btn" onclick="switchTab('traces')">全链路追踪</button>
        <button class="tab-btn" onclick="switchTab('metrics')">请求日志</button>
      </div>
      <!-- 日志页 -->
      <div id="logs" class="tab-content active">
        <div class="search-bar">
          <input type="text" class="search-input" id="logSearch" placeholder="搜索关键词/ TraceID，按回车搜索" onkeyup="if(event.keyCode===13) searchLogs()">
        </div>
        <table>
          <thead>
            <tr>
              <th width="180">时间</th>
              <th width="80">级别</th>
              <th width="200">TraceID</th>
              <th>日志内容</th>
            </tr>
          </thead>
          <tbody id="logTable"></tbody>
        </table>
      </div>
      <!-- 链路页 -->
      <div id="traces" class="tab-content">
        <div class="search-bar">
          <input type="text" class="search-input" id="traceSearch" placeholder="搜索 TraceID，按回车搜索" onkeyup="if(event.keyCode===13) searchTraces()">
        </div>
        <table>
          <thead>
            <tr>
              <th width="180">开始时间</th>
              <th width="200">TraceID</th>
              <th width="150">业务场景</th>
              <th width="100">总耗时</th>
              <th width="80">状态</th>
              <th width="80">操作</th>
            </tr>
          </thead>
          <tbody id="traceTable"></tbody>
        </table>
      </div>
      <!-- 指标日志页 -->
      <div id="metrics" class="tab-content">
        <table>
          <thead>
            <tr>
              <th width="180">时间</th>
              <th width="80">方法</th>
              <th width="80">状态码</th>
              <th>请求路径</th>
              <th width="100">耗时</th>
            </tr>
          </thead>
          <tbody id="metricTable"></tbody>
        </table>
      </div>
    </div>
  </div>
  <script>
    let currentTab = 'logs';
    let allLogs = [];
    let allTraces = [];
    // 切换标签
    function switchTab(tab) {
      currentTab = tab;
      document.querySelectorAll('.tab-btn').forEach(function(b) { b.classList.remove('active'); });
      document.querySelectorAll('.tab-content').forEach(function(c) { c.classList.remove('active'); });
      // 用简单的 data-tab 属性替代复杂的 onclick 属性选择器
      var tabs = {logs:0, traces:1, metrics:2};
      document.querySelectorAll('.tab-btn')[tabs[tab]].classList.add('active');
      document.getElementById(tab).classList.add('active');
      fetchData();
    }
    // 搜索日志
    function searchLogs() {
      const keyword = document.getElementById('logSearch').value.toLowerCase();
      renderLogs(allLogs.filter(l => l.content.toLowerCase().includes(keyword) || l.traceId.toLowerCase().includes(keyword)));
    }
    // 搜索链路
    function searchTraces() {
      const keyword = document.getElementById('traceSearch').value.toLowerCase();
      renderTraces(allTraces.filter(t => t.traceId.toLowerCase().includes(keyword)));
    }
    // 跳转链路
    function jumpToTrace(traceId) {
      switchTab('traces');
      document.getElementById('traceSearch').value = traceId;
      searchTraces();
    }
    // 展开链路详情
    function toggleTrace(id) {
      const el = document.getElementById('trace_' + id);
      el.classList.toggle('active');
    }
    // 渲染日志
    function renderLogs(logs) {
      const logTable = document.getElementById('logTable');
      logTable.innerHTML = '';
      logs.slice().reverse().forEach(log => {
        const row = document.createElement('tr');
        let levelTag = '<span class="tag tag-green">INFO</span>';
        if (log.level === 'ERROR') levelTag = '<span class="tag tag-red">ERROR</span>';
        if (log.level === 'WARN') levelTag = '<span class="tag tag-gold">WARN</span>';
        row.innerHTML = \`
          <td>\${log.time}</td>
          <td>\${levelTag}</td>
          <td><span class="tag tag-blue" onclick="jumpToTrace('\${log.traceId}')">\${log.traceId}</span></td>
          <td>\${log.content}</td>
        \`;
        logTable.appendChild(row);
      });
    }
    // 渲染链路
    function renderTraces(traces) {
      const traceTable = document.getElementById('traceTable');
      traceTable.innerHTML = '';
      traces.slice().reverse().forEach((trace, idx) => {
        const row = document.createElement('tr');
        let statusTag = '<span class="tag tag-green">成功</span>';
        if (trace.status === 'ERROR') statusTag = '<span class="tag tag-red">失败</span>';
        row.innerHTML = \`
          <td>\${trace.time}</td>
          <td><span class="tag tag-blue">\${trace.traceId}</span></td>
          <td>\${trace.name || '业务请求'}</td>
          <td>\${trace.duration} ms</td>
          <td>\${statusTag}</td>
          <td><span class="expand-btn" onclick="toggleTrace(\${idx})">展开详情</span></td>
        \`;
        const detailRow = document.createElement('tr');
        detailRow.innerHTML = \`
          <td colspan="6">
            <div class="trace-detail" id="trace_\${idx}">
              <h4 style="margin-bottom: 16px;">链路详情：\${trace.traceId}</h4>
              <div class="timeline">
                \${trace.spans.map(span => \`
                  <div class="timeline-item">
                    <span class="timeline-dot \${span.status === 'ERROR' ? 'error' : ''}"></span>
                    <div><b>\${span.name}</b> <span class="tag tag-blue">\${span.duration} ms</span></div>
                    <div style="margin-top: 4px; color: #666; font-size: 13px;">\${span.service} · \${span.time}</div>
                  </div>
                \`).join('')}
              </div>
            </div>
          </td>
        \`;
        traceTable.appendChild(row);
        traceTable.appendChild(detailRow);
      });
    }
    // 渲染指标日志
    function renderMetrics(metrics) {
      const metricTable = document.getElementById('metricTable');
      metricTable.innerHTML = '';
      metrics.slice().reverse().forEach(req => {
        const row = document.createElement('tr');
        const statusTag = req.status.startsWith('2') ? '<span class="tag tag-green">' + req.status + '</span>' : '<span class="tag tag-red">' + req.status + '</span>';
        row.innerHTML = \`
          <td>\${req.time}</td>
          <td>\${req.method}</td>
          <td>\${statusTag}</td>
          <td>\${req.uri}</td>
          <td>\${(req.duration * 1000).toFixed(2)} ms</td>
        \`;
        metricTable.appendChild(row);
      });
    }
    // 拉取数据
    async function fetchData() {
      try {
        // 指标数据
        const res = await fetch('/api/v1/dashboard/stats');
        const data = await res.json();
        document.getElementById('totalReq').innerText = data.totalRequests;
        document.getElementById('successRate').innerText = data.successRate + '%';
        document.getElementById('avgTime').innerText = data.avgResponseTime + ' ms';
        document.getElementById('errorCount').innerText = data.errorCount;
        renderMetrics(data.recentRequests);
        // 日志数据
        const logRes = await fetch('/api/v1/logs');
        allLogs = await logRes.json();
        if (currentTab === 'logs') renderLogs(allLogs);
        // 链路数据
        const traceRes = await fetch('/api/v1/traces');
        allTraces = await traceRes.json();
        if (currentTab === 'traces') renderTraces(allTraces);
      } catch (e) {
        console.error('拉取数据失败:', e);
      }
    }
    // 3秒自动刷新
    fetchData();
    setInterval(fetchData, 3000);
  </script>
</body>
</html>
`;

const server = http.createServer((req, res) => {
  // CORS处理
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') {
    res.writeHead(200);
    res.end();
    return;
  }

  // 根路径返回前端页面
  if (req.url === '/' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(frontendHtml);
    return;
  }

  // 健康检查
  if (req.url === '/health' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', time: new Date().toISOString() }));
    return;
  }

  // 指标上报接口
  if ((req.url === '/api/v1/metrics' || req.url === '/v1/metrics') && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk.toString());
    req.on('end', () => {
      try {
        const data = JSON.parse(body);
        const rMetrics = data.resourceMetrics || data.resource_metrics || [];
        rMetrics.forEach(rm => {
          const sMetrics = rm.scopeMetrics || rm.scope_metrics || [];
          sMetrics.forEach(sm => {
            (sm.metrics || []).forEach(m => {
              const name = m.name || '';
              // 只处理 HTTP 请求耗时 & AI 模型调用指标
              const isHttpDuration = name === 'http.server.duration' || name.includes('http.server.request.duration');
              const isAiMetric = name.includes('gen_ai') || name.includes('ai.model');
              if (!isHttpDuration && !isAiMetric) return;
              
              // 提取数据点（histogram / sum / gauge）
              const dps = (m.histogram || m.sum || m.gauge || {}).dataPoints || [];
              if (dps.length === 0 && (m.histogram || m.sum || m.gauge)) {
                // OTLP 可能把 dataPoints 放在 metric 顶层
                const dp = (m.histogram || m.sum || m.gauge) || {};
                const attrs = {};
                ((dp.attributes || m.attributes || [])).forEach(a => {
                  const v = a.value || {};
                  attrs[a.key] = v.stringValue || v.intValue || v.doubleValue || '';
                });
                if (isHttpDuration) {
                  const cnt = parseInt(dp.count) || 1;
                  const sum = parseFloat(dp.sum) || 0;
                  requestCount += cnt;
                  totalResponseTime += sum;
                  metricsStore.httpRequests.push({
                    time: new Date().toISOString().slice(0, 19).replace('T', ' '),
                    method: attrs['http.request.method'] || 'GET',
                    status: String(attrs['http.response.status_code'] || '200'),
                    uri: attrs['url.path'] || attrs['http.route'] || '/',
                    duration: cnt > 0 ? Math.round(sum / cnt * 100) / 100 : 0,
                    success: !attrs['error.type'],
                    count: cnt
                  });
                } else if (name.includes('gen_ai') || name.includes('ai.model')) {
                  metricsStore.aiModelCalls.push({
                    time: new Date().toISOString(),
                    model: attrs['gen_ai.request.model'] || 'unknown',
                    success: !attrs['error.type'],
                    duration: dp.sum || dp.asDouble || dp.asInt || 0
                  });
                }
              } else {
                dps.forEach(dp => {
                  const attrs = {};
                  ((dp.attributes || m.attributes || [])).forEach(a => {
                    const v = a.value || {};
                    attrs[a.key] = v.stringValue || v.intValue || v.doubleValue || '';
                  });
                  if (isHttpDuration) {
                    const cnt = parseInt(dp.count) || 1;
                    const sum = parseFloat(dp.sum || dp.asDouble || dp.asInt) || 0;
                    if (cnt > 1) {
                      requestCount += cnt;
                      totalResponseTime += sum;
                    } else {
                      requestCount++;
                      totalResponseTime += sum;
                    }
                    metricsStore.httpRequests.push({
                      time: new Date().toISOString().slice(0, 19).replace('T', ' '),
                      method: attrs['http.request.method'] || 'GET',
                      status: String(attrs['http.response.status_code'] || '200'),
                      uri: attrs['url.path'] || attrs['http.route'] || '/',
                      duration: cnt > 1 ? Math.round(sum / cnt * 100) / 100 : sum,
                      success: !attrs['error.type'],
                      count: Math.max(cnt, 1)
                    });
                  } else if (name.includes('gen_ai') || name.includes('ai.model')) {
                    metricsStore.aiModelCalls.push({
                      time: new Date().toISOString(),
                      model: attrs['gen_ai.request.model'] || 'unknown',
                      success: !attrs['error.type'],
                      duration: dp.sum || dp.asDouble || dp.asInt || 0
                    });
                  }
                });
              }
            });
          });
        });
      } catch (e) {/* ignore - metrics format may vary */}
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'success' }));
    });
    return;
  }

  // 链路上报接口
  if ((req.url === '/api/v1/traces' || req.url === '/v1/traces') && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk.toString());
    req.on('end', () => {
      try {
        const traceData = JSON.parse(body);
        const rSpans = traceData.resourceSpans || traceData.resource_spans || [];
        // 遍历所有 resourceSpans（Collector 会批量发送多个 resource）
        rSpans.forEach(rs => {
          const resAttrs = rs.resource?.attributes || [];
          const svcAttr = resAttrs.find(a => a.key === 'service.name');
          const serviceName = svcAttr?.value?.stringValue || 'unknown';
          const sSpans = rs.scopeSpans || rs.scope_spans || [];
          sSpans.forEach(ss => {
            const spans = ss.spans || [];
            if (spans.length === 0) return;
            const traceId = spans[0].traceId || spans[0].trace_id || '';
            const rootSpan = spans.find(s => !(s.parentSpanId || s.parent_span_id)) || spans[0];
            const startNano = parseInt(rootSpan.startTimeUnixNano || rootSpan.start_time_unix_nano || '0', 10) || parseInt(rootSpan.startTimeUnixNano || rootSpan.start_time_unix_nano || '0', 16) || 0;
            const endNano = parseInt(rootSpan.endTimeUnixNano || rootSpan.end_time_unix_nano || '0', 10) || parseInt(rootSpan.endTimeUnixNano || rootSpan.end_time_unix_nano || '0', 16) || 0;
            const msDuration = Math.round((endNano - startNano) / 1000000);
            const status = (rootSpan.status || {}).code === 2 ? 'ERROR' : 'SUCCESS';
            const traceSpans = spans.map(s => {
              const sStart = parseInt(s.startTimeUnixNano || s.start_time_unix_nano || '0', 10) || parseInt(s.startTimeUnixNano || s.start_time_unix_nano || '0', 16) || 0;
              const sEnd = parseInt(s.endTimeUnixNano || s.end_time_unix_nano || '0', 10) || parseInt(s.endTimeUnixNano || s.end_time_unix_nano || '0', 16) || 0;
              return {
                name: s.name || '',
                duration: Math.round((sEnd - sStart) / 1000000),
                service: serviceName,
                status: (s.status || {}).code === 2 ? 'ERROR' : 'SUCCESS',
                time: new Date(sStart / 1000000).toISOString().slice(0, 19).replace('T', ' ')
              };
            });
            tracesStore.push({
              time: new Date().toISOString().slice(0, 19).replace('T', ' '),
              traceId, name: rootSpan.name || '', duration: msDuration, status, spans: traceSpans
            });
          });
        });
        if (tracesStore.length > 200) tracesStore.splice(0, tracesStore.length - 200);
      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'error', message: e.message }));
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'success' }));
    });
    return;
  }

  // 日志上报接口
  if ((req.url === '/api/v1/logs' || req.url === '/v1/logs') && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk.toString());
    req.on('end', () => {
      try {
        const logData = JSON.parse(body);
        // OTLP JSON: resourceLogs or resource_logs
        const rLogs = logData.resourceLogs || logData.resource_logs || [];
        const sLogs = rLogs[0]?.scopeLogs || rLogs[0]?.scope_logs || [];
        const logRecords = sLogs[0]?.logRecords || sLogs[0]?.log_records || [];
        logRecords.forEach(log => {
          const traceId = log.traceId || log.trace_id || '';
          const severity = log.severityText || log.severity_text || 'INFO';
          const bodyObj = log.body || {};
          const content = bodyObj.stringValue || bodyObj.string_value || JSON.stringify(bodyObj);
          const ts = log.timeUnixNano || log.time_unix_nano || '0';
          const ms = parseInt(ts, 10) || parseInt(ts, 16) || 0;
          logsStore.push({
            time: new Date(ms / 1000000).toISOString().slice(0, 19).replace('T', ' '),
            level: severity,
            traceId: traceId,
            content: content
          });
        });
        if (logsStore.length > 200) logsStore.splice(0, logsStore.length - 200);
      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'error', message: e.message }));
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'success' }));
    });
    return;
  }

  // 指标查询接口
  if (req.url === '/api/v1/dashboard/stats' && req.method === 'GET') {
    // 从 Trace 数据计算真实统计（比 OTLP histogram 聚合更准确）
    const apiTraces = tracesStore.filter(t => t.name && (t.name.includes('/api/') || t.name.includes('/actuator/')));
    const traceCount = apiTraces.length;
    const traceSum = apiTraces.reduce((s, t) => s + t.duration, 0);
    const traceAvg = traceCount > 0 ? Math.round(traceSum / traceCount * 100) / 100 : 0;
    const traceErrors = apiTraces.filter(t => t.status === 'ERROR').length;
    const traceRate = traceCount === 0 ? 100 : Math.round((traceCount - traceErrors) / traceCount * 100 * 100) / 100;
    // 最近请求从 Trace 提取（每条耗时各不同）
    const recentFromTraces = apiTraces.slice(-20).reverse().map(t => ({
      time: t.time,
      method: t.name.split(' ')[0],
      status: t.status === 'ERROR' ? '500' : '200',
      uri: t.name.split(' ').slice(1).join(' '),
      duration: t.duration
    }));
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      totalRequests: traceCount,
      successRate: traceRate,
      avgResponseTime: traceAvg,
      errorCount: traceErrors,
      aiCallCount: metricsStore.aiModelCalls.length,
      recentRequests: recentFromTraces.length > 0 ? recentFromTraces : []
    }));
    return;
  }

  // 日志查询接口
  if (req.url === '/api/v1/logs' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(logsStore.slice(-100)));
    return;
  }

  // 链路查询接口
  if (req.url === '/api/v1/traces' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(tracesStore.slice(-100)));
    return;
  }

  // 404
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'Not Found' }));
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('✅ 全链路可观测系统启动成功！');
  console.log(`📍 面板地址：http://localhost:${PORT}`);
});
