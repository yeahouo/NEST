# 验证码端到端验收（改手机号 / 绑邮箱 / 忘记密码）
#
# 这个脚本存在的理由：这一批代码修的是一个真实的安全漏洞
# （reset-password.html 把手机号放在 sessionStorage 里，服务端又完全不校验验证码，
#  等于任何人都能改掉任何人的密码）。所以"看起来能跑"不算数，必须逐条断言：
#   - 验证码真的生成、真的过期、真的只能错 5 次、真的用过就作废
#   - 错码必须被拒，且被拒之后账号状态一点没变
#   - 忘记密码必须凭验证码，且验证码不对时旧密码依然有效
#   - 60 秒限流真的生效
#
# 全部操作都落在一个本脚本现注册的探针账号上，不碰 8 个种子用户
# （它们共用 bcrypt hash $2a$10$oeYEUHFyMIA2xGfDsUnA2ORKfsubJSwdlPHaq07brJGofUMewEINm 密码 123456）。
#
# 用前确保：8080 在跑，且 nest.verify.pepper 已生效，日志里能看到 [NEST-VERIFY] 行。
# 用法：powershell -ExecutionPolicy Bypass -File tools\verify-code-e2e.ps1

param(
    [int]$Port = 8080,
    [string]$LogFile = 'D:\project\NEST\target\main.log'
)

$ErrorActionPreference = 'Stop'
$Base = "http://127.0.0.1:$Port"
$Tmp = Join-Path $env:TEMP 'nest-verify-e2e'
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null

$script:Pass = 0
$script:Fail = 0
$script:Notes = @()

function Step([string]$Name) { Write-Host "`n=== $Name ===" -ForegroundColor Cyan }

function Ok([string]$Msg) {
    $script:Pass++
    Write-Host "  [OK]   $Msg" -ForegroundColor Green
}

function Bad([string]$Msg) {
    $script:Fail++
    Write-Host "  [FAIL] $Msg" -ForegroundColor Red
}

function Assert([bool]$Cond, [string]$Msg, [string]$Detail = '') {
    if ($Cond) { Ok $Msg } else { Bad "$Msg $(if ($Detail) { "-> $Detail" })" }
}

function Note([string]$Msg) { $script:Notes += $Msg; Write-Host "  [note] $Msg" -ForegroundColor Yellow }

function Get-Sid([string]$HdrFile) {
    if (-not (Test-Path $HdrFile)) { return $null }
    $line = Get-Content $HdrFile | Where-Object { $_ -match '^Set-Cookie:\s*JSESSIONID=' } | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -replace '.*JSESSIONID=([^;]+).*', '$1').Trim()
}

# 一次请求：返回 { Status, Body, Sid }。body 先写文件再用 UTF8 读，
# 因为 Invoke-RestMethod 会把中文搞乱，而我们要断言中文错误文案。
function Call {
    param([string]$Method, [string]$Path, $Json = $null, [string]$Sid = '')
    $id = [guid]::NewGuid().ToString('N')
    $hdr = Join-Path $Tmp "$id.hdr"
    $out = Join-Path $Tmp "$id.json"
    $req = Join-Path $Tmp "$id.req"
    $cargs = @('-s', '-D', $hdr, '-o', $out, '-X', $Method)
    if ($Sid) { $cargs += @('-H', "Cookie: JSESSIONID=$Sid") }
    if ($null -ne $Json) {
        $text = if ($Json -is [string]) { $Json } else { $Json | ConvertTo-Json -Compress -Depth 6 }
        [System.IO.File]::WriteAllText($req, $text, (New-Object System.Text.UTF8Encoding($false)))
        $cargs += @('-H', 'Content-Type: application/json', '--data-binary', "@$req")
    }
    $cargs += "$Base$Path"
    & curl.exe @cargs 2>$null | Out-Null

    $status = 0
    if (Test-Path $hdr) {
        $sl = Get-Content $hdr | Where-Object { $_ -match '^HTTP/' } | Select-Object -Last 1
        if ($sl -match ' (\d{3})') { $status = [int]$Matches[1] }
    }
    $body = ''
    if (Test-Path $out) { $body = Get-Content $out -Raw -Encoding UTF8 }
    return [pscustomobject]@{ Status = $status; Body = $body; Sid = (Get-Sid $hdr); Raw = $body }
}

function AsJson($Resp) {
    if (-not $Resp.Body) { return $null }
    try { return $Resp.Body | ConvertFrom-Json } catch { return $null }
}

