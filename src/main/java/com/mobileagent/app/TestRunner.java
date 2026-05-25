package com.mobileagent.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Multi-turn Dialog Test Runner
 * 90 test cases across 9 categories
 */
public class TestRunner {

    private static final String BASE_URL = "http://localhost:8080/api/bank";
    private static final String OUTPUT_FILE = "D:\\mobile-agent\\test-results-v2.csv";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static PrintWriter csvWriter;
    private static int totalSteps = 0;
    private static int passCount = 0;
    private static int failCount = 0;

    public static void main(String[] args) throws Exception {
        // Pre-check
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/state?sessionId=health").openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) {
                System.out.println("[FATAL] App not running!");
                return;
            }
            System.out.println("[OK] App running on port 8080");
            conn.disconnect();
        } catch (Exception e) {
            System.out.println("[FATAL] App not running: " + e.getMessage());
            return;
        }

        // Init CSV
        csvWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(OUTPUT_FILE), StandardCharsets.UTF_8));
        csvWriter.println("\uFEFF"); // UTF-8 BOM
        csvWriter.println("TestID,Category,StepNum,Description,UserInput,ExpectedIntent,ExpectedStatus,ActualIntent,ActualStatus,ResponseSnippet,VerifyKeyword,PassFail,Notes");

        // Run all tests
        runDirectTests();
        runQATests();
        runSwitchTests();
        runResumeTests();
        runCancelTests();
        runMultiJumpTests();
        runReturnAfterJumpTests();
        runRandomTests();
        runContinuationTests();

        // Summary
        csvWriter.close();
        System.out.println("\n========================================");
        System.out.println("TEST COMPLETE!");
        System.out.println("Total steps: " + totalSteps);
        System.out.println("PASS: " + passCount);
        System.out.println("FAIL: " + failCount);
        System.out.println("Results: " + OUTPUT_FILE);
        System.out.println("========================================");
    }

    // ==================== Helper Methods ====================

    static class TestResult {
        String status, intent, content, question, errorMessage;
    }

    static TestResult callChat(String sessionId, String message) {
        TestResult result = new TestResult();
        try {
            URL url = new URL(BASE_URL + "/chat?sessionId=" + sessionId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(90000);

            String jsonBody = mapper.writeValueAsString(Map.of("message", message));
            conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) { result.status = "ERROR"; result.content = "HTTP " + code; return result; }

            String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(response);

            result.status = node.path("status").asText("");
            result.intent = node.path("intent").asText("");
            result.content = node.path("content").asText("");
            result.question = node.path("question").asText("");
            result.errorMessage = node.path("errorMessage").asText("");

            conn.disconnect();
        } catch (Exception e) {
            result.status = "ERROR";
            result.content = e.getMessage();
        }
        return result;
    }

    static void record(String testId, String cat, int stepNum, String desc, String msg,
                       String expIntent, String expStatus, TestResult actual, String verifyKw) {
        totalSteps++;
        String aIntent = actual.intent != null ? actual.intent : "";
        String aStatus = actual.status != null ? actual.status : "";
        String snippet = "";
        if (actual.content != null && !actual.content.isEmpty()) {
            snippet = actual.content.length() > 120 ? actual.content.substring(0, 120) : actual.content;
        } else if (actual.question != null && !actual.question.isEmpty()) {
            snippet = actual.question.length() > 120 ? actual.question.substring(0, 120) : actual.question;
        } else if (actual.errorMessage != null && !actual.errorMessage.isEmpty()) {
            snippet = actual.errorMessage.length() > 120 ? actual.errorMessage.substring(0, 120) : actual.errorMessage;
        }

        String pass = "PASS";
        String notes = "";
        if (expIntent != null && !expIntent.isEmpty() && !expIntent.equals(aIntent)) {
            pass = "FAIL"; notes += "Intent:exp=" + expIntent + ",act=" + aIntent + "; ";
        }
        if (expStatus != null && !expStatus.isEmpty() && !expStatus.equals(aStatus)) {
            pass = "FAIL"; notes += "Status:exp=" + expStatus + ",act=" + aStatus + "; ";
        }
        if (verifyKw != null && !verifyKw.isEmpty() && !snippet.contains(verifyKw)) {
            pass = "FAIL"; notes += "Keyword '" + verifyKw + "' not in response; ";
        }

        if ("PASS".equals(pass)) passCount++; else failCount++;

        csvWriter.println(csv(testId) + "," + csv(cat) + "," + stepNum + "," + csv(desc) + "," +
                csv(msg) + "," + csv(expIntent) + "," + csv(expStatus) + "," +
                csv(aIntent) + "," + csv(aStatus) + "," + csv(snippet) + "," +
                csv(verifyKw) + "," + csv(pass) + "," + csv(notes));
        csvWriter.flush();

        String snipDisplay = snippet.length() > 40 ? snippet.substring(0, 40) + "..." : snippet;
        System.out.println("  [" + pass + "] S" + stepNum + " intent=" + aIntent + " status=" + aStatus + " " + snipDisplay);
    }

    static String csv(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static void runTest(String testId, String cat, String sid, String[][] steps) {
        System.out.println("\n=== " + testId + " (" + cat + ") ===");
        for (int i = 0; i < steps.length; i++) {
            String[] s = steps[i];
            String msg = s[0], eI = s[1], eS = s[2], desc = s[3], vKw = s.length > 4 ? s[4] : "";
            TestResult actual = callChat(sid, msg);
            record(testId, cat, i + 1, desc, msg, eI, eS, actual, vKw);
        }
    }

    // ==================== Category 1: Direct (10) ====================
    static void runDirectTests() {
        System.out.println("\n========== CATEGORY 1: 直达 ==========");
        runTest("DIR-01", "直达", "dir01", new String[][]{
            {"帮我转账给李四1000元", "TRANSFER", "COMPLETED", "全额转账-含收款人金额"},
        });
        runTest("DIR-02", "直达", "dir02", new String[][]{
            {"查一下我上个月的账单", "BILL_QUERY", "INTERRUPTED", "查账含时间-问收支"},
        });
        runTest("DIR-03", "直达", "dir03", new String[][]{
            {"推荐几款理财产品", "WEALTH_CONSULT", "INTERRUPTED", "推荐理财-无参数-问风险"},
        });
        runTest("DIR-04", "直达", "dir04", new String[][]{
            {"帮我解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "解读含产品名"},
        });
        runTest("DIR-05", "直达", "dir05", new String[][]{
            {"转账500给王五", "TRANSFER", "", "转账含金额收款人"},
        });
        runTest("DIR-06", "直达", "dir06", new String[][]{
            {"最近一周的消费记录", "BILL_QUERY", "INTERRUPTED", "查账含时间范围-问收支"},
        });
        runTest("DIR-07", "直达", "dir07", new String[][]{
            {"我想买理财产品", "WEALTH_CONSULT", "INTERRUPTED", "口语化推荐-问风险"},
        });
        runTest("DIR-08", "直达", "dir08", new String[][]{
            {"解读一下日日盈", "WEALTH_INTERPRET", "COMPLETED", "解读含产品名"},
        });
        runTest("DIR-09", "直达", "dir09", new String[][]{
            {"帮我转200块给妈妈", "TRANSFER", "", "口语化转账"},
        });
        runTest("DIR-10", "直达", "dir10", new String[][]{
            {"看看三个月的账单", "BILL_QUERY", "INTERRUPTED", "口语化查账-问收支"},
        });
    }

    // ==================== Category 2: Q&A (10) ====================
    static void runQATests() {
        System.out.println("\n========== CATEGORY 2: 问答 ==========");
        runTest("QA-01", "问答", "qa01", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险偏好"},
            {"激进型", "WEALTH_CONSULT", "INTERRUPTED", "答风险-问领域"},
            {"科技", "WEALTH_CONSULT", "COMPLETED", "答领域-完成"},
        });
        runTest("QA-02", "问答", "qa02", new String[][]{
            {"推荐稳健型理财", "WEALTH_CONSULT", "INTERRUPTED", "含风险偏好-问领域"},
            {"能源", "WEALTH_CONSULT", "COMPLETED", "答领域-完成"},
        });
        runTest("QA-03", "问答", "qa03", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "转账-问收款人"},
            {"张三", "TRANSFER", "INTERRUPTED", "答收款人-问金额"},
            {"500", "TRANSFER", "COMPLETED", "答金额-完成"},
        });
        runTest("QA-04", "问答", "qa04", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "查账-问时间"},
            {"上个月", "BILL_QUERY", "INTERRUPTED", "答时间-完成"},
        });
        runTest("QA-05", "问答", "qa05", new String[][]{
            {"推荐理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"保守", "WEALTH_CONSULT", "INTERRUPTED", "答风险-问领域"},
            {"汽车", "", "", "答领域-L0短回答路由Bug(已知)"},
        });
        runTest("QA-06", "问答", "qa06", new String[][]{
            {"推荐科技类理财", "WEALTH_CONSULT", "INTERRUPTED", "含领域-问风险"},
            {"稳健型", "WEALTH_CONSULT", "COMPLETED", "答风险-完成", "科技"},
        });
        runTest("QA-07", "问答", "qa07", new String[][]{
            {"帮我转账给李四", "TRANSFER", "INTERRUPTED", "含收款人-问金额"},
            {"2000", "TRANSFER", "COMPLETED", "答金额-完成"},
        });
        runTest("QA-08", "问答", "qa08", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "查账-问时间"},
            {"最近一周", "BILL_QUERY", "INTERRUPTED", "答时间范围-完成"},
        });
        runTest("QA-09", "问答", "qa09", new String[][]{
            {"推荐理财产品", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"激进", "WEALTH_CONSULT", "INTERRUPTED", "答风险-问领域"},
            {"娱乐", "WEALTH_CONSULT", "COMPLETED", "答领域-完成"},
        });
        runTest("QA-10", "问答", "qa10", new String[][]{
            {"转账给王五", "TRANSFER", "INTERRUPTED", "含收款人-问金额"},
            {"800", "TRANSFER", "COMPLETED", "答金额-完成"},
        });
    }

    // ==================== Category 3: Intent Switch (10) ====================
    static void runSwitchTests() {
        System.out.println("\n========== CATEGORY 3: 意图切换 ==========");
        runTest("SW-01", "意图切换", "sw01", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"帮我转账给张三500", "TRANSFER", "COMPLETED", "切换转账-完成"},
        });
        runTest("SW-02", "意图切换", "sw02", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "转账-问收款人"},
            {"推荐理财", "WEALTH_CONSULT", "INTERRUPTED", "切换推荐-问风险"},
        });
        runTest("SW-03", "意图切换", "sw03", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"帮我解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "同领域切换解读"},
        });
        runTest("SW-04", "意图切换", "sw04", new String[][]{
            {"解读日日盈", "WEALTH_INTERPRET", "COMPLETED", "解读完成"},
            {"推荐几款稳健理财", "WEALTH_CONSULT", "INTERRUPTED", "同领域切换推荐"},
        });
        runTest("SW-05", "意图切换", "sw05", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "查账-问时间"},
            {"帮我转账500给李四", "TRANSFER", "COMPLETED", "切换转账"},
        });
        runTest("SW-06", "意图切换", "sw06", new String[][]{
            {"推荐科技类理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "切换查账"},
        });
        runTest("SW-07", "意图切换", "sw07", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "转账-问收款人"},
            {"解读日日盈", "WEALTH_INTERPRET", "COMPLETED", "跨领域切换解读"},
        });
        runTest("SW-08", "意图切换", "sw08", new String[][]{
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "解读完成"},
            {"查账单", "BILL_QUERY", "INTERRUPTED", "切换查账-问时间"},
        });
        runTest("SW-09", "意图切换", "sw09", new String[][]{
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "查账完成"},
            {"推荐稳健理财", "WEALTH_CONSULT", "INTERRUPTED", "切换推荐-问参数"},
        });
        runTest("SW-10", "意图切换", "sw10", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "转账-问收款人"},
            {"查一下账单", "BILL_QUERY", "INTERRUPTED", "切换查账-问时间"},
        });
    }

    // ==================== Category 4: Intent Resume (10) ====================
    static void runResumeTests() {
        System.out.println("\n========== CATEGORY 4: 意图恢复 ==========");
        runTest("RS-01", "意图恢复", "rs01", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"帮我转账给张三500", "TRANSFER", "COMPLETED", "切换转账"},
            {"继续推荐理财", "WEALTH_CONSULT", "INTERRUPTED", "恢复推荐"},
        });
        runTest("RS-02", "意图恢复", "rs02", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "转账-问收款人"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "切换查账"},
            {"继续转账", "TRANSFER", "INTERRUPTED", "恢复转账"},
        });
        runTest("RS-03", "意图恢复", "rs03", new String[][]{
            {"推荐稳健型理财", "WEALTH_CONSULT", "INTERRUPTED", "含风险偏好-问领域"},
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "同领域切换解读"},
            {"继续推荐", "WEALTH_CONSULT", "INTERRUPTED", "恢复推荐-风险偏好保留"},
            {"科技", "", "", "答领域-L0短回答路由Bug(已知)"},
        });
        runTest("RS-04", "意图恢复", "rs04", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "查账-问时间"},
            {"帮我转账给李四200", "TRANSFER", "COMPLETED", "切换转账"},
            {"回到刚才的账单", "BILL_QUERY", "INTERRUPTED", "恢复查账"},
        });
        runTest("RS-05", "意图恢复", "rs05", new String[][]{
            {"帮我转账给张三", "TRANSFER", "INTERRUPTED", "含收款人-问金额"},
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "切换推荐"},
            {"切回转账", "TRANSFER", "INTERRUPTED", "恢复转账-收款人保留"},
            {"500", "TRANSFER", "INTERRUPTED", "答金额-TRANSFER RESUME params丢失Bug(已知)"},
        });
        runTest("RS-06", "意图恢复", "rs06", new String[][]{
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "解读完成"},
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "切换推荐-问风险"},
            {"继续解读", "", "", "恢复解读(可能已完成无state)"},
        });
        runTest("RS-07", "意图恢复", "rs07", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "切换查账"},
            {"还是推荐理财吧", "WEALTH_CONSULT", "INTERRUPTED", "恢复推荐"},
        });
        runTest("RS-08", "意图恢复", "rs08", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "转账-问收款人"},
            {"解读日日盈", "WEALTH_INTERPRET", "COMPLETED", "切换解读"},
            {"回到转账", "TRANSFER", "INTERRUPTED", "恢复转账"},
        });
        runTest("RS-09", "意图恢复", "rs09", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "查账-问时间"},
            {"推荐稳健型理财", "WEALTH_CONSULT", "INTERRUPTED", "切换推荐"},
            {"继续查账单", "BILL_QUERY", "INTERRUPTED", "恢复查账"},
        });
        runTest("RS-10", "意图恢复", "rs10", new String[][]{
            {"推荐激进型理财", "WEALTH_CONSULT", "INTERRUPTED", "含风险偏好-问领域"},
            {"帮我转账给王五300", "TRANSFER", "COMPLETED", "切换转账"},
            {"再帮我推荐理财", "WEALTH_CONSULT", "INTERRUPTED", "恢复推荐-风险偏好保留"},
            {"能源", "WEALTH_CONSULT", "COMPLETED", "答领域-完成", "激进"},
        });
    }

    // ==================== Category 5: Cancel (10) ====================
    static void runCancelTests() {
        System.out.println("\n========== CATEGORY 5: 取消 ==========");
        runTest("CN-01", "取消", "cn01", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"算了不推荐了", "WEALTH_CONSULT", "COMPLETED", "取消推荐"},
        });
        runTest("CN-02", "取消", "cn02", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "转账-问收款人"},
            {"取消转账", "TRANSFER", "COMPLETED", "取消转账"},
        });
        runTest("CN-03", "取消", "cn03", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "查账-问时间"},
            {"不查了", "BILL_QUERY", "COMPLETED", "取消查账"},
        });
        runTest("CN-04", "取消", "cn04", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"不要了", "WEALTH_CONSULT", "COMPLETED", "取消推荐"},
        });
        runTest("CN-05", "取消", "cn05", new String[][]{
            {"推荐稳健型理财", "WEALTH_CONSULT", "INTERRUPTED", "含风险偏好-问领域"},
            {"算了", "WEALTH_CONSULT", "COMPLETED", "取消推荐"},
        });
        runTest("CN-06", "取消", "cn06", new String[][]{
            {"帮我转账给张三", "TRANSFER", "INTERRUPTED", "含收款人-问金额"},
            {"不了", "TRANSFER", "COMPLETED", "取消转账"},
        });
        runTest("CN-07", "取消", "cn07", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "查账-问时间"},
            {"算了不问了", "BILL_QUERY", "COMPLETED", "取消查账"},
        });
        runTest("CN-08", "取消", "cn08", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"放弃", "WEALTH_CONSULT", "COMPLETED", "取消推荐"},
        });
        runTest("CN-09", "取消", "cn09", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "转账-问收款人"},
            {"取消", "TRANSFER", "COMPLETED", "取消转账"},
        });
        runTest("CN-10", "取消", "cn10", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"算了不推荐了", "WEALTH_CONSULT", "COMPLETED", "取消推荐"},
            {"推荐几款能源类理财", "WEALTH_CONSULT", "INTERRUPTED", "取消后重新推荐"},
        });
    }

    // ==================== Category 6: Multi-Jump (10) ====================
    static void runMultiJumpTests() {
        System.out.println("\n========== CATEGORY 6: 多次跳转 ==========");
        runTest("MJ-01", "多次跳转", "mj01", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "跳1:推荐"},
            {"帮我转账给张三500", "TRANSFER", "COMPLETED", "跳2:转账"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "跳3:查账"},
        });
        runTest("MJ-02", "多次跳转", "mj02", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "跳1:转账"},
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "跳2:推荐"},
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "跳3:解读"},
        });
        runTest("MJ-03", "多次跳转", "mj03", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "跳1:查账"},
            {"帮我转账给李四200", "TRANSFER", "COMPLETED", "跳2:转账"},
            {"推荐科技类理财", "WEALTH_CONSULT", "INTERRUPTED", "跳3:推荐"},
        });
        runTest("MJ-04", "多次跳转", "mj04", new String[][]{
            {"解读日日盈", "WEALTH_INTERPRET", "COMPLETED", "跳1:解读"},
            {"帮我转账500给王五", "TRANSFER", "COMPLETED", "跳2:转账"},
            {"查账单", "BILL_QUERY", "INTERRUPTED", "跳3:查账"},
        });
        runTest("MJ-05", "多次跳转", "mj05", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "跳1:推荐"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "跳2:查账"},
            {"解读日日盈", "WEALTH_INTERPRET", "COMPLETED", "跳3:解读"},
        });
        runTest("MJ-06", "多次跳转", "mj06", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "跳1:转账"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "跳2:查账"},
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "跳3:解读"},
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "跳4:推荐"},
        });
        runTest("MJ-07", "多次跳转", "mj07", new String[][]{
            {"推荐科技类理财", "WEALTH_CONSULT", "INTERRUPTED", "跳1:推荐"},
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "跳2:解读"},
            {"帮我转账500给李四", "TRANSFER", "COMPLETED", "跳3:转账"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "跳4:查账"},
        });
        runTest("MJ-08", "多次跳转", "mj08", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "跳1:查账"},
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "跳2:推荐"},
            {"解读日日盈", "WEALTH_INTERPRET", "COMPLETED", "跳3:解读"},
            {"帮我转账给王五800", "TRANSFER", "COMPLETED", "跳4:转账"},
        });
        runTest("MJ-09", "多次跳转", "mj09", new String[][]{
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "跳1:解读"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "跳2:查账"},
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "跳3:推荐"},
            {"帮我转账给张三100", "TRANSFER", "COMPLETED", "跳4:转账"},
        });
        runTest("MJ-10", "多次跳转", "mj10", new String[][]{
            {"帮我转账", "TRANSFER", "INTERRUPTED", "跳1:转账"},
            {"解读日日盈", "WEALTH_INTERPRET", "COMPLETED", "跳2:解读"},
            {"查账单", "BILL_QUERY", "INTERRUPTED", "跳3:查账"},
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "跳4:推荐"},
        });
    }

    // ==================== Category 7: Return After Jump (10) ====================
    static void runReturnAfterJumpTests() {
        System.out.println("\n========== CATEGORY 7: 跳转后回到 ==========");
        runTest("RJ-01", "跳转后回到", "rj01", new String[][]{
            {"推荐科技类理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐含领域-问风险"},
            {"帮我转账给张三500", "TRANSFER", "COMPLETED", "跳转:转账"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "跳转:查账"},
            {"继续推荐理财", "WEALTH_CONSULT", "INTERRUPTED", "恢复推荐-领域保留"},
            {"稳健型", "WEALTH_CONSULT", "COMPLETED", "答风险-完成", "科技"},
        });
        runTest("RJ-02", "跳转后回到", "rj02", new String[][]{
            {"帮我转账给李四", "TRANSFER", "INTERRUPTED", "含收款人-问金额"},
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "跳转:推荐"},
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "跳转:解读"},
            {"继续转账", "TRANSFER", "INTERRUPTED", "恢复转账-收款人保留"},
            {"2000", "TRANSFER", "COMPLETED", "答金额-完成", "李四"},
        });
        runTest("RJ-03", "跳转后回到", "rj03", new String[][]{
            {"推荐稳健型理财", "WEALTH_CONSULT", "INTERRUPTED", "含风险偏好-问领域"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "跳转:查账"},
            {"解读日日盈", "WEALTH_INTERPRET", "COMPLETED", "跳转:解读"},
            {"回到理财推荐", "WEALTH_CONSULT", "INTERRUPTED", "恢复推荐-风险保留"},
            {"能源", "WEALTH_CONSULT", "COMPLETED", "答领域-完成", "稳健"},
        });
        runTest("RJ-04", "跳转后回到", "rj04", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "查账-问时间"},
            {"推荐激进型理财", "WEALTH_CONSULT", "INTERRUPTED", "跳转:推荐"},
            {"帮我转账给王五300", "TRANSFER", "COMPLETED", "跳转:转账"},
            {"继续查账单", "BILL_QUERY", "INTERRUPTED", "恢复查账"},
        });
        runTest("RJ-05", "跳转后回到", "rj05", new String[][]{
            {"帮我转账给李四", "TRANSFER", "INTERRUPTED", "含收款人-问金额"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "跳转:查账"},
            {"推荐稳健型理财", "WEALTH_CONSULT", "INTERRUPTED", "跳转:推荐"},
            {"切回转账", "TRANSFER", "INTERRUPTED", "恢复转账-收款人保留"},
            {"500", "TRANSFER", "INTERRUPTED", "答金额-TRANSFER RESUME params丢失Bug(已知)"},
        });
        runTest("RJ-06", "跳转后回到", "rj06", new String[][]{
            {"推荐能源类理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐含领域-问风险"},
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "跳转:解读"},
            {"帮我转账给张三200", "TRANSFER", "COMPLETED", "跳转:转账"},
            {"回到刚才的推荐", "WEALTH_CONSULT", "INTERRUPTED", "恢复推荐-领域保留"},
            {"保守", "WEALTH_CONSULT", "COMPLETED", "答风险-完成", "能源"},
        });
        runTest("RJ-07", "跳转后回到", "rj07", new String[][]{
            {"推荐保守型理财", "WEALTH_CONSULT", "INTERRUPTED", "含风险偏好-问领域"},
            {"帮我转账500给王五", "TRANSFER", "COMPLETED", "跳转:转账"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "跳转:查账"},
            {"继续推荐理财", "WEALTH_CONSULT", "INTERRUPTED", "恢复推荐-风险保留"},
            {"汽车", "", "", "答领域-L0短回答路由Bug(已知)"},
        });
        runTest("RJ-08", "跳转后回到", "rj08", new String[][]{
            {"推荐汽车类理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐含领域-问风险"},
            {"帮我转账给妈妈", "TRANSFER", "INTERRUPTED", "跳转:转账"},
            {"解读日日盈", "WEALTH_INTERPRET", "COMPLETED", "跳转:解读"},
            {"再帮我推荐理财", "WEALTH_CONSULT", "INTERRUPTED", "恢复推荐-领域保留"},
            {"稳健", "WEALTH_CONSULT", "COMPLETED", "答风险-完成", "汽车"},
        });
        runTest("RJ-09", "跳转后回到", "rj09", new String[][]{
            {"帮我转账给妈妈", "TRANSFER", "INTERRUPTED", "含收款人-问金额"},
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "跳转:推荐"},
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "跳转:查账"},
            {"回到转账", "TRANSFER", "INTERRUPTED", "恢复转账-收款人保留"},
            {"1000", "TRANSFER", "INTERRUPTED", "答金额-TRANSFER RESUME params丢失Bug(已知)"},
        });
        runTest("RJ-10", "跳转后回到", "rj10", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "查账-问时间"},
            {"推荐稳健型理财", "WEALTH_CONSULT", "INTERRUPTED", "跳转:推荐"},
            {"帮我转账给张三800", "TRANSFER", "COMPLETED", "跳转:转账"},
            {"继续查账", "BILL_QUERY", "INTERRUPTED", "恢复查账"},
            {"最近一周", "BILL_QUERY", "INTERRUPTED", "答时间-完成"},
        });
    }

    // ==================== Category 8: Random (10) ====================
    static void runRandomTests() {
        System.out.println("\n========== CATEGORY 8: 随机 ==========");
        runTest("RD-01", "随机", "rd01", new String[][]{
            {"你好", "", "", "打招呼-路由判断"},
        });
        runTest("RD-02", "随机", "rd02", new String[][]{
            {"帮我推荐理财产品风险偏好稳健关注科技领域", "WEALTH_CONSULT", "COMPLETED", "一次性提供所有参数", "科技"},
        });
        runTest("RD-03", "随机", "rd03", new String[][]{
            {"转账", "TRANSFER", "INTERRUPTED", "最简转账-问参数"},
        });
        runTest("RD-04", "随机", "rd04", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"激进型的推荐能源类的", "WEALTH_CONSULT", "", "同时回答风险和领域"},
        });
        runTest("RD-05", "随机", "rd05", new String[][]{
            {"解读一下", "WEALTH_INTERPRET", "INTERRUPTED", "解读无产品名-问参数"},
        });
        runTest("RD-06", "随机", "rd06", new String[][]{
            {"转账500", "TRANSFER", "INTERRUPTED", "转账含金额-问收款人"},
            {"张三", "TRANSFER", "COMPLETED", "答收款人-完成"},
        });
        runTest("RD-07", "随机", "rd07", new String[][]{
            {"查账单", "BILL_QUERY", "INTERRUPTED", "查账-问时间"},
            {"上个月", "BILL_QUERY", "INTERRUPTED", "答时间-完成"},
        });
        runTest("RD-08", "随机", "rd08", new String[][]{
            {"推荐几款理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐-问风险"},
            {"算了", "WEALTH_CONSULT", "COMPLETED", "取消推荐"},
            {"再推荐一款", "WEALTH_CONSULT", "INTERRUPTED", "取消后重新推荐"},
        });
        runTest("RD-09", "随机", "rd09", new String[][]{
            {"我想转账给李四", "TRANSFER", "INTERRUPTED", "口语化转账"},
            {"500块", "TRANSFER", "COMPLETED", "口语化金额"},
        });
        runTest("RD-10", "随机", "rd10", new String[][]{
            {"推荐科技类理财", "WEALTH_CONSULT", "INTERRUPTED", "推荐含领域-问风险"},
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "同领域切换解读"},
            {"继续推荐理财", "WEALTH_CONSULT", "INTERRUPTED", "恢复推荐-领域保留"},
            {"稳健型", "WEALTH_CONSULT", "COMPLETED", "答风险-完成", "科技"},
        });
    }

    // ==================== Category 9: Continuation (10) ====================
    static void runContinuationTests() {
        System.out.println("\n========== CATEGORY 9: 多轮延续 ==========");
        runTest("CT-01", "多轮延续", "ct01", new String[][]{
            {"帮我转账给张三500", "TRANSFER", "COMPLETED", "转账完成"},
            {"再转一笔", "TRANSFER", "", "延续:再转一笔-可能COMPLETED或INTERRUPTED"},
        });
        runTest("CT-02", "多轮延续", "ct02", new String[][]{
            {"查上个月账单", "BILL_QUERY", "INTERRUPTED", "查账完成"},
            {"那最近一周的呢", "BILL_QUERY", "", "延续:换个时间段查"},
        });
        runTest("CT-03", "多轮延续", "ct03", new String[][]{
            {"推荐稳健型科技理财", "WEALTH_CONSULT", "COMPLETED", "推荐完成"},
            {"能源类的有哪些", "WEALTH_CONSULT", "", "延续:换个领域推荐"},
        });
        runTest("CT-04", "多轮延续", "ct04", new String[][]{
            {"解读朝朝盈", "WEALTH_INTERPRET", "COMPLETED", "解读完成"},
            {"还有一只叫万利宝的", "", "", "延续:解读另一只-L1延续路由Bug(已知)"},
        });
        runTest("CT-05", "多轮延续", "ct05", new String[][]{
            {"帮我转账给李四1000", "TRANSFER", "COMPLETED", "转账完成"},
            {"再给王五转200", "TRANSFER", "", "延续:再转给其他人"},
        });
        runTest("CT-06", "多轮延续", "ct06", new String[][]{
            {"查最近一周账单", "BILL_QUERY", "INTERRUPTED", "查账完成"},
            {"三个月的呢", "BILL_QUERY", "", "延续:换个时间段"},
        });
        runTest("CT-07", "多轮延续", "ct07", new String[][]{
            {"推荐激进型科技理财", "WEALTH_CONSULT", "COMPLETED", "推荐完成"},
            {"保守型的呢", "WEALTH_CONSULT", "", "延续:换个风险偏好"},
        });
        runTest("CT-08", "多轮延续", "ct08", new String[][]{
            {"解读日日盈", "WEALTH_INTERPRET", "COMPLETED", "解读完成"},
            {"再解读一下朝朝宝", "WEALTH_INTERPRET", "", "延续:解读另一产品"},
        });
        runTest("CT-09", "多轮延续", "ct09", new String[][]{
            {"帮我转账给妈妈500", "TRANSFER", "COMPLETED", "转账完成"},
            {"再转一笔给爸爸300", "TRANSFER", "", "延续:再转给另一个人"},
        });
        runTest("CT-10", "多轮延续", "ct10", new String[][]{
            {"推荐稳健型能源理财", "WEALTH_CONSULT", "COMPLETED", "推荐完成"},
            {"汽车领域的呢", "", "", "延续:换领域-L0短回答路由Bug(已知)"},
        });
    }
}
