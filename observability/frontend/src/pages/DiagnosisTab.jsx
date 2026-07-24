import React from 'react';
import DiagnosisUnified from '../components/DiagnosisUnified';

/**
 * 智能诊断 TAB（整合版，M2）。
 *
 * 直接渲染整合后的统一视图 DiagnosisUnified（合并原「智能诊断」+「智能洞察诊断驾驶舱」）。
 * cockpit 独立 TAB 已移除（docs/system_design.md §1.4 / Q3）；diagnosis 键即指向此统一组件。
 */
function DiagnosisTab() {
  return <DiagnosisUnified />;
}

export default DiagnosisTab;
