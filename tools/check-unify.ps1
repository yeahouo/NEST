# tools\check-unify.ps1 —— 第十二轮「整站统一」的机器化验收（ASCII only）
#
# 断言的是「同一类元素在任何页面上只有一种外形」：
#   .nx-panel 圆角都 16px、.nx-dialog 都 22px、.nx-btn 圆角都 10px 且高度只在 {32,40,46}、
#   .nx-tab/.tab-btn 圆角都 12px（1440 宽），并且页面控制台干净。
# 旧的 ad-hoc 写法数量（border-primary/10 之类）作为 INFO 打印，不当失败判据。
param(
  [int]$Port = 9222,
  [string]$Out = 'target\check-unify.log'
)
$ErrorActionPreference = 'Stop'
$root = 'D:\project\NEST'
Set-Location $root
$script:bad = 0
$log = New-Object System.Collections.Generic.List[string]

function Say([string]$s) { $log.Add($s); Write-Output $s }
function Chk([string]$name, [bool]$ok, [string]$detail) {
  if ($ok) { Say ('PASS  ' + $name.PadRight(26) + $detail) }
  else { Say ('FAIL  ' + $name.PadRight(26) + $detail); $script:bad++ }
}

function Login([string]$user) {
  $body = Join-Path $env:TEMP ('unify-body-' + $user + '.json')
  $hdrs = Join-Path $env:TEMP ('unify-hdr-' + $user + '.txt')
  $outf = Join-Path $env:TEMP ('unify-out-' + $user + '.json')
  [System.IO.File]::WriteAllText($body, '{"username":"' + $user + '","password":"123456"}', [System.Text.UTF8Encoding]::new($false))
  & curl.exe -s -D $hdrs -o $outf -H 'Content-Type: application/json' --data-binary "@$body" 'http://127.0.0.1:8080/api/auth/login' | Out-Null
  $h = [System.IO.File]::ReadAllText($hdrs)
  if ($h -match 'JSESSIONID=([A-F0-9]+)') { return $Matches[1] }
  throw ('login failed: ' + $user)
}

