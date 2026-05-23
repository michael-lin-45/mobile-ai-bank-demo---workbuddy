$baseUrl = "http://localhost:8080/api/bank"
$totalPass = 0; $totalFail = 0

function CallChat($sid, $msg) {
    $body = @{message = $msg} | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $r = Invoke-WebRequest -Uri "$baseUrl/chat?sessionId=$sid" -Method POST -ContentType "application/json; charset=utf-8" -Body $bytes -UseBasicParsing -TimeoutSec 120
    return $r.Content | ConvertFrom-Json
}

function RunStep($tid, $sid, $msg, $expI, $expS, $desc) {
    $a = CallChat $sid $msg
    $aI = if ($a.intent) {$a.intent} else {""}
    $aS = if ($a.status) {$a.status} else {""}
    $pass = $true; $notes = ""
    if ($expI -and $expI -ne $aI) { $pass = $false; $notes += "I:exp=$expI,act=$aI; " }
    if ($expS -and $expS -ne $aS) { $pass = $false; $notes += "S:exp=$expS,act=$aS; " }
    if ($pass) { $script:totalPass++ } else { $script:totalFail++ }
    $pf = if ($pass) {"PASS"} else {"FAIL"}
    Write-Host "  [$pf] $tid $aI/$aS | $desc $notes"
}

Write-Host "`n===== 10 Cases Quick Test ====="

# 1. Direct
RunStep "DIR-03" "t10_01" "推荐几款理财产品" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"

# 2. QA多轮
RunStep "QA-01" "t10_02" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "QA-01" "t10_02" "激进型" "WEALTH_CONSULT" "INTERRUPTED" "答风险-问领域"
RunStep "QA-01" "t10_02" "科技" "WEALTH_CONSULT" "COMPLETED" "答领域-完成"

# 3. 切换+恢复
RunStep "RS-01" "t10_03" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "RS-01" "t10_03" "帮我转账给张三500" "TRANSFER" "COMPLETED" "切换转账"
RunStep "RS-01" "t10_03" "继续推荐理财" "WEALTH_CONSULT" "INTERRUPTED" "恢复推荐"

# 4. 取消
RunStep "CN-01" "t10_04" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "CN-01" "t10_04" "算了不推荐了" "WEALTH_CONSULT" "COMPLETED" "取消推荐"

# 5. 同领域切换
RunStep "SW-03" "t10_05" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "SW-03" "t10_05" "帮我解读朝朝盈" "WEALTH_INTERPRET" "COMPLETED" "同领域切换解读"

# 6. 转账多轮
RunStep "QA-03" "t10_06" "帮我转账" "TRANSFER" "INTERRUPTED" "转账-问收款人"
RunStep "QA-03" "t10_06" "张三" "TRANSFER" "INTERRUPTED" "答收款人-问金额"
RunStep "QA-03" "t10_06" "500" "TRANSFER" "COMPLETED" "答金额-完成"

# 7. 跳转后回到
RunStep "RJ-01" "t10_07" "推荐科技类理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐含领域"
RunStep "RJ-01" "t10_07" "帮我转账给张三500" "TRANSFER" "COMPLETED" "跳:转账"
RunStep "RJ-01" "t10_07" "继续推荐理财" "WEALTH_CONSULT" "INTERRUPTED" "恢复推荐"
RunStep "RJ-01" "t10_07" "稳健型" "WEALTH_CONSULT" "COMPLETED" "答风险-完成"

# 8. 延续:换领域
RunStep "CT-03" "t10_08" "推荐稳健型科技理财" "WEALTH_CONSULT" "COMPLETED" "推荐完成"
RunStep "CT-03" "t10_08" "能源类的有哪些" "WEALTH_CONSULT" "" "延续:换领域"

# 9. 解读
RunStep "DIR-04" "t10_09" "帮我解读朝朝盈" "WEALTH_INTERPRET" "COMPLETED" "解读含产品名"

# 10. 查账单
RunStep "QA-04" "t10_10" "查账单" "BILL_QUERY" "INTERRUPTED" "查账-问时间"
RunStep "QA-04" "t10_10" "上个月" "BILL_QUERY" "INTERRUPTED" "答时间"

Write-Host "`n========================================"
Write-Host "PASS: $totalPass | FAIL: $totalFail"
Write-Host "========================================"
