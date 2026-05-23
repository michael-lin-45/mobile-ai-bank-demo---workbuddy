try {
    $r = Invoke-RestMethod -Uri 'http://localhost:8080/api/bank/state?sessionId=test_kw' -Method GET -TimeoutSec 5
    Write-Output "state endpoint works: $($r.state)"
} catch {
    Write-Output "ERROR: $($_.Exception.Message)"
}
