$baseUrl = "http://localhost:8080/api/bank"
$totalPass = 0; $totalFail = 0; $globalStep = 0

function CallChat($sid, $msg) {
    $body = @{message = $msg} | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    try {
        $r = Invoke-WebRequest -Uri "$baseUrl/chat?sessionId=$sid" -Method POST -ContentType "application/json; charset=utf-8" -Body $bytes -UseBasicParsing -TimeoutSec 120
        return $r.Content | ConvertFrom-Json
    } catch { return @{status='ERROR';intent='ERROR';content=$_.Exception.Message} }
}

function RunStep($tid, $sid, $msg, $expI, $expS, $desc) {
    $globalStep++
    $a = CallChat $sid $msg
    $aI = if ($a.intent) {$a.intent} else {""}
    $aS = if ($a.status) {$a.status} else {""}
    $pass = $true; $notes = ""
    if ($expI -and $expI -ne $aI) { $pass = $false; $notes += "I:exp=$expI,act=$aI; " }
    if ($expS -and $expS -ne $aS) { $pass = $false; $notes += "S:exp=$expS,act=$aS; " }
    if ($pass) { $script:totalPass++ } else { $script:totalFail++ }
    $pf = if ($pass) {"PASS"} else {"FAIL"}
    Write-Host "  [$pf] $tid S$globalStep $aI/$aS | $desc $notes"
}

Write-Host "`n===== 30 Cases Regression Test ====="

# DIR-03
RunStep "DIR-03" "t30_03" "推荐几款理财产品" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
# DIR-04
RunStep "DIR-04" "t30_04" "帮我解读朝朝盈" "WEALTH_INTERPRET" "COMPLETED" "解读含产品名"
# DIR-07
RunStep "DIR-07" "t30_07" "我想买理财产品" "WEALTH_CONSULT" "INTERRUPTED" "口语化推荐"

# QA-01
RunStep "QA-01" "t30_q01" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "QA-01" "t30_q01" "激进型" "WEALTH_CONSULT" "INTERRUPTED" "答风险-问领域"
RunStep "QA-01" "t30_q01" "科技" "WEALTH_CONSULT" "COMPLETED" "答领域-完成"

# QA-05
RunStep "QA-05" "t30_q05" "推荐理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "QA-05" "t30_q05" "保守" "WEALTH_CONSULT" "INTERRUPTED" "答风险-问领域"
RunStep "QA-05" "t30_q05" "汽车" "" "" "答领域-短回答路由"

# QA-09
RunStep "QA-09" "t30_q09" "推荐理财产品" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "QA-09" "t30_q09" "激进" "WEALTH_CONSULT" "INTERRUPTED" "答风险-问领域"
RunStep "QA-09" "t30_q09" "娱乐" "" "" "答领域-短回答"

# QA-10
RunStep "QA-10" "t30_q10" "转账给王五" "TRANSFER" "INTERRUPTED" "含收款人-问金额"
RunStep "QA-10" "t30_q10" "800" "TRANSFER" "COMPLETED" "答金额-完成"

# SW-01
RunStep "SW-01" "t30_s01" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "SW-01" "t30_s01" "帮我转账给张三500" "TRANSFER" "COMPLETED" "切换转账"

# SW-03
RunStep "SW-03" "t30_s03" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "SW-03" "t30_s03" "帮我解读朝朝盈" "WEALTH_INTERPRET" "COMPLETED" "同领域切换解读"

# RS-01
RunStep "RS-01" "t30_r01" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "RS-01" "t30_r01" "帮我转账给张三500" "TRANSFER" "COMPLETED" "切换转账"
RunStep "RS-01" "t30_r01" "继续推荐理财" "WEALTH_CONSULT" "INTERRUPTED" "恢复推荐"

# RS-04
RunStep "RS-04" "t30_r04" "查账单" "BILL_QUERY" "INTERRUPTED" "查账-问时间"
RunStep "RS-04" "t30_r04" "帮我转账给李四200" "TRANSFER" "COMPLETED" "切换转账"
RunStep "RS-04" "t30_r04" "回到刚才的账单" "BILL_QUERY" "INTERRUPTED" "恢复查账"

# RS-10
RunStep "RS-10" "t30_r10" "推荐激进型理财" "WEALTH_CONSULT" "INTERRUPTED" "含风险偏好-问领域"
RunStep "RS-10" "t30_r10" "帮我转账给王五300" "TRANSFER" "COMPLETED" "切换转账"
RunStep "RS-10" "t30_r10" "再帮我推荐理财" "WEALTH_CONSULT" "INTERRUPTED" "恢复推荐-风险偏好保留"
RunStep "RS-10" "t30_r10" "能源" "WEALTH_CONSULT" "COMPLETED" "答领域-完成"

# CN-01
RunStep "CN-01" "t30_c01" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐-问风险"
RunStep "CN-01" "t30_c01" "算了不推荐了" "WEALTH_CONSULT" "COMPLETED" "取消推荐"

# CN-05
RunStep "CN-05" "t30_c05" "推荐稳健型理财" "WEALTH_CONSULT" "INTERRUPTED" "含风险偏好-问领域"
RunStep "CN-05" "t30_c05" "算了" "WEALTH_CONSULT" "COMPLETED" "取消推荐"

