Add-Type -AssemblyName System.Net.Http
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$handler = [System.Net.Http.HttpClientHandler]::new()
$script:client = [System.Net.Http.HttpClient]::new($handler)
$script:client.Timeout = [TimeSpan]::FromSeconds(30)

function Chat($sid, $msg) {
    $body = '{"message":"' + $msg.Replace('"','\"') + '"}'
    $content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, 'application/json')
    $url = 'http://localhost:8080/api/bank/chat?sessionId=' + $sid
    $resp = $script:client.PostAsync($url, $content).Result
    $txt = $resp.Content.ReadAsStringAsync().Result
    return $txt | ConvertFrom-Json
}

function Clr($sid) {
    try {
        $url = 'http://localhost:8080/api/bank/session?sessionId=' + $sid
        $script:client.DeleteAsync($url).Result | Out-Null
    } catch {}
}

$script:P = 0
$script:F = 0
$script:R = @()

function OK($id, $n, $s, $chk) {
    if ($chk) {
        $script:P++
        $script:R += '[PASS] ' + $id + ' ' + $n
    } else {
        $script:F++
        $detail = 's=' + $s.status + ' i=' + $s.intent + ' c=' + $s.content
        $script:R += '[FAIL] ' + $id + ' ' + $n + ' | ' + $detail
    }
}