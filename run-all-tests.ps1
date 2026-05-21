# mobile-agent test suite (30+ cases)
Add-Type -AssemblyName System.Net.Http
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$handler = [System.Net.Http.HttpClientHandler]::new()
$script:client = [System.Net.Http.HttpClient]::new($handler)
$script:client.Timeout = [TimeSpan]::FromSeconds(60)

function Chat($sid, $msg) {
    $body = ('{"message":"' + $msg.Replace('"','\"') + '"}')
    $content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, 'application/json')
    $url = ('http://localhost:8080/api/bank/chat?sessionId=' + $sid)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $resp = $script:client.PostAsync($url, $content).Result
    $sw.Stop()
    $txt = $resp.Content.ReadAsStringAsync().Result
    $obj = $txt | ConvertFrom-Json
    Write-Host ("  -> {0}ms | status={1}" -f $sw.ElapsedMilliseconds, $obj.status) -ForegroundColor DarkGray
    return $obj
}

function Clr($sid) {
    try {
        $url = ('http://localhost:8080/api/bank/session?sessionId=' + $sid)
        $script:client.DeleteAsync($url).Result | Out-Null
    } catch {}
}

$script:P = 0
$script:F = 0
$script:R = [System.Collections.ArrayList]::new()

function OK($id, $n, $s, $chk) {
    if ($chk) {
        $script:P++
        $script:R.Add(('[PASS] ' + $id + ' ' + $n)) | Out-Null
        Write-Host ('[PASS] {0} {1}' -f $id, $n) -ForegroundColor Green
    } else {
        $c = if ($s.content) { $s.content.Substring(0, [Math]::Min(50, $s.content.Length)) } else { 'null' }
        $q = if ($s.question) { $s.question.Substring(0, [Math]::Min(50, $s.question.Length)) } else { 'null' }
        $detail = ('status=' + $s.status + ' content=' + $c + ' question=' + $q)
        $script:F++
        $script:R.Add(('[FAIL] ' + $id + ' ' + $n + ' | ' + $detail)) | Out-Null
        Write-Host ('[FAIL] {0} {1} | {2}' -f $id, $n, $detail) -ForegroundColor Red
    }
}

# 1. Transfer full flow
$s = 't1'; Clr $s
$r = Chat $s ([char]0x6211 + [char]0x60F3 + [char]0x8F6C + [char]0x8D26)
OK "1" "Transfer-Start" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x8F6C + [char]0x7ED9 + [char]0x8C01))

$r = Chat $s ([char]0x5F20 + [char]0x4E09)
OK "2" "Transfer-Payee" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x91D1 + [char]0x989D))

$r = Chat $s "100"
OK "3" "Transfer-Amount-Done" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x8F6C + [char]0x8D26 + [char]0x6210 + [char]0x529F))

# 2. Bill query full flow
$s = 't2'; Clr $s
$r = Chat $s ([char]0x67E5 + [char]0x4E00 + [char]0x4E0B + [char]0x8D26 + [char]0x5355)
OK "4" "Bill-Start" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x65F6 + [char]0x95F4))

# Bill may ask follow-up about expense type - either INTERRUPTED or COMPLETED is OK
$r = Chat $s ([char]0x6700 + [char]0x8FD1 + [char]0x4E00 + [char]0x4E2A + [char]0x6708)
OK "5" "Bill-Range" $r ($r.status -eq "COMPLETED" -or $r.status -eq "INTERRUPTED")

# 3. WealthConsult - may go direct or via disambig depending on LLM
$s = 't3'; Clr $s
$r = Chat $s ([char]0x6211 + [char]0x60F3 + [char]0x54A8 + [char]0x8BE2 + [char]0x7406 + [char]0x8D22)
OK "6" "WealthConsult-Start" $r ($r.status -eq "INTERRUPTED" -or $r.status -eq "DISAMBIGUATION")

if ($r.status -eq "DISAMBIGUATION") {
    $r = Chat $s ([char]0x7406 + [char]0x8D22 + [char]0x54A8 + [char]0x8BE2)
    OK "6b" "WealthConsult-DisambigAnswer" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x98CE + [char]0x9669))
}

$r = Chat $s ([char]0x7A33 + [char]0x5065)
OK "7" "WealthConsult-Done" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x63A8 + [char]0x8350))

# 4. WealthInterpret full flow
$s = 't4'; Clr $s
$r = Chat $s ([char]0x5E2E + [char]0x6211 + [char]0x89E3 + [char]0x8BFB + [char]0x7406 + [char]0x8D22 + [char]0x4EA7 + [char]0x54C1)
OK "8" "WealthInterp-Start" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x4EA7 + [char]0x54C1))

