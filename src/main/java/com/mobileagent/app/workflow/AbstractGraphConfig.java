package com.mobileagent.app.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Graph配置基类 - 提取4个子Graph共享的基础设施代码
 *
 * 子Graph共享的模式:
 * - extractJson / getLatestInput / getStringValue 等工具方法
 * - callExtractModel (buildExtractPrompt + LLM call + parseExtractResult)
 * - 取消信号处理:
 *   - cancelAwareExtractParams: 自动拦截_cancelSignal + LLM取消意图检测, 子类无需关心
 *   - cancelAwareParamRouter: 自动拦截_cancelSignal, 子类只需关心业务路由
 *   - addCancelNode: 一行代码添加cancelExecution节点+END边
 *   - addCancelEdge: 一行代码给paramRouter的edges map添加CANCEL路由
 *   - detectCancelFromInput: 关键字+LLM分层检测取消意图(流程统一,关键字和提示词均可定制)
 * - 公共KeyStrategy注册 (messages, _latestUserInput, _question, _cancelSignal等)
 * - SaverConfig + CompileConfig构建
 * - ask节点条件路由 (CONTINUE→paramRouter / WAIT→END)
 *
 * 子类只需实现:
 * - getGraphName(): Graph名称 (用于日志)
 * - buildExtractPrompt(): 提取参数的prompt
 * - parseExtractResult(): 解析LLM返回的JSON
 * - registerCustomKeys(): 注册Graph专用的state keys
 * - getCancelDetectionContext(): 取消检测的上下文描述(如"正在询问收款人")
 *
 * 子类可覆盖:
 * - onCleanup(): 取消时的自定义清理逻辑 (如释放资源、回滚等)
 */
@Slf4j
public abstract class AbstractGraphConfig {

    protected final ChatModel chatModel;
    protected final ObjectMapper objectMapper;

    protected AbstractGraphConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    // ==================== 子类必须实现 ====================

    /** Graph名称, 用于日志 */
    protected abstract String getGraphName();

    /** 构建参数提取prompt */
    protected abstract String buildExtractPrompt(String userInput);

    /** 解析LLM返回的提取结果JSON */
    protected abstract Map<String, Object> parseExtractResult(String content);

    /** 注册Graph专用的state keys (如transfer.receiver, bill.timePeriod等) */
    protected abstract void registerCustomKeys(Map<String, KeyStrategy> strategies);

    /**
     * 取消检测上下文 - 子类必须实现, 描述当前Graph正在做什么
     *
     * 用于LLM判断用户是否想取消, 例如:
     * - TransferGraph: "正在向用户询问转账收款人"
     * - BillQueryGraph: "正在向用户询问账单查询时间范围"
     * - WealthConsultGraph: "正在向用户询问风险偏好"
     * - WealthInterpretGraph: "正在向用户询问理财产品名称"
     */
    protected abstract String getCancelDetectionContext();

    /**
     * 子Graph专属取消关键字 - 子类可覆盖以添加领域特定的取消关键词
     *
     * 基类提供通用取消关键词, 子类调用super后追加自己的关键词:
     *   protected List<String> getCancelKeywords() {
     *       List<String> keywords = new ArrayList<>(super.getCancelKeywords());
     *       keywords.addAll(List.of("不转了", "别转了", "取消转账"));
     *       return keywords;
     *   }
     *
     * 匹配流程: 先匹配关键字(0ms), 不匹配再调LLM(200-500ms)
     */
    protected List<String> getCancelKeywords() {
        return List.of("取消", "算了", "不要了", "放弃", "不了");
    }

    // ==================== 公共工具方法 ====================

