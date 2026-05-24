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

RunStep "DIR-03" "t30_03" ([char]0x63A8+[char]0x8350+[char]0x51E0+[char]0x6B3E+[char]0x7406+[char]0x8D22+[char]0x4EA7+[char]0x54C1) "WEALTH_CONSULT" "INTERRUPTED" "tuijian-wenrisk"
RunStep "DIR-04" "t30_04" ([char]0x5E2E+[char]0x6211+[char]0x89E3+[char]0x8BFB+[char]0x671D+[char]0x671D+[char]0x76C8) "WEALTH_INTERPRET" "COMPLETED" "jiedu-product"
RunStep "DIR-07" "t30_07" ([char]0x6211+[char]0x60F3+[char]0x4E70+[char]0x7406+[char]0x8D22+[char]0x4EA7+[char]0x54C1) "WEALTH_CONSULT" "INTERRUPTED" "kouyuahua-tuijian"

RunStep "QA-01" "t30_q01" ([char]0x63A8+[char]0x8350+[char]0x51E0+[char]0x6B3E+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "tuijian-wenrisk"
RunStep "QA-01" "t30_q01" ([char]0x6FC0+[char]0x8FDB+[char]0x578B) "WEALTH_CONSULT" "INTERRUPTED" "da-risk-wen-area"
RunStep "QA-01" "t30_q01" ([char]0x79D1+[char]0x6280) "WEALTH_CONSULT" "COMPLETED" "da-area-done"

RunStep "QA-10" "t30_q10" ([char]0x8F6C+[char]0x8D26+[char]0x7ED9+[char]0x738B+[char]0x4E94) "TRANSFER" "INTERRUPTED" "zhuanghr-wenmoney"
RunStep "QA-10" "t30_q10" "800" "TRANSFER" "COMPLETED" "da-money-done"

RunStep "SW-01" "t30_s01" ([char]0x63A8+[char]0x8350+[char]0x51E0+[char]0x6B3E+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "tuijian-wenrisk"
RunStep "SW-01" "t30_s01" ([char]0x5E2E+[char]0x6211+[char]0x8F6C+[char]0x8D26+[char]0x7ED9+[char]0x5F20+[char]0x4E09+"500") "TRANSFER" "COMPLETED" "switch-transfer"

RunStep "RS-01" "t30_r01" ([char]0x63A8+[char]0x8350+[char]0x51E0+[char]0x6B3E+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "tuijian-wenrisk"
RunStep "RS-01" "t30_r01" ([char]0x5E2E+[char]0x6211+[char]0x8F6C+[char]0x8D26+[char]0x7ED9+[char]0x5F20+[char]0x4E09+"500") "TRANSFER" "COMPLETED" "switch-transfer"
RunStep "RS-01" "t30_r01" ([char]0x7EE7+[char]0x7EED+[char]0x63A8+[char]0x8350+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "resume-tuijian"

RunStep "RS-04" "t30_r04" ([char]0x67E5+[char]0x8D26+[char]0x5355) "BILL_QUERY" "INTERRUPTED" "bill-wentime"
RunStep "RS-04" "t30_r04" ([char]0x5E2E+[char]0x6211+[char]0x8F6C+[char]0x8D26+[char]0x7ED9+[char]0x674E+[char]0x56DB+"200") "TRANSFER" "COMPLETED" "switch-transfer"
RunStep "RS-04" "t30_r04" ([char]0x56DE+[char]0x5230+[char]0x521A+[char]0x624D+[char]0x7684+[char]0x8D26+[char]0x5355) "BILL_QUERY" "INTERRUPTED" "resume-bill"

RunStep "RS-10" "t30_r10" ([char]0x63A8+[char]0x8350+[char]0x6FC0+[char]0x8FDB+[char]0x578B+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "risk-pref-wenarea"
RunStep "RS-10" "t30_r10" ([char]0x5E2E+[char]0x6211+[char]0x8F6C+[char]0x8D26+[char]0x7ED9+[char]0x738B+[char]0x4E94+"300") "TRANSFER" "COMPLETED" "switch-transfer"
RunStep "RS-10" "t30_r10" ([char]0x518D+[char]0x5E2E+[char]0x6211+[char]0x63A8+[char]0x8350+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "resume-risk-kept"
RunStep "RS-10" "t30_r10" ([char]0x80FD+[char]0x6E90) "WEALTH_CONSULT" "COMPLETED" "da-area-done"

RunStep "CN-01" "t30_c01" ([char]0x63A8+[char]0x8350+[char]0x51E0+[char]0x6B3E+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "tuijian-wenrisk"
RunStep "CN-01" "t30_c01" ([char]0x7B97+[char]0x4E86+[char]0x4E0D+[char]0x63A8+[char]0x8350+[char]0x4E86) "WEALTH_CONSULT" "COMPLETED" "cancel"

RunStep "CN-05" "t30_c05" ([char]0x63A8+[char]0x8350+[char]0x7A33+[char]0x5065+[char]0x578B+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "risk-pref-wenarea"
RunStep "CN-05" "t30_c05" ([char]0x7B97+[char]0x4E86) "WEALTH_CONSULT" "COMPLETED" "cancel"

RunStep "MJ-01" "t30_m01" ([char]0x63A8+[char]0x8350+[char]0x51E0+[char]0x6B3E+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "jump1"
RunStep "MJ-01" "t30_m01" ([char]0x5E2E+[char]0x6211+[char]0x8F6C+[char]0x8D26+[char]0x7ED9+[char]0x5F20+[char]0x4E09+"500") "TRANSFER" "COMPLETED" "jump2-transfer"
RunStep "MJ-01" "t30_m01" ([char]0x67E5+[char]0x4E0A+[char]0x4E2A+[char]0x6708+[char]0x8D26+[char]0x5355) "BILL_QUERY" "INTERRUPTED" "jump3-bill"

RunStep "MJ-06" "t30_m06" ([char]0x5E2E+[char]0x6211+[char]0x8F6C+[char]0x8D26) "TRANSFER" "INTERRUPTED" "jump1-transfer"
RunStep "MJ-06" "t30_m06" ([char]0x67E5+[char]0x4E0A+[char]0x4E2A+[char]0x6708+[char]0x8D26+[char]0x5355) "BILL_QUERY" "INTERRUPTED" "jump2-bill"
RunStep "MJ-06" "t30_m06" ([char]0x89E3+[char]0x8BFB+[char]0x671D+[char]0x671D+[char]0x76C8) "WEALTH_INTERPRET" "COMPLETED" "jump3-jiedu"
RunStep "MJ-06" "t30_m06" ([char]0x63A8+[char]0x8350+[char]0x51E0+[char]0x6B3E+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "jump4-tuijian"

RunStep "RJ-01" "t30_j01" ([char]0x63A8+[char]0x8350+[char]0x79D1+[char]0x6280+[char]0x7C7B+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "tuijian-area-wenrisk"
RunStep "RJ-01" "t30_j01" ([char]0x5E2E+[char]0x6211+[char]0x8F6C+[char]0x8D26+[char]0x7ED9+[char]0x5F20+[char]0x4E09+"500") "TRANSFER" "COMPLETED" "jump-transfer"
RunStep "RJ-01" "t30_j01" ([char]0x67E5+[char]0x4E0A+[char]0x4E2A+[char]0x6708+[char]0x8D26+[char]0x5355) "BILL_QUERY" "INTERRUPTED" "jump-bill"
RunStep "RJ-01" "t30_j01" ([char]0x7EE7+[char]0x7EED+[char]0x63A8+[char]0x8350+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "resume-area-kept"
RunStep "RJ-01" "t30_j01" ([char]0x7A33+[char]0x5065+[char]0x578B) "WEALTH_CONSULT" "COMPLETED" "da-risk-done"

RunStep "RJ-05" "t30_j05" ([char]0x5E2E+[char]0x6211+[char]0x8F6C+[char]0x8D26+[char]0x7ED9+[char]0x674E+[char]0x56DB) "TRANSFER" "INTERRUPTED" "transfer-wenmoney"
RunStep "RJ-05" "t30_j05" ([char]0x67E5+[char]0x4E0A+[char]0x4E2A+[char]0x6708+[char]0x8D26+[char]0x5355) "BILL_QUERY" "INTERRUPTED" "jump-bill"
RunStep "RJ-05" "t30_j05" ([char]0x63A8+[char]0x8350+[char]0x7A33+[char]0x5065+[char]0x578B+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "jump-tuijian"
RunStep "RJ-05" "t30_j05" ([char]0x5207+[char]0x56DE+[char]0x8F6C+[char]0x8D26) "TRANSFER" "INTERRUPTED" "resume-transfer"
RunStep "RJ-05" "t30_j05" "500" "TRANSFER" "" "da-money-RESUME-bug"

RunStep "RJ-10" "t30_j10" ([char]0x67E5+[char]0x8D26+[char]0x5355) "BILL_QUERY" "INTERRUPTED" "bill-wentime"
RunStep "RJ-10" "t30_j10" ([char]0x63A8+[char]0x8350+[char]0x7A33+[char]0x5065+[char]0x578B+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "jump-tuijian"
RunStep "RJ-10" "t30_j10" ([char]0x5E2E+[char]0x6211+[char]0x8F6C+[char]0x8D26+[char]0x7ED9+[char]0x5F20+[char]0x4E09+"800") "TRANSFER" "COMPLETED" "jump-transfer"
RunStep "RJ-10" "t30_j10" ([char]0x7EE7+[char]0x7EED+[char]0x67E5+[char]0x8D26) "BILL_QUERY" "INTERRUPTED" "resume-bill"
RunStep "RJ-10" "t30_j10" ([char]0x6700+[char]0x8FD1+[char]0x4E00+[char]0x5468) "BILL_QUERY" "INTERRUPTED" "da-time"

RunStep "RD-02" "t30_d02" ([char]0x5E2E+[char]0x6211+[char]0x63A8+[char]0x8350+[char]0x7406+[char]0x8D22+[char]0x4EA7+[char]0x54C1+[char]0x98CE+[char]0x9669+[char]0x504F+[char]0x597D+[char]0x7A33+[char]0x5065+[char]0x5173+[char]0x6CE8+[char]0x79D1+[char]0x6280+[char]0x9886+[char]0x57DF) "WEALTH_CONSULT" "COMPLETED" "all-params"

RunStep "RD-06" "t30_d06" ([char]0x8F6C+[char]0x8D26+"500") "TRANSFER" "INTERRUPTED" "transfer-money-wenperson"
RunStep "RD-06" "t30_d06" ([char]0x5F20+[char]0x4E09) "TRANSFER" "COMPLETED" "da-person-done"

RunStep "RD-10" "t30_d10" ([char]0x63A8+[char]0x8350+[char]0x79D1+[char]0x6280+[char]0x7C7B+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "tuijian-area"
RunStep "RD-10" "t30_d10" ([char]0x89E3+[char]0x8BFB+[char]0x671D+[char]0x671D+[char]0x76C8) "WEALTH_INTERPRET" "COMPLETED" "switch-jiedu"
RunStep "RD-10" "t30_d10" ([char]0x7EE7+[char]0x7EED+[char]0x63A8+[char]0x8350+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "INTERRUPTED" "resume-tuijian"
RunStep "RD-10" "t30_d10" ([char]0x7A33+[char]0x5065+[char]0x578B) "WEALTH_CONSULT" "COMPLETED" "da-risk-done"

RunStep "CT-01" "t30_ct01" ([char]0x5E2E+[char]0x6211+[char]0x8F6C+[char]0x8D26+[char]0x7ED9+[char]0x5F20+[char]0x4E09+"500") "TRANSFER" "COMPLETED" "transfer-done"
RunStep "CT-01" "t30_ct01" ([char]0x518D+[char]0x8F6C+[char]0x4E00+[char]0x7B14) "TRANSFER" "" "continuation"

RunStep "CT-03" "t30_ct03" ([char]0x63A8+[char]0x8350+[char]0x7A33+[char]0x5065+[char]0x578B+[char]0x79D1+[char]0x6280+[char]0x7406+[char]0x8D22) "WEALTH_CONSULT" "COMPLETED" "tuijian-done"
RunStep "CT-03" "t30_ct03" ([char]0x80FD+[char]0x6E90+[char]0x7C7B+[char]0x7684+[char]0x6709+[char]0x54EA+[char]0x4E9B) "WEALTH_CONSULT" "" "continuation-area"

RunStep "CT-04" "t30_ct04" ([char]0x89E3+[char]0x8BFB+[char]0x671D+[char]0x671D+[char]0x76C8) "WEALTH_INTERPRET" "COMPLETED" "jiedu-done"
RunStep "CT-04" "t30_ct04" ([char]0x8FD8+[char]0x6709+[char]0x4E00+[char]0x53EA+[char]0x53EB+[char]0x4E07+[char]0x5229+[char]0x5B9D+[char]0x7684) "" "" "continuation-jiedu"

Write-Host ""
Write-Host "========================================"
Write-Host "STEPS: $globalStep | PASS: $totalPass | FAIL: $totalFail"
Write-Host "========================================"