package com.observability.dto;

import java.util.List;

/**
 * 请求 & Token 趋势视图对象 — 对应 GET /api/v1/metrics/trend 响应
 *
 * 前端 TrendChart 期望结构：{ times: string[], requests: number[], tokens: number[] }
 */
public class TrendVO {

    private List<String> times;
    private List<Long> requests;
    private List<Long> tokens;

    public TrendVO() {}

    public TrendVO(List<String> times, List<Long> requests, List<Long> tokens) {
        this.times = times;
        this.requests = requests;
        this.tokens = tokens;
    }

    public List<String> getTimes() { return times; }
    public void setTimes(List<String> times) { this.times = times; }

    public List<Long> getRequests() { return requests; }
    public void setRequests(List<Long> requests) { this.requests = requests; }

    public List<Long> getTokens() { return tokens; }
    public void setTokens(List<Long> tokens) { this.tokens = tokens; }
}
