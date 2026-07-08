# ==================================================
# 手机银行 AI 助手 — 测试数据播种脚本 (PowerShell)
# 用法：在 PowerShell 窗口执行 .\test_data_seed.ps1
# 前提：手机银行主应用已在 8080 端口启动
# ==================================================
$ErrorActionPreference = 'Continue'
$BASE = "http://127.0.0.1:8080/api/bank/chat"

function Send-Msg($sid, $msg) {
    $body = @{message=$msg} | ConvertTo-Json -Compress
    try {
        $r = Invoke-WebRequest -Uri "$BASE?sessionId=$sid" -Method POST -Body $body -ContentType "application/json; charset=utf-8" -TimeoutSec 60 -UseBasicParsing
        $resp = $r.Content | ConvertFrom-Json
        return "[$($resp.status)] $($resp.content.Substring(0, [Math]::Min(60, $resp.content.Length)))..."
    } catch {
        return "(request sent, status: $($_.Exception.Response.StatusCode.value__))"
    }
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  手机银行 AI 可观测数据播种" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# === 张三：转账 ===
Write-Host ">>> 张三: 转账 (3轮)" -ForegroundColor Green
Send-Msg "zhangsan-01" "帮我转50000给李四，用途是房租"
Start-Sleep 3
Send-Msg "zhangsan-01" "确定，就是他"
Start-Sleep 3
Send-Msg "zhangsan-01" "好的，确认转账"
Start-Sleep 3

# === 张三：账单 ===
Write-Host ">>> 张三: 账单查询 (2轮)" -ForegroundColor Green
Send-Msg "zhangsan-02" "帮我查一下上个月的账单明细"
Start-Sleep 3
Send-Msg "zhangsan-02" "那餐饮支出多少"
Start-Sleep 3

# === 王五：理财全链路 ===
Write-Host ">>> 王五: 理财全链路 (5轮含REROUTE)" -ForegroundColor Green
Send-Msg "wangwu-01" "我比较保守，有没有稳健的理财产品推荐"
Start-Sleep 3
Send-Msg "wangwu-01" "帮我解读一下朝朝盈这个产品"
Start-Sleep 3
Send-Msg "wangwu-01" "除了朝朝盈还有什么"
Start-Sleep 3
Send-Msg "wangwu-01" "算了，帮我转3万给赵六"  # REROUTE
Start-Sleep 3
Send-Msg "wangwu-01" "确认"
Start-Sleep 3

# === 小美：消歧 ===
Write-Host ">>> 小美: 意图消歧 (2轮)" -ForegroundColor Green
Send-Msg "xiaomei-01" "我想看看理财"
Start-Sleep 3
Send-Msg "xiaomei-01" "产品解读"
Start-Sleep 3

# === 老刘：取消 ===
Write-Host ">>> 老刘: 转账取消 (2轮)" -ForegroundColor Green
Send-Msg "laoliu-01" "转500给张三"
Start-Sleep 3
Send-Msg "laoliu-01" "算了不转了，取消"
Start-Sleep 3

# === 阿明：追问 ===
Write-Host ">>> 阿明: 理财追问 (3轮)" -ForegroundColor Green
Send-Msg "aming-01" "我是激进型投资者，推荐几只科技领域的基金"
Start-Sleep 3
Send-Msg "aming-01" "这几只基金历史收益怎么样"
Start-Sleep 3
Send-Msg "aming-01" "风险等级呢"
Start-Sleep 3

# === 小明：闲聊 ===
Write-Host ">>> 小明: 闲聊 (2轮)" -ForegroundColor Green
Send-Msg "xiaoming-01" "你好，今天天气不错"
Start-Sleep 3
Send-Msg "xiaoming-01" "对了，我的工资到账了吗"
Start-Sleep 3

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  播种完成！7 sessions, 19 turns" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
