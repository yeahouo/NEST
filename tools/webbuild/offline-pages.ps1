# 把静态页面从「运行时 CDN」切到本地预编译资源。
#
# 为什么必须做：cdn.tailwindcss.com 是官方明确标注「仅供演示、不要用于生产」的运行时
# 编译器，fonts.googleapis.com / fonts.gstatic.com 在国内校园网通常不可达。外链一断，
# 页面上的图标会退化成 "fact_check" 这类字面英文词，布局也会跟着崩。
#
# 幂等：只处理「还引用外链」的页面，重复执行是安全的。
# 用法：cd D:\project\NEST\tools\webbuild ; .\offline-pages.ps1
#
# 前置：先跑 build-web-assets.ps1（生成 /css/nest.css）和 fetch-icons.mjs（生成图标字体子集），
# 否则切完页面会引到不存在的资源上。
$ErrorActionPreference = 'Stop'

$root      = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$staticDir = Join-Path $root 'src\main\resources\static'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

$EXT = 'cdn\.tailwindcss\.com|fonts\.googleapis\.com|fonts\.gstatic\.com'
$targets = Get-ChildItem $staticDir -Filter *.html -Recurse | Where-Object {
    ([System.IO.File]::ReadAllText($_.FullName, $utf8NoBom)) -match $EXT
}
if ($targets.Count -eq 0) {
    Write-Host '没有页面还引用外链，无需切换。' -ForegroundColor Green
    exit 0
}

$links = "  <link href=""/css/nest-fonts.css"" rel=""stylesheet""/>`r`n" +
         "  <link href=""/css/nest.css"" rel=""stylesheet""/>`r`n"
$styleRe = [regex]'(?m)^([ \t]*)<style'

$report = @()
foreach ($f in $targets) {
    $p = $f.FullName
    $t = [System.IO.File]::ReadAllText($p, $utf8NoBom)
    $before = $t.Length

    # 1) Google Fonts 的 preconnect 与 stylesheet —— 本地字体由 nest-fonts.css 提供
    $t = [regex]::Replace($t, '[ \t]*<link[^>]*fonts\.(?:googleapis|gstatic)\.com[^>]*>\r?\n', '')
    # 2) Tailwind 的运行时编译器
    $t = [regex]::Replace($t, '[ \t]*<script[^>]*cdn\.tailwindcss\.com[^>]*>\s*</script>[ \t]*\r?\n', '')
    # 3) 内联的 `tailwind.config = {...}`
    #    必须删掉：CDN 脚本一走，这段立刻抛 ReferenceError: tailwind is not defined，
    #    而页面看上去「能显示」，只有打开控制台才看得见。
    #    有些页面写的是 <script id="tailwind-config">，所以 [^>]* 不能省。
    $t = [regex]::Replace($t,
        '(?s)[ \t]*<script\b[^>]*>(?:(?!</script>).)*?tailwind\.config.*?</script>[ \t]*\r?\n', '')
    # 4) 插本地样式：放在第一个 <style> 之前，页面自己的样式仍在后面、仍能覆盖它
    if ($t -match '[ \t]*<style') {
        $t = $styleRe.Replace($t, ($links + '$1<style'), 1)
    } else {
        $t = $t -replace '</head>', ($links + '</head>')
    }

    [System.IO.File]::WriteAllText($p, $t, $utf8NoBom)

    $report += [pscustomobject]@{
        File    = $f.Name
        Chars   = "$before -> $($t.Length)"
        ExtLeft = ([regex]::Matches($t, 'https?://(?:cdn\.tailwindcss|fonts\.googleapis|fonts\.gstatic)')).Count
        CfgLeft = ([regex]::Matches($t, 'tailwind\.config')).Count
    }
}

$report | Format-Table -AutoSize | Out-String -Width 200 | Write-Host

$bad = @($report | Where-Object { $_.ExtLeft -ne 0 -or $_.CfgLeft -ne 0 })
if ($bad.Count -gt 0) {
    throw "有 $($bad.Count) 个页面仍残留外链或 tailwind.config 引用，见上表。"
}
Write-Host "已切换 $($report.Count) 个页面，并确认没有外链与 tailwind.config 残留。" -ForegroundColor Green
