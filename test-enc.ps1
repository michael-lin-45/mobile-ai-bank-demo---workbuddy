# Test 1: ASCII only (should go to CHAT - no keyword match)
$b1 = @{message='hello'} | ConvertTo-Json
$r1 = Invoke-RestMethod -Uri 'http://localhost:8080/api/bank/chat?sessionId=enc1' -Method POST -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($b1))
Write-Output "ASCII: intent=$($r1.intent) status=$($r1.status)"

# Test 2: URL-encode Chinese  
$b2 = @{message='转账'} | ConvertTo-Json
$bytes2 = [System.Text.Encoding]::UTF8.GetBytes($b2)
$r2 = Invoke-RestMethod -Uri 'http://localhost:8080/api/bank/chat?sessionId=enc2' -Method POST -ContentType 'application/json; charset=utf-8' -Body $bytes2
Write-Output "Chinese: intent=$($r2.intent) status=$($r2.status)"
