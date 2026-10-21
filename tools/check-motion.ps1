param(
  [int]$Port = 9222,
  [string]$StudentUser = 'chendw',
  [string]$TeacherUser = 'lisi',
  [string]$Password = '123456',
  [string]$Out = 'target\check-motion.log'
)

# Motion layer regression check (nest-motion.js + /libs/ animation libraries).
# ASCII only on purpose: this repo's Windows PowerShell 5.1 reads .ps1 without a BOM as ANSI,
# so Chinese in a script written without a BOM breaks parsing.
#
# What it proves, on the student dashboard (8 checks; P8 前还有一节教师课程页 tab 指示器，已随页面一起删):
#   1. the login pair works (student + teacher sessions)
#   2. anime.js / vanilla-tilt / canvas-confetti / nest-motion.js are actually loaded
#   3. html.nx-motion is on and elements revealed by nest-motion.js reach .nx-in
#      (a stuck .nx-appear means half the page is invisible: the round-9 equivalent of the
#       hidden/display bug that round 8 was about)
#   4. the three stat cells settle to digits (CountUp.js driven by nest-ui.js)
#   5. the mouse spotlight really writes --nx-mx / --nx-my (read AFTER a rAF, never in the
#       same tick as the synthetic pointer event)
#   6. after a full scroll down and back, (a) every [data-nx-reveal] section reached .is-in and
#      (b) nothing is left stuck at .nx-appear **past the reveal line**:
#      the observer uses rootMargin '0px 0px -6% 0px', so an element can sit between that line
#      and the viewport bottom without ever being revealed (P12 measured: dashboard tile top=847 /
#      vh=900 / line=846). The check judges with that same -6% line, not the raw viewport edge.
#   7. the page console stays clean
#
# Usage: & tools\check-motion.ps1            (needs the app on :8080 and headless Chrome on 9222)

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$enc = [System.Text.UTF8Encoding]::new($false)
$log = Join-Path $repo $Out
$results = @()

function Login([string]$user, [string]$pass) {
  $body = '{"username":"' + $user + '","password":"' + $pass + '"}'
  $tmpBody = Join-Path $env:TEMP 'nest-motion-body.json'
  $tmpHead = Join-Path $env:TEMP 'nest-motion-head.txt'
  [System.IO.File]::WriteAllText($tmpBody, $body, $enc)
  & curl.exe -s -D $tmpHead -o NUL -H 'Content-Type: application/json' --data-binary "@$tmpBody" 'http://127.0.0.1:8080/api/auth/login' | Out-Null
  $raw = [System.IO.File]::ReadAllText($tmpHead)
  $m = [regex]::Match($raw, 'JSESSIONID=([0-9A-Fa-f]+)')
  if (-not $m.Success) { return '' }
  return $m.Groups[1].Value
}

function Invoke-Probe([string]$name, [string]$sid, [string]$url, [string]$stepsJson) {
  $cfg = '{"port":' + $Port + ',"cookie":{"name":"JSESSIONID","value":"' + $sid +
    '","url":"http://127.0.0.1:8080"},"page":{"url":"' + $url + '","width":1440,"height":900,"waitMs":5000},"steps":' + $stepsJson + '}'
  $cfgPath = Join-Path $env:TEMP ("nest-motion-$name.json")
  [System.IO.File]::WriteAllText($cfgPath, $cfg, $enc)
  $lines = & node (Join-Path $repo 'tools\probe.mjs') $cfgPath 2>&1
  $lines | Out-File -FilePath $log -Append -Encoding utf8
  return $lines
}

function StepJson($lines, [string]$stepName) {
  $line = $lines | Where-Object { $_ -match ('^\[' + [regex]::Escape($stepName) + '\] ') } | Select-Object -First 1
  if (-not $line) { return $null }
  $raw = $line -replace ('^\[' + [regex]::Escape($stepName) + '\] '), ''
  try { return ($raw | ConvertFrom-Json) | ConvertFrom-Json } catch { return $null }
}

function Check([string]$what, [bool]$ok, [string]$detail) {
  $script:results += [pscustomobject]@{ check = $what; result = $(if ($ok) { 'PASS' } else { 'FAIL' }); detail = $detail }
}

function ConsoleClean($lines) {
  $bad = $lines | Where-Object { $_ -match '^页面控制台错误' }
  return (-not $bad)
}

if (Test-Path $log) { Remove-Item $log -Force }
$stu = Login $StudentUser $Password
$tea = Login $TeacherUser $Password
Check 'login: student + teacher' (($stu -ne '') -and ($tea -ne '')) ("student=" + $stu.Length + " chars, teacher=" + $tea.Length + " chars")
if (($stu -eq '') -or ($tea -eq '')) {
  $results | Format-Table -AutoSize | Out-String -Width 200 | Write-Output
  exit 1
}