$r = Chat $s ([char]0x7A33 + [char]0x5229 + [char]0x5B9D)
OK "9" "WealthInterp-Done" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x89E3 + [char]0x8BFB))

# 5. Disambig - choose consult
$s = 't5'; Clr $s
$r = Chat $s ([char]0x7406 + [char]0x8D22)
OK "10" "Disambig-Wealth" $r ($r.status -eq "DISAMBIGUATION")

$r = Chat $s ([char]0x7406 + [char]0x8D22 + [char]0x54A8 + [char]0x8BE2)
OK "11" "Disambig-Consult" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x98CE + [char]0x9669))

# 6. Disambig - choose interpret
$s = 't6'; Clr $s
$r = Chat $s ([char]0x7406 + [char]0x8D22)
OK "12" "Disambig-Wealth2" $r ($r.status -eq "DISAMBIGUATION")

$r = Chat $s ([char]0x4EA7 + [char]0x54C1 + [char]0x89E3 + [char]0x8BFB)
OK "13" "Disambig-Interpret" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x4EA7 + [char]0x54C1))

# 7. Disambig still ambiguous -> reject
$s = 't7'; Clr $s
$r = Chat $s ([char]0x7406 + [char]0x8D22)
OK "14" "Disambig-First" $r ($r.status -eq "DISAMBIGUATION")

$r = Chat $s ([char]0x7406 + [char]0x8D22)
OK "15" "Disambig-StillAmbig" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x4E0D + [char]0x652F + [char]0x6301))

# 8. Unsupported intent
$s = 't8'; Clr $s
$r = Chat $s ([char]0x6211 + [char]0x60F3 + [char]0x4E70 + [char]0x4FDD + [char]0x9669)
OK "16" "Unsupported" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x4E0D + [char]0x652F + [char]0x6301))

# 9. Cancel transfer (keyword)
$s = 't9'; Clr $s
$r = Chat $s ([char]0x8F6C + [char]0x8D26)
OK "17" "CancelTfr-Start" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x53D6 + [char]0x6D88 + [char]0x8F6C + [char]0x8D26)
OK "18" "CancelTfr-Keyword" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x53D6 + [char]0x6D88))

# 10. Cancel bill (keyword)
$s = 't10'; Clr $s
$r = Chat $s ([char]0x67E5 + [char]0x8D26 + [char]0x5355)
OK "19" "CancelBill-Start" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x4E0D + [char]0x8981 + [char]0x4E86)
OK "20" "CancelBill-Keyword" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x53D6 + [char]0x6D88))

# 11. Cancel mid-transfer
$s = 't11'; Clr $s
$r = Chat $s ([char]0x8F6C + [char]0x8D26)
OK "21" "CancelMid-Start" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x674E + [char]0x56DB)
OK "22" "CancelMid-Payee" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x53D6 + [char]0x6D88)
OK "23" "CancelMid-Cancel" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x53D6 + [char]0x6D88))

# 12. Cancel during disambiguation
$s = 't12'; Clr $s
$r = Chat $s ([char]0x7406 + [char]0x8D22)
OK "24" "CancelDisambig-Start" $r ($r.status -eq "DISAMBIGUATION")

$r = Chat $s ([char]0x7B97 + [char]0x4E86 + [char]0x4E0D + [char]0x95EE + [char]0x4E86)
OK "25" "CancelDisambig-Cancel" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x53D6 + [char]0x6D88))

# 13. Switch: transfer -> bill
$s = 't13'; Clr $s
$r = Chat $s ([char]0x8F6C + [char]0x8D26)
OK "26" "Switch-TfrStart" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x67E5 + [char]0x8D26 + [char]0x5355)
OK "27" "Switch-ToBill" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x65F6 + [char]0x95F4))

# 14. Switch: bill -> transfer
$s = 't14'; Clr $s
$r = Chat $s ([char]0x67E5 + [char]0x8D26 + [char]0x5355)
OK "28" "Switch2-BillStart" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x6211 + [char]0x60F3 + [char]0x8F6C + [char]0x8D26)
OK "29" "Switch2-ToTransfer" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x8F6C + [char]0x7ED9 + [char]0x8C01))

# 15. Resume suspended intent
$s = 't15'; Clr $s
$r = Chat $s ([char]0x8F6C + [char]0x8D26)
OK "30" "Resume-Start" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x5F20 + [char]0x4E09)
OK "31" "Resume-Payee" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x91D1 + [char]0x989D))

