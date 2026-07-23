import React, { useState, useEffect, useCallback } from 'react';
import {
  Card, Table, Tag, Alert, Empty, Spin, Progress, Statistic, Button, Space, List,
  Typography,
} from 'antd';
import {
  fetchInsightsReport, fetchBottlenecks, fetchRootCause,
  fetchUnsatisfied, fetchInsightsConversion, fetchInsightsActions,
} from '../api/client';
import ApiErrorAlert from '../components/ApiErrorAlert';
import Top5Actions from '../components/Top5Actions';

/**
 * 智能诊断 TAB（V23 B4 / 任务分解 M4）。
 *
 * 统一消费 6 个洞察端点，按「诊断快照 → 性能准确率瓶颈 → 慢会话 → 不满意聚类 → 转化漏斗 → 优化建议」
 * 组织「智能诊断」视图。慢会话 / 不满意均为带 triggered 标志的块，触发时高亮告警。
 */
const { Text } = Typography;

function DiagnosisTab() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [report, setReport] = useState(null);
  const [bottlenecks, setBottlenecks] = useState([]);
  const [unsat, setUnsat] = useState(null);
  const [conversion, setConversion] = useState(null);
  const [actions, setActions] = useState([]);

  const [rootCause, setRootCause] = useState(null);
  const [rootLoading, setRootLoading] = useState(false);
  const [rootSession, setRootSession] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [rep, bn, un, conv, act] = await Promise.all([
        fetchInsightsReport(),
        fetchBottlenecks(),
        fetchUnsatisfied(),
        fetchInsightsConversion(),
        fetchInsightsActions(),
      ]);
      setReport(rep);
      setBottlenecks(bn || []);
      setUnsat(un);
      setConversion(conv);
      setActions(act || []);

      // 自动对最慢会话做一次根因下钻
      const rows = rep && rep.slowSessions && rep.slowSessions.rows ? rep.slowSessions.rows : [];
      if (rows.length > 0) {
        setRootSession(rows[0].sessionId);
        doRootCause(rows[0].sessionId);
      }
    } catch (err) {
      console.error('[DiagnosisTab] load failed:', err);
      setError(err.message || '诊断数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  const doRootCause = useCallback(async (sessionId) => {
    if (!sessionId) return;
    setRootLoading(true);
    try {
      const rc = await fetchRootCause(sessionId);
      setRootCause(rc);
    } catch (err) {
      console.warn('[DiagnosisTab] root cause failed:', err.message);
    } finally {
      setRootLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  const slowBlock = report && report.slowSessions ? report.slowSessions : null;
  const unsatTriggered = unsat && unsat.triggered;
  const slowTriggered = slowBlock && slowBlock.triggered;

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <ApiErrorAlert error={error} onRetry={loadData} />

        {/* Top5 优化建议（水平紧凑） */}
        <Top5Actions />

        {/* 诊断快照 */}
        <Card size="small">
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <div>
              <Text strong style={{ fontSize: 15 }}>智能诊断快照</Text>
              <Tag color="blue" style={{ marginLeft: 8 }}>{report ? report.boundary : 'L1'}</Tag>
            </div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {report ? report.generatedAt : ''}
            </Text>
          </Space>
          <div style={{ marginTop: 8 }}>
            <Text>{report ? report.summary : '加载中…'}</Text>
          </div>
        </Card>

        {/* 性能 / 准确率瓶颈 */}
        <Card size="small" title="性能与准确率瓶颈 (L1)">
          {bottlenecks.length === 0 ? (
            <Empty description="暂无瓶颈" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            <Table
              dataSource={bottlenecks}
              rowKey={(r) => r.metric || r.title}
              pagination={false}
              size="small"
              columns={[
                { title: '类别', dataIndex: 'category', width: 120, render: (c) => <Tag>{c}</Tag> },
                { title: '指标', dataIndex: 'title', render: (t) => t },
                {
                  title: '值', key: 'value', width: 120, align: 'right',
                  render: (_, r) => <Text strong>{r.value}{r.unit}</Text>,
                },
                { title: '说明', dataIndex: 'detail', ellipsis: true },
              ]}
            />
          )}
        </Card>

        {/* 慢会话诊断 */}
        <Card
          size="small"
          title="慢会话诊断 (L1)"
          extra={slowBlock ? <Tag color={slowTriggered ? 'red' : 'green'}>{slowTriggered ? '已触发告警' : '正常'}</Tag> : null}
        >
          {slowTriggered && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={`慢会话 P90 时延 ${slowBlock.p90Seconds}s 超过阈值 ${slowBlock.thresholdSeconds}s`}
            />
          )}
          {slowBlock ? (
            <Space direction="vertical" style={{ width: '100%' }}>
              <Statistic title="会话时延 P90" value={slowBlock.p90Seconds} suffix="s" valueStyle={{ color: slowTriggered ? '#cf1322' : '#3f8600' }} />
              <Table
                dataSource={slowBlock.rows || []}
                rowKey="sessionId"
                pagination={false}
                size="small"
                columns={[
                  { title: '会话 ID', dataIndex: 'sessionId', render: (v) => <Text code>{v}</Text> },
                  { title: '时长(s)', dataIndex: 'durationSeconds', align: 'right' },
                  { title: '轮次', dataIndex: 'turnCount', align: 'right' },
                  { title: '意图流', dataIndex: 'intentFlow', ellipsis: true },
                  {
                    title: '操作', key: 'op', width: 100,
                    render: (_, r) => (
                      <Button
                        type="link"
                        size="small"
                        onClick={() => { setRootSession(r.sessionId); doRootCause(r.sessionId); }}
                      >
                        根因
                      </Button>
                    ),
                  },
                ]}
              />
              {rootCause && (
                <Card size="small" type="inner" title={`根因分析 — ${rootSession || ''}`} loading={rootLoading}>
                  <List
                    size="small"
                    dataSource={rootCause.rootCauseCandidates || []}
                    renderItem={(c) => (
                      <List.Item>
                        <Space direction="vertical" size={2} style={{ width: '100%' }}>
                          <Text strong>{c.spanName}</Text>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            独占时间 {c.exclusiveTimeMs}ms · 置信度 {c.confidence}
                          </Text>
                          <Text style={{ fontSize: 12 }}>{c.hypothesis}</Text>
                        </Space>
                      </List.Item>
                    )}
                  />
                </Card>
              )}
            </Space>
          ) : <Empty description="暂无慢会话数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        </Card>

        {/* 不满意聚类 */}
        <Card
          size="small"
          title="不满意会话聚类 (L2)"
          extra={unsat ? <Tag color={unsatTriggered ? 'red' : 'green'}>{unsatTriggered ? '已触发告警' : '正常'}</Tag> : null}
        >
          {unsatTriggered && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={`不满意率 ${unsat.rate}% 超过阈值 10%（${unsat.unsatisfied}/${unsat.total}）`}
            />
          )}
          {unsat && unsat.clusters && unsat.clusters.length > 0 ? (
            <List
              size="small"
              dataSource={unsat.clusters}
              renderItem={(c) => (
                <List.Item>
                  <Space direction="vertical" size={2} style={{ width: '100%' }}>
                    <Text strong>{c.dimension} <Tag>{c.count}</Tag></Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>{c.commonPattern}</Text>
                    <Text style={{ fontSize: 12 }}>样本：{(c.examples || []).join(', ')}</Text>
                  </Space>
                </List.Item>
              )}
            />
          ) : <Empty description="无负面满意度聚类" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        </Card>

        {/* 转化漏斗 */}
        <Card size="small" title="业务转化漏斗 (L1)">
          {conversion ? (
            <div>
              <Space wrap style={{ marginBottom: 12 }}>
                <Statistic title="总会话" value={conversion.totalSessions} />
                <Statistic title="完成会话" value={conversion.completedSessions} />
                <Statistic
                  title="转化率"
                  value={conversion.conversionRate}
                  suffix="%"
                  valueStyle={{ color: '#1677ff' }}
                />
              </Space>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {(conversion.stages || []).map((s, i) => {
                  const max = conversion.totalSessions || 1;
                  const pct = max > 0 ? Math.round((s.count / max) * 100) : 0;
                  return (
                    <div key={s.name}>
                      <div style={{ fontSize: 12, color: 'rgba(0,0,0,.65)', marginBottom: 2 }}>
                        {s.name} · {s.count}
                      </div>
                      <Progress percent={pct} showInfo={false} size="small" strokeColor="#1677ff" />
                    </div>
                  );
                })}
              </div>
            </div>
          ) : <Empty description="暂无转化数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        </Card>

        {/* 优化建议 Top10 */}
        <Card size="small" title="优化建议 Top10 (L2)">
          <List
            size="small"
            dataSource={actions}
            renderItem={(a) => (
              <List.Item>
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Space direction="vertical" size={2}>
                    <Text strong>{a.title}</Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>{a.description}</Text>
                  </Space>
                  <Space>
                    <Tag color={a.severity === 'HIGH' ? 'red' : a.severity === 'MED' ? 'orange' : 'default'}>{a.severity}</Tag>
                    <Tag color="blue">P{a.priority}</Tag>
                  </Space>
                </Space>
              </List.Item>
            )}
          />
        </Card>
      </div>
    </Spin>
  );
}

export default DiagnosisTab;
