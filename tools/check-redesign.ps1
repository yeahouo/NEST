param(
  [int]$Port = 9222,
  [string]$Base = 'http://127.0.0.1:8080'
)

# 第十九轮「逐页重做」的整站验收：tools/redesign-shell.mjs 机械换壳之后，逐页在真浏览器里看
# 「外壳换上了没有 + 有没有把东西弄坏」。判据（任一不过即 FAIL）：
#   · 页面没被重定向走（登录失效会跳到 /login.html，这类失败必须是人看得懂的）
#   · 顶栏真的带上了 navbar 类（只对**机械换壳改过**的 17 页断言；手写样板页与工具页不断言）
#   · 刊头小标 .nx-kicker 的文字与预期一致（按 role/page 写死，防止插错位置）
#   · 没有横向溢出（scrollWidth ≤ innerWidth + 1）
#   · 共享交互层还活着（typeof NestUI === 'object'）
#   · 页面控制台无错误
#
# 为什么三个角色都要跑：宿舍管理端只对 admin 开放（nest.dorm.staff-roles 默认 admin），
# 教师页对学生是 403；用错角色会得到一片假的「控制台错误」。
#
# 用法：& tools\check-redesign.ps1            （需要 8080 与 9222 都活着）
#       & tools\check-redesign.ps1 -Port 9222
$ErrorActionPreference = 'Stop'
$enc = [System.Text.UTF8Encoding]::new($false)
$cfgPath = Join-Path $env:TEMP 'nest-redesign.json'

function New-Sid([string]$user, [string]$pass) {
  $body = '{"username":"' + $user + '","password":"' + $pass + '"}'
  $bt = Join-Path $env:TEMP "nest-rd-body-$user.json"
  $hd = Join-Path $env:TEMP "nest-rd-hdr-$user.txt"
  $oj = Join-Path $env:TEMP "nest-rd-out-$user.json"
  [System.IO.File]::WriteAllText($bt, $body, $enc)
  & curl.exe -s -D $hd -o $oj -H 'Content-Type: application/json' --data-binary "@$bt" "$Base/api/auth/login" | Out-Null
  $h = [System.IO.File]::ReadAllText($hd)
  $m = [regex]::Match($h, 'JSESSIONID=([A-Fa-f0-9]+)')
  if (-not $m.Success) { throw "登录失败：$user（$Base/api/auth/login）" }
  return $m.Groups[1].Value
}

