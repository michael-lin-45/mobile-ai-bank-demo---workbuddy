import React from 'react';
import { Alert } from 'antd';

/**
 * ApiErrorAlert — 统一 API 错误提示横幅（T-G 错误态）
 *
 * 当某个 TAB 的数据请求失败时展示友好错误提示，不注入假数据，
 * 不影响其他已加载区块的展示。error 为 null 时不渲染。
 */
function ApiErrorAlert({ error, onRetry }) {
  if (!error) return null;
  return (
    <Alert
      type="error"
      showIcon
      style={{ borderRadius: 8, marginBottom: 16 }}
      message="数据加载失败"
      description={
        <span>
          {error}
          {onRetry && (
            <a style={{ marginLeft: 8 }} onClick={onRetry}>
              重试
            </a>
          )}
          （已自动重试，不影响其他区块展示）
        </span>
      }
    />
  );
}

export default ApiErrorAlert;
