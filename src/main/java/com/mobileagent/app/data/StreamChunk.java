package com.mobileagent.app.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 流式传输单元 — L0/L1/GES之间的唯一数据载体
 *
 * 两类chunk:
 * - 中间态: CHUNK — 流式文本增量，前端追加显示，ChatMemory累积但不写入
 * - 终结态: COMPLETE/INTERRUPTED/DISAMBIGUATION/ERROR/REROUTE
 *   一个Flux<StreamChunk>有且仅有1个终结chunk（最后一个元素）
 *
 * ChatMemory写入（由StreamingChatMemoryWriter统一处理）:
 * - 流式Graph: 终结chunk不带content，完整文本从累积的CHUNK拼接
 * - 非流式Graph: 终结chunk带content，直接使用
 * - 判断逻辑: 累积器有内容用累积器，没有就用终结chunk.getReplyContent()
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamChunk {

    private ChunkType type;
    private String intent;

    /**
     * 统一文本字段
     * - CHUNK时: 增量文本（如 "根据"、"产品信息"、"分析..."）
     * - COMPLETE时（非流式Graph）: 完整文本（如 "转账成功！已向..."）
     * - COMPLETE时（流式Graph）: null（前端已通过CHUNK获得全部文本）
     *
     * 前端统一读 content，按 type 判断是追加(CHUNK)还是显示/结束(COMPLETE)
     */
    private String content;

    /** 提问 (INTERRUPTED/DISAMBIGUATION时) */
    private String question;

    /** 错误信息 (ERROR时) */
    private String errorMessage;

    /** 候选意图 (DISAMBIGUATION时) */
    private List<String> candidateIntents;

    /** REROUTE信息 — 仅L1→L0内部信号，不暴露给前端 */
    @JsonIgnore
    private String rerouteIntent;
    @JsonIgnore
    private String rerouteHint;

    // ==================== 工厂方法 ====================

    /** 流式文本增量 — content承载增量文本 */
    public static StreamChunk chunk(String intent, String text) {
        return StreamChunk.builder().type(ChunkType.CHUNK).intent(intent).content(text).build();
    }

    /** 非流式完成 — content承载完整文本 */
    public static StreamChunk complete(String intent, String content) {
        return StreamChunk.builder().type(ChunkType.COMPLETE).intent(intent).content(content).build();
    }

    /** 流式完成 — 只是结束信号，不带content */
    public static StreamChunk streamingDone(String intent) {
        return StreamChunk.builder().type(ChunkType.COMPLETE).intent(intent).build();
    }

    /** 中断 */
    public static StreamChunk interrupted(String intent, String question) {
        return StreamChunk.builder().type(ChunkType.INTERRUPTED).intent(intent).question(question).build();
    }

    /** 消歧 */
    public static StreamChunk disambiguation(String question, List<String> candidates) {
        return StreamChunk.builder().type(ChunkType.DISAMBIGUATION).question(question).candidateIntents(candidates).build();
    }

    /** 错误 */
    public static StreamChunk error(String errorMessage) {
        return StreamChunk.builder().type(ChunkType.ERROR).errorMessage(errorMessage).build();
    }

    /** 重新路由 */
    public static StreamChunk reroute(String rerouteIntent, String rerouteHint) {
        return StreamChunk.builder().type(ChunkType.REROUTE).rerouteIntent(rerouteIntent).rerouteHint(rerouteHint).build();
    }

    // ==================== 工具方法 ====================

    /** 是否为终结chunk */
    public boolean isTerminal() {
        return type != ChunkType.CHUNK;
    }

    /** 是否为流式Graph的COMPLETE（不带content） */
    public boolean isStreamingComplete() {
        return type == ChunkType.COMPLETE && content == null;
    }

    /** 提取回复内容 — 供ChatMemory写入（只用终结chunk），不序列化给前端 */
    @JsonIgnore
    public String getReplyContent() {
        return switch (type) {
            case COMPLETE -> content;       // 非流式时有值，流式时为null
            case INTERRUPTED, DISAMBIGUATION -> question;
            case ERROR -> errorMessage;
            case CHUNK, REROUTE -> null;
        };
    }

    /** 转换为WorkflowOutput — 仅用于Accept: application/json场景 */
    public WorkflowOutput toWorkflowOutput() {
        return switch (type) {
            case COMPLETE -> WorkflowOutput.completed(intent, content);
            case INTERRUPTED -> WorkflowOutput.interrupted(intent, question);
            case DISAMBIGUATION -> WorkflowOutput.disambiguation(question, candidateIntents);
            case ERROR -> WorkflowOutput.error(errorMessage);
            case REROUTE -> WorkflowOutput.reroute(rerouteIntent, rerouteHint);
            case CHUNK -> WorkflowOutput.completed(intent, content);
        };
    }

    /** 从WorkflowOutput转换 — 非流式路径GES内部使用 */
    public static StreamChunk fromWorkflowOutput(WorkflowOutput output) {
        return switch (output.getStatus()) {
            case COMPLETED -> complete(output.getIntent(), output.getContent());
            case INTERRUPTED -> interrupted(output.getIntent(), output.getQuestion());
            case DISAMBIGUATION -> disambiguation(output.getQuestion(), output.getCandidateIntents());
            case ERROR -> error(output.getErrorMessage());
            case REROUTE -> reroute(output.getRerouteIntent(), output.getRerouteHint());
        };
    }
}
