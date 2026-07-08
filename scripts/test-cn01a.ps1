$body = @{message='转账500给张三'} | ConvertTo-Json
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
$r = Invoke-RestMethod -Uri 'http://localhost:8080/api/bank/chat?sessionId=cn01a_retest' -Method POST -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 120
Write-Host "intent=[$($r.intent)] status=[$($r.status)] question=[$($r.question)]"
if ($r.content) { Write-Host "content=[$($r.content.Substring(0, [Math]::Min(200, $r.content.Length)))]" }
