$Pass = 0; $Fail = 0

function RunTest($tag, $sid, $msg, $expIntent, $expStatus) {
    $body = @{message=$msg} | ConvertTo-Json
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $r = Invoke-RestMethod -Uri "http://localhost:8080/api/bank/chat?sessionId=$sid" -Method POST -ContentType 'application/json; charset=utf-8' -Body $bytes
    $intent = $r.intent; $status = $r.status
    if ($intent -eq $expIntent -and $status -eq $expStatus) {
        Write-Host "PASS $tag intent=$intent status=$status"
        $script:Pass++
    } else {
        Write-Host "FAIL $tag intent=$intent status=$status (expected $expIntent/$expStatus)"
        $script:Fail++
    }
}

# Clear sessions
1..6 | ForEach-Object { Invoke-RestMethod -Uri "http://localhost:8080/api/bank/session?sessionId=t_a2_$_" -Method DELETE }

# 1. Direct wealth
RunTest "DIR-01" "t_a2_1" "推荐理财产品" "WEALTH_CONSULT" "INTERRUPTED"
# 2. Direct transfer
RunTest "DIR-02" "t_a2_2" "转账给张三" "TRANSFER" "INTERRUPTED"
# 3. Direct bill
RunTest "DIR-03" "t_a2_3" "查账单" "BILL_QUERY" "INTERRUPTED"
# 4. Chat
RunTest "QA-01"  "t_a2_4" "你好" $null "COMPLETED"
# 5. Direct interpret
RunTest "DIR-04" "t_a2_5" "解读朝朝盈" "WEALTH_INTERPRET" "COMPLETED"
# 6. Unsupported
RunTest "UNS-01" "t_a2_6" "开户" $null "COMPLETED"

Write-Host "`n===== PASS: $Pass | FAIL: $Fail ====="
