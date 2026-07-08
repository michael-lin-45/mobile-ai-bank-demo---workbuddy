# Test: DomainRouter deterministic routing should NOT need model
# "转账" is a keyword in TRANSFER_KEYWORDS - should be caught deterministically

$body = @{message='转账'} | ConvertTo-Json
try {
    $r = Invoke-RestMethod -Uri 'http://localhost:8080/api/bank/chat?sessionId=test_kw' -Method POST -ContentType 'application/json' -Body $body -TimeoutSec 30
    Write-Output "intent=$($r.intent) status=$($r.status)"
    Write-Output "question=$($r.question)"
} catch {
    Write-Output "ERROR: $($_.Exception.Message)"
}
