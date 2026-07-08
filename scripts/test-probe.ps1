function TestChat($sid, $msg) {
    $body = @{message=$msg} | ConvertTo-Json
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $r = Invoke-RestMethod -Uri "http://localhost:8080/api/bank/chat?sessionId=$sid" -Method POST -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 120
    return $r
}

# Clear
1..5 | ForEach-Object { Invoke-RestMethod -Uri "http://localhost:8080/api/bank/session?sessionId=probe_$_" -Method DELETE }

# 1. 转账 - 确定性路由
Write-Host "`n--- Probe 1: 转账 ---"
$r1 = TestChat "probe_1" "转账500元给张三"
Write-Host "intent=[$($r1.intent)] status=[$($r1.status)] question=[$($r1.question)]"

# 2. 理财推荐 - 确定性路由
Write-Host "`n--- Probe 2: 理财推荐 ---"
$r2 = TestChat "probe_2" "帮我推荐理财产品"
Write-Host "intent=[$($r2.intent)] status=[$($r2.status)] question=[$($r2.question)]"
if ($r2.content) { Write-Host "content=[$($r2.content.Substring(0, [Math]::Min(200, $r2.content.Length)))]" }

# 3. 理财解读 - 确定性路由
Write-Host "`n--- Probe 3: 理财解读 ---"
$r3 = TestChat "probe_3" "解读朝朝盈"
Write-Host "intent=[$($r3.intent)] status=[$($r3.status)] question=[$($r3.question)]"
if ($r3.content) { Write-Host "content=[$($r3.content.Substring(0, [Math]::Min(200, $r3.content.Length)))]" }

# 4. 查账单
Write-Host "`n--- Probe 4: 查账单 ---"
$r4 = TestChat "probe_4" "查账单"
Write-Host "intent=[$($r4.intent)] status=[$($r4.status)] question=[$($r4.question)]"

# 5. 闲聊
Write-Host "`n--- Probe 5: 闲聊 ---"
$r5 = TestChat "probe_5" "你好"
Write-Host "intent=[$($r5.intent)] status=[$($r5.status)]"
