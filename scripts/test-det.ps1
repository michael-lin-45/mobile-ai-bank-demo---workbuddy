$body = @{message='转账500元给张三'} | ConvertTo-Json
$r = Invoke-RestMethod -Uri 'http://localhost:8080/api/bank/chat?sessionId=test_det' -Method POST -ContentType 'application/json' -Body $body
Write-Output "intent=$($r.intent) status=$($r.status)"
if ($r.content) { Write-Output "content=$($r.content.Substring(0, [Math]::Min(100, $r.content.Length)))" }
