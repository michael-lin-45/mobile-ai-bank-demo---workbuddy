# Test with ASCII-only message
$body = @{message='hello'} | ConvertTo-Json
$r = Invoke-RestMethod -Uri 'http://localhost:8080/api/bank/chat?sessionId=test_ascii' -Method POST -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
Write-Output "intent=$($r.intent) status=$($r.status) content=$($r.content)"
