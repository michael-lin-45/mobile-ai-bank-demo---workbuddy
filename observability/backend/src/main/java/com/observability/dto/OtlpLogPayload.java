package com.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OTLP Log JSON Payload
 *
 * 标准 OTLP JSON 格式:
 * <pre>{@code
 * {
 *   "resourceLogs": [{
 *     "resource": { "attributes": [...] },
 *     "scopeLogs": [{
 *       "scope": { "name": "...", "version": "..." },
 *       "logRecords": [{
 *         "timeUnixNano": "...", "severityText": "ERROR",
 *         "body": { "stringValue": "..." },
 *         "traceId": "...", "spanId": "...",
 *         "attributes": [...]
 *       }]
 *     }]
 *   }]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OtlpLogPayload {

    @JsonProperty("resourceLogs")
    private List<ResourceLog> resourceLogs;

    public List<ResourceLog> getResourceLogs() { return resourceLogs; }
    public void setResourceLogs(List<ResourceLog> resourceLogs) { this.resourceLogs = resourceLogs; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceLog {
        private OtlpMetricPayload.Resource resource;
        @JsonProperty("scopeLogs")
        private List<ScopeLog> scopeLogs;

        public OtlpMetricPayload.Resource getResource() { return resource; }
        public void setResource(OtlpMetricPayload.Resource resource) { this.resource = resource; }
        public List<ScopeLog> getScopeLogs() { return scopeLogs; }
        public void setScopeLogs(List<ScopeLog> scopeLogs) { this.scopeLogs = scopeLogs; }

        public String getServiceName() {
            return resource != null ? resource.getServiceName() : "unknown";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScopeLog {
        private OtlpMetricPayload.Scope scope;
        @JsonProperty("logRecords")
        private List<LogRecord> logRecords;

        public OtlpMetricPayload.Scope getScope() { return scope; }
        public void setScope(OtlpMetricPayload.Scope scope) { this.scope = scope; }
        public List<LogRecord> getLogRecords() { return logRecords; }
        public void setLogRecords(List<LogRecord> logRecords) { this.logRecords = logRecords; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LogRecord {
        private String timeUnixNano;
        private String observedTimeUnixNano;
        private String severityText;
        private String severityNumber;
        private LogBody body;
        private String traceId;
        private String spanId;
        private List<OtlpMetricPayload.Attribute> attributes;

        public String getTimeUnixNano() { return timeUnixNano; }
        public void setTimeUnixNano(String timeUnixNano) { this.timeUnixNano = timeUnixNano; }
        public String getObservedTimeUnixNano() { return observedTimeUnixNano; }
        public void setObservedTimeUnixNano(String observedTimeUnixNano) { this.observedTimeUnixNano = observedTimeUnixNano; }
        public String getSeverityText() { return severityText; }
        public void setSeverityText(String severityText) { this.severityText = severityText; }
        public String getSeverityNumber() { return severityNumber; }
        public void setSeverityNumber(String severityNumber) { this.severityNumber = severityNumber; }
        public LogBody getBody() { return body; }
        public void setBody(LogBody body) { this.body = body; }
        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
        public String getSpanId() { return spanId; }
        public void setSpanId(String spanId) { this.spanId = spanId; }
        public List<OtlpMetricPayload.Attribute> getAttributes() { return attributes; }
        public void setAttributes(List<OtlpMetricPayload.Attribute> attributes) { this.attributes = attributes; }

        /** 提取消息文本 */
        public String getMessage() {
            if (body != null && body.getStringValue() != null) {
                return body.getStringValue();
            }
            return "";
        }

        /** 提取 attributes JSON */
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
                sv = sv.replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append(sv).append("\"");
            }
            sb.append("}");
            return sb.toString();
        }

        /** nano 字符串转毫秒 */
        public static long nanoToMillis(String nanoStr) {
            if (nanoStr == null) return System.currentTimeMillis();
            try { return Long.parseLong(nanoStr) / 1_000_000; }
            catch (NumberFormatException e) { return System.currentTimeMillis(); }
        }

        /** 标准化日志级别 */
        public String getNormalizedLevel() {
            if (severityText != null) {
                String upper = severityText.toUpperCase();
                if (upper.equals("ERROR") || upper.equals("WARN") || upper.equals("INFO")
                        || upper.equals("DEBUG") || upper.equals("TRACE")) {
                    return upper;
                }
            }
            if (severityNumber != null) {
                try {
                    int num = Integer.parseInt(severityNumber);
                    if (num >= 17) return "ERROR";
                    if (num >= 13) return "WARN";
                    if (num >= 9) return "INFO";
                    return "DEBUG";
                } catch (NumberFormatException ignored) {}
            }
            return "INFO";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LogBody {
        private String stringValue;

        public String getStringValue() { return stringValue; }
        public void setStringValue(String stringValue) { this.stringValue = stringValue; }
    }

    public String extractServiceName() {
        if (resourceLogs != null && !resourceLogs.isEmpty()) {
            return resourceLogs.get(0).getServiceName();
        }
        return "unknown";
    }
}
