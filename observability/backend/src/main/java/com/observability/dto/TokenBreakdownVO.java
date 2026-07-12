package com.observability.dto;

/**
 * Token 分解视图对象
 */
public class TokenBreakdownVO {
    private int systemTokens;
    private int contextTokens;
    private int inputTokens;
    private int outputTokens;

    public TokenBreakdownVO() {}

    public TokenBreakdownVO(int systemTokens, int contextTokens, int inputTokens, int outputTokens) {
        this.systemTokens = systemTokens;
        this.contextTokens = contextTokens;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public int getSystemTokens() { return systemTokens; }
    public void setSystemTokens(int systemTokens) { this.systemTokens = systemTokens; }
    public int getContextTokens() { return contextTokens; }
    public void setContextTokens(int contextTokens) { this.contextTokens = contextTokens; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
}
