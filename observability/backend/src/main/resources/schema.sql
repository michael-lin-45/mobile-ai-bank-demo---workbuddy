-- 指标聚合表（写入 Schema）
CREATE TABLE IF NOT EXISTS metrics_agg (
    id BIGINT AUTO_INCREMENT,
    metric_name VARCHAR(128) NOT NULL,
    tags VARCHAR(512),
    "value" DOUBLE NOT NULL,
    agg_window VARCHAR(16) NOT NULL,
    "timestamp" TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_metrics_name_ts ON metrics_agg(metric_name, "timestamp");
CREATE INDEX IF NOT EXISTS idx_metrics_window ON metrics_agg(agg_window);

-- Span 表
CREATE TABLE IF NOT EXISTS spans (
    id BIGINT AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    span_id VARCHAR(64) NOT NULL,
    parent_span_id VARCHAR(64),
    service_name VARCHAR(64),
    operation_name VARCHAR(256),
    kind VARCHAR(16),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    duration_ms BIGINT,
    status_code VARCHAR(16),
    attributes VARCHAR(4096),
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_spans_trace ON spans(trace_id);
CREATE INDEX IF NOT EXISTS idx_spans_time ON spans(start_time);
CREATE INDEX IF NOT EXISTS idx_spans_opname ON spans(operation_name);

-- 日志表
CREATE TABLE IF NOT EXISTS logs (
    id BIGINT AUTO_INCREMENT,
    trace_id VARCHAR(64),
    span_id VARCHAR(64),
    "level" VARCHAR(16) NOT NULL,
    service_name VARCHAR(64),
    message TEXT NOT NULL,
    "timestamp" TIMESTAMP NOT NULL,
    attributes VARCHAR(1024),
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_logs_time ON logs("timestamp");
CREATE INDEX IF NOT EXISTS idx_logs_trace ON logs(trace_id);
CREATE INDEX IF NOT EXISTS idx_logs_level ON logs("level");

-- ============================================================
-- ★ Phase 1 P0 新增表（8张）
-- ============================================================

-- 1. sessions — 会话聚合表
CREATE TABLE IF NOT EXISTS sessions (
    id BIGINT AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64),
    channel VARCHAR(32),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    duration_seconds BIGINT,
    turn_count INT DEFAULT 0,
    intent_flow VARCHAR(1024),
    total_tokens BIGINT DEFAULT 0,
    status VARCHAR(32),
    satisfaction_rating VARCHAR(16),
    satisfaction_reason VARCHAR(512),
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_sid ON sessions(session_id);
CREATE INDEX IF NOT EXISTS idx_sessions_time ON sessions(start_time);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status);

-- 2. session_turns — 会话轮次明细表
CREATE TABLE IF NOT EXISTS session_turns (
    id BIGINT AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    turn_number INT NOT NULL,
    user_message TEXT,
    ai_response TEXT,
    intent VARCHAR(64),
    agent_path VARCHAR(256),
    confidence DOUBLE,
    duration_ms BIGINT,
    tokens INT,
    status VARCHAR(32),
    trace_id VARCHAR(64),
    "timestamp" TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_turns_sid ON session_turns(session_id);

-- 3. agent_performance — Agent性能快照表
CREATE TABLE IF NOT EXISTS agent_performance (
    id BIGINT AUTO_INCREMENT,
    agent_name VARCHAR(64),
    agent_level VARCHAR(8),
    model VARCHAR(64),
    call_count INT DEFAULT 0,
    total_duration_ms BIGINT DEFAULT 0,
    ttft_p50_ms BIGINT,
    ttft_p95_ms BIGINT,
    tpot_p50_ms BIGINT,
    tpot_p95_ms BIGINT,
    total_tokens_in BIGINT DEFAULT 0,
    total_tokens_out BIGINT DEFAULT 0,
    error_count INT DEFAULT 0,
    agg_window VARCHAR(16),
    "timestamp" TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ap_agent ON agent_performance(agent_name, "timestamp");

-- 4. token_cost — Token成本明细表
CREATE TABLE IF NOT EXISTS token_cost (
    id BIGINT AUTO_INCREMENT,
    intent VARCHAR(64),
    model VARCHAR(64),
    call_count INT DEFAULT 0,
    tokens_in BIGINT DEFAULT 0,
    tokens_out BIGINT DEFAULT 0,
    agg_window VARCHAR(16),
    "timestamp" TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_tc_intent ON token_cost(intent, "timestamp");

-- 5. tool_calls — 工具调用记录表
CREATE TABLE IF NOT EXISTS tool_calls (
    id BIGINT AUTO_INCREMENT,
    trace_id VARCHAR(64),
    tool_name VARCHAR(128),
    call_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    fail_count INT DEFAULT 0,
    avg_duration_ms BIGINT,
    p95_duration_ms BIGINT,
    error_rate DOUBLE,
    typical_errors VARCHAR(512),
    provider VARCHAR(64) DEFAULT 'banking-api',
    agg_window VARCHAR(16),
    "timestamp" TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_mcp_name ON tool_calls(tool_name, "timestamp");

-- 6. skill_stats — Skill业务效果统计表（P1实现，P0建表但暂不使用）
-- CREATE TABLE IF NOT EXISTS skill_stats (
--     id BIGINT AUTO_INCREMENT,
--     skill_name VARCHAR(128) NOT NULL,
--     intent VARCHAR(64),
--     total_calls INT DEFAULT 0,
--     success_calls INT DEFAULT 0,
--     completion_rate DOUBLE,
--     avg_duration_ms DOUBLE,
--     avg_turns DOUBLE,
--     drop_off_rate DOUBLE,
--     window_start TIMESTAMP NOT NULL,
--     window_end TIMESTAMP NOT NULL,
--     PRIMARY KEY (id)
-- );
-- CREATE INDEX IF NOT EXISTS idx_ss_skill ON skill_stats(skill_name);
-- CREATE INDEX IF NOT EXISTS idx_ss_window ON skill_stats(window_start);

-- 7. alert_rules — 告警规则定义表
CREATE TABLE IF NOT EXISTS alert_rules (
    id BIGINT AUTO_INCREMENT,
    rule_name VARCHAR(128) NOT NULL,
    metric_name VARCHAR(128),
    threshold DOUBLE,
    operator VARCHAR(8),
    duration_seconds INT,
    severity VARCHAR(16),
    enabled BOOLEAN DEFAULT TRUE,
    notify_channels VARCHAR(256),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- 8. alert_events — 告警事件记录表
CREATE TABLE IF NOT EXISTS alert_events (
    id BIGINT AUTO_INCREMENT,
    rule_id BIGINT,
    rule_name VARCHAR(128),
    severity VARCHAR(16),
    status VARCHAR(32),
    triggered_at TIMESTAMP,
    resolved_at TIMESTAMP,
    message TEXT,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ae_time ON alert_events(triggered_at);

-- ============================================================
-- Redis metrics snapshot table - periodic snapshots of Redis gauge/counter metrics
-- ============================================================
CREATE TABLE IF NOT EXISTS redis_metrics_snapshot (
    id BIGINT AUTO_INCREMENT,
    metric_key VARCHAR(256) NOT NULL,
    metric_type VARCHAR(16) NOT NULL,
    metric_value DOUBLE,
    tags VARCHAR(512),
    "timestamp" TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_rms_key_ts ON redis_metrics_snapshot(metric_key, "timestamp");
CREATE INDEX IF NOT EXISTS idx_rms_ts ON redis_metrics_snapshot("timestamp");

-- ============================================================
-- ★ Phase 2 V4 增强（T-N / T-B / T-M / T-L）
-- ============================================================

-- T-N 待定项 B：reRoute 原报文透传标记（sessions / session_turns）
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS reroute_triggered BOOLEAN DEFAULT FALSE;
ALTER TABLE session_turns ADD COLUMN IF NOT EXISTS reroute_triggered BOOLEAN DEFAULT FALSE;

-- T-B 告警引擎 DDL 增强（§3.2③）
ALTER TABLE alert_rules ADD COLUMN IF NOT EXISTS evaluation_interval INT DEFAULT 30;
ALTER TABLE alert_rules ADD COLUMN IF NOT EXISTS last_evaluated_at TIMESTAMP;
ALTER TABLE alert_rules ADD COLUMN IF NOT EXISTS current_value DOUBLE;

ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS notified_channels VARCHAR(128);
ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS notify_result VARCHAR(64);
ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS suppression_count INT DEFAULT 0;

-- T-M P20 提示词版本化（§3.4①）
CREATE TABLE IF NOT EXISTS prompt_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_key VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    content CLOB NOT NULL,
    model VARCHAR(64),
    status VARCHAR(16) DEFAULT 'DRAFT',
    description VARCHAR(256),
    created_by VARCHAR(64),
    created_at TIMESTAMP,
    UNIQUE (prompt_key, version)
);
CREATE INDEX IF NOT EXISTS idx_pv_key_status ON prompt_versions(prompt_key, status);

-- T-L P13 三层边界治理审计表（§3.3②，本轮先建表不接业务）
CREATE TABLE IF NOT EXISTS ai_action_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_version_id BIGINT,
    action_key VARCHAR(64),
    boundary VARCHAR(8) DEFAULT 'L3',
    suggested_by VARCHAR(64),
    approved_by VARCHAR(64),
    status VARCHAR(16) DEFAULT 'PENDING',
    created_at TIMESTAMP,
    executed_at TIMESTAMP
);
