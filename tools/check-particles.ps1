param([int]$Port = 9222, [string]$Out = 'target\check-particles.log')
# check-particles.ps1 -- background particle layer regression check (ASCII only on purpose:
# Windows PowerShell 5.1 reads non-BOM files as ANSI, so any Chinese here would break parsing).
#
# It does not look at screenshots: it reads the live computed state out of a headless Chrome.
#   * public pages (index)  -> one <canvas>, preset dense, running, z-index -10, library loaded
#   * prefers-reduced-motion -> library is NEVER fetched (libTags 0), no host element at all
#   * work pages (settings, admin-dashboard) -> preset soft, running
#   * every page: exactly one nest-particles script tag and a clean console
#
# Requires: app on :8080, headless Chrome with --remote-debugging-port=<Port>.
# Usage: & tools\check-particles.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $root) { $root = (Get-Location).Path }
Set-Location $root

$transcript = New-Object System.Collections.ArrayList
function Say([string]$text, [string]$color = 'Gray') {
  Write-Host $text -ForegroundColor $color
  [void]$transcript.Add($text)
}

$tmp = $env:TEMP
function Get-Sid([string]$user) {
  $body = Join-Path $tmp ('chk-login-' + $user + '.json')
  [System.IO.File]::WriteAllText($body, ('{"username":"' + $user + '","password":"123456"}'), [System.Text.UTF8Encoding]::new($false))
  $hdr = Join-Path $tmp ('chk-hdr-' + $user + '.txt')
  $out = Join-Path $tmp ('chk-o-' + $user + '.json')
  & curl.exe -s -D $hdr -o $out -H 'Content-Type: application/json' --data-binary "@$body" 'http://127.0.0.1:8080/api/auth/login' | Out-Null
  $sid = ([regex]::Match([System.IO.File]::ReadAllText($hdr), 'JSESSIONID=([A-F0-9]+)')).Groups[1].Value
  if (-not $sid) { throw ('login failed for ' + $user) }
  return $sid
}

Say ('== check-particles: port ' + $Port + ', app 8080 ==') 'Cyan'
$stu = Get-Sid 'chendw'
$adm = Get-Sid 'admin'
Say ('   student SID ' + $stu.Substring(0, 8) + '...  admin SID ' + $adm.Substring(0, 8) + '...')

& node 'tools\mk-probe-particles.mjs' $stu $adm | Out-Null

# the generator writes configs without a port: pin them to the requested debug port
foreach ($f in (Get-ChildItem 'target\probe-par2-*.json')) {
  $txt = [System.IO.File]::ReadAllText($f.FullName)
  if ($txt -notmatch '"port"') {
    $txt = $txt -replace '^\s*\{', ('{' + "`r`n  ""port"": " + $Port + ',')
    [System.IO.File]::WriteAllText($f.FullName, $txt, [System.Text.UTF8Encoding]::new($false))
  }
}

$plans = @(
  @{ name = 'index-light';   preset = 'dense'; reduced = $false },
  @{ name = 'index-reduced'; preset = 'dense'; reduced = $true },
  @{ name = 'settings-soft'; preset = 'soft';  reduced = $false },
  @{ name = 'admin-soft';    preset = 'soft';  reduced = $false }
)

$fails = 0
$checks = 0
foreach ($plan in $plans) {
  $name = $plan.name
  $log = 'target\probe-par2-' + $name + '.log'
  & node 'tools\probe.mjs' ('target\probe-par2-' + $name + '.json') 2>&1 | Set-Content -Path $log -Encoding UTF8
  $lines = @(Get-Content $log)
  $stateLine = @($lines | Where-Object { $_ -match '^\[state-' })[0]
  if (-not $stateLine) {
    Say ('  ' + $name.PadRight(15) + ' FAIL  no state line in ' + $log) 'Red'
    $fails++
    continue
  }
  $inner = ($stateLine -replace '^\[[^\]]+\]\s*', '')
  $s = $inner | ConvertFrom-Json
  $s = $s | ConvertFrom-Json
  $consoleClean = (@($lines | Where-Object { $_ -match '页面控制台错误' }).Count -eq 0)

  $expectHost = -not $plan.reduced
  $expectCanvases = 0
  $expectState = 'skipped-reduced-motion'
  if (-not $plan.reduced) { $expectCanvases = 1; $expectState = 'running' }

  $problems = New-Object System.Collections.ArrayList
  $checks++
  if ([bool]$s.host -ne $expectHost) { [void]$problems.Add('host=' + $s.host + ' (expect ' + $expectHost + ')') }
  if ([int]$s.canvases -ne $expectCanvases) { [void]$problems.Add('canvases=' + $s.canvases + ' (expect ' + $expectCanvases + ')') }
  if ($s.state -ne $expectState) { [void]$problems.Add('state=' + $s.state + ' (expect ' + $expectState + ')') }
  if ($s.preset -ne $plan.preset) { [void]$problems.Add('preset=' + $s.preset + ' (expect ' + $plan.preset + ')') }
  if ([int]$s.scriptTags -ne 1) { [void]$problems.Add('scriptTags=' + $s.scriptTags) }
  if ($plan.reduced) {
    if ([int]$s.libTags -ne 0) { [void]$problems.Add('libTags=' + $s.libTags + ' (must be 0: the 158 KB library must not load)') }
  } else {
    if ([int]$s.libTags -lt 1) { [void]$problems.Add('libTags=' + $s.libTags) }
    if ($s.css.zIndex -ne '-10') { [void]$problems.Add('zIndex=' + $s.css.zIndex) }
    if ($s.css.pointerEvents -ne 'none') { [void]$problems.Add('pointerEvents=' + $s.css.pointerEvents) }
  }
  if (-not $consoleClean) { [void]$problems.Add('console errors in ' + $log) }

  if ($problems.Count -eq 0) {
    Say ('  ' + $name.PadRight(15) + ' PASS  state=' + $s.state + ' preset=' + $s.preset + ' canvas=' + $s.canvases + ' lib=' + $s.libTags + ' ' + $s.css.zIndex) 'Green'
  } else {
    Say ('  ' + $name.PadRight(15) + ' FAIL  ' + ($problems -join ' | ')) 'Red'
    $fails++
  }
}

if ($fails -eq 0) {
  Say ('ALL PASS: ' + $checks + ' page(s)') 'Green'
} else {
  Say ('FAILED: ' + $fails + ' of ' + $checks + ' page(s)') 'Red'
}
$transcript -join "`r`n" | Set-Content -Path $Out -Encoding UTF8
if ($fails -ne 0) { exit 1 }
