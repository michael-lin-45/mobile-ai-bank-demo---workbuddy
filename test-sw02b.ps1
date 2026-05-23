# SW-02b: 理财→转账切换
$body1 = @{message='推荐科技类理财'} | ConvertTo-Json
$bytes1 = [System.Text.Encoding]::UTF8.GetBytes($body1)
$r1 = Invoke-RestMethod -Uri 'http://localhost:8080/api/bank/chat?sessionId=sw02b_retest' -Method POST -ContentType 'application/json; charset=utf-8' -Body $bytes1 -TimeoutSec 120
Write-Host "S1: intent=[$($r1.intent)] status=[$($r1.status)]"

$body2 = @{message='算了，转账给王五'} | ConvertTo-Json
$bytes2 = [System.Text.Encoding]::UTF8.GetBytes($body2)
$r2 = Invoke-RestMethod -Uri 'http://localhost:8080/api/bank/chat?sessionId=sw02b_retest' -Method POST -ContentType 'application/json; charset=utf-8' -Body $bytes2 -TimeoutSec 120
Write-Host "S2: intent=[$($r2.intent)] status=[$($r2.status)] question=[$($r2.question)]"
if ($r2.content) { Write-Host "content=[$($r2.content.Substring(0, [Math]::Min(200, $r2.content.Length)))]" }
