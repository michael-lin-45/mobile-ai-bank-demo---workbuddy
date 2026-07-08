package com.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OTLP Trace JSON Payload
 *
 * 标准 OTLP JSON 格式:
 * <pre>{@code
 * {
 *   "resourceSpans": [{
 *     "resource": { "attributes": [...] },
 *     "scopeSpans": [{
 *       "scope": { "name": "...", "version": "..." },
 *       "spans": [{
 *         "traceId": "...", "spanId": "...", "parentSpanId": "...",
 *         "name": "...", "kind": 1, "startTimeUnixNano": "...",
 *         "endTimeUnixNano": "...", "status": { "code": 1 },
 *         "attributes": [...]
 *       }]
 *     }]
 *   }]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OtlpTracePayload {

    @JsonProperty("resourceSpans")
    private List<ResourceSpan> resourceSpans;

    public List<ResourceSpan> getResourceSpans() { return resourceSpans; }
    public void setResourceSpans(List<ResourceSpan> resourceSpans) { this.resourceSpans = resourceSpans; }

    // ── ResourceSpan ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceSpan {
        private OtlpMetricPayload.Resource resource;
        @JsonProperty("scopeSpans")
        private List<ScopeSpan> scopeSpans;

        public OtlpMetricPayload.Resource getResource() { return resource; }
        public void setResource(OtlpMetricPayload.Resource resource) { this.resource = resource; }
        public List<ScopeSpan> getScopeSpans() { return scopeSpans; }
        public void setScopeSpans(List<ScopeSpan> scopeSpans) { this.scopeSpans = scopeSpans; }

        public String getServiceName() {
            return resource != null ? resource.getServiceName() : "unknown";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScopeSpan {
        private OtlpMetricPayload.Scope scope;
        private List<Span> spans;

        public OtlpMetricPayload.Scope getScope() { return scope; }
        public void setScope(OtlpMetricPayload.Scope scope) { this.scope = scope; }
        public List<Span> getSpans() { return spans; }
        public void setSpans(List<Span> spans) { this.spans = spans; }
    }

    // ── Span ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Span {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String name;
        private int kind; // 1=INTERNAL, 2=SERVER, 3=CLIENT
        private String startTimeUnixNano;
        private String endTimeUnixNano;
        private SpanStatus status;
        private List<OtlpMetricPayload.Attribute> attributes;

        // 从 attributes 提取自定义属性 JSON
        public String extractAttributesJson() {
            if (attributes == null || attributes.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var attr : attributes) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(attr.getKey()).append("\":\"");
                var v = attr.getValue();
                String sv = v != null ? v.getStringValue() : "";
                // 转义 JSON 中需要转义的字符
                sv = sv.replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append(sv).append("\"");
            }
            sb.append("}");
            return sb.toString();
        }

        /** 从 attributes 中提取 span 级别的 IO prompt */
        public String extractIoPrompt() {
            if (attributes == null) return null;
            return attributes.stream()
                    .filter(a -> "ai.io.prompt".equals(a.getKey()))
                    .map(a -> a.getValue() != null ? a.getValue().getStringValue() : null)
                    .findFirst().orElse(null);
        }

        /** 从 attributes 中提取 span 级别的 IO response */
        public String extractIoResponse() {
            if (attributes == null) return null;
            return attributes.stream()
                    .filter(a -> "ai.io.response".equals(a.getKey()))
                    .map(a -> a.getValue() != null ? a.getValue().getStringValue() : null)
                    .findFirst().orElse(null);
        }

        /** 从 attributes 中提取 token 信息 */
        public String extractTokenBreakdown() {
            if (attributes == null) return null;
            int sys = 0, ctx = 0, out = 0;
            for (var attr : attributes) {
                try {
                    switch (attr.getKey()) {
                        case "ai.token.system":
                            sys = Integer.parseInt(attr.getValue().getStringValue());
                            break;
                        case "ai.token.context":
                            ctx = Integer.parseInt(attr.getValue().getStringValue());
                            break;
                        case "ai.token.output":
                            out = Integer.parseInt(attr.getValue().getStringValue());
                            break;
                    }
                } catch (Exception ignored) {}
            }
            if (sys == 0 && ctx == 0 && out == 0) return null;
            return "{\"system\":" + sys + ",\"context\":" + ctx + ",\"output\":" + out + "}";
        }

        /** nano 字符串转毫秒 long */
        public static long nanoToMillis(String nanoStr) {
            if (nanoStr == null) return 0;
            try { return Long.parseLong(nanoStr) / 1_000_000; }
            catch (NumberFormatException e) { return 0; }
        }

        public static String kindName(int kind) {
            return switch (kind) {
                case 1 -> "INTERNAL";
                case 2 -> "SERVER";
                case 3 -> "CLIENT";
                default -> "UNSPECIFIED";
            };
        }

        // ── Getters/Setters ──

        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
        public String getSpanId() { return spanId; }
        public void setSpanId(String spanId) { this.spanId = spanId; }
        public String getParentSpanId() { return parentSpanId; }
        public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getKind() { return kind; }
        public void setKind(int kind) { this.kind = kind; }
        public String getStartTimeUnixNano() { return startTimeUnixNano; }
        public void setStartTimeUnixNano(String startTimeUnixNano) { this.startTimeUnixNano = startTimeUnixNano; }
        public String getEndTimeUnixNano() { return endTimeUnixNano; }
        public void setEndTimeUnixNano(String endTimeUnixNano) { this.endTimeUnixNano = endTimeUnixNano; }
        public SpanStatus getStatus() { return status; }
        public void setStatus(SpanStatus status) { this.status = status; }
        public List<OtlpMetricPayload.Attribute> getAttributes() { return attributes; }
        public void setAttributes(List<OtlpMetricPayload.Attribute> attributes) { this.attributes = attributes; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpanStatus {
        private int code; // 0=UNSET, 1=OK, 2=ERROR
        private String message;

        public int getCode() { return code; }
        public void setCode(int code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String codeName() {
            return switch (code) {
                case 1 -> "OK";
                case 2 -> "ERROR";
                default -> "UNSET";
            };
        }
    }

    /** 获取服务名 */
    public String extractServiceName() {
        if (resourceSpans != null && !resourceSpans.isEmpty()) {
            return resourceSpans.get(0).getServiceName();
        }
        return "unknown";
    }
}
