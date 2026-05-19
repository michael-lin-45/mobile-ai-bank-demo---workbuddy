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
 * - 取消信号处理 (isCancelled / cancelExecutionNode / onCleanup)
 *   - cancelAwareExtractParams: 自动拦截_cancelSignal, 子类无需关心
 *   - cancelAwareParamRouter: 自动拦截_cancelSignal, 子类只需关心业务路由
 *   - addCancelNode: 一行代码添加cancelExecution节点+END边
 *   - addCancelEdge: 一行代码给paramRouter的edges map添加CANCEL路由
 * - 公共KeyStrategy注册 (messages, _latestUserInput, _question, _cancelSignal等)
 * - SaverConfig + CompileConfig构建
 * - ask节点条件路由 (CONTINUE→paramRouter / WAIT→END)
 *
 * 子类只需实现:
 * - getGraphName(): Graph名称 (用于日志)
 * - buildExtractPrompt(): 提取参数的prompt
 * - parseExtractResult(): 解析LLM返回的JSON
 * - registerCustomKeys(): 注册Graph专用的state keys
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
        ChatResponse response = chatModel.call(new Prompt(prompt));
        String content = response.getResult().getOutput().getText();
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
     * 带取消检查的extractParams - 自动拦截_cancelSignal,子类无需关心
     *
     * 子类的extractParamsNode实现应使用此方法包装:
     *   private Map<String, Object> extractParamsNode(OverAllState state) {
     *       if (cancelAwareExtractParams(state)) return Map.of();
     *       // ... 原有extractParams逻辑 ...
     *   }
     *
     * @return true表示检测到取消信号(子类应直接return Map.of()), false表示正常执行
     */
    protected boolean cancelAwareExtractParams(OverAllState state) {
        if (isCancelled(state)) {
            log.info("[{}.extractParams] Cancel signal detected, skipping LLM", getGraphName());
            return true;
        }
        return false;
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
