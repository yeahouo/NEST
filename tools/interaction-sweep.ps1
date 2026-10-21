param(
  [Parameter(Mandatory = $true)][string]$Sid,
  [Parameter(Mandatory = $true)][string[]]$Pages,
  [int]$Port = 9222,
  [int]$Width = 1440,
  [int]$Height = 1100,
  [string]$Out = 'target\interaction-sweep.log'
)

# 交互扫描驱动器：把 tools\interaction-sweep.page.js 作为一步 eval 塞进 probe.mjs，
# 逐页跑，打印 clicked/skipped/mutations/errors 摘要，并把原始行落盘。
# 用法：& tools\interaction-sweep.ps1 -Sid <JSESSIONID> -Pages @('/dashboard.html')
$ErrorActionPreference = 'Continue'
$enc = [System.Text.UTF8Encoding]::new($false)
$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$js = [System.IO.File]::ReadAllText((Join-Path $repo 'tools\interaction-sweep.page.js'), [System.Text.Encoding]::UTF8)
$cfgPath = Join-Path $env:TEMP 'nest-sweep.json'
$cb = [DateTimeOffset]::Now.ToUnixTimeSeconds()
$summary = @()

foreach ($p in $Pages) {
  $cb++
  $sep = '?'
  if ($p -match '\?') { $sep = '&' }
  $cfg = @{
    port   = $Port
    cookie = @{ name = 'JSESSIONID'; value = $Sid; url = 'http://127.0.0.1:8080' }
    page   = @{ url = "http://127.0.0.1:8080$p$sep" + "cb=$cb"; width = $Width; height = $Height; waitMs = 3200 }
    steps  = @(
      @{ name = 'sweep'; eval = $js; waitMs = 300 }
    )
  }
  [System.IO.File]::WriteAllText($cfgPath, ($cfg | ConvertTo-Json -Depth 6 -Compress), $enc)
  Write-Output "===== $p"
  $lines = & node (Join-Path $repo 'tools\probe.mjs') $cfgPath 2>&1
  $lines | Out-File -FilePath (Join-Path $repo $Out) -Append -Encoding utf8
  $sweepLine = $lines | Where-Object { $_ -match '^\[sweep\] ' } | Select-Object -First 1
  $consoleLine = $lines | Where-Object { $_ -match '^页面控制台' } | Select-Object -First 1
  if ($sweepLine) {
    $json = $sweepLine -replace '^\[sweep\] ', ''
    try {
      $r = $json | ConvertFrom-Json
      $errN = @($r.errs).Count
      Write-Output ("  clicked={0}/{1} skipped={2} mutations={3} errs={4}" -f $r.clicked, $r.visible, $r.skipped, $r.mutations, $r.errCount)
      foreach ($e in @($r.errs)) {
        if ($e) { Write-Output ("  ! [{0}] {1} -> {2}" -f $e.label, $e.sig, ($e.errs -join ' | ')) }
      }
      foreach ($s in @($r.silentSample)) {
        if ($s) { Write-Output ("  . silent {0}" -f $s) }
      }
      $hvN = [int]$r.hiddenVisibleCount
      if ($hvN -gt 0) {
        Write-Output ("  !!! hidden 却可见 {0} 个（class 写了 hidden，但计算样式不是 none）" -f $hvN)
        foreach ($h in @($r.hiddenVisible)) { if ($h) { Write-Output ("      " + $h) } }
      }
      $ovN = [int]$r.overlayCount
      if ($ovN -gt 0) {
        Write-Output ("  !!! 初始状态就盖住整屏的浮层 {0} 个" -f $ovN)
        foreach ($o in @($r.overlay)) { if ($o) { Write-Output ("      " + $o) } }
      }
      $summary += [pscustomobject]@{ page = $p; clicked = $r.clicked; visible = $r.visible; skipped = $r.skipped; mutations = $r.mutations; errs = $r.errCount; hiddenVisible = $hvN; overlay = $ovN; console = $consoleLine }
    } catch {
      Write-Output ("  解析失败: " + $_.Exception.Message)
      Write-Output ($json.Substring(0, [Math]::Min(300, $json.Length)))
    }
  } else {
    Write-Output "  没有拿到 sweep 结果："
    $lines | Select-Object -Last 6 | ForEach-Object { Write-Output ("  | " + $_) }
  }
  if ($consoleLine) { Write-Output ("  {0}" -f $consoleLine) }
}
Write-Output ''
Write-Output '===== 汇总'
$summary | Format-Table -AutoSize | Out-String -Width 200 | Write-Output
