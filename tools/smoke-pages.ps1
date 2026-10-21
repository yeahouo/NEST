param(
  [Parameter(Mandatory = $true)][string]$Sid,
  [Parameter(Mandatory = $true)][string[]]$Pages,
  [int]$Port = 9222,
  [int]$Width = 1440,
  [int]$Height = 1000,
  [string]$CookieName = 'JSESSIONID'
)

# Smoke test: load each page in the real browser and check that the shared
# interaction layer is alive there (no console errors, command palette opens).
# Usage:
#   & tools\smoke-pages.ps1 -Sid <JSESSIONID> -Pages @('/dashboard.html','/settings.html')
$ErrorActionPreference = 'Stop'
$enc = [System.Text.UTF8Encoding]::new($false)
$cfgPath = Join-Path $env:TEMP 'nest-smoke.json'
$cb = [DateTimeOffset]::Now.ToUnixTimeSeconds()

foreach ($p in $Pages) {
  $cb++
  $sep = '?'
  if ($p -match '\?') { $sep = '&' }
  $cfg = @{
    port   = $Port
    cookie = @{ name = $CookieName; value = $Sid; url = 'http://127.0.0.1:8080' }
    page   = @{ url = "http://127.0.0.1:8080$p$sep" + "cb=$cb"; width = $Width; height = $Height; waitMs = 3500 }
    steps  = @(
      @{ name = 'boot'; eval = "JSON.stringify({path: location.pathname, title: document.title, nestUI: typeof NestUI, dark: document.documentElement.classList.contains('dark'), chip: !!document.querySelector('.nx-cmdk-chip'), progress: !!document.querySelector('.nx-progress-top'), hOverflow: document.documentElement.scrollWidth > window.innerWidth + 1})" },
      @{ name = 'scroll'; eval = "window.scrollTo(0, 600); 'scrolled'"; waitMs = 400 },
      @{ name = 'open'; eval = "document.dispatchEvent(new KeyboardEvent('keydown', {key: 'k', ctrlKey: true, bubbles: true})); 'open'"; waitMs = 600 },
      @{ name = 'palette'; eval = "JSON.stringify({items: document.querySelectorAll('.nx-cmdk-item').length, groups: Array.prototype.map.call(document.querySelectorAll('.nx-cmdk-group'), function (e) { return e.textContent }), labels: Array.prototype.map.call(document.querySelectorAll('.nx-cmdk-item-label'), function (e) { return e.textContent }).slice(0, 6)})" },
      @{ name = 'esc'; eval = "document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true})); 'closed'"; waitMs = 300 },
      @{ name = 'state'; eval = "JSON.stringify({cmdkOpen: (function () { var c = document.querySelector('.nx-cmdk'); return c ? c.classList.contains('is-open') : 'no-palette' })(), bodyLocked: document.body.classList.contains('nx-noscroll')})" },
      # Dark mode has to survive a reload, and the class has to be set by a head script that comes
      # BEFORE the stylesheet -- otherwise the user sees a white flash first. Both are checked here.
      @{ name = 'theme-dark'; eval = "localStorage.setItem('nest-theme', 'dark'); location.reload(); 'reloading'"; waitMs = 4200 },
      @{ name = 'theme-after'; eval = "JSON.stringify({dark: document.documentElement.classList.contains('dark'), scheme: document.documentElement.style.colorScheme, bg: getComputedStyle(document.body).backgroundColor, headOrder: (function () { var k = Array.prototype.slice.call(document.head.children); var s = k.findIndex(function (e) { return e.tagName === 'SCRIPT' && e.textContent.indexOf('nest-theme') >= 0 }); var l = k.findIndex(function (e) { return e.tagName === 'LINK' && e.rel === 'stylesheet' }); return 'script@' + s + ' link@' + l })(), metaTheme: (document.querySelector('meta[name=theme-color]') || {}).content || null, nestUIAfterReload: typeof NestUI})" },
      @{ name = 'theme-reset'; eval = "localStorage.removeItem('nest-theme'); 'reset'" }
    )
  }
  [System.IO.File]::WriteAllText($cfgPath, ($cfg | ConvertTo-Json -Depth 8 -Compress), $enc)
  Write-Output "===== $p"
  $out = node 'tools\probe.mjs' $cfgPath 2>&1
  $out | Select-String -Pattern '\[boot\]|\[palette\]|\[state\]|\[theme-after\]|^  ! |EXCEPTION'
  if ($LASTEXITCODE -ne 0) { Write-Output "  probe exit=$LASTEXITCODE" }
}