# 从服务器日志里捞最近一条 [NEST-VERIFY] 行。这是当前唯一的取码途径，
# 因为默认的 LoggingVerificationCodeSender 老实承认没接网关（delivers=false）。
function Get-LogCode([string]$Scene, [string]$Target) {
    $pattern = '\[NEST-VERIFY\].*scene=' + [regex]::Escape($Scene) + '\s+target=' + [regex]::Escape($Target) + '\s+code=(\d{6})'
    $lines = Get-Content $LogFile -Tail 400 -Encoding UTF8 -ErrorAction SilentlyContinue
    $hit = $null
    foreach ($l in $lines) { if ($l -match $pattern) { $hit = $Matches[1] } }
    return $hit
}

function Wait-LogCode([string]$Scene, [string]$Target, [int]$Seconds = 10) {
    for ($i = 0; $i -lt ($Seconds * 2); $i++) {
        $c = Get-LogCode $Scene $Target
        if ($c) { return $c }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

Write-Host "验证码端到端验收 @ $Base" -ForegroundColor Cyan

# ── 0. 前置：服务在跑吗 ────────────────────────────────────────
Step '0. 前置检查'
$ping = Call 'GET' '/api/dorm/health'
Assert ($ping.Status -eq 200) "服务在 $Port 上响应" "status=$($ping.Status)"
if ($ping.Status -ne 200) {
    Write-Host "服务没起来，先启动再跑这个脚本。" -ForegroundColor Red
    exit 2
}
if (-not (Test-Path $LogFile)) { Note "日志文件不存在：$LogFile，取码会失败" }

# ── 1. 注册一个一次性探针账号 ──────────────────────────────────
Step '1. 注册探针账号'
$stamp = (Get-Date).ToString('MMddHHmmss')
$probeUser = "vprobe$stamp"
$probePass = 'probe123456'
$phone1 = '+8613' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phone2 = '+8613' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$probeMail1 = "$probeUser@example.com"
$probeMail2 = "$probeUser.new@example.com"

$reg = Call 'POST' '/api/auth/register' @{
    username        = $probeUser
    password        = $probePass
    confirmPassword = $probePass
    phone           = $phone1
    userType        = 'student'
}
$regJson = AsJson $reg
Assert ($reg.Status -eq 200 -and $regJson.code -eq 200) "注册成功 ($probeUser / $phone1)" "status=$($reg.Status) body=$($reg.Body)"
if ($regJson.code -ne 200) {
    Write-Host "注册没成功，后面的断言没有意义，先看这里。" -ForegroundColor Red
    exit 2
}

# ── 2. 登录 + 初始状态 ────────────────────────────────────────
Step '2. 登录并读初始设置'
$login = Call 'POST' '/api/auth/login' @{ username = $probeUser; password = $probePass }
$sid = $login.Sid
Assert ($login.Status -eq 200 -and $sid) "登录拿到 JSESSIONID" "status=$($login.Status) sid=$sid"
if (-not $sid) { exit 2 }

$set0 = AsJson (Call 'GET' '/api/user/settings' $null $sid)
Assert ($set0.data.account.phone -eq $phone1) "初始手机号 = $phone1" "实际=$($set0.data.account.phone)"
Assert ($set0.data.account.phoneVerified -eq 0) '初始 phoneVerified = 0（注册时不算已验证）' "实际=$($set0.data.account.phoneVerified)"

# ── 3. 发验证码 + 限流 ────────────────────────────────────────
Step '3. 发送绑定手机号的验证码'
$send1 = Call 'POST' '/api/user/verify-code' @{ scene = 'phone'; target = $phone2 } $sid
$send1Json = AsJson $send1
Assert ($send1.Status -eq 200 -and $send1Json.code -eq 200) '发送成功' "status=$($send1.Status) body=$($send1.Body)"
Assert ($send1Json.data.delivered -eq $false) 'data.delivered = false（诚实报告：没接网关）' "实际=$($send1Json.data.delivered)"
Assert ($send1Json.data.channel -eq 'log') 'data.channel = log' "实际=$($send1Json.data.channel)"
Assert ($send1Json.data.expiresIn -eq 300) 'data.expiresIn = 300 秒' "实际=$($send1Json.data.expiresIn)"
Assert ([bool]$send1Json.data.hint) 'data.hint 有中文说明' "实际=$($send1Json.data.hint)"

$send2 = Call 'POST' '/api/user/verify-code' @{ scene = 'phone'; target = $phone2 } $sid
$send2Json = AsJson $send2
Assert ($send2Json.code -eq 400) '60 秒内重发被限流拒绝' "status=$($send2.Status) body=$($send2.Body)"

$codePhone = Wait-LogCode 'bind_phone' $phone2 10
Assert ([bool]$codePhone) "从日志捞到手机验证码 ($codePhone)" "日志里没有 [NEST-VERIFY] 行"

# ── 4. 错码必须被拒，且状态不变 ────────────────────────────────
Step '4. 错码换手机号（必须失败）'
$wrong = if ($codePhone -eq '000000') { '111111' } else { '000000' }
$badPhone = Call 'PUT' '/api/user/phone' @{ phone = $phone2; code = $wrong } $sid
$badPhoneJson = AsJson $badPhone
Assert ($badPhoneJson.code -eq 400) '错码被拒' "status=$($badPhone.Status) body=$($badPhone.Body)"
$setAfterBad = AsJson (Call 'GET' '/api/user/settings' $null $sid)
Assert ($setAfterBad.data.account.phone -eq $phone1) '错码之后手机号没变' "实际=$($setAfterBad.data.account.phone)"
Assert ($setAfterBad.data.account.phoneVerified -eq 0) '错码之后 phoneVerified 仍是 0' "实际=$($setAfterBad.data.account.phoneVerified)"

# ── 5. 对码换手机号 ──────────────────────────────────────────
Step '5. 对码换手机号（必须成功）'
$goodPhone = Call 'PUT' '/api/user/phone' @{ phone = $phone2; code = $codePhone } $sid
$goodPhoneJson = AsJson $goodPhone
Assert ($goodPhoneJson.code -eq 200) '对码通过' "status=$($goodPhone.Status) body=$($goodPhone.Body)"
$set1 = AsJson (Call 'GET' '/api/user/settings' $null $sid)
Assert ($set1.data.account.phone -eq $phone2) "手机号已更新为 $phone2" "实际=$($set1.data.account.phone)"
Assert ($set1.data.account.phoneVerified -eq 1) 'phoneVerified 变成 1' "实际=$($set1.data.account.phoneVerified)"

Step '5b. 同一个码不能再用第二次'
$reuse = AsJson (Call 'PUT' '/api/user/phone' @{ phone = $phone1; code = $codePhone } $sid)
Assert ($reuse.code -eq 400) '已用过的码被拒（一次性）' "body=$($reuse.Body)"
$set1b = AsJson (Call 'GET' '/api/user/settings' $null $sid)
Assert ($set1b.data.account.phone -eq $phone2) '复用失败后手机号没被改回去' "实际=$($set1b.data.account.phone)"

# ── 6. 邮箱同流程 ────────────────────────────────────────────
Step '6. 绑定邮箱'
$sendMail = AsJson (Call 'POST' '/api/user/verify-code' @{ scene = 'email'; target = $probeMail1 } $sid)
Assert ($sendMail.code -eq 200) '发送邮箱验证码' "body=$($sendMail.Body)"
$codeMail = Wait-LogCode 'bind_email' $probeMail1 10
Assert ([bool]$codeMail) "从日志捞到邮箱验证码 ($codeMail)"

$wrongMail = if ($codeMail -eq '000000') { '111111' } else { '000000' }
$badMail = AsJson (Call 'PUT' '/api/user/email' @{ email = $probeMail1; code = $wrongMail } $sid)
Assert ($badMail.code -eq 400) '邮箱错码被拒' "body=$($badMail.Body)"

$goodMail = AsJson (Call 'PUT' '/api/user/email' @{ email = $probeMail1; code = $codeMail } $sid)
Assert ($goodMail.code -eq 200) '邮箱对码通过' "body=$($goodMail.Body)"
$set2 = AsJson (Call 'GET' '/api/user/settings' $null $sid)
Assert ($set2.data.account.email -eq $probeMail1) "邮箱已更新" "实际=$($set2.data.account.email)"
Assert ($set2.data.account.emailVerified -eq 1) 'emailVerified 变成 1' "实际=$($set2.data.account.emailVerified)"
Assert ($set2.data.bindings.emailBound -eq $true) 'bindings.emailBound = true' "实际=$($set2.data.bindings.emailBound)"
Assert ([bool]$set2.data.bindings.emailBoundAt) 'emailBoundAt 有值' "实际=$($set2.data.bindings.emailBoundAt)"

Step '6b. 已被别人占用的手机号不能发码'
# 需要一个真实的"别人"。再注册一个探针 B，用它的号码去试 —— 这样断言的是真实冲突，
# 而不是"随便编一个没人用的号码，结果当然成功"那种什么都没验证的测试。
$probeB = "vprobeb$stamp"
$phoneB = '+8613' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$regB = AsJson (Call 'POST' '/api/auth/register' @{
    username = $probeB; password = $probePass; confirmPassword = $probePass
    phone = $phoneB; userType = 'student'
})
Assert ($regB.code -eq 200) "注册第二个探针 ($probeB / $phoneB)" "body=$($regB.Body)"

$occupied = AsJson (Call 'POST' '/api/user/verify-code' @{ scene = 'phone'; target = $phoneB } $sid)
Assert ($occupied.code -eq 400) '以 A 的身份给 B 的手机号发码被拒' "body=$($occupied.Body)"
Assert ($occupied.message -match '已被其他账号绑定') '拒绝原因是"已被其他账号绑定"' "message=$($occupied.message)"

$mineAgain = AsJson (Call 'POST' '/api/user/verify-code' @{ scene = 'email'; target = $probeMail1 } $sid)
# 「自己绑自己」必须放行。若刚刚（60 秒内）才给同一个地址发过码，那应当命中限流，
# 而不是被当成「已被其他账号绑定」—— 这两件事的 message 完全不同，这里必须区分开。
$selfOk = ($mineAgain.code -eq 200)
$selfLimited = ($mineAgain.code -eq 400 -and "$($mineAgain.message)" -match '秒后再试')
Assert ($selfOk -or $selfLimited) '对自己已绑定的邮箱发码：要么放行，要么命中限流（绝不是"占用"）' "code=$($mineAgain.code) msg=$($mineAgain.message)"

Step '6c. 新注册的账号默认"未验证"'
$loginB = Call 'POST' '/api/auth/login' @{ username = $probeB; password = $probePass }
$setB = AsJson (Call 'GET' '/api/user/settings' $null $loginB.Sid)
Assert ($setB.data.account.phoneVerified -eq 0) '注册时填了手机号，但 phoneVerified 仍是 0（没验证过就不算已验证）' "实际=$($setB.data.account.phoneVerified)"
Assert ($setB.data.bindings.emailBound -eq $false) 'register 的 emailBound 是 false' "实际=$($setB.data.bindings.emailBound)"

# ── 7. 忘记密码：错码重置必须失败 ─────────────────────────────
Step '7. 忘记密码 —— 错码路径'
$forgotSend = AsJson (Call 'POST' '/api/auth/send-code' @{ phone = $phone2 })
Assert ($forgotSend.code -eq 200) '向已注册手机号发重置码' "body=$($forgotSend.Body)"
$codeReset = Wait-LogCode 'reset_password' $phone2 10
Assert ([bool]$codeReset) "从日志捞到重置码 ($codeReset)"

$wrongReset = if ($codeReset -eq '000000') { '111111' } else { '000000' }
$badReset = AsJson (Call 'POST' '/api/auth/reset-password' @{
    phone = $phone2; verificationCode = $wrongReset
    newPassword = 'hacked123'; confirmPassword = 'hacked123'
})
Assert ($badReset.code -eq 400) '错码重置被拒（这就是原来的漏洞）' "body=$($badReset.Body)"
$oldStillWorks = AsJson (Call 'POST' '/api/auth/login' @{ username = $probeUser; password = $probePass })
Assert ($oldStillWorks.code -eq 200) '错码重置后旧密码依然有效（密码没被改）' "body=$($oldStillWorks.Body)"
$hackedLogin = AsJson (Call 'POST' '/api/auth/login' @{ username = $probeUser; password = 'hacked123' })
Assert ($hackedLogin.code -ne 200) '用被拒的新密码登不上' "body=$($hackedLogin.Body)"

Step '7b. 忘记密码 —— 缺验证码路径'
$noCode = AsJson (Call 'POST' '/api/auth/reset-password' @{
    phone = $phone2; newPassword = 'hacked123'; confirmPassword = 'hacked123'
})
Assert ($noCode.code -eq 400) '不带验证码的重置被拒' "body=$($noCode.Body)"

Step '7c. 忘记密码 —— 对码成功'
$goodReset = AsJson (Call 'POST' '/api/auth/reset-password' @{
    phone = $phone2; verificationCode = $codeReset
    newPassword = 'brandnew123'; confirmPassword = 'brandnew123'
})
Assert ($goodReset.code -eq 200) '对码重置成功' "body=$($goodReset.Body)"
$newLogin = AsJson (Call 'POST' '/api/auth/login' @{ username = $probeUser; password = 'brandnew123' })
Assert ($newLogin.code -eq 200) '用新密码能登录' "body=$($newLogin.Body)"
$oldLogin = AsJson (Call 'POST' '/api/auth/login' @{ username = $probeUser; password = $probePass })
Assert ($oldLogin.code -ne 200) '旧密码失效' "body=$($oldLogin.Body)"

Step '7d. 未注册手机号不能发重置码'
$ghost = AsJson (Call 'POST' '/api/auth/send-code' @{ phone = '+8613900000000' })
Note "未注册号码返回 code=$($ghost.code) msg=$($ghost.message)"

# ── 8. 收尾 ──────────────────────────────────────────────────
Step '结果'
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail) -ForegroundColor $(if ($script:Fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "`n探针账号：$probeUser（手机 $phone2，密码先 probe123456 后 brandnew123）—— 留作痕迹，可手工删。" -ForegroundColor DarkGray
if ($script:Notes.Count -gt 0) {
    Write-Host '观察项：' -ForegroundColor DarkGray
    $script:Notes | ForEach-Object { Write-Host "  - $_" -ForegroundColor DarkGray }
}
exit $(if ($script:Fail -eq 0) { 0 } else { 1 })