$r = Chat $s ([char]0x67E5 + [char]0x8D26 + [char]0x5355)
OK "32" "Resume-SwitchToBill" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x65F6 + [char]0x95F4))

$r = Chat $s ([char]0x7EE7 + [char]0x7EED + [char]0x8F6C + [char]0x8D26)
OK "33" "Resume-Back" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x91D1 + [char]0x989D))

# 16. Cancel with no context
$s = 't16'; Clr $s
$r = Chat $s ([char]0x53D6 + [char]0x6D88)
OK "34" "CancelNothing" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x4E0D + [char]0x652F + [char]0x6301))

# 17. Greeting -> unsupported
$s = 't17'; Clr $s
$r = Chat $s ([char]0x4F60 + [char]0x597D)
OK "35" "Greeting" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x4E0D + [char]0x652F + [char]0x6301))

# 18. Context rewrite - follow-up with history
$s = 't18'; Clr $s
$r = Chat $s ([char]0x67E5 + [char]0x4E00 + [char]0x4E0B + [char]0x6211 + [char]0x8FD9 + [char]0x4E2A + [char]0x661F + [char]0x671F + [char]0x7684 + [char]0x5F00 + [char]0x9500)
# "查一下我这个星期的开销" has enough info - may complete directly or ask time
OK "36" "Rewrite-BillStart" $r ($r.status -eq "INTERRUPTED" -or $r.status -eq "COMPLETED")

if ($r.status -eq "INTERRUPTED") {
    $r = Chat $s ([char]0x4E0A + [char]0x4E2A + [char]0x6708 + [char]0x7684)
    # Bill may ask follow-up about expense type - either INTERRUPTED or COMPLETED is OK
    OK "37" "Rewrite-BillFollowUp" $r ($r.status -eq "COMPLETED" -or $r.status -eq "INTERRUPTED")
}

# 19. Context rewrite - detail follow-up
$s = 't19'; Clr $s
$r = Chat $s ([char]0x67E5 + [char]0x4E00 + [char]0x4E0B + [char]0x8D26 + [char]0x5355)
OK "38" "RewriteDetail-Start" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x6700 + [char]0x8FD1 + [char]0x4E00 + [char]0x5468)
# Bill may ask follow-up - either is OK
OK "39" "RewriteDetail-Range" $r ($r.status -eq "COMPLETED" -or $r.status -eq "INTERRUPTED")

# 20. Transfer with payee in first msg
$s = 't20'; Clr $s
$r = Chat $s ([char]0x8F6C + [char]0x8D26 + [char]0x7ED9 + [char]0x674E + [char]0x56DB)
OK "40" "TfrWithPayee-Start" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x91D1 + [char]0x989D))

$r = Chat $s "500"
OK "41" "TfrWithPayee-Amount" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x8F6C + [char]0x8D26 + [char]0x6210 + [char]0x529F))

# 21. Another full transfer
$s = 't21'; Clr $s
$r = Chat $s ([char]0x8F6C + [char]0x8D26)
OK "42" "Tfr2-Start" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x738B + [char]0x4E94)
OK "43" "Tfr2-Payee" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s "200"
OK "44" "Tfr2-Amount" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x8F6C + [char]0x8D26 + [char]0x6210 + [char]0x529F))

# 22. Switch from transfer to wealth (disambig)
$s = 't22'; Clr $s
$r = Chat $s ([char]0x8F6C + [char]0x8D26)
OK "45" "Switch3-TfrStart" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x7406 + [char]0x8D22)
OK "46" "Switch3-Disambig" $r ($r.status -eq "DISAMBIGUATION")

# 23. Session clear then restart
$s = 't23'; Clr $s
$r = Chat $s ([char]0x8F6C + [char]0x8D26)
OK "47" "Clear-TfrStart" $r ($r.status -eq "INTERRUPTED")

Clr $s
$r = Chat $s ([char]0x4F60 + [char]0x597D)
OK "48" "Clear-AfterClear" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x4E0D + [char]0x652F + [char]0x6301))

# 24. Bill cancel with specific keyword
$s = 't24'; Clr $s
$r = Chat $s ([char]0x67E5 + [char]0x8D26 + [char]0x5355)
OK "49" "BillCancel2-Start" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x53D6 + [char]0x6D88 + [char]0x67E5 + [char]0x8BE2)
OK "50" "BillCancel2-Kw" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x53D6 + [char]0x6D88))

