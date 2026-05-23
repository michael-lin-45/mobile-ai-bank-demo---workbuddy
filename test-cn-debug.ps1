function TestChat($sid, $msg) {
    $body = @{message=$msg} | ConvertTo-Json
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    return Invoke-RestMethod -Uri "http://localhost:8080/api/bank/chat?sessionId=$sid" -Method POST -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 120
}

# Clear
Invoke-RestMethod -Uri "http://localhost:8080/api/bank/session?sessionId=dbg_cn" -Method DELETE -ErrorAction SilentlyContinue

# Step 1: 先进WEALTH
Write-Host "--- Step 1: 推荐理财产品 ---"
$r1 = TestChat "dbg_cn" "推荐理财产品"
Write-Host "intent=[$($r1.intent)] status=[$($r1.status)] question=[$($r1.question)]"

# Step 2: 取消
Write-Host "`n--- Step 2: 取消 ---"
$r2 = TestChat "dbg_cn" "取消"
Write-Host "intent=[$($r2.intent)] status=[$($r2.status)]"
if ($r2.content) { Write-Host "content=[$($r2.content.Substring(0, [Math]::Min(200, $r2.content.Length)))]" }
if ($r2.question) { Write-Host "question=[$($r2.question)]" }

# Check state
Write-Host "`n--- State ---"
$state = Invoke-RestMethod -Uri "http://localhost:8080/api/bank/state?sessionId=dbg_cn" -Method GET
Write-Host ($state.state | Out-String)
