package com.mobileagent.app.orchestration.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 条件性回答 — 打岔+条件时设置（S4: 进阶用例）
 *
 * <p>当用户输入复合语句如"先查收入，如果超过1万就转5000"时：
 * <ul>
 *   <li>dependsOnStepId: 条件依赖哪个步骤的结果（通常是新插入的步骤）</li>
 *   <li>condition: 判断条件，如 "收入 > 10000"（结构化格式，复用evaluateCondition评估）</li>
 *   <li>answerIfMet: 条件满足时的答案，如 "5000"</li>
 *   <li>cancelIfNotMet: 条件不满足时是否取消被中断步骤（如"否则就算了"→true）</li>
 * </ul>
 *
 * <p>条件性回答是一次性的——评估后立即清除，不残留。
 */
@Data
public class ConditionalAnswer implements Serializable {
    private String dependsOnStepId;   // 条件依赖的步骤ID（通常是新插入的步骤）
    private String condition;         // 判断条件，如 "收入 > 10000"
    private String answerIfMet;       // 条件满足时的答案，如 "5000"
    private boolean cancelIfNotMet;   // 条件不满足时取消被中断步骤（如"否则就算了"）
}