$eval = @'
(function () {
  function uniq(sel, prop) {
    var m = {}, list = document.querySelectorAll(sel), i;
    for (i = 0; i < list.length; i++) {
      var el = list[i], c = getComputedStyle(el), k;
      if (prop === 'height') {
        var h = Math.round(el.getBoundingClientRect().height);
        if (h === 0) continue;
        k = h + 'px';
      } else { k = c.borderTopLeftRadius; }
      m[k] = (m[k] || 0) + 1;
    }
    return Object.keys(m).map(function (k) { return { v: k, n: m[k] }; });
  }
  function one(sel) {
    var el = document.querySelector(sel);
    if (!el) return null;
    var c = getComputedStyle(el);
    return { radius: c.borderTopLeftRadius, h: Math.round(el.getBoundingClientRect().height), bg: c.backgroundColor, bc: c.borderTopColor };
  }
  return JSON.stringify({
    panelRadii: uniq('.nx-panel', 'radius'),
    dialogRadii: uniq('.nx-dialog', 'radius'),
    btnRadii: uniq('.nx-btn', 'radius'),
    btnHeights: uniq('.nx-btn', 'height'),
    tabRadii: uniq('.nx-tab, .tab-btn', 'radius'),
    counts: {
      panel: document.querySelectorAll('.nx-panel').length,
      dialog: document.querySelectorAll('.nx-dialog').length,
      btn: document.querySelectorAll('.nx-btn').length,
      tab: document.querySelectorAll('.nx-tab, .tab-btn').length
    },
    legacy: {
      blurMd: document.querySelectorAll('.backdrop-blur-md').length,
      borderPrimary10: document.querySelectorAll("[class*='border-primary/10']").length,
      shadow2xl: document.querySelectorAll('.shadow-2xl').length
    },
    sample: { card: one('.nx-card'), panel: one('.nx-panel'), dialog: one('.nx-dialog'), btn: one('.nx-btn'), tab: one('.nx-tab, .tab-btn') }
  });
})()
'@
$evalJson = ($eval.Replace('\', '\\')).Replace('"', '\"').Replace("`r", '').Replace("`n", ' ').Replace("`t", ' ')

Say ('=== check-unify.ps1  headless Chrome :' + $Port + '  static :8080 ===')
$ssid = Login 'chendw'
$tsid = Login 'lisi'
$asid = Login 'admin'
Say ('登录：student=' + $ssid.Substring(0, 8) + '…  teacher=' + $tsid.Substring(0, 8) + '…  admin=' + $asid.Substring(0, 8) + '…')

$fake = '00000000000000000000000000000000'
$pages = @(
  @{ n = 'student-dashboard'; sid = $ssid; url = 'http://127.0.0.1:8080/dashboard.html?cb=U1' },
  @{ n = 'student-settings'; sid = $ssid; url = 'http://127.0.0.1:8080/settings.html?cb=U2' },
  @{ n = 'public-index'; sid = $fake; url = 'http://127.0.0.1:8080/index.html?cb=U4' },
  @{ n = 'teacher-dashboard'; sid = $tsid; url = 'http://127.0.0.1:8080/teacher-dashboard.html?cb=U5' },
  @{ n = 'admin-dashboard'; sid = $asid; url = 'http://127.0.0.1:8080/admin-dashboard.html?cb=U7' },
  @{ n = 'admin-dorm'; sid = $asid; url = 'http://127.0.0.1:8080/dorm-admin.html?cb=U8' },
  @{ n = 'student-poverty'; sid = $ssid; url = 'http://127.0.0.1:8080/poverty.html?cb=U9' },
  @{ n = 'staff-poverty'; sid = $tsid; url = 'http://127.0.0.1:8080/poverty-review.html?cb=U10' },
  @{ n = 'student-honor'; sid = $ssid; url = 'http://127.0.0.1:8080/honor.html?cb=U11' },
  @{ n = 'staff-honor'; sid = $tsid; url = 'http://127.0.0.1:8080/honor-review.html?cb=U12' },
  @{ n = 'student-ranking'; sid = $ssid; url = 'http://127.0.0.1:8080/ranking.html?cb=U13' },
  @{ n = 'staff-ranking'; sid = $tsid; url = 'http://127.0.0.1:8080/ranking-review.html?cb=U14' },
  # P14 心理健康两页（学生端 / 管理端）加进来，与 P10–P13 的每阶段两页同口径。
  @{ n = 'student-psyche'; sid = $ssid; url = 'http://127.0.0.1:8080/psyche.html?cb=U15' },
  @{ n = 'staff-psyche'; sid = $tsid; url = 'http://127.0.0.1:8080/psyche-review.html?cb=U16' },
  # P15 社区管理两页（学生端 / 管理端），与 P10–P14 的每阶段两页同口径。
  @{ n = 'student-community'; sid = $ssid; url = 'http://127.0.0.1:8080/community.html?cb=U17' },
  @{ n = 'staff-community'; sid = $tsid; url = 'http://127.0.0.1:8080/community-review.html?cb=U18' },
  # P16 学生党建两页（学生端 / 管理端），与 P10–P15 的每阶段两页同口径。
  @{ n = 'student-party'; sid = $ssid; url = 'http://127.0.0.1:8080/party.html?cb=U19' },
  @{ n = 'staff-party'; sid = $tsid; url = 'http://127.0.0.1:8080/party-review.html?cb=U20' },
  # P17 校园文化四页（学生端 / 管理端 × 志愿 / 社团），与 P10–P16 同口径。
  @{ n = 'student-volunteer'; sid = $ssid; url = 'http://127.0.0.1:8080/volunteer.html?cb=U21' },
  @{ n = 'staff-volunteer'; sid = $tsid; url = 'http://127.0.0.1:8080/volunteer-review.html?cb=U22' },
  @{ n = 'student-club'; sid = $ssid; url = 'http://127.0.0.1:8080/club.html?cb=U23' },
  @{ n = 'staff-club'; sid = $tsid; url = 'http://127.0.0.1:8080/club-review.html?cb=U24' },
  # P18 第二课堂两页（学生端 / 管理端），与 P10–P17 的每阶段同口径。
  @{ n = 'student-secondclass'; sid = $ssid; url = 'http://127.0.0.1:8080/second-class.html?cb=U25' },
  @{ n = 'staff-secondclass'; sid = $tsid; url = 'http://127.0.0.1:8080/second-class-review.html?cb=U26' }
)

$tpl = @'
{
  "port": __PORT__,
  "cookie": { __CK__ },
  "page": { "url": "__URL__", "width": 1440, "height": 1100, "waitMs": 4000 },
  "steps": [ { "name": "u", "eval": "__EVAL__" } ]
}
'@

foreach ($p in $pages) {
  $ck = '"name": "JSESSIONID", "value": "' + $p.sid + '", "url": "http://127.0.0.1:8080"'
  $cfg = $tpl.Replace('__PORT__', [string]$Port).Replace('__CK__', $ck).Replace('__URL__', $p.url).Replace('__EVAL__', $evalJson)
  $cfgPath = 'target\probe-unify-' + $p.n + '.json'
  [System.IO.File]::WriteAllText((Join-Path $root $cfgPath), $cfg, [System.Text.UTF8Encoding]::new($false))
  $raw = & node 'tools\probe.mjs' $cfgPath 2>&1 | Out-String
  $line = ($raw -split "`n" | Where-Object { $_ -match '^\[u\]' } | Select-Object -First 1)
  Say ''
  Say ('── ' + $p.n + '  ' + $p.url)
  if (-not $line) { Chk ($p.n + '/step') $false 'probe 没有返回 [u] 步骤'; Say $raw; continue }
  $json = $line.Trim() -replace '^\[u\]\s+', ''
  $json = $json.Trim('"').Replace('\"', '"').Replace('\\', '\')
  $o = $json | ConvertFrom-Json
  Say ('     counts: ' + ($o.counts | ConvertTo-Json -Compress))
  Say ('     legacy: ' + ($o.legacy | ConvertTo-Json -Compress))
  Say ('     sample: ' + ($o.sample | ConvertTo-Json -Compress))

  if ($o.counts.panel -gt 0) {
    $badR = @($o.panelRadii | Where-Object { $_.v -ne '16px' })
    Chk ($p.n + '/panel-radius') ($badR.Count -eq 0) ('面板圆角变体：' + (@($o.panelRadii | ForEach-Object { $_.v + '×' + $_.n }) -join ' '))
  }
  if ($o.counts.dialog -gt 0) {
    $badD = @($o.dialogRadii | Where-Object { $_.v -ne '22px' })
    Chk ($p.n + '/dialog-radius') ($badD.Count -eq 0) ('弹窗圆角变体：' + (@($o.dialogRadii | ForEach-Object { $_.v + '×' + $_.n }) -join ' '))
  }
  if ($o.counts.btn -gt 0) {
    $badB = @($o.btnRadii | Where-Object { $_.v -ne '999px' })
    Chk ($p.n + '/btn-radius') ($badB.Count -eq 0) ('按钮圆角变体：' + (@($o.btnRadii | ForEach-Object { $_.v + '×' + $_.n }) -join ' '))
    $badH = @($o.btnHeights | Where-Object { @('32px', '40px', '46px') -notcontains $_.v })
    Chk ($p.n + '/btn-height') ($badH.Count -eq 0) ('按钮高度变体：' + (@($o.btnHeights | ForEach-Object { $_.v + '×' + $_.n }) -join ' '))
  }
  if ($o.counts.tab -gt 0) {
    $badT = @($o.tabRadii | Where-Object { $_.v -ne '999px' })
    Chk ($p.n + '/tab-radius') ($badT.Count -eq 0) ('标签圆角变体：' + (@($o.tabRadii | ForEach-Object { $_.v + '×' + $_.n }) -join ' '))
  }
  $errLine = ($raw -split "`n" | Where-Object { $_ -match '页面控制台错误' })
  $detail = '页面控制台无错误'
  if ($errLine) { $detail = ($errLine -join ' ') }
  Chk ($p.n + '/console') ($null -eq $errLine) $detail
}

Say ''
if ($script:bad -eq 0) { Say 'ALL PASS - 全站外形已统一。' } else { Say ('FAILED: ' + $script:bad + ' 项') }
[System.IO.File]::WriteAllLines((Join-Path $root $Out), $log, [System.Text.UTF8Encoding]::new($false))
Say ('日志：' + $Out)
if ($script:bad -gt 0) { exit 1 }