# ---------------------------------------------------------------- dashboard (student)
$dashSteps = '[' +
  '{"name":"libs","eval":"JSON.stringify({anime:typeof window.anime,cu:typeof window.countUp,tilt:typeof window.VanillaTilt,conf:typeof window.confetti,motion:(window.NestMotion||{}).version||null})"},' +
  '{"name":"settle","eval":"1","waitMs":900},' +
  '{"name":"state","eval":"JSON.stringify({htmlMotion:document.documentElement.classList.contains(\"nx-motion\"),appear:document.querySelectorAll(\".nx-appear\").length,nxIn:document.querySelectorAll(\".nx-appear.nx-in\").length,nestReveal:document.querySelectorAll(\".nx-reveal\").length,nestRevealIn:document.querySelectorAll(\".nx-reveal.is-in\").length,tilt:document.querySelectorAll(\".nx-tilt\").length})"},' +
  '{"name":"stats","eval":"JSON.stringify({a:(document.getElementById(\"stat-leave-pending\")||{}).textContent,b:(document.getElementById(\"stat-aid-pending\")||{}).textContent})"},' +
  '{"name":"hover","eval":"(function(){var b=document.querySelector(\".nx-btn, .btn\");if(!b)return \"no button\";var r=b.getBoundingClientRect();b.dispatchEvent(new PointerEvent(\"pointerenter\",{clientX:r.left+r.width/2,clientY:r.top+r.height/2}));b.dispatchEvent(new PointerEvent(\"pointermove\",{bubbles:true,clientX:r.left+5,clientY:r.top+5}));return \"dispatched\";})()","waitMs":900},' +
  '{"name":"spot","eval":"(function(){var b=document.querySelector(\".nx-btn, .btn\");if(!b)return \"no button\";return JSON.stringify({mx:b.style.getPropertyValue(\"--nx-mx\"),my:b.style.getPropertyValue(\"--nx-my\"),on:b.classList.contains(\"nx-spot-on\"),before:getComputedStyle(b,\"::before\").opacity});})()"},' +
  # P8 补记：揭示用的 IntersectionObserver 带 rootMargin '0px 0px -6% 0px'，也就是「元素进到视口底部
  # 6% 以内还不算进入」；而下面 stuck 的判据是「只要和视口有一点重叠就算在视口里」。二者在**视口最底下
  # 那一条**上必然打架（实测 dashboard 的 #aid-container 第 4 行 top=1089 / vh=1100，重叠 11px ⇒ 假失败）。
  # 所以先整页滚到底再滚回来：既保留 stuck 的原判据，又真的走一遍「用户滚动时该揭示的都揭示了」。
  '{"name":"scroll","eval":"(function(){var h=document.documentElement.scrollHeight;window.scrollTo(0,h);return {h:h};})()","waitMs":1400},' +
  '{"name":"scrollback","eval":"(function(){window.scrollTo(0,0);return window.scrollY;})()","waitMs":900},' +
  '{"name":"stateAfter","eval":"JSON.stringify({nestReveal:document.querySelectorAll(\".nx-reveal\").length,nestRevealIn:document.querySelectorAll(\".nx-reveal.is-in\").length})"},' +
  '{"name":"stuck","eval":"(function(){var out=[];var vh=window.innerHeight;var m=Math.round(vh*0.06);Array.prototype.forEach.call(document.querySelectorAll(\".nx-appear:not(.nx-in)\"),function(e){var r=e.getBoundingClientRect();if(r.top<vh-m&&r.bottom>0)out.push(e.className.slice(0,34));});return JSON.stringify({stuck:out.length,sample:out.slice(0,3),line:vh-m});})()"}' +
  ']'
$dash = Invoke-Probe 'dashboard' $stu 'http://127.0.0.1:8080/dashboard.html?cb=motion1' $dashSteps

$libs = StepJson $dash 'libs'
Check 'dashboard: libraries loaded' ($libs -and $libs.anime -eq 'object' -and $libs.tilt -eq 'function' -and $libs.conf -eq 'function' -and $libs.motion) ($libs | ConvertTo-Json -Compress)
$state = StepJson $dash 'state'
Check 'dashboard: motion layer active' ($state -and $state.htmlMotion -and [int]$state.appear -ge 1) ($state | ConvertTo-Json -Compress)
$stateAfter = StepJson $dash 'stateAfter'
Check 'dashboard: after a full scroll every [data-nx-reveal] section is is-in' ($stateAfter -and [int]$stateAfter.nestReveal -ge 1 -and ([int]$stateAfter.nestReveal -eq [int]$stateAfter.nestRevealIn)) ($stateAfter | ConvertTo-Json -Compress)
$stats = StepJson $dash 'stats'
Check 'dashboard: stat text settled (CountUp.js via nest-ui.js)' ($stats -and $stats.a -match '^\d+$' -and $stats.b -match '^\d+$') ($stats | ConvertTo-Json -Compress)
$spot = StepJson $dash 'spot'
Check 'dashboard: spotlight writes --nx-mx/--nx-my' ($spot -and $spot.mx -and $spot.my -and $spot.on -eq $true) ($spot | ConvertTo-Json -Compress)
$stuck = StepJson $dash 'stuck'
Check 'dashboard: nothing left stuck at .nx-appear in the viewport' ($stuck -and [int]$stuck.stuck -eq 0) ($stuck | ConvertTo-Json -Compress)
Check 'dashboard: console clean' (ConsoleClean $dash) ''


# ------------------------------------------------- (P8 教学模块下线：原「教师课程页 tab 指示器」一节随 teacher-course-detail.html 一起删除)

Write-Output ''
Write-Output '=== check-motion ==='
$results | Format-Table -AutoSize | Out-String -Width 220 | Write-Output
$failed = @($results | Where-Object { $_.result -eq 'FAIL' })
Write-Output ("log: " + $log)
if ($failed.Count -gt 0) {
  Write-Output ("FAILED: " + $failed.Count + " / " + $results.Count)
  exit 1
}
Write-Output ("ALL PASS: " + $results.Count + " checks")