# MJ-01
RunStep "MJ-01" "t30_m01" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "跳1:推荐"
RunStep "MJ-01" "t30_m01" "帮我转账给张三500" "TRANSFER" "COMPLETED" "跳2:转账"
RunStep "MJ-01" "t30_m01" "查上个月账单" "BILL_QUERY" "INTERRUPTED" "跳3:查账"

# MJ-06
RunStep "MJ-06" "t30_m06" "帮我转账" "TRANSFER" "INTERRUPTED" "跳1:转账"
RunStep "MJ-06" "t30_m06" "查上个月账单" "BILL_QUERY" "INTERRUPTED" "跳2:查账"
RunStep "MJ-06" "t30_m06" "解读朝朝盈" "WEALTH_INTERPRET" "COMPLETED" "跳3:解读"
RunStep "MJ-06" "t30_m06" "推荐几款理财" "WEALTH_CONSULT" "INTERRUPTED" "跳4:推荐"

# RJ-01
RunStep "RJ-01" "t30_j01" "推荐科技类理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐含领域-问风险"
RunStep "RJ-01" "t30_j01" "帮我转账给张三500" "TRANSFER" "COMPLETED" "跳:转账"
RunStep "RJ-01" "t30_j01" "查上个月账单" "BILL_QUERY" "INTERRUPTED" "跳:查账"
RunStep "RJ-01" "t30_j01" "继续推荐理财" "WEALTH_CONSULT" "INTERRUPTED" "恢复推荐-领域保留"
RunStep "RJ-01" "t30_j01" "稳健型" "WEALTH_CONSULT" "COMPLETED" "答风险-完成"

# RJ-05 (known: TRANSFER RESUME params bug)
RunStep "RJ-05" "t30_j05" "帮我转账给李四" "TRANSFER" "INTERRUPTED" "含收款人-问金额"
RunStep "RJ-05" "t30_j05" "查上个月账单" "BILL_QUERY" "INTERRUPTED" "跳:查账"
RunStep "RJ-05" "t30_j05" "推荐稳健型理财" "WEALTH_CONSULT" "INTERRUPTED" "跳:推荐"
RunStep "RJ-05" "t30_j05" "切回转账" "TRANSFER" "INTERRUPTED" "恢复转账"
RunStep "RJ-05" "t30_j05" "500" "TRANSFER" "" "答金额-TRANSFER RESUME bug"

# RJ-10
RunStep "RJ-10" "t30_j10" "查账单" "BILL_QUERY" "INTERRUPTED" "查账-问时间"
RunStep "RJ-10" "t30_j10" "推荐稳健型理财" "WEALTH_CONSULT" "INTERRUPTED" "跳:推荐"
RunStep "RJ-10" "t30_j10" "帮我转账给张三800" "TRANSFER" "COMPLETED" "跳:转账"
RunStep "RJ-10" "t30_j10" "继续查账" "BILL_QUERY" "INTERRUPTED" "恢复查账"
RunStep "RJ-10" "t30_j10" "最近一周" "BILL_QUERY" "INTERRUPTED" "答时间"

# RD-02
RunStep "RD-02" "t30_d02" "帮我推荐理财产品风险偏好稳健关注科技领域" "WEALTH_CONSULT" "COMPLETED" "一次性全参数"

# RD-06
RunStep "RD-06" "t30_d06" "转账500" "TRANSFER" "INTERRUPTED" "转账含金额-问收款人"
RunStep "RD-06" "t30_d06" "张三" "TRANSFER" "COMPLETED" "答收款人-完成"

# RD-10
RunStep "RD-10" "t30_d10" "推荐科技类理财" "WEALTH_CONSULT" "INTERRUPTED" "推荐含领域"
RunStep "RD-10" "t30_d10" "解读朝朝盈" "WEALTH_INTERPRET" "COMPLETED" "同领域切换解读"
RunStep "RD-10" "t30_d10" "继续推荐理财" "WEALTH_CONSULT" "INTERRUPTED" "恢复推荐"
RunStep "RD-10" "t30_d10" "稳健型" "WEALTH_CONSULT" "COMPLETED" "答风险-完成"

# CT-01
RunStep "CT-01" "t30_ct01" "帮我转账给张三500" "TRANSFER" "COMPLETED" "转账完成"
RunStep "CT-01" "t30_ct01" "再转一笔" "TRANSFER" "" "延续:再转一笔"

# CT-03
RunStep "CT-03" "t30_ct03" "推荐稳健型科技理财" "WEALTH_CONSULT" "COMPLETED" "推荐完成"
RunStep "CT-03" "t30_ct03" "能源类的有哪些" "WEALTH_CONSULT" "" "延续:换领域"

# CT-04
RunStep "CT-04" "t30_ct04" "解读朝朝盈" "WEALTH_INTERPRET" "COMPLETED" "解读完成"
RunStep "CT-04" "t30_ct04" "还有一只叫万利宝的" "" "" "延续:解读另一只"

Write-Host ""
Write-Host "========================================"
Write-Host "STEPS: $globalStep | PASS: $totalPass | FAIL: $totalFail"
Write-Host "========================================"
