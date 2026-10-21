# tools\check-art-layer.ps1 —— 第十七轮「整站艺术化」的机器化验收
#
# 断言四件事：
#   1) 艺术向令牌真的生效：暖纸底 #f5f3ef / 深底 #0d0e11、Open Props 的 --shadow-2 与 --ease-3
#      解析出真值、标题走 Instrument Serif、按钮与标签是胶囊（999px）；
#   2) 入口页（index/login 等 6 页）有一层固定的 WebGL 着色器「纸」+ 一层 canvas 粒子：
#      state=running、主题跟着 html.dark 变、speed=0.2、canvas 数正确；
#   3) 工作页（dashboard 等）**没有**着色器（只留粒子），且组件外形是同一套令牌；
#   4) 无人值守的降级路径：prefers-reduced-motion 下着色器 speed=0 且帧号不前进（粒子干脆不加载）、
#      真断网后仍能跑起来（SW 缓存）、控制台零错误、截图有真实色差。
#
# 用法：& tools\check-art-layer.ps1            （默认无头 Chrome :9222）
#       & tools\check-art-layer.ps1 -Port 9223
# 前置：应用在 8080、无头 Chrome 开着（见 docs\给学校的部署说明.md 第 3 步）。
#       配置由本脚本现场重新生成（模板会过期：第十八轮把主题写进 data-theme 之后，
#       旧配置里那份「只写 localStorage + class」的浅色步骤已经不足以把页面切回浅色）。
param(
  [int]$Port = 9222,
  [string]$Out = 'target\check-art-layer.log'
)
$ErrorActionPreference = 'Stop'
$root = 'D:\project\NEST'
Set-Location $root
$script:bad = 0
$log = New-Object System.Collections.Generic.List[string]

function Say([string]$s) { $log.Add($s); Write-Output $s }
function Chk([string]$name, [bool]$ok, [string]$detail) {
  if ($ok) { Say ('PASS  ' + $name.PadRight(34) + $detail) }
  else { Say ('FAIL  ' + $name.PadRight(34) + $detail); $script:bad++ }
}

$cb = [DateTimeOffset]::Now.ToUnixTimeSeconds()
Say '== 重新生成探针配置（tools\art17-probe\gen.mjs）=='
& node 'tools\art17-probe\gen.mjs' '--port' $Port '--cb' $cb 2>&1 | ForEach-Object { Say ('  ' + $_) }
Say ''

function Login([string]$user) {
  $body = Join-Path $env:TEMP ('art17-body-' + $user + '.json')
  $hdrs = Join-Path $env:TEMP ('art17-hdr-' + $user + '.txt')
  $outf = Join-Path $env:TEMP ('art17-out-' + $user + '.json')
  [System.IO.File]::WriteAllText($body, '{"username":"' + $user + '","password":"123456"}', [System.Text.UTF8Encoding]::new($false))
  & curl.exe -s -D $hdrs -o $outf -H 'Content-Type: application/json' --data-binary "@$body" 'http://127.0.0.1:8080/api/auth/login' | Out-Null
  $h = [System.IO.File]::ReadAllText($hdrs)
  if ($h -match 'JSESSIONID=([A-F0-9]+)') { return $Matches[1] }
  throw ('login failed: ' + $user)
}

