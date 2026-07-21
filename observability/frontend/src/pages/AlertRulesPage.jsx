import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Button, Modal, Form, Input, Select, Empty, Tag, Space, Popconfirm, message, Typography } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, AlertOutlined } from '@ant-design/icons';
import { fetchAlertRules, createAlertRule, updateAlertRule, deleteAlertRule, fetchAlertEvents } from '../api/client';
import { formatBeijingTime } from '../utils/time';

const { Title, Paragraph } = Typography;
const { Option } = Select;

/**
 * AlertRulesPage — 告警规则页
 *
 * 双栏布局:
 * - 左: 告警规则表（规则名/阈值/状态/通知方式/操作按钮）
 * - 右: 告警闭环时间线（触发时间→处理动作→恢复时间）
 */
function AlertRulesPage() {
  const [rules, setRules] = useState([]);
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingRule, setEditingRule] = useState(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [rulesRes, eventsRes] = await Promise.allSettled([
        fetchAlertRules(),
        fetchAlertEvents(),
      ]);
      if (rulesRes.status === 'fulfilled' && rulesRes.value) {
        setRules(Array.isArray(rulesRes.value) ? rulesRes.value : []);
      } else {
        setRules([]);
      }
      if (eventsRes.status === 'fulfilled' && eventsRes.value) {
        setEvents(Array.isArray(eventsRes.value) ? eventsRes.value : []);
      } else {
        setEvents([]);
      }
    } catch (err) {
      console.error('Failed to load alert data:', err);
      setRules([]);
      setEvents([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleCreate = () => {
    setEditingRule(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (rule) => {
    setEditingRule(rule);
    form.setFieldsValue(rule);
    setModalVisible(true);
  };

  const handleDelete = async (id) => {
    try {
      await deleteAlertRule(id);
      message.success('规则已删除');
      loadData();
    } catch (err) {
      message.error('删除失败: ' + err.message);
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      if (editingRule) {
        await updateAlertRule(editingRule.id, values);
        message.success('规则已更新');
      } else {
        await createAlertRule(values);
        message.success('规则已创建');
      }
      setModalVisible(false);
      loadData();
    } catch (err) {
      if (err.message) message.error('操作失败: ' + err.message);
    } finally {
      setSaving(false);
    }
  };

  const rulesColumns = [
    { title: '规则名', dataIndex: 'name', key: 'name', render: (t) => <span style={{ fontWeight: 500 }}>{t}</span> },
    { title: '阈值', dataIndex: 'threshold', key: 'threshold', render: (t) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{t}</span> },
    {
      title: '状态', dataIndex: 'status', key: 'status', align: 'center',
      render: (s) => {
        const meta = STATUS_MAP[s] || { color: '#8c8c8c', bg: '#f5f5f5', label: s };
        return (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 4, fontSize: 12, fontWeight: 500, background: meta.bg, color: meta.color, border: `1px solid ${meta.border}` }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', display: 'inline-block', background: meta.color }} />
            {meta.label}
          </span>
        );
      },
    },
    { title: '通知方式', dataIndex: 'notify', key: 'notify' },
    {
      title: '操作', key: 'actions', align: 'center', width: 120,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)} />
          <Popconfirm title="确认删除此规则?" onConfirm={() => handleDelete(record.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>
          <AlertOutlined style={{ marginRight: 8 }} />
          告警规则
        </Title>
        <Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 4 }}>
          配置告警规则，监控关键指标异常
        </Paragraph>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        {/* 左: 告警规则表 */}
        <Card
          title="告警规则"
          extra={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={handleCreate}>新增</Button>}
          loading={loading}
        >
          <Table
            dataSource={rules}
            columns={rulesColumns}
            rowKey="id"
            pagination={false}
            size="small"
            locale={{ emptyText: <Empty description="暂无告警规则" /> }}
          />
        </Card>

        {/* 右: 告警闭环时间线 */}
        <Card title="告警闭环" loading={loading}>
          {events.length > 0 ? (
            <div style={{ fontSize: 13 }}>
              {events.map((evt, idx) => (
                <div
                  key={idx}
                  style={{
                    padding: '10px 0',
                    borderBottom: idx < events.length - 1 ? '1px solid #f0f0f0' : 'none',
                  }}
                >
                  <b style={{ color: evt.severity === 'error' ? '#ff4d4f' : evt.severity === 'warning' ? '#faad14' : '#52c41a' }}>
                    {formatBeijingTime(evt.time) || '-'} {evt.title}
                  </b>
                  <p style={{ color: 'rgba(0,0,0,.45)', marginTop: 4 }}>{evt.description}</p>
                  {evt.links && evt.links.length > 0 && (
                    <div style={{ marginTop: 6 }}>
                      {evt.links.map((link, i) => (
                        <span key={i} style={{ color: '#1677ff', cursor: 'pointer', fontSize: 12, marginRight: 12 }}>
                          {link}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <Empty description="暂无告警事件" />
          )}
        </Card>
      </div>

      {/* 新建/编辑 Modal */}
      <Modal
        title={editingRule ? '编辑告警规则' : '新建告警规则'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例: 首 Token P95 异常" />
          </Form.Item>
          <Form.Item name="threshold" label="阈值" rules={[{ required: true, message: '请输入阈值' }]}>
            <Input placeholder="例: > 1.2s / 5min" />
          </Form.Item>
          <Form.Item name="status" label="状态" initialValue="active">
            <Select>
              <Option value="active">启用</Option>
              <Option value="observing">观察</Option>
              <Option value="disabled">禁用</Option>
            </Select>
          </Form.Item>
          <Form.Item name="notify" label="通知方式" initialValue="企微">
            <Select>
              <Option value="企微">企微</Option>
              <Option value="企微 + 短信">企微 + 短信</Option>
              <Option value="邮件">邮件</Option>
              <Option value="企微 + 邮件 + 短信">企微 + 邮件 + 短信</Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

const STATUS_MAP = {
  triggered: { label: '触发中', color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7' },
  observing: { label: '观察', color: '#faad14', bg: '#fffbe6', border: '#ffe58f' },
  normal: { label: '正常', color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f' },
  active: { label: '启用', color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f' },
  disabled: { label: '禁用', color: '#8c8c8c', bg: '#f5f5f5', border: '#d9d9d9' },
};

export default AlertRulesPage;