# 25. WealthInterpret cancel
$s = 't25'; Clr $s
$r = Chat $s ([char]0x89E3 + [char]0x8BFB + [char]0x7406 + [char]0x8D22)
OK "51" "WICancel-Start" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x4E0D + [char]0x60F3 + [char]0x8981 + [char]0x4E86)
OK "52" "WICancel-Cancel" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x53D6 + [char]0x6D88))

# 26. Disambig then reject then new intent
$s = 't26'; Clr $s
$r = Chat $s ([char]0x7406 + [char]0x8D22)
OK "53" "AfterReject-Disambig" $r ($r.status -eq "DISAMBIGUATION")

# 27. Clear answer after disambig - consult
$s = 't27'; Clr $s
$r = Chat $s ([char]0x7406 + [char]0x8D22)
OK "54" "ClearAnswer-Disambig" $r ($r.status -eq "DISAMBIGUATION")

$r = Chat $s ([char]0x54A8 + [char]0x8BE2)
OK "55" "ClearAnswer-Consult" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x98CE + [char]0x9669))

# 28. Transfer with payee in first msg
$s = 't28'; Clr $s
$r = Chat $s ([char]0x8F6C + [char]0x8D26 + [char]0x7ED9 + [char]0x8D75 + [char]0x516D)
OK "56" "EdgeTfr-Start" $r ($r.status -eq "INTERRUPTED")

# 29. Multiple cancels in a row
$s = 't29'; Clr $s
$r = Chat $s ([char]0x53D6 + [char]0x6D88)
OK "57" "MultiCancel-1" $r ($r.status -eq "COMPLETED")

$r = Chat $s ([char]0x53D6 + [char]0x6D88)
OK "58" "MultiCancel-2" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x4E0D + [char]0x652F + [char]0x6301))

# 30. Full disambig -> consult -> complete
$s = 't30'; Clr $s
$r = Chat $s ([char]0x7406 + [char]0x8D22)
OK "59" "FullDisambig-Start" $r ($r.status -eq "DISAMBIGUATION")

$r = Chat $s ([char]0x7406 + [char]0x8D22 + [char]0x54A8 + [char]0x8BE2)
OK "60" "FullDisambig-Consult" $r ($r.status -eq "INTERRUPTED" -and $r.question -match ([char]0x98CE + [char]0x9669))

$r = Chat $s ([char]0x6FC0 + [char]0x8FDB)
OK "61" "FullDisambig-Aggressive" $r ($r.status -eq "COMPLETED" -and $r.content -match ([char]0x63A8 + [char]0x8350))

# 31. Follow-up: bill query then answer time
$s = 't31'; Clr $s
$r = Chat $s ([char]0x67E5 + [char]0x8D26 + [char]0x5355)
OK "62" "FollowUpBill-Start" $r ($r.status -eq "INTERRUPTED")

$r = Chat $s ([char]0x4E0A + [char]0x4E2A + [char]0x6708)
# Bill may ask more questions or complete
OK "63" "FollowUpBill-Answer" $r ($r.status -eq "COMPLETED" -or $r.status -eq "INTERRUPTED")

# 32. Switch from wealth consult to transfer
$s = 't32'; Clr $s
$r = Chat $s ([char]0x7406 + [char]0x8D22 + [char]0x54A8 + [char]0x8BE2)
OK "64" "SwitchFromWealth-Start" $r ($r.status -eq "INTERRUPTED" -or $r.status -eq "DISAMBIGUATION")

if ($r.status -eq "DISAMBIGUATION") {
    $r = Chat $s ([char]0x54A8 + [char]0x8BE2)
    OK "64b" "SwitchFromWealth-Disambig" $r ($r.status -eq "INTERRUPTED")
}

$r = Chat $s ([char]0x8F6C + [char]0x8D26)
OK "65" "SwitchFromWealth-ToTransfer" $r ($r.status -eq "INTERRUPTED")

# Summary
Write-Host ""
Write-Host "===============================" -ForegroundColor Cyan
$total = $script:P + $script:F
Write-Host ("Total: {0}  PASS: {1}  FAIL: {2}" -f $total, $script:P, $script:F) -ForegroundColor $(if($script:F -eq 0){"Green"}else{"Red"})
Write-Host "===============================" -ForegroundColor Cyan
Write-Host ""
if ($script:F -gt 0) {
    Write-Host "Failed tests:" -ForegroundColor Red
    foreach ($item in $script:R) {
        if ($item -match '^\[FAIL\]') { Write-Host ("  " + $item) -ForegroundColor Red }
    }
} else {
    Write-Host "All tests passed!" -ForegroundColor Green
}
