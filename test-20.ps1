$Pass = 0; $Fail = 0

function RunTest($tag, $sid, $msg, $expIntent, $expStatus) {
    $body = @{message=$msg} | ConvertTo-Json
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $r = Invoke-RestMethod -Uri "http://localhost:8080/api/bank/chat?sessionId=$sid" -Method POST -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 120
    $intent = $r.intent; $status = $r.status
    $ok = ($intent -eq $expIntent -and $status -eq $expStatus)
    if ($ok) {
        Write-Host "PASS $tag intent=$intent status=$status"
        $script:Pass++
    } else {
        Write-Host "FAIL $tag intent=[$intent] status=[$status] (expected $expIntent/$expStatus)"
        $script:Fail++
    }
}

# Clear sessions
1..20 | ForEach-Object { Invoke-RestMethod -Uri "http://localhost:8080/api/bank/session?sessionId=t20_$_" -Method DELETE -ErrorAction SilentlyContinue }

# ========== 直达测试 (6) ==========
# 1. 理财咨询
RunTest "DIR-01" "t20_1"  "帮我推荐理财产品" "WEALTH_CONSULT" "INTERRUPTED"
# 2. 理财解读
RunTest "DIR-02" "t20_2"  "解读朝朝盈" "WEALTH_INTERPRET" "COMPLETED"
# 3. 转账
RunTest "DIR-03" "t20_3"  "转账给张三" "TRANSFER" "INTERRUPTED"
# 4. 查账单
RunTest "DIR-04" "t20_4"  "查账单" "BILL_QUERY" "INTERRUPTED"
# 5. 闲聊
RunTest "QA-01"  "t20_5"  "你好" "CHAT" "COMPLETED"
# 6. 不支持
RunTest "UNS-01" "t20_6"  "开户" $null "COMPLETED"

# ========== 问答测试 (4) ==========
# 7. 理财问风险偏好
RunTest "QA-02" "t20_7"  "有什么稳健的理财" "WEALTH_CONSULT" "INTERRUPTED"
# 8. 转账问收款人
RunTest "QA-03" "t20_8"  "我要转账" "TRANSFER" "INTERRUPTED"
# 9. 账单问时间
RunTest "QA-04" "t20_9"  "最近消费了多少" "BILL_QUERY" "INTERRUPTED"
# 10. 闲聊2
RunTest "QA-05" "t20_10" "今天天气怎么样" "CHAT" "COMPLETED"

# ========== 多轮-意图切换 (4) ==========
# 11. 转账→账单
RunTest "SW-01a" "t20_11" "转账给李四" "TRANSFER" "INTERRUPTED"
RunTest "SW-01b" "t20_11" "帮我查账单" "BILL_QUERY" "INTERRUPTED"
# 12. 理财→转账
RunTest "SW-02a" "t20_12" "推荐科技类理财" "WEALTH_CONSULT" "INTERRUPTED"
RunTest "SW-02b" "t20_12" "算了，转账给王五" "TRANSFER" "INTERRUPTED"

# ========== 多轮-意图恢复 (4) ==========
# 13. 转账中断→切走→回来
RunTest "RS-01a" "t20_13" "转账给赵六" "TRANSFER" "INTERRUPTED"
RunTest "RS-01b" "t20_13" "查下账单" "BILL_QUERY" "INTERRUPTED"
RunTest "RS-01c" "t20_13" "继续转账" "TRANSFER" "INTERRUPTED"
# 14. 理财中断→切走→回来
RunTest "RS-02a" "t20_14" "推荐理财产品" "WEALTH_CONSULT" "INTERRUPTED"
RunTest "RS-02b" "t20_14" "查账单" "BILL_QUERY" "INTERRUPTED"
RunTest "RS-02c" "t20_14" "继续推荐理财" "WEALTH_CONSULT" "INTERRUPTED"

# ========== 取消 (2) ==========
# 15. 转账中取消
RunTest "CN-01a" "t20_15" "转账500给张三" "TRANSFER" "INTERRUPTED"
RunTest "CN-01b" "t20_15" "算了" "TRANSFER" "COMPLETED"
# 16. 理财中取消
RunTest "CN-02a" "t20_16" "推荐理财产品" "WEALTH_CONSULT" "INTERRUPTED"
RunTest "CN-02b" "t20_16" "取消" "WEALTH_CONSULT" "COMPLETED"

# ========== 边界 (4) ==========
# 17. 短回答-风险偏好
RunTest "EDGE-01" "t20_17" "稳健" "WEALTH_CONSULT" "INTERRUPTED"
# 18. 不支持+带意图
RunTest "EDGE-02" "t20_18" "贷款买房" $null "COMPLETED"
# 19. 理财解读含产品名
RunTest "EDGE-03" "t20_19" "帮我解读万利宝" "WEALTH_INTERPRET" "COMPLETED"
# 20. 连续同领域
RunTest "EDGE-04a" "t20_20" "推荐理财产品" "WEALTH_CONSULT" "INTERRUPTED"
RunTest "EDGE-04b" "t20_20" "还有一只叫万利宝的" "WEALTH_INTERPRET" "COMPLETED"

Write-Host "`n===== PASS: $Pass | FAIL: $Fail ====="