# hdr=$true 的页面就是 redesign-shell.mjs 改过的 17 页（断言 navbar）；其余页只查「没弄坏」
$Pages = @(
  @{ p = '/index.html';                  r = 'anon';    hdr = $true;  k = 'Overview_/_Landing' },
  @{ p = '/register.html';               r = 'anon';    hdr = $true;  k = 'Account_/_Register' },
  @{ p = '/forgot-password.html';        r = 'anon';    hdr = $true;  k = 'Account_/_Recovery' },
  # allow：这一页没有 token 时会自己跳到 forgot-password，跳转会把上一场站内
  # View Transition（input.css:1252 的 @view-transition）掐断，Chrome 因此打一条
  # 「Transition was aborted because of invalid state」——竞态噪音，不是页面缺陷。
  @{ p = '/reset-password.html';         r = 'anon';    hdr = $true;  k = 'Account_/_Reset'; may = '/forgot-password.html'; allow = 'Transition was aborted' },
  @{ p = '/under-development.html';      r = 'anon';    hdr = $true;  k = '-' },
  @{ p = '/login.html';                  r = 'anon';    hdr = $false; k = '-' },
  @{ p = '/art.html';                    r = 'anon';    hdr = $false; k = '-'; ui = 'undefined' },
  @{ p = '/dashboard.html';              r = 'student'; hdr = $false; k = '-' },
  @{ p = '/settings.html';               r = 'student'; hdr = $true;  k = '_Account_/_Settings' },
  @{ p = '/dorm-portal.html';            r = 'student'; hdr = $true;  k = 'Residence_/_Student_portal' },
  # 学工平台 P4「请假 · 离校」两页是手写的，但顶栏照 admin-dashboard 的体例写（header 上带 navbar
  # 且 min-h-0），所以这里按 hdr=$true 断言。小标写的是「§ 04 · Student / Leave」，但读数侧的正则把
  # [^A-Za-z中文/-]+ 连成**一个**下划线，digit 也算非字母 ⇒ 开头「§ 04 · 」只贡献一个 '_'，
  # 期望值因此是 '_Student_/_Leave'，不是 '_04_/_Student_/_Leave'（别按字面段数推）。
  @{ p = '/leave.html';                  r = 'student'; hdr = $true;  k = '_Student_/_Leave' },
  @{ p = '/leave-review.html';           r = 'teacher'; hdr = $true;  k = '_Staff_/_Review' },
  @{ p = '/profile.html';             r = 'student'; hdr = $true;  k = '_Student_/_Profile' },
  @{ p = '/counselor-students.html';  r = 'teacher'; hdr = $true;  k = '_Staff_/_Students' },
  @{ p = '/award-punishment.html'; r = 'student'; hdr = $true; k = '_Student_/_Awards' },
  @{ p = '/award-manage.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Awards' },
  @{ p = '/aid.html'; r = 'student'; hdr = $true; k = '_Student_/_Aid' },
  @{ p = '/aid-review.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Aid' },
  @{ p = '/evaluation.html'; r = 'student'; hdr = $true; k = '_Student_/_Evaluation' },
  @{ p = '/eval-review.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Evaluation' },
  @{ p = '/counselor-dashboard.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Overview' },
  @{ p = '/teacher-dashboard.html';      r = 'teacher'; hdr = $true;  k = '_Staff_/_Dashboard' },
  @{ p = '/admin-dashboard.html';        r = 'admin';   hdr = $true;  k = '_Admin_/_Overview' },
  @{ p = '/admin-users.html';            r = 'admin';   hdr = $true;  k = 'Admin_/_Users' },
  @{ p = '/admin-invitation-codes.html'; r = 'admin';   hdr = $true;  k = 'Admin_/_Invitation_codes' },
  @{ p = '/dorm-admin.html';             r = 'admin';   hdr = $true;  k = 'Residence_/_Admin_console' },
  @{ p = '/dorm-psyche.html';            r = 'admin';   hdr = $true;  k = 'Residence_/_Psychometrics' },
  @{ p = '/dorm.html';                   r = 'admin';   hdr = $false; k = '-' }
  @{ p = '/workstudy.html';        r = 'student'; hdr = $true;  k = '_Student_/_Work-study' },
  @{ p = '/workstudy-manage.html'; r = 'teacher'; hdr = $true;  k = '_Staff_/_Work-study' },
  # P11 困难认定两页（学生端 / 管理端）。两页顶栏都是 <header class="nx-topbar navbar …">，故 hdr=$true；
  # 小标折叠同理：'§ 12 · ' 里 § 与 digit 都算非字母，整段只贡献一个 '_'。
  @{ p = '/poverty.html';          r = 'student'; hdr = $true;  k = '_Student_/_Poverty' },
  @{ p = '/poverty-review.html';   r = 'teacher'; hdr = $true;  k = '_Staff_/_Poverty' },
  # P12 评优申报两页（学生端 / 管理端）；小标 '§ 14 · ' 与 '§ 15 · ' 折叠后同样只贡献一个 '_'。
  @{ p = '/honor.html';        r = 'student'; hdr = $true; k = '_Student_/_Honors' },
  @{ p = '/honor-review.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Honors' },
  # P13 综测排名两页（学生端 / 管理端）；小标 § 16 · 与 § 17 · 折叠后同样只贡献一个 _。
  @{ p = '/ranking.html';        r = 'student'; hdr = $true; k = '_Student_/_Ranking' },
  @{ p = '/ranking-review.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Ranking' },
  # P14 心理健康两页（学生端 / 管理端）；小标 § 18 · 与 § 19 · 折叠后同样只贡献一个 _。
  @{ p = '/psyche.html';        r = 'student'; hdr = $true; k = '_Student_/_Psyche' },
  @{ p = '/psyche-review.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Psyche' },
  # P15 社区管理两页（学生端 / 管理端）；小标 § 20 · 与 § 21 · 折叠后同样只贡献一个 _。
  @{ p = '/community.html';        r = 'student'; hdr = $true; k = '_Student_/_Community' },
  @{ p = '/community-review.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Community' },
  # P16 学生党建两页（学生端 / 管理端）；小标 § 22 · 与 § 23 · 折叠后同样只贡献一个 _。
  @{ p = '/party.html';        r = 'student'; hdr = $true; k = '_Student_/_Party' },
  @{ p = '/party-review.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Party' },
  # P17 校园文化四页（学生端 / 管理端 × 志愿 / 社团）；小标 § 24 ·/§ 25 · 折叠后同样只贡献一个 _。
  @{ p = '/volunteer.html';        r = 'student'; hdr = $true; k = '_Student_/_Volunteer' },
  @{ p = '/volunteer-review.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Volunteer' },
  @{ p = '/club.html';             r = 'student'; hdr = $true; k = '_Student_/_Club' },
  @{ p = '/club-review.html';      r = 'teacher'; hdr = $true; k = '_Staff_/_Club' },
  # P18 第二课堂两页（学生端 / 管理端）；小标 § 28 ·/§ 29 · 折叠后同样只贡献一个 _。
  @{ p = '/second-class.html';        r = 'student'; hdr = $true; k = '_Student_/_Second_Class' },
  @{ p = '/second-class-review.html'; r = 'teacher'; hdr = $true; k = '_Staff_/_Second_Class' }
)

