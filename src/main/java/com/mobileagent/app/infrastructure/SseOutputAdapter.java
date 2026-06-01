package com.mobileagent.app.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobileagent.app.data.StreamChunk;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * SSE输出适配器 — 唯一知道SSE格式的组件
 *
 * 使用SseEmitter（Spring MVC原生），与Spring AI Alibaba官方做法一致:
 *   Flux<StreamChunk> → subscribe → emitter.send() → emitter.complete()
 *
 * 职责:
 * 1. StreamChunk → JSON字符串 → emitter.send()
 * 2. 流结束时发送 [DONE]（OpenAI格式）
 * 3. 设置SSE响应头
 *
 * 不关心业务语义（isTerminal、ChatMemory等），只做格式转换
 * L0/L1/L2通过StreamChunk的isTerminal()判断流结束，不依赖SSE
 */
@Slf4j
@Component
public class SseOutputAdapter {

    private static final long DEFAULT_TIMEOUT = 0L; // 无超时（由Flux控制生命周期）

    private final ObjectMapper objectMapper;

    public SseOutputAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Flux<StreamChunk> → OpenAI兼容SSE流
     *
     * @param pipeline  业务管道（Flux<StreamChunk>）
     * @param response  HttpServletResponse，用于设置SSE响应头
     * @return SseEmitter — Spring MVC自动处理SSE输出
     *
     * 输出示例:
     *   data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"根据"}
     *   data: {"type":"CHUNK","intent":"WEALTH_INTERPRET","content":"产品信息"}
     *   data: {"type":"COMPLETE","intent":"WEALTH_INTERPRET"}
     *   data: [DONE]
     */
    public SseEmitter toSse(Flux<StreamChunk> pipeline, HttpServletResponse response) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);

        // 设置SSE响应头（防止Nginx等反向代理缓冲）
        response.addHeader("X-Accel-Buffering", "no");
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);

        pipeline
            .doOnNext(chunk -> {
                try {
                    emitter.send(serialize(chunk), MediaType.TEXT_EVENT_STREAM);
                } catch (Exception e) {
                    log.warn("[SseAdapter] Failed to send chunk: {}", e.getMessage());
                    emitter.completeWithError(e);
                }
            })
            .doOnComplete(() -> {
                try {
                    emitter.send("[DONE]", MediaType.TEXT_EVENT_STREAM);
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("[SseAdapter] Failed to send [DONE]: {}", e.getMessage());
                    emitter.completeWithError(e);
                }
            })
            .doOnError(emitter::completeWithError)
            .subscribe();

        return emitter;
    }

    /**
     * Flux<StreamChunk> → JSON响应（非SSE模式）
     *
     * 取终结chunk → WorkflowOutput → block返回
     */
    public Object toJson(Flux<StreamChunk> pipeline) {
        return pipeline
                .filter(StreamChunk::isTerminal)
                .last()
                .map(StreamChunk::toWorkflowOutput)
                .block();
    }

    private String serialize(StreamChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"ERROR\",\"errorMessage\":\"JSON serialization failed\"}";
        }
    }
}
