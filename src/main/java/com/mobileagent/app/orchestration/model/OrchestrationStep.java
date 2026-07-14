package com.mobileagent.app.orchestration.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class OrchestrationStep implements Serializable {
    private String stepId;          // 编排内唯一稳定ID，如 "s-0", "s-ins-a3f2"
    private int index;              // 位置序号（插入步骤后可能变化）
    private String domain;          // "TRANSFER" / "BILL" / "WEALTH" — from yaml, NOT hardcoded in Java
    private String intent;          // "TRANSFER_QUERY" / "TRANSFER_CONFIRM" / "BILL_QUERY"
    private String description;     // "查询收款人信息"
    private String rewrittenInput;  // 自包含输入（Agent只看到这个）
    private String condition;       // "余额>50000"
    private StepStatus status;
    private String dependsOnStepIndex;
    private String cancelReason;

    // Phase 2A — stepId线程管理 + 打岔恢复
    private String waitingQuestion;            // L2中断时保存的问题（从全局WAITING_QUESTION移到步骤级）
    private String l2ThreadId;                 // L2子图的threadId — 存在步骤上而非图级stepThreadMap，避免框架状态丢失

    // Phase 2D — 条件性回答（打岔+条件时设置）
    private ConditionalAnswer conditionalAnswer; // 条件性回答（如"如果收入>10000就转5000"）

    // Phase 2E — L1 取消意图领养信号
    // 由 StepPreparator 在处理 CANCELLED_L1_INTENTS 时设置，
    // L2GraphTool 检测到此信号后调用 GES cancelAndResumeGraph 接口
    // 注入 _cancelSignal=true 到 L2 子图 state，让子图走 cancelExecution 干净终止
    private boolean cancelSignal;
}
