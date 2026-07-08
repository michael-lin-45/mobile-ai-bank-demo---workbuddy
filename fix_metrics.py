
import sys

fpath = r"D:\GitHub\mobile-ai-bank-demo - workbuddy\observability\backend\src\main\java\com\observability\service\MetricsQueryService.java"

with open(fpath, "r", encoding="utf-8-sig") as f:
    content = f.read()

# 1. Change fallback window from 24h to 7d
content = content.replace(
    "Instant h2From = Instant.now().minusSeconds(86400);",
    "Instant h2From = Instant.now().minusSeconds(7 * 86400);"
)

# 2. Change token fallback from sumMetricValue to getLatestMetricValue
content = content.replace(
    'long h2Input = sumMetricValue("llm.token.input", h2From, h2To);',
    'long h2Input = getLatestMetricValue("llm.token.input", h2From, h2To);'
)
content = content.replace(
    'long h2Output = sumMetricValue("llm.token.output", h2From, h2To);',
    'long h2Output = getLatestMetricValue("llm.token.output", h2From, h2To);'
)

# 3. Change errorCount fallback from sumMetricValue to getLatestMetricValue  
content = content.replace(
    'long h2Err = sumMetricValue("llm.error.count", h2From, h2To);',
    'long h2Err = getLatestMetricValue("llm.error.count", h2From, h2To);'
)

# 4. Add getLatestMetricValue method after sumMetricValue method
# Find the end of sumMetricValue method
old_method_end = """    private long sumMetricValue(String metricName, Instant from, Instant to) {
        try {
            return metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to)
                    .stream()
                    .mapToLong(m -> m.getValue() != null ? m.getValue().longValue() : 0L)
                    .sum();
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 fallback sum failed for {}: {}", metricName, e.getMessage());
            return 0L;
        }
    }"""

new_method_end = old_method_end + """

    /** Get the latest (most recent) metric value - for cumulative counters */
    private long getLatestMetricValue(String metricName, Instant from, Instant to) {
        try {
            var list = metricsAggRepository
                    .findByMetricNameAndTimestampBetweenOrderByTimestampAsc(metricName, from, to);
            if (list.isEmpty()) return 0L;
            // Return the last (most recent) value for cumulative counters
            return list.get(list.size() - 1).getValue() != null
                    ? list.get(list.size() - 1).getValue().longValue() : 0L;
        } catch (Exception e) {
            log.debug("[MetricsQuery] H2 latest value failed for {}: {}", metricName, e.getMessage());
            return 0L;
        }
    }"""

content = content.replace(old_method_end, new_method_end)

with open(fpath, "w", encoding="utf-8") as f:
    f.write(content)

# Verify
with open(fpath, "r", encoding="utf-8") as f:
    c = f.read()

checks = [
    ("7 * 86400", "7 * 86400" in c),
    ("getLatestMetricValue for token.input", "getLatestMetricValue" in c),
    ("new method added", "Get the latest" in c),
]
for label, ok in checks:
    print(f"{'OK' if ok else 'FAIL'}: {label}")