    /** 从OverAllState获取最新的用户输入 */
    protected String getLatestInput(OverAllState state) {
        Object input = state.value("_latestUserInput").orElse(null);
        if (input != null && !input.toString().isEmpty()) {
            return input.toString();
        }
        // fallback: 从messages取最后一条
        Object messages = state.value("messages").orElse(null);
        if (messages instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(list.size() - 1));
        }
        return "";
    }

    /** 从OverAllState获取字符串值 */
    protected String getStringValue(OverAllState state, String key) {
        Object value = state.value(key).orElse(null);
        return value != null ? value.toString() : null;
    }

    /** 调用LLM提取参数: buildExtractPrompt → LLM call → parseExtractResult */
    protected Map<String, Object> callExtractModel(String userInput) {
        String prompt = buildExtractPrompt(userInput);
        long startMs = System.currentTimeMillis();
        ChatResponse response = chatModel.call(new Prompt(prompt));
        long elapsedMs = System.currentTimeMillis() - startMs;
        String content = response.getResult().getOutput().getText();
        log.info("[{}.callExtractModel] LLM call completed in {}ms | input={}", getGraphName(), elapsedMs, userInput);
        log.debug("[{}.callExtractModel] LLM raw response: {}", getGraphName(), content);
        return parseExtractResult(content);
    }

    /** 从LLM返回内容中提取JSON */
    protected String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) trimmed = trimmed.substring(7);
        else if (trimmed.startsWith("```")) trimmed = trimmed.substring(3);
        if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        trimmed = trimmed.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    // ==================== 取消信号处理 ====================

    /** 检查是否收到取消信号 */
    protected boolean isCancelled(OverAllState state) {
        Object signal = state.value("_cancelSignal").orElse(null);
        return Boolean.TRUE.equals(signal);
    }

    /**
     * 使用关键字+LLM检测用户输入是否表达取消意图
     *
     * 检测策略 (分层优化):
     * 1. 关键字匹配 (0ms): 常见取消表达如"取消""算了""不转了"等直接命中
     * 2. LLM判断 (200-500ms): 关键字未命中时, 由LLM理解上下文判断
     *
     * 子类定制:
     * - getCancelKeywords(): 配置领域特定关键词 (如Transfer: "不转了""别转了")
     * - getCancelDetectionContext(): LLM判断时的上下文描述
     *
     * @param state 当前Graph状态
     * @return true表示用户想取消当前操作
     */
    protected boolean detectCancelFromInput(OverAllState state) {
        String userInput = getLatestInput(state);
        if (userInput == null || userInput.isBlank()) return false;

        // 已有cancel信号则无需再检测
        if (isCancelled(state)) return true;

        // 第1层: 关键字匹配 (0ms, 快速路径)
        List<String> keywords = getCancelKeywords();
        for (String keyword : keywords) {
            if (userInput.contains(keyword)) {
                log.info("[{}.detectCancel] Keyword matched: keyword={}, input={}", getGraphName(), keyword, userInput);
                return true;
            }
        }

        // 第2层: LLM判断 (200-500ms, 兜底路径)
        String context = getCancelDetectionContext();
        String prompt = String.format("""
            你是手机银行智能助手。判断用户是否想取消/放弃/中断当前操作。

            当前场景: %s
            用户输入: %s

            判断标准:
            - 用户明确表达不想继续(如"取消""算了""不要了""不查了""不转了""别转了")
            - 用户表达的否定意图针对当前操作, 而非回答问题
            - 简单的否定回答(如"不是""不对")不算取消

            只输出JSON:
            {"cancel": true或false}
            """, context, userInput);

        try {
            long startMs = System.currentTimeMillis();
            ChatResponse response = chatModel.call(new Prompt(prompt));
            long elapsedMs = System.currentTimeMillis() - startMs;
            String content = response.getResult().getOutput().getText().trim();
            log.info("[{}.detectCancel] LLM call completed in {}ms | input={}", getGraphName(), elapsedMs, userInput);
            String json = extractJson(content);
            var node = objectMapper.readTree(json);
            boolean cancel = node.has("cancel") && node.get("cancel").asBoolean();
            if (cancel) {
                log.info("[{}.detectCancel] LLM detected cancel intent: input={}", getGraphName(), userInput);
            } else {
                log.debug("[{}.detectCancel] LLM no cancel: input={}", getGraphName(), userInput);
            }
            return cancel;
        } catch (Exception e) {
            log.warn("[{}.detectCancel] LLM call failed, defaulting to no cancel", getGraphName(), e);
            return false;
        }
    }

    /**
     * 取消时的自定义清理逻辑 - 子类可覆盖
     *
     * 典型场景: 释放锁、回滚临时数据、记录审计日志等
     * 默认实现: 无操作
     *
     * @param state 当前Graph状态 (包含已收集的参数)
     * @return 需要写入state的额外数据 (通常返回Map.of())
     */
    protected Map<String, Object> onCleanup(OverAllState state) {
        return Map.of();
    }

    /**
     * 取消执行节点 - 调用子类onCleanup()后设置取消输出并终止Graph
     */
    protected Map<String, Object> cancelExecutionNode(OverAllState state) {
        log.info("[{}.cancelExecution] Cancel signal received, calling cleanup", getGraphName());
        Map<String, Object> cleanupResult = onCleanup(state);
        Map<String, Object> result = new HashMap<>();
        result.put("_outputContent", "操作已取消");
        result.put("_outputType", "TEXT");
        result.put("_isFinal", true);
        result.put("_cancelSignal", null);
        if (cleanupResult != null && !cleanupResult.isEmpty()) {
            result.putAll(cleanupResult);
        }
        return result;
    }

    /**
     * 带取消检查的extractParams - 自动拦截_cancelSignal + 关键字/LLM取消检测,子类无需关心
     *
     * 子类的extractParamsNode实现应使用此方法包装:
     *   private Map<String, Object> extractParamsNode(OverAllState state) {
     *       Map<String, Object> cancelResult = cancelAwareExtractParams(state);
     *       if (cancelResult != null) return cancelResult;
     *       // ... 原有extractParams逻辑 ...
     *   }
     *
     * 检测逻辑 (分层优化):
     * 1. 检查_cancelSignal信号(由cancelGraph()注入)
     * 2. 关键字匹配 (0ms) - 常见取消表达直接命中
     * 3. LLM判断 (200-500ms) - 关键字未命中时的兜底
     *
     * @return 非null表示检测到取消(子类应直接return此结果), null表示正常执行
     */
    protected Map<String, Object> cancelAwareExtractParams(OverAllState state) {
        if (isCancelled(state)) {
            log.info("[{}.extractParams] Cancel signal detected, skipping LLM", getGraphName());
            return Map.of("_cancelSignal", true);
        }
        if (detectCancelFromInput(state)) {
            log.info("[{}.extractParams] Cancel intent detected from user input, injecting _cancelSignal", getGraphName());
            return Map.of("_cancelSignal", true);
        }
        return null;
    }

    /**
     * 带取消检查的paramRouter - 自动拦截_cancelSignal,子类无需关心
     *
     * 子类的paramRouterNode实现应使用此方法包装:
     *   private Map<String, Object> paramRouterNode(OverAllState state) {
     *       Map<String, Object> cancelResult = cancelAwareParamRouter(state);
     *       if (cancelResult != null) return cancelResult;
     *       // ... 原有paramRouter逻辑 ...
     *   }
     *
     * @return 非null表示检测到取消信号(子类应直接return此结果), null表示正常执行
     */
    protected Map<String, Object> cancelAwareParamRouter(OverAllState state) {
        if (isCancelled(state)) {
            log.info("[{}.paramRouter] Cancel signal → CANCEL", getGraphName());
            return Map.of("_paramName", "CANCEL");
        }
        return null;
    }

    /**
     * 添加cancelExecution节点 + END边 - 子类Graph构建时调用
     *
     * 用法: 在graph构建链中调用
     *   .addNode("cancelExecution", node_async(this::cancelExecutionNode))
     *   ...
     *   addCancelEdge(graph);
     */
    protected void addCancelNode(StateGraph graph) throws GraphStateException {
        graph.addNode("cancelExecution", node_async(this::cancelExecutionNode))
             .addEdge("cancelExecution", END);
    }

    /**
     * 给paramRouter的条件边map添加CANCEL路由 - 子类Graph构建时调用
     *
     * 用法:
     *   Map<String, String> edges = new HashMap<>(Map.of(
     *       "ASK_TIME", "askTime",
     *       "ALL_GOOD", "executeBillQuery"
     *   ));
     *   addCancelEdge(edges);
     */
    protected void addCancelEdge(Map<String, String> edges) {
        edges.put("CANCEL", "cancelExecution");
    }

    /**
     * 创建带取消检查的paramRouter条件路由函数
     *
     * 用法:
     *   .addConditionalEdges("paramRouter",
     *       createCancelAwareRouter(),
     *       edgeMap)
     */
    protected AsyncEdgeAction createCancelAwareRouter() {
        return edge_async(state -> {
            if (isCancelled(state)) return "CANCEL";
            Object param = state.value("_paramName").orElse("ALL_GOOD");
            return param.toString();
        });
    }

    // ==================== 公共构建方法 ====================

    /** 创建包含公共keys + 子类自定义keys的KeyStrategyFactory */
    protected KeyStrategyFactory createKeyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            // 公共keys
            strategies.put("messages", new AppendStrategy());
            strategies.put("_latestUserInput", new ReplaceStrategy());
            strategies.put("_question", new ReplaceStrategy());
            strategies.put("_paramName", new ReplaceStrategy());
            strategies.put("_outputContent", new ReplaceStrategy());
            strategies.put("_outputType", new ReplaceStrategy());
            strategies.put("_isFinal", new ReplaceStrategy());
            strategies.put("_cancelSignal", new ReplaceStrategy());
            // 子类自定义keys
            registerCustomKeys(strategies);
            return strategies;
        };
    }

    /** 构建带MemorySaver的SaverConfig */
    protected SaverConfig createSaverConfig() {
        return SaverConfig.builder()
                .register(new MemorySaver())
                .build();
    }

    /** 构建CompileConfig: saverConfig + interruptBefore + recursionLimit */
    protected CompileConfig createCompileConfig(String... interruptBeforeNodes) {
        return CompileConfig.builder()
                .saverConfig(createSaverConfig())
                .interruptBefore(interruptBeforeNodes)
                .recursionLimit(50)
                .build();
    }

    /** 创建ask节点的条件路由 (有用户输入→paramRouter, 无→END) */
    protected void addAskConditionalEdges(StateGraph graph, String askNodeName) throws GraphStateException {
        graph.addConditionalEdges(askNodeName,
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state -> {
                    String input = getLatestInput(state);
                    boolean hasInput = input != null && !input.isEmpty();
                    log.info("[{}.{}→route] hasInput={} → {}", getGraphName(), askNodeName, hasInput, hasInput ? "CONTINUE" : "WAIT");
                    return hasInput ? "CONTINUE" : "WAIT";
                }),
                Map.of("CONTINUE", "paramRouter", "WAIT", StateGraph.END));
    }
}
