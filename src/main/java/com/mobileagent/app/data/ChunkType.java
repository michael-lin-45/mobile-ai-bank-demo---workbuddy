package com.mobileagent.app.data;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 流式传输chunk类型枚举
 *
 * 中间态: CHUNK — 流式文本增量
 * 终结态: COMPLETE/INTERRUPTED/DISAMBIGUATION/ERROR/REROUTE — 一个Flux有且仅有1个终结chunk
 */
public enum ChunkType {

    /** 流式文本增量 — 中间态 */
    CHUNK("CHUNK"),
    /** 完成 — 终结态 */
    COMPLETE("COMPLETE"),
    /** 中断 — 终结态 */
    INTERRUPTED("INTERRUPTED"),
    /** 消歧 — 终结态 */
    DISAMBIGUATION("DISAMBIGUATION"),
    /** 错误 — 终结态 */
    ERROR("ERROR"),
    /** 重新路由 — 终结态（L1→L0信号，不暴露前端） */
    REROUTE("REROUTE");

    private final String value;

    ChunkType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