# 跑一份配置（工具目录里的模板 -> target 里的实跑配置，顺便改端口与 SID）
function Run([string]$name, [string]$sid) {
  $src = Join-Path $root ('tools\art17-probe\' + $name + '.json')
  if (-not (Test-Path $src)) { throw ('missing probe config: ' + $src) }
  $cfg = [System.IO.File]::ReadAllText($src)
  $cfg = $cfg.Replace('"port": 9222', '"port": ' + [string]$Port)
  if ($sid) { $cfg = $cfg.Replace('__SID__', $sid) }
  $path = 'target\probe-art17-' + $name + '.json'
  [System.IO.File]::WriteAllText((Join-Path $root $path), $cfg, [System.Text.UTF8Encoding]::new($false))
  return (& node 'tools\probe.mjs' $path 2>&1 | Out-String)
}

function Step($raw, [string]$name) {
  $pat = '^\[' + [regex]::Escape($name) + '\]\s+(.*)$'
  $line = ($raw -split "`n" | Where-Object { $_ -match $pat } | Select-Object -First 1)
  if (-not $line) { return $null }
  $j = [regex]::Match($line, $pat).Groups[1].Value.Trim()
  $j = $j.Trim('"').Replace('\"', '"').Replace('\\', '\')
  return ($j | ConvertFrom-Json)
}

# probe.mjs 末尾会打印「页面控制台错误 N 条」或「页面控制台无错误」
function ConsoleOk($raw) {
  $errLine = ($raw -split "`n" | Where-Object { $_ -match '页面控制台错误' })
  return ($null -eq $errLine)
}

Say ('=== check-art-layer.ps1   headless Chrome :' + $Port + '   static :8080 ===')
$sid = Login 'chendw'
Say ('登录：student=' + $sid.Substring(0, 8) + '…')

# ── 1. 入口页 index.html（浅色）────────────────────────────────────────────
Say ''
Say '── index.html  (light)'
$light = Run 'index-light' $sid
$s = Step $light 'state'
if (-not $s) { Chk 'index-light/state' $false 'probe 没有返回 [state]'; Say $light }
else {
  Say ('     ' + (($s | ConvertTo-Json -Compress)))
  Chk 'index/paper background' (($s.htmlBg -eq 'rgb(245, 243, 239)') -or ($s.htmlBg -like 'oklch(0.96*')) ('htmlBg=' + $s.htmlBg)
  # 这两条是 2026-10「粒子在跑却一个像素都不画」的回归守卫：
  # body 自己再画一层底色会盖住 z-index:-10 的粒子层；缺 color/opacity 更新器时 fill 算不出来。
  Chk 'index/body 透明（保住 -z 粒子层）' ($s.bodyBg -eq 'rgba(0, 0, 0, 0)') ('bodyBg=' + $s.bodyBg)
  Chk 'index/粒子可画（fill 算得出）' ($s.partFill -eq 'ok') ('partFill=' + $s.partFill)
  Chk 'index/body wash' ($s.bodyImg -like 'radial-gradient*') ('bodyImg=' + $s.bodyImg)
  Chk 'index/serif display type' ($s.hFont -like 'Instrument Serif*') ('h1 font=' + $s.hFont)
  Chk 'index/pill buttons + cards 16px' (($s.btnR -eq '999px') -and ($s.cardR -eq '16px')) ('btnR=' + $s.btnR + ' cardR=' + $s.cardR)
  Chk 'index/open-props shadow token' ($s.shadowToken -match 'px') ('--shadow-2=' + $s.shadowToken)
  Chk 'index/open-props ease token' ($s.easeToken -match 'cubic-bezier') ('--ease-3=' + $s.easeToken)
  Chk 'index/shader layer running' (($s.shader -eq 'running') -and [string]::IsNullOrEmpty($s.shaderErr)) ('shader=' + $s.shader + ' err=' + $s.shaderErr + ' theme=' + $s.shaderTheme + ' speed=' + $s.shaderSpeed)
  Chk 'index/shader speed 0.2' ([math]::Abs([double]$s.shaderSpeed - 0.2) -lt 0.001) ('speed=' + $s.shaderSpeed)
  Chk 'index/particles dense + 2 canvases' (($s.canvases -eq 2) -and ($s.part -eq 'running') -and ($s.partPreset -eq 'dense')) ('canvases=' + $s.canvases + ' part=' + $s.part + '/' + $s.partPreset)
  Chk 'index/no false no-webgl flag' ($s.noWebgl -eq $false) ('nx-no-webgl=' + $s.noWebgl)
  Chk 'index/console clean' (ConsoleOk $light) ''
}

# ── 2. 入口页 index.html（深色）────────────────────────────────────────────
Say ''
Say '── index.html  (dark)'
$dark = Run 'index-dark' $sid
$d = Step $dark 'state'
if (-not $d) { Chk 'index-dark/state' $false 'probe 没有返回 [state]' }
else {
  Say ('     ' + (($d | ConvertTo-Json -Compress)))
  Chk 'index-dark/ink background' (($d.dark -eq $true) -and (($d.htmlBg -eq 'rgb(13, 14, 17)') -or ($d.htmlBg -like 'oklch(0.16*'))) ('dark=' + $d.dark + ' htmlBg=' + $d.htmlBg)
  Chk 'index-dark/body 透明（保住 -z 粒子层）' ($d.bodyBg -eq 'rgba(0, 0, 0, 0)') ('bodyBg=' + $d.bodyBg)
  Chk 'index-dark/shader follows theme' (($d.shaderTheme -eq 'dark') -and ($d.shader -eq 'running')) ('shaderTheme=' + $d.shaderTheme + ' state=' + $d.shader)
  Chk 'index-dark/console clean' (ConsoleOk $dark) ''
}

# ── 3. login.html（浅色，登录页是「on」档粒子）────────────────────────────
Say ''
Say '── login.html  (light)'
$login = Run 'login-light' $sid
$l = Step $login 'state'
if (-not $l) { Chk 'login/state' $false 'probe 没有返回 [state]' }
else {
  Say ('     ' + (($l | ConvertTo-Json -Compress)))
  Chk 'login/shader + particles on' (($l.shader -eq 'running') -and ($l.part -eq 'running') -and ($l.partPreset -eq 'on') -and ($l.canvases -eq 2)) ('shader=' + $l.shader + ' part=' + $l.part + '/' + $l.partPreset + ' canvases=' + $l.canvases)
  Chk 'login/paper background' (($l.htmlBg -like 'oklch(0.9*') -or ($l.htmlBg -eq 'rgb(245, 243, 239)')) ('htmlBg=' + $l.htmlBg)
  Chk 'login/body 透明（保住 -z 粒子层）' ($l.bodyBg -eq 'rgba(0, 0, 0, 0)') ('bodyBg=' + $l.bodyBg)
  Chk 'login/粒子可画（fill 算得出）' ($l.partFill -eq 'ok') ('partFill=' + $l.partFill)
  Chk 'login/pill buttons' ($l.btnR -eq '999px') ('btnR=' + $l.btnR)
  Chk 'login/console clean' (ConsoleOk $login) ''
}

# ── 4. 手机 375×812 ───────────────────────────────────────────────────────
Say ''
Say '── login.html  (375x812)'
$mobile = Run 'mobile' $sid
$m = Step $mobile 'state'
if (-not $m) { Chk 'mobile/state' $false 'probe 没有返回 [state]' }
else {
  Say ('     ' + (($m | ConvertTo-Json -Compress)))
  Chk 'mobile/no horizontal overflow' ($m.scrollW -le $m.innerW) ('scrollW=' + $m.scrollW + ' innerW=' + $m.innerW)
  Chk 'mobile/shader + particles mounted' (($m.shader -eq 'running') -and ($m.canvases -eq 2)) ('shader=' + $m.shader + ' canvases=' + $m.canvases)
  Chk 'mobile/console clean' (ConsoleOk $mobile) ''
}

# ── 5. prefers-reduced-motion ─────────────────────────────────────────────
Say ''
Say '── index.html  (prefers-reduced-motion: reduce)'
$red = Run 'reduced' $sid
$r = Step $red 'state'
$fa = Step $red 'frame-a'
$fb = Step $red 'frame-b'
if (-not $r) { Chk 'reduced/state' $false 'probe 没有返回 [state]' }
else {
  Say ('     ' + (($r | ConvertTo-Json -Compress)))
  Chk 'reduced/shader speed 0' ([double]$r.shaderSpeed -eq 0) ('speed=' + $r.shaderSpeed + ' state=' + $r.shader)
  Chk 'reduced/shader frame frozen' (($null -ne $fa) -and ($null -ne $fb) -and ([double]$fa -eq 0) -and ([double]$fa -eq [double]$fb)) ('frame=' + $fa + ' -> ' + $fb)
  Chk 'reduced/particles not even loaded' (($r.part -eq 'skipped-reduced-motion') -and ($r.partHost -eq $false) -and ($r.canvases -eq 1)) ('part=' + $r.part + ' host=' + $r.partHost + ' canvases=' + $r.canvases)
  Chk 'reduced/console clean' (ConsoleOk $red) ''
}

# ── 6. 真断网（SW 缓存）──────────────────────────────────────────────────
Say ''
Say '── index.html  (offline reload)'
$off = Run 'offline' $sid
$on1 = Step $off 'state-online'
$on2 = Step $off 'state-offline'
if (-not $on2) { Chk 'offline/state' $false 'probe 没有返回 [state-offline]' }
else {
  Say ('     online : ' + (($on1 | ConvertTo-Json -Compress)))
  Say ('     offline: ' + (($on2 | ConvertTo-Json -Compress)))
  Chk 'offline/served from SW cache' (($on2.shader -eq 'running') -and ($on2.canvases -eq 2) -and ($on2.part -eq 'running')) ('shader=' + $on2.shader + ' canvases=' + $on2.canvases + ' part=' + $on2.part)
  Chk 'offline/console clean' (ConsoleOk $off) ''
}

# ── 7. 工作页 dashboard.html（没有着色器，只有粒子）────────────────────────
Say ''
Say '── dashboard.html  (work page)'
$work = Run 'dashboard' $sid
$w = Step $work 'state'
if (-not $w) { Chk 'dashboard/state' $false 'probe 没有返回 [state]' }
else {
  Say ('     ' + (($w | ConvertTo-Json -Compress)))
  Chk 'dashboard/no shader on work pages' ($w.shader -eq 'absent') ('shader=' + $w.shader + ' canvases=' + $w.canvases)
  Chk 'dashboard/particles soft' (($w.part -eq 'running') -and ($w.partPreset -eq 'soft') -and ($w.canvases -eq 1)) ('part=' + $w.part + '/' + $w.partPreset + ' canvases=' + $w.canvases)
  Chk 'dashboard/art typography + tokens' (($w.hFont -like 'Instrument Serif*') -and ($w.btnR -eq '999px') -and ($w.cardR -eq '16px')) ('h1=' + $w.hFont + ' btnR=' + $w.btnR + ' cardR=' + $w.cardR)
  # Round 18 redesigned this page with daisyUI cards (flat + hairline, on purpose),
  # so the Open Props layering is asserted on the token itself, not on a page shadow.
  Chk 'dashboard/open-props shadow token' ($w.shadowToken -ne '') ('--shadow-2=' + $w.shadowToken)
  Chk 'dashboard/console clean' (ConsoleOk $work) ''
}

# ---- 8. served copy must be byte-identical to src ----
Say ''
Say '-- served copy is in sync with src'
$syncPairs = @('css\nest.css', 'css\nest-fonts.css', 'js\nest-shader-bg.js', 'js\nest-particles.js', 'libs\open-props\open-props.min.css', 'index.html', 'login.html', 'dashboard.html')
$drift = @()
foreach ($rel in $syncPairs) {
  $s1 = Join-Path $root ('src\main\resources\static\' + $rel)
  $s2 = Join-Path $root ('target\classes\static\' + $rel)
  $same = (Test-Path $s1) -and (Test-Path $s2)
  if ($same) { $same = ((Get-FileHash $s1 -Algorithm SHA256).Hash -eq (Get-FileHash $s2 -Algorithm SHA256).Hash) }
  if (-not $same) { $drift += $rel }
}
Chk 'served copy in sync with src' ($drift.Count -eq 0) ($syncPairs.Count.ToString() + ' files, drift ' + $drift.Count.ToString() + ' ' + ($drift -join ' '))

# ── 9. 截图真的有色差（不是一张空图）──────────────────────────────────────
Say ''
Say '── screenshots'
$shots = @('target\zz-art17-light-index.png', 'target\zz-art17-dark-index.png', 'target\zz-art17-light-login.png', 'target\zz-art17-mobile-login.png', 'target\zz-art17-work-dashboard.png')
$missing = @($shots | Where-Object { -not (Test-Path (Join-Path $root $_)) })
Chk 'screenshots exist' ($missing.Count -eq 0) ($shots.Count.ToString() + ' 张，缺 ' + $missing.Count.ToString())
$stats = (& node 'tools\png-stats.mjs' 'target\zz-art17-light-index.png' 2>&1 | Out-String)
$sd = 0
if ($stats -match 'sdLum"?\s*[:=]\s*([0-9]+)') { $sd = [int]$Matches[1] }
Chk 'screenshot has real variance' ($sd -ge 6) ('zz-art17-light-index.png sdLum=' + $sd)

Say ''
if ($script:bad -eq 0) { Say 'ALL PASS - 整站艺术层已生效。' } else { Say ('FAILED: ' + $script:bad + ' 项') }
[System.IO.File]::WriteAllLines((Join-Path $root $Out), $log, [System.Text.UTF8Encoding]::new($false))
Say ('日志：' + $Out)
if ($script:bad -gt 0) { exit 1 }
