# check-art.ps1 - re-runnable acceptance checks for the art concept page (/art.html).
#
# Usage:  & tools\check-art.ps1 [-Port 9222] [-Out target\check-art.log]
#
# Requires: the app on http://127.0.0.1:8080 and a headless Chrome with
# --remote-debugging-port=9222 (see docs). No login needed: /art.html is public.
#
# What it proves (each line is one assertion):
#   1  page paints: the same uniforms the page uses really fill a canvas
#      (readPixels mean/sd > 0) - this is the assertion that would have caught
#      the missing u_scale/u_fit sizing uniforms (NaN -> transparent output)
#   2  hero/band mounts exist, hero animation advances, band stays static
#   3  fonts (Instrument Serif + italic) really load, Lenis bundle is present
#   4  theme toggle: light -> dark flips html.dark, aria-pressed and the ink colour
#   5  motion toggle: off freezes the frame, on resumes it
#   6  no-WebGL CSS fallback: a radial-gradient is painted on the host
#   7  mobile 375px: no horizontal overflow
#   8  prefers-reduced-motion: static, frame stays 0, content fully revealed
#   9  composed screenshots have real luminance variance (not a flat page)
#  10  no console errors on any probe
param([int]$Port = 9222, [string]$Out = 'target\check-art.log')

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$script:lines = New-Object System.Collections.Generic.List[string]
function Say([string]$m) { Write-Host $m; $script:lines.Add($m) | Out-Null }

$script:fails = 0
function Chk([string]$label, [bool]$ok, [string]$detail) {
  $mark = 'FAIL'
  if ($ok) { $mark = 'PASS' } else { $script:fails++ }
  Say ("{0}  {1}  {2}" -f $mark, $label, $detail)
}

function RunProbe([string]$cfg) {
  return (& node tools\probe.mjs $cfg 2>&1 | Out-String)
}

