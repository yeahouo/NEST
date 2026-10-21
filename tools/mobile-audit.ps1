param(
  [Parameter(Mandatory = $true)][string]$Sid,
  [Parameter(Mandatory = $true)][string[]]$Pages,
  [int[]]$Widths = @(375, 405),
  [int]$Port = 9222,
  [string]$Out = 'target\mobile-audit.log'
)

# 移动端审计：每个页面在若干窄视口下各跑一次 tools\mobile-audit.page.js。
# 用法：& tools\mobile-audit.ps1 -Sid <JSESSIONID> -Pages @('/dashboard.html')
$ErrorActionPreference = 'Continue'
$enc = [System.Text.UTF8Encoding]::new($false)
$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$js = [System.IO.File]::ReadAllText((Join-Path $repo 'tools\mobile-audit.page.js'), [System.Text.Encoding]::UTF8)
$cfgPath = Join-Path $env:TEMP 'nest-mobile.json'
$cb = [DateTimeOffset]::Now.ToUnixTimeSeconds()
$summary = @()

foreach ($p in $Pages) {
  foreach ($w in $Widths) {
    $cb++
    $sep = '?'
    if ($p -match '\?') { $sep = '&' }
    $cfg = @{
      port   = $Port
      cookie = @{ name = 'JSESSIONID'; value = $Sid; url = 'http://127.0.0.1:8080' }
      page   = @{ url = "http://127.0.0.1:8080$p$sep" + "cb=$cb"; width = $w; height = 812; waitMs = 2800 }
      steps  = @(
        @{ name = 'mobile'; eval = $js; waitMs = 200 }
      )
    }
    [System.IO.File]::WriteAllText($cfgPath, ($cfg | ConvertTo-Json -Depth 6 -Compress), $enc)
    $lines = & node (Join-Path $repo 'tools\probe.mjs') $cfgPath 2>&1
    $lines | Out-File -FilePath (Join-Path $repo $Out) -Append -Encoding utf8
    $line = $lines | Where-Object { $_ -match '^\[mobile\] ' } | Select-Object -First 1
    $consoleLine = $lines | Where-Object { $_ -match '^页面控制台' } | Select-Object -First 1
    if ($line) {
      $r = ($line -replace '^\[mobile\] ', '') | ConvertFrom-Json
      Write-Output ("{0,-44} w={1,-4} hOverflow={2,-6} scrollW={3,-6} wide={4,-3} touch<40={5}/{6}" -f $p, $w, $r.hOverflow, $r.scrollWidth, $r.wideCount, $r.touchSmall, $r.touchTotal)
      foreach ($x in @($r.wide)) { if ($x) { Write-Output ("    wide  {0} -> {1}px" -f $x.sel, $x.w) } }
      foreach ($s in @($r.small)) { if ($s) { Write-Output ("    small {0} -> {1}x{2}" -f $s.t, $s.w, $s.h) } }
      $summary += [pscustomobject]@{ page = $p; w = $w; overflow = $r.hOverflow; wide = $r.wideCount; touchSmall = $r.touchSmall; touchTotal = $r.touchTotal }
    } else {
      Write-Output ("{0,-44} w={1,-4} 没拿到结果" -f $p, $w)
      $lines | Select-Object -Last 4 | ForEach-Object { Write-Output ("    | " + $_) }
    }
    if ($consoleLine) { Write-Output ("    " + $consoleLine) }
  }
}
Write-Output ''
Write-Output '===== 移动端汇总'
$summary | Format-Table -AutoSize | Out-String -Width 200 | Write-Output
