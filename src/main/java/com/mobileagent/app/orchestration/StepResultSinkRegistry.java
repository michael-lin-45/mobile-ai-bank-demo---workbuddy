package com.mobileagent.app.orchestration;

import com.mobileagent.app.data.StreamChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;

/**
 * L2子图流式输出桥接注册表
 *
 * <p>问题：L2GraphTool.call()阻塞等待L2子图结果，中间chunk（流式文本增量）被丢弃。
 * 方案：用Sinks.Many桥接L2子图的非终结chunk到编排层的Flux管道。
 *
 * <ul>
 *   <li>OrchestrationAgent在启动编排时注册sink（key=graphThreadId）</li>
 *   <li>L2GraphTool在执行L2子图时，将非终结chunk转发到sink</li>
 *   <li>OrchestrationAgent将sink的flux合并到主SSE输出管道</li>
 *   <li>编排结束时注销sink</li>
 * </ul>
 */
@Slf4j
@Component
public class StepResultSinkRegistry {

    private final ConcurrentHashMap<String, Sinks.Many<StreamChunk>> sinks = new ConcurrentHashMap<>();

    /** 注册一个sink，返回sink引用供OrchestrationAgent使用 */
    public Sinks.Many<StreamChunk> register(String graphThreadId) {
        Sinks.Many<StreamChunk> sink = Sinks.many().replay().limit(1024);
        sinks.put(graphThreadId, sink);
        log.info("[StepResultSinkRegistry] Registered sink for graphThreadId={}", graphThreadId);
        return sink;
    }

    /** 注销sink */
    public void unregister(String graphThreadId) {
        Sinks.Many<StreamChunk> sink = sinks.remove(graphThreadId);
        if (sink != null) {
            sink.tryEmitComplete();
            log.info("[StepResultSinkRegistry] Unregistered sink for graphThreadId={}", graphThreadId);
        }
    }

    /** 获取sink，供L2GraphTool使用 */
    public Sinks.Many<StreamChunk> getSink(String graphThreadId) {
        Sinks.Many<StreamChunk> sink = sinks.get(graphThreadId);
        log.info("[StepResultSinkRegistry][DEBUG-DUP] getSink: graphThreadId={}, exists={}", graphThreadId, sink != null);
        return sink;
    }

    /** 获取sink的Flux，供OrchestrationAgent合并到主管道 */
    public Flux<StreamChunk> getFlux(String graphThreadId) {
        Sinks.Many<StreamChunk> sink = sinks.get(graphThreadId);
        log.info("[StepResultSinkRegistry][DEBUG-DUP] getFlux: graphThreadId={}, exists={}", graphThreadId, sink != null);
        return sink != null ? sink.asFlux() : Flux.empty();
    }
}
