# L0→L1→L2 50用例测试脚本
$baseUrl = "http://localhost:8080/api/bank"
$results = @()
$passCount = 0
$failCount = 0

function Send-Chat {
    param([string]$sessionId, [string]$message)
    try {
        $body = @{message = $message} | ConvertTo-Json -Compress
        $resp = Invoke-RestMethod -Uri "$baseUrl/chat?sessionId=$sessionId" -Method Post -ContentType "application/json" -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
        return $resp
    } catch {
        return @{status = "ERROR"; errorMessage = $_.Exception.Message}
    }
}

function Clear-Session {
    param([string]$sessionId)
    try {
        Invoke-RestMethod -Uri "$baseUrl/session?sessionId=$sessionId" -Method Delete | Out-Null
    } catch {}
}

function Test-Case {
    param(
        [int]$num,
        [string]$desc,
        [string[]]$messages,
        [string]$expectedStatus,
        [string]$expectedContains = "",
        [string]$expectedNotContains = ""
    )
    $sessionId = "test_$num`_" + (Get-Random)
    
    $lastResp = $null
    foreach ($msg in $messages) {
        $lastResp = Send-Chat -sessionId $sessionId -message $msg
    }
    
    $passed = $true
    $reason = ""
    
    if ($expectedStatus -and $lastResp.status -ne $expectedStatus) {
        $passed = $false
        $reason = "status=$($lastResp.status) expected=$expectedStatus"
    }
    
    if ($expectedContains -and $passed) {
        $contentToCheck = if ($lastResp.content) { $lastResp.content } elseif ($lastResp.question) { $lastResp.question } elseif ($lastResp.errorMessage) { $lastResp.errorMessage } else { "" }
        if ($contentToCheck -notmatch $expectedContains) {
            $passed = $false
            $reason = "content not contain '$expectedContains': $contentToCheck"
        }
    }
    
    if ($expectedNotContains -and $passed) {
        $contentToCheck = if ($lastResp.content) { $lastResp.content } elseif ($lastResp.question) { $lastResp.question } else { "" }
        if ($contentToCheck -match $expectedNotContains) {
            $passed = $false
            $reason = "content should not contain '$expectedNotContains': $contentToCheck"
        }
    }
    
    if ($passed) { $passCount++ } else { $failCount++ }
    
    $contentPreview = if ($lastResp.content) { $lastResp.content.Substring(0, [Math]::Min(80, $lastResp.content.Length)) } elseif ($lastResp.question) { $lastResp.question } else { "N/A" }
    
    Write-Host ("[{0}] #{1} {2} -> status={3} content={4}" -f $(if($passed){"PASS"}else{"FAIL"}), $num, $desc, $lastResp.status, $contentPreview) -ForegroundColor $(if($passed){"Green"}else{"Red"})
    if (-not $passed) {
        Write-Host "      Reason: $reason" -ForegroundColor Yellow
    }
    
    Clear-Session -sessionId $sessionId
    return $passed
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " L0-L1-L2 50 Test Cases" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ==================== 1. 直接达成 (5 cases) ====================
Write-Host "`n--- 1. 直接达成 ---" -ForegroundColor Cyan

Test-Case -num 1 -desc "转账-完整参数" -messages @("给张三转账500元") -expectedStatus "COMPLETED" -expectedContains "张三|转账"
Test-Case -num 2 -desc "账单-查支出" -messages @("查一下上个月的支出") -expectedStatus "COMPLETED" -expectedContains "支出|账单|消费"
Test-Case -num 3 -desc "理财推荐" -messages @("推荐稳健型理财产品") -expectedStatus "COMPLETED" -expectedContains "理财|推荐"
Test-Case -num 4 -desc "理财解读" -messages @("解读金享利理财产品") -expectedStatus "COMPLETED" -expectedContains "金享利|解读"
Test-Case -num 5 -desc "闲聊" -messages @("你好") -expectedStatus "COMPLETED" -expectedContains ""

# ==================== 2. FOLLOW_UP多轮 (7 cases) ====================
Write-Host "`n--- 2. FOLLOW_UP多轮 ---" -ForegroundColor Cyan

Test-Case -num 6 -desc "转账-两轮追问" -messages @("我要转账","张三") -expectedStatus "INTERRUPTED" -expectedContains "金额|多少"
Test-Case -num 7 -desc "转账-三轮完成" -messages @("我要转账","张三","500") -expectedStatus "COMPLETED" -expectedContains "张三|500|成功"
Test-Case -num 8 -desc "账单-两轮追问" -messages @("查账单","上个月") -expectedStatus "INTERRUPTED" -expectedContains "收支|类型|支出"
Test-Case -num 9 -desc "账单-三轮完成" -messages @("查账单","上个月","支出") -expectedStatus "COMPLETED" -expectedContains "支出|账单"
Test-Case -num 10 -desc "理财推荐-两轮" -messages @("推荐理财产品","稳健型") -expectedStatus "COMPLETED" -expectedContains "稳健|推荐"
Test-Case -num 11 -desc "理财解读-两轮" -messages @("解读金享利","金享利") -expectedStatus "COMPLETED" -expectedContains "金享利"
Test-Case -num 12 -desc "闲聊-多轮" -messages @("你好","今天天气怎么样") -expectedStatus "COMPLETED"

# ==================== 3. 意图切换 (8 cases) ====================
Write-Host "`n--- 3. 意图切换 ---" -ForegroundColor Cyan

Test-Case -num 13 -desc "转账→账单切换" -messages @("我要转账","算了查一下账单") -expectedStatus "INTERRUPTED" -expectedContains "账单|时间"
Test-Case -num 14 -desc "账单→转账切换" -messages @("查账单","算了我要转账") -expectedStatus "INTERRUPTED" -expectedContains "转给谁|收款"
Test-Case -num 15 -desc "转账→闲聊切换" -messages @("我要转账","今天天气不错") -expectedStatus "COMPLETED"
Test-Case -num 16 -desc "闲聊→转账切换" -messages @("你好","我要转账") -expectedStatus "INTERRUPTED" -expectedContains "转给谁|收款"
Test-Case -num 17 -desc "理财→转账切换" -messages @("推荐理财产品","算了我要转账") -expectedStatus "INTERRUPTED" -expectedContains "转给谁|收款"
Test-Case -num 18 -desc "转账→理财切换" -messages @("我要转账","推荐稳健理财") -expectedStatus "COMPLETED" -expectedContains "稳健|推荐"
Test-Case -num 19 -desc "账单→闲聊切换" -messages @("查账单","讲个笑话") -expectedStatus "COMPLETED"
Test-Case -num 20 -desc "闲聊→账单切换" -messages @("你好","查账单") -expectedStatus "INTERRUPTED" -expectedContains "账单|时间"

# ==================== 4. RESUME (8 cases) ====================
Write-Host "`n--- 4. RESUME ---" -ForegroundColor Cyan

Test-Case -num 21 -desc "转账中断→查账单→恢复转账" -messages @("我要转账","张三","算了查账单","继续转账吧500块") -expectedStatus "COMPLETED" -expectedContains "张三|500|成功"
Test-Case -num 22 -desc "账单中断→闲聊→恢复账单" -messages @("查账单","上个月","你好","继续查账单支出") -expectedStatus "COMPLETED" -expectedContains "支出|账单"
Test-Case -num 23 -desc "理财推荐中断→转账→恢复理财" -messages @("推荐理财","稳健型","算了我要转账","继续理财咨询吧") -expectedStatus "COMPLETED" -expectedContains "稳健|推荐"
Test-Case -num 24 -desc "转账→闲聊→继续转账" -messages @("我要转账","张三","你好","转500") -expectedStatus "COMPLETED" -expectedContains "张三|500"
Test-Case -num 25 -desc "理财解读→转账→恢复解读" -messages @("解读金享利","金享利","我要转账","继续解读") -expectedStatus "COMPLETED" -expectedContains "金享利|解读"
Test-Case -num 26 -desc "多领域切换后恢复" -messages @("我要转账","查账单","推荐理财","继续转账500") -expectedStatus "COMPLETED" -expectedContains "500"
Test-Case -num 27 -desc "恢复时用明确意图词" -messages @("我要转账","张三","查账单","上个月","回到转账500元") -expectedStatus "COMPLETED" -expectedContains "500"
Test-Case -num 28 -desc "理财推荐→解读→恢复推荐" -messages @("推荐稳健理财","解读金享利","继续推荐") -expectedStatus "COMPLETED" -expectedContains "推荐|稳健"

# ==================== 5. 取消 (7 cases) ====================
Write-Host "`n--- 5. 取消 ---" -ForegroundColor Cyan

Test-Case -num 29 -desc "转账中取消" -messages @("我要转账","取消") -expectedStatus "COMPLETED" -expectedContains "取消"
Test-Case -num 30 -desc "账单中取消" -messages @("查账单","算了") -expectedStatus "COMPLETED" -expectedContains "取消|算了"
Test-Case -num 31 -desc "转账追问时取消" -messages @("我要转账","张三","不转了") -expectedStatus "COMPLETED" -expectedContains "取消"
Test-Case -num 32 -desc "理财消歧时取消" -messages @("理财","取消") -expectedStatus "COMPLETED" -expectedContains "取消"
Test-Case -num 33 -desc "取消后新意图" -messages @("我要转账","取消","查账单") -expectedStatus "INTERRUPTED" -expectedContains "账单|时间"
Test-Case -num 34 -desc "放弃当前操作" -messages @("我要转账","放弃") -expectedStatus "COMPLETED" -expectedContains "取消|放弃"
Test-Case -num 35 -desc "不查了" -messages @("查账单","不查了") -expectedStatus "COMPLETED" -expectedContains "取消"

# ==================== 6. 消歧 (5 cases) ====================
Write-Host "`n--- 6. 消歧 ---" -ForegroundColor Cyan

Test-Case -num 36 -desc "理财模糊→消歧" -messages @("理财") -expectedStatus "DISAMBIGUATION" -expectedContains "咨询|解读"
Test-Case -num 37 -desc "消歧回答-咨询" -messages @("理财","咨询") -expectedStatus "COMPLETED" -expectedContains "推荐|咨询"
Test-Case -num 38 -desc "消歧回答-解读" -messages @("理财","解读") -expectedStatus "COMPLETED" -expectedContains "解读"
Test-Case -num 39 -desc "明确说理财咨询-不消歧" -messages @("理财咨询") -expectedStatus "COMPLETED" -expectedContains "推荐|咨询"
Test-Case -num 40 -desc "明确说理财解读-不消歧" -messages @("理财产品解读") -expectedStatus "COMPLETED" -expectedContains "解读"

# ==================== 7. 边界/UNSUPPORTED (10 cases) ====================
Write-Host "`n--- 7. 边界/UNSUPPORTED ---" -ForegroundColor Cyan

Test-Case -num 41 -desc "贷款-不支持" -messages @("我要申请贷款") -expectedStatus "COMPLETED" -expectedContains "不支持"
Test-Case -num 42 -desc "信用卡-不支持" -messages @("信用卡额度查询") -expectedStatus "COMPLETED" -expectedContains "不支持"
Test-Case -num 43 -desc "活动-不支持" -messages @("有什么优惠活动") -expectedStatus "COMPLETED" -expectedContains "不支持"
Test-Case -num 44 -desc "积分-不支持" -messages @("积分兑换") -expectedStatus "COMPLETED" -expectedContains "不支持"
Test-Case -num 45 -desc "空消息" -messages @("") -expectedStatus "ERROR"
Test-Case -num 46 -desc "理财产品购买-不支持" -messages @("购买理财产品") -expectedStatus "COMPLETED" -expectedContains "不支持|仅支持"
Test-Case -num 47 -desc "短数字续答" -messages @("我要转账","100") -expectedStatus "INTERRUPTED" -expectedContains "转给谁|收款"
Test-Case -num 48 -desc "重复意图" -messages @("查账单","再查一次账单") -expectedStatus "INTERRUPTED" -expectedContains "账单|时间"
Test-Case -num 49 -desc "转账后继续转账" -messages @("给李四转200元","再给王五转300") -expectedStatus "COMPLETED" -expectedContains "王五|300"
Test-Case -num 50 -desc "权益查询-不支持" -messages @("我的会员权益") -expectedStatus "COMPLETED" -expectedContains "不支持"

# ==================== Summary ====================
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host (" RESULTS: {0} PASS / {1} FAIL / {2} TOTAL" -f $passCount, $failCount, ($passCount + $failCount)) -ForegroundColor $(if($failCount -eq 0){"Green"}else{"Red"})
Write-Host "========================================" -ForegroundColor Cyan
