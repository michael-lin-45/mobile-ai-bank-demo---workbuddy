import express from 'express'
import cors from 'cors'
import { Low } from 'lowdb'
import { JSONFile } from 'lowdb/node'

const app = express()
const PORT = 9090

// 配置中间件
app.use(cors())
app.use(express.json({ limit: '10mb' }))
app.use(express.text({ type: 'application/x-protobuf' }))

// 初始化轻量数据库
const adapter = new JSONFile('./db.json')
const db = new Low(adapter, { metrics: [], logs: [], traces: [] })
await db.read()

// 健康检查接口（用于启动检测）
app.get('/actuator/health', (req, res) => {
  res.json({ status: 'UP' })
})

// Prometheus指标端点
app.get('/actuator/prometheus', async (req, res) => {
  await db.read()
  const metrics = db.data.metrics.slice(-100)
  let output = ''
  metrics.forEach(m => {
    output += `${m.name}{${m.labels || ''}} ${m.value} ${m.timestamp}\n`
  })
  res.set('Content-Type', 'text/plain')
  res.send(output)
})

// OTLP 指标接收接口
app.post('/v1/metrics', async (req, res) => {
  // 这里处理OTLP格式的指标上报，暂时简化存储
  await db.read()
  db.data.metrics.push({
    timestamp: Date.now(),
    name: 'custom.request.count',
    value: 1,
    labels: 'service="mobile-bank"'
  })
  await db.write()
  res.json({ success: true })
})

// OTLP 日志接收接口
app.post('/v1/logs', async (req, res) => {
  await db.read()
  db.data.logs.push({
    timestamp: Date.now(),
    ...req.body
  })
  await db.write()
  res.json({ success: true })
})

// OTLP 链路接收接口
app.post('/v1/traces', async (req, res) => {
  await db.read()
  db.data.traces.push({
    timestamp: Date.now(),
    ...req.body
  })
  await db.write()
  res.json({ success: true })
})

// 查询指标列表接口
app.get('/api/metrics', async (req, res) => {
  await db.read()
  res.json(db.data.metrics.slice(-200))
})

// 查询日志列表接口
app.get('/api/logs', async (req, res) => {
  await db.read()
  res.json(db.data.logs.slice(-200))
})

// 启动服务
app.listen(PORT, '0.0.0.0', () => {
  console.log(`Observability Backend started successfully on http://localhost:${PORT}`)
})