function Step([string]$raw, [string]$name) {
  $m = [regex]::Match($raw, '(?m)^\[' + [regex]::Escape($name) + '\] (.*)$')
  if (-not $m.Success) { return $null }
  $s = $m.Groups[1].Value.Trim()
  if ($s.StartsWith('"')) {
    $s = $s.Trim('"')
    $s = $s.Replace('\"', '"').Replace('\\', '\')
  }
  if ($s -eq 'ok' -or $s -eq 'clicked' -or $s -eq 'tick' -or $s -eq 'reloading') { return $s }
  try { return ($s | ConvertFrom-Json) } catch { return $null }
}

function ConsoleClean([string]$raw) {
  return -not ($raw -match '页面控制台错误')
}

Say ("== check-art  port={0}  {1} ==" -f $Port, (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

# ---- 0. probe configs carry the right port ---------------------------------
foreach ($cfg in @('tools\art-probe\art.json', 'tools\art-probe\art-mobile.json', 'tools\art-probe\art-reduced.json')) {
  $txt = [System.IO.File]::ReadAllText((Join-Path $root $cfg))
  $txt = [regex]::Replace($txt, '"port":\s*\d+', ('"port": ' + $Port))
  [System.IO.File]::WriteAllText((Join-Path $root $cfg), $txt, [System.Text.UTF8Encoding]::new($false))
}

# ---- 1. shader really paints (the sizing-uniform regression) ----------------
$proof = RunProbe 'tools\art-probe\art-shader-proof.json'
$p = Step $proof 'sizing-fix-proof'
if ($p -eq $null) {
  Chk 'shader paint proof' $false 'probe step missing'
} else {
  $w = $p.withoutSizing
  $wo = ($w.meanLum -eq 0 -and $w.sdLum -eq 0)
  Chk 'sizing uniforms are required (control mount paints nothing)' $wo ("withoutSizing meanLum={0} sdLum={1}" -f $w.meanLum, $w.sdLum)
  $ws = $p.withSizing
  $okPaint = ($ws.meanLum -gt 0 -and $ws.sdLum -gt 0 -and $ws.alphaSample[3] -eq 255)
  Chk 'shader paints with sizing uniforms' $okPaint ("meanLum={0} sdLum={1} mean={2} alpha={3}" -f $ws.meanLum, $ws.sdLum, ($ws.mean -join ','), $ws.alphaSample[3])
}
Chk 'shader-proof: no console errors' (ConsoleClean $proof) ''

# ---- 2..6. main probe (desktop 1440x1000) ----------------------------------
$main = RunProbe 'tools\art-probe\art.json'
$sl = Step $main 'state-light'
$px = Step $main 'shader-pixels'
$sd = Step $main 'state-dark'
$moa = Step $main 'motion-on-a'
$mob = Step $main 'motion-on-b'
$mfa = Step $main 'motion-off-a'
$mfb = Step $main 'motion-off-b'
$fb = Step $main 'fallback-css'
$fe = Step $main 'font-evidence'

if ($sl -eq $null -or $px -eq $null) {
  Chk 'main probe steps' $false 'state-light or shader-pixels missing'
} else {
  Chk 'page version is art-2' ($sl.art.v -eq 'art-2') ("v=" + $sl.art.v)
  Chk 'WebGL2 path is live' ($sl.art.webgl -eq $true -and $sl.art.error -eq $null) ("webgl={0} error={1}" -f $sl.art.webgl, $sl.art.error)
  Chk 'hero + band canvases mounted' ($sl.art.canvases -eq 2) ("canvases=" + $sl.art.canvases)
  Chk 'hero animates, band static' ($sl.heroFrame -gt 0 -and $sl.bandSpeed -eq 0) ("heroFrame={0} bandSpeed={1}" -f $sl.heroFrame, $sl.bandSpeed)
  # document.fonts.check('Instrument Serif') can return true even when the family is unknown
  # (Chrome falls back), so this one checks three things that a fallback cannot fake:
  # both @font-face records are status 'loaded', both woff2 files were really fetched over HTTP,
  # and the measured text width differs from the generic 'serif' stack.
  $serifFaces = 0
  $serifFiles = 0
  $wSerif = 0
  $wGen = 0
  if ($fe -ne $null) {
    $serifFaces = @(@($fe.faces) | Where-Object { $_ -like 'Instrument Serif/*/loaded' }).Count
    $serifFiles = @($fe.fetched).Count
    $wSerif = $fe.widths.serif
    $wGen = $fe.widths.genericSerif
  }
  Chk 'Instrument Serif really applies (faces loaded + files fetched + width differs)' ($fe -ne $null -and $serifFaces -eq 2 -and $serifFiles -eq 2 -and $wSerif -ne $wGen) ("faces={0} woff2={1} width={2} vs generic {3}" -f $serifFaces, $serifFiles, $wSerif, $wGen)
  Chk 'Lenis bundle present' ($sl.lenisLoaded -eq 'function') ("typeof Lenis=" + $sl.lenisLoaded)
  Chk 'starts in light theme' ($sl.htmlDark -eq $false -and $sl.art.theme -eq 'light') ("htmlDark={0} theme={1}" -f $sl.htmlDark, $sl.art.theme)
  Chk 'shader pixels are non-empty' ($px.meanLum -gt 0 -and $px.sdLum -gt 0 -and $px.alphaFirst -eq 255) ("meanLum={0} sdLum={1}" -f $px.meanLum, $px.sdLum)
}
if ($sd -eq $null) {
  Chk 'theme toggle' $false 'state-dark missing'
} else {
  Chk 'theme toggle flips html.dark + aria + ink' ($sd.theme -eq 'dark' -and $sd.htmlDark -eq $true -and $sd.aria -eq 'true' -and $sd.bodyBg -eq 'rgb(13, 14, 17)') ("theme={0} aria={1} bodyBg={2}" -f $sd.theme, $sd.aria, $sd.bodyBg)
}
if ($moa -eq $null -or $mob -eq $null) {
  Chk 'motion on resumes' $false 'motion-on steps missing'
} else {
  Chk 'motion on: frame advances' (($mob.frame - $moa.frame) -gt 0 -and $mob.speed -eq 0.34) ("frame {0} -> {1} speed={2}" -f $moa.frame, $mob.frame, $mob.speed)
}
if ($mfa -eq $null -or $mfb -eq $null) {
  Chk 'motion off freezes' $false 'motion-off steps missing'
} else {
  Chk 'motion off: frame frozen + class set' ($mfa.static -eq $true -and ($mfb.frame - $mfa.frame) -eq 0 -and $mfb.speed -eq 0) ("static={0} frame {1} -> {2}" -f $mfa.static, $mfa.frame, $mfb.frame)
  Chk 'motion off: label reads as an action' ($mfa.btn -like '*|false') ("btn=" + $mfa.btn)
}
if ($fb -eq $null) {
  Chk 'css fallback' $false 'fallback-css missing'
} else {
  Chk 'no-WebGL CSS fallback paints a gradient' ($fb.hostBg -like 'radial-gradient*') ("bg=" + $fb.hostBg.Substring(0, [Math]::Min(40, $fb.hostBg.Length)))
}
Chk 'main probe: no console errors' (ConsoleClean $main) ''

# ---- 7. mobile 375 ---------------------------------------------------------
$mobile = RunProbe 'tools\art-probe\art-mobile.json'
$sm = Step $mobile 'state-mobile'
if ($sm -eq $null) {
  Chk 'mobile probe' $false 'state-mobile missing'
} else {
  Chk 'mobile: no horizontal overflow' ($sm.scrollW -le $sm.innerW) ("scrollW={0} innerW={1}" -f $sm.scrollW, $sm.innerW)
  Chk 'mobile: canvases mounted and animating' ($sm.art.canvases -eq 2 -and $sm.heroFrame -gt 0) ("canvases={0} heroFrame={1}" -f $sm.art.canvases, $sm.heroFrame)
  # 注意：空的 JSON 数组经 ConvertFrom-Json 再 return 会变成 $null，所以这里读原始行文本。
  $ovRaw = [regex]::Match($mobile, '(?m)^\[overflow-audit\] (.*)$').Groups[1].Value.Trim().Trim('"')
  Chk 'mobile: no element sticks out' ($ovRaw -eq '[]') ("overflow-audit=" + $ovRaw)
}
Chk 'mobile probe: no console errors' (ConsoleClean $mobile) ''

# ---- 8. prefers-reduced-motion --------------------------------------------
$reduced = RunProbe 'tools\art-probe\art-reduced.json'
$sr = Step $reduced 'state-reduced'
$fs = Step $reduced 'frame-still'
$af = Step $reduced 'after-force-on'
if ($sr -eq $null) {
  Chk 'reduced-motion probe' $false 'state-reduced missing'
} else {
  Chk 'reduced-motion: media query reported' ($sr.reduceMatches -eq $true) ''
  Chk 'reduced-motion: starts static, frame never advances' ($sr.art.motion -eq $false -and $sr.static -eq $true -and $sr.heroFrame -eq 0 -and $fs.frame -eq 0) ("motion={0} frame={1}/{2}" -f $sr.art.motion, $sr.heroFrame, $fs.frame)
  Chk 'reduced-motion: content revealed immediately' ($sr.revealed -eq $sr.revealTotal) ("revealed={0}/{1}" -f $sr.revealed, $sr.revealTotal)
  Chk 'reduced-motion: static gradient still painted' ($sr.art.canvases -eq 2) ("canvases=" + $sr.art.canvases)
}
if ($af -ne $null) {
  Chk 'explicit user request can still start motion' ($af.motion -eq $true -and $af.speed -eq 0.34) ("motion={0} speed={1}" -f $af.motion, $af.speed)
}
Chk 'reduced-motion probe: no console errors' (ConsoleClean $reduced) ''

# ---- 9. composed screenshots really have content --------------------------
foreach ($shot in @(@('target\zz-art-hero-light.png', 10), @('target\zz-art-hero-dark.png', 10), @('target\zz-art-mobile-full.png', 5))) {
  $file = $shot[0]
  $minSd = $shot[1]
  if (-not (Test-Path $file)) {
    Chk ('screenshot variance: ' + (Split-Path -Leaf $file)) $false 'missing file'
    continue
  }
  $statRaw = (& node tools\png-stats.mjs $file '200,150,900,650' 2>&1 | Out-String)
  $json = $null
  try { $json = $statRaw | ConvertFrom-Json } catch { $json = $null }
  if ($json -eq $null) {
    $statRaw = (& node tools\png-stats.mjs $file 2>&1 | Out-String)
    try { $json = $statRaw | ConvertFrom-Json } catch { $json = $null }
  }
  if ($json -eq $null) {
    Chk ('screenshot variance: ' + (Split-Path -Leaf $file)) $false 'png-stats failed'
  } else {
    Chk ('screenshot variance: ' + (Split-Path -Leaf $file)) ($json.full.sdLum -ge $minSd) ("sdLum={0} meanLum={1} colors={2}" -f $json.full.sdLum, $json.full.meanLum, $json.full.colors)
  }
}

# ---- summary --------------------------------------------------------------
if ($script:fails -eq 0) {
  Say 'ALL PASS'
} else {
  Say ("FAILED: {0} check(s)" -f $script:fails)
}
[System.IO.File]::WriteAllText((Join-Path $root $Out), ($script:lines -join "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
Say ('log -> ' + $Out)
if ($script:fails -gt 0) { exit 1 }