$SHELL_JS = "(function(){var h=document.querySelector('header');var k=document.querySelector('.nx-kicker');" +
  "var b=document.querySelector('.nx-brand');var t=document.querySelector('h1,h2');" +
  "var f=function(s){return (s||'').replace(/[^A-Za-z\u4e00-\u9fa5\/-]+/g,'_').slice(0,26)};" +
  "return 'path='+location.pathname+' nav='+(h&&h.classList.contains('navbar')?1:0)" +
  "+' kicker='+(k?f(k.textContent.trim()):'-')+' brand='+(b?f(b.textContent.trim()):'-')" +
  "+' title='+(t?f(t.textContent.trim()):'-')+' over='+(document.documentElement.scrollWidth>window.innerWidth+1?1:0)" +
  "+' sw='+document.documentElement.scrollWidth+' iw='+window.innerWidth+' ui='+(typeof NestUI)" +
  "+' hdrH='+(h?Math.round(h.getBoundingClientRect().height):0)+' canv='+document.querySelectorAll('canvas').length})()"

Write-Output "== check-redesign: port $Port, app $Base =="
$sids = @{}
foreach ($role in @('admin', 'teacher', 'student')) {
  $user = switch ($role) { 'admin' { 'admin' } 'teacher' { 'lisi' } 'student' { 'chendw' } }
  $sids[$role] = New-Sid $user '123456'
  Write-Output ("   {0,-7} {1}" -f $role, $sids[$role].Substring(0, 8))
}
$sids['anon'] = $null

$pass = 0; $fail = 0
# cb 用时间戳：固定 cb 会让同一轮渲染过的 HTML/JS 有机会被浏览器缓存端出来，
# 那时读数看着像「改完还是旧的」，白查一轮。probe.mjs 的 clearBrowserCache 只清
# HTTP 缓存，不能只靠它。
$cb = [DateTimeOffset]::Now.ToUnixTimeSeconds()
foreach ($row in $Pages) {
  $cb++
  $sep = '?'
  if ($row.p.Contains('?')) { $sep = '&' }
  $cfg = @{
    port  = $Port
    page  = @{ url = "$Base$($row.p)$sep" + "cb=$cb"; width = 1440; height = 1000; waitMs = 3200 }
    steps = @( @{ name = 'shell'; eval = $SHELL_JS } )
  }
  if ($sids[$row.r]) { $cfg.cookie = @{ name = 'JSESSIONID'; value = $sids[$row.r]; url = $Base } }
  [System.IO.File]::WriteAllText($cfgPath, ($cfg | ConvertTo-Json -Depth 8 -Compress), $enc)
  $out = (node 'tools\probe.mjs' $cfgPath 2>&1) -join "`n"

  $problems = @()
  if ($out -match '页面控制台错误') {
    $bad = @(($out -split "`n") | Where-Object { $_ -match '^  ! ' })
    if ($row.allow) { $bad = @($bad | Where-Object { $_ -notmatch $row.allow }) }
    if ($bad.Count) { $problems += "控制台错误：$($bad[0] -replace '^\s*!\s*', '')" }
  }
  $line = (($out -split "`n") | Where-Object { $_ -match '^\[shell\] ' } | Select-Object -First 1)
  if (-not $line) {
    $problems += '没拿到 [shell] 读数（页面没加载出来？）'
  } else {
    $kv = @{}
    foreach ($tok in (($line -replace '^\[shell\]\s*', '').Trim('"').Trim() -split '\s+')) {
      $i = $tok.IndexOf('=')
      if ($i -gt 0) { $kv[$tok.Substring(0, $i)] = $tok.Substring($i + 1) }
    }
    $want = ($row.p -split '\?')[0]
    $note = ''
    $redirected = $false
    if ($kv['path'] -ne $want) {
      # 少数页会自己重定向（例如 reset-password 没有 token 时会回 forgot-password）：这类写在 may 里，
      # 此时不再断言那页的顶栏与小标（人已经在另一页上了）
      if ($row.may -and $kv['path'] -eq $row.may) { $redirected = $true; $note = "（按预期重定向到 $($row.may)）" }
      else { $problems += "被重定向：$want → $($kv['path'])" }
    }
    if (-not $redirected) {
      if ($row.hdr -and $kv['nav'] -ne '1') { $problems += '顶栏没有 navbar 类' }
      if ($row.k -ne '-' -and $kv['kicker'] -ne $row.k) { $problems += "小标不对：$($kv['kicker']) ≠ $($row.k)" }
      if ($kv['over'] -ne '0') { $problems += "横向溢出：scrollW $($kv['sw']) > innerW $($kv['iw'])" }
      $wantUi = 'object'
      if ($row.ui) { $wantUi = $row.ui }
      if ($kv['ui'] -ne $wantUi) { $problems += "共享交互层：typeof NestUI = $($kv['ui'])（期望 $wantUi）" }
    }
  }

  if ($problems.Count) {
    $fail++
    Write-Output ("FAIL {0,-30} {1}" -f $row.p, ($problems -join ' | '))
  } else {
    $pass++
    Write-Output ("PASS {0,-30} nav={1} hdrH={2} kicker={3} title={4} canv={5}{6}" -f `
      $row.p, $kv['nav'], $kv['hdrH'], $kv['kicker'], $kv['title'], $kv['canv'], $note)
  }
}

Write-Output ""
if ($fail) { Write-Output "FAILED: $fail / $($pass + $fail)" } else { Write-Output "ALL PASS: $($pass) page(s)" }
exit $(if ($fail) { 1 } else { 0 })
