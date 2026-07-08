$body = @{message='推荐理财产品'} | ConvertTo-Json
$r = Invoke-RestMethod -Uri 'http://localhost:8080/api/bank/chat?sessionId=debug_a2' -Method POST -ContentType 'application/json' -Body $body
Write-Output "intent=$($r.intent) status=$($r.status) question=$($r.question)"
if ($r.content) { Write-Output "content=$($r.content.Substring(0, [Math]::Min(200, $r.content.Length)))" }
