# 校验：四个页面上用到的每一个 class，在预编译出来的 nest.css 里都真的存在。
#
# 为什么需要这个脚本：Tailwind 是按「扫到才生成」工作的，靠正则扫描源码文本。
# 写错了路径、或者某个 class 是在 JS 里拼出来的，都不会报错——只会静默地少一条样式，
# 页面上表现为「某一块布局莫名其妙不对」，肉眼很难判断。所以在这里一次性查干净。
#
# 解析方式：把 class="..." 里的 ${...} 插值整体摘掉，剩下的按空白切成字面 class；
# 插值内部再用引号抠出条件 class（例如 ${x ? 'text-red-600' : 'text-emerald-600'}）。
# 这样 JS 表达式片段（c.delta、extra||）不会被误当成 class 名。
#
# 两条已加的形状豁免（都是 P13/P14 新页面触发的假警报，触发点是「候选来源」而非页面本身）：
#   ① 与字面量做比较的操作数不是 class：className = 'x ' + (type === 'success' ? ...) 里的
#      success 是取值判断，v-0.1 版本把 'success' 当成缺失类报假警。
#   ② className 只是局部变量名时右边是元素查找：var className = document.getElementById('rank-class')
#      里的 'rank-class' 是 id 不是 class（页面既有惯例是局部变量别叫 className，这里两边都兜住）。
#
# 用法：cd D:\project\NEST\tools\webbuild ; .\verify-css.ps1
$ErrorActionPreference = 'Stop'

$root      = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$staticDir = Join-Path $root 'src\main\resources\static'
$cssPath   = Join-Path $staticDir 'css\nest.css'

if (-not (Test-Path $cssPath)) {
    throw "missing $cssPath - run build-web-assets.ps1 first"
}
$css = Get-Content $cssPath -Raw -Encoding UTF8

# 扫全部页面（含教学模块的遗留页），不再只列 5 个交付页。
# 本地化覆盖到某一页之后，那一页用到的 class 就必须全部在 nest.css 里——
# 漏一个不会报错，只会在那一页静默少一条样式。
$pages = Get-ChildItem -Path $staticDir -Filter *.html -Recurse |
    Sort-Object FullName | ForEach-Object { $_.FullName }

# 不是 Tailwind 工具类，而是页面自己 <style> 里定义的（或由别的样式表提供），
# 本来就不该出现在 nest.css 里。新增自定义 class 时把名字加到这里。
$custom = @(
    'animate-blur-in', 'dim-bar', 'ghost-layer', 'grid-table', 'layout-container',
    'modal-container-animate', 'mono', 'panel', 'particle', 'polygon-bg',
    'tab-active', 'tab-btn', 'material-symbols-outlined',
    # 标记类：本身不产出任何 CSS 规则，只供 group-hover: / peer-checked: 这类变体引用，
    # 所以在生成的 CSS 里找不到 .group{} 是正常的。
    'group', 'peer'
)

# 页面里写了、但哪儿都没定义的类 = 死类（写了也没有任何效果）。
# 下面这批已逐个 grep 确认「没有任何 .名{} 定义」，而且**在改用 CDN 的版本里同样不存在**，
# 所以它们不是本地化引入的回归，是页面自身的固有缺陷（多数在遗留教学模块页里）。
# 列在这里不是豁免，是标明「已知且不在本次范围内」，免得这个检查每次都喊狼来了、
# 最后没人再看它 —— 一个总在报警的检查等于没有检查。修页面时顺手把对应项删掉。
$deadInMarkup = @(
    'analysis', 'cls', 'green',   # JS 对象键 / 变量名，被分词器捞进来了
    'announcement-content', 'assignments-list-section', 'pending-review-section',
    'quizzes-list-section', 'quiz-grade-feedback', 'quiz-grade-score', 'quiz-results-tab',
    'cover-option', 'dynamic-row', 'btn-text', 'submissions', 'student-only',
    'hoverable', 'truncated'
)

$candidates = New-Object System.Collections.Generic.HashSet[string]

# 所有页面自己 <style> 里定义过的 class 名。它们本来就该由页面样式提供，
# 不在 nest.css 里是正确的。用「扫出来」而不是手工白名单：遗留教学页里的
# 自定义类有几十个（hero / marquee / service-card / quiz-* / expand-btn-* …），
# 一个个列举下去必然腐烂，而且漏掉一个就会把假警报喂给人，最后没人再看这个检查。
$definedAll = New-Object System.Collections.Generic.HashSet[string]

# 其它手写的项目样式表（css\*.css，nest.css 自己除外）里定义的 class，也算「别处已经提供」：
# 概念页 art.html 完全不吃 nest.css，它的 art-* 全部在 css\nest-art.css 里。
# 不这么写的话，加了第 22 个页面之后这个检查会报 50 个 art-* 假警报 —— 一个总在报警的
# 检查等于没有检查（和下面 $deadInMarkup 那段注释是同一个道理）。
$cssDir = Join-Path $staticDir 'css'
foreach ($sheet in (Get-ChildItem -Path $cssDir -Filter *.css -File)) {
    if ($sheet.Name -eq 'nest.css') { continue }
    $sheetText = Get-Content $sheet.FullName -Raw -Encoding UTF8
    foreach ($sm in [regex]::Matches($sheetText, '\.([a-zA-Z][a-zA-Z0-9_-]*)')) {
        [void]$definedAll.Add($sm.Groups[1].Value)
    }
}

# 从一段 JS 表达式里抠出「可能是 class 列表」的引号字符串。
# 先删掉与字面量做比较的操作数（type === 'success'），再取剩下的单引号/反引号字符串。
function Get-ClassishQuotes {
    param([string] $expr)
    $scrubbed = [regex]::Replace($expr, '(===|!==|==|!=)\s*''[^'']*''', ' ')
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($q in [regex]::Matches($scrubbed, "'([^']*)'")) { $out.Add($q.Groups[1].Value) }
    foreach ($q in [regex]::Matches($scrubbed, '`([^`]*)`'))  { $out.Add($q.Groups[1].Value) }
    return $out.ToArray()
}

function Add-Candidates {
    param([System.Collections.Generic.HashSet[string]] $Set, [string[]] $Items)
    foreach ($x in $Items) {
        if ($x -and $x.Trim()) { [void]$Set.Add($x.Trim()) }
    }
}

foreach ($p in $pages) {
    $text = Get-Content $p -Raw -Encoding UTF8

    # 该页 <style> 里定义的 class（.hero、.marquee-item 这类页面私有样式）
    foreach ($st in [regex]::Matches($text, '(?s)<style[^>]*>(.*?)</style>')) {
        foreach ($sm in [regex]::Matches($st.Groups[1].Value, '\.([a-zA-Z][a-zA-Z0-9_-]*)')) {
            [void]$definedAll.Add($sm.Groups[1].Value)
        }
    }

    foreach ($m in [regex]::Matches($text, 'class="([^"]*)"')) {
        $cls = $m.Groups[1].Value

        # 1) 插值内部的引号字符串 = 条件 class
        foreach ($e in [regex]::Matches($cls, '\$\{([^}]*)\}')) {
            foreach ($v in (Get-ClassishQuotes $e.Groups[1].Value)) { Add-Candidates $candidates ($v -split '\s+') }
        }

        # 2) 摘掉插值后剩下的字面 class
        $literal = [regex]::Replace($cls, '\$\{[^}]*\}', ' ')
        Add-Candidates $candidates ($literal -split '\s+')
    }

    # 3) JS 里拼出来的 class —— 这一类最容易被漏掉，因为静态 HTML 里根本看不到它：
    #    el.className = 'mt-4 p-4 bg-green-100 ...'（赋值可能跨好几行，所以用 [^;]+ 一路吃到分号）
    foreach ($m in [regex]::Matches($text, 'className\s*=\s*([^;]+);')) {
        $rhs = $m.Groups[1].Value
        # className 只是个局部变量名（读 DOM 元素）时，右边引号里是 id/选择器，不是 class
        if ($rhs -match 'getElementById\(|querySelector\(') { continue }
        foreach ($v in (Get-ClassishQuotes $rhs)) { Add-Candidates $candidates ($v -split '\s+') }
    }
    #    el.classList.add('bg-primary', 'text-white') / remove / toggle / replace
    foreach ($m in [regex]::Matches($text, 'classList\.(?:add|remove|toggle|replace)\(([^)]*)\)')) {
        foreach ($v in (Get-ClassishQuotes $m.Groups[1].Value)) { Add-Candidates $candidates ($v -split '\s+') }
    }
}

# 判断一个 token 是否「像」一个 class 名。页面里大量 class 属性是 JS 模板字符串，
# 按空白切开会切出 c.delta、cur[k、extra||、hover:、bg- 这类残渣；
# 这里只做形状判断，剩下拿不准的一律交给 $custom / $definedAll 两个集合豁免。
function Test-LooksLikeClass {
    param([string] $tok)
    # 方括号里允许任意字符（任意值语法 blur-[100px]），先摘掉再判形状
    $outside = [regex]::Replace($tok, '\[[^\]]*\]', '')
    if ($outside -match '[.?|&()<>=+;,$''`"]') { return $false }  # JS 残渣
    # 必须用 -cmatch：PowerShell 的 -match / -notmatch 默认**不区分大小写**，
    # 写成 -match '[A-Z]' 会连 flex 这种纯小写 token 一起排掉，
    # 结果是 checked 0 的「空转通过」——比报错更危险。
    if ($outside -cmatch '[A-Z]') { return $false }               # JS 变量名
    if ($outside -notmatch '^!?-?[a-z]') { return $false }        # : -1 -500/10
    if ($outside -match '[-:/]$') { return $false }               # bg- text- hover: group-hover:
    if ($outside -match '^!?-?[a-z]$') { return $false }          # 单字符
    return $true
}

$missing = New-Object System.Collections.Generic.HashSet[string]
$checked = 0

foreach ($tok in $candidates) {
    if ($tok.Contains('${')) { continue }
    if ($custom -contains $tok) { continue }
    if ($deadInMarkup -contains $tok) { continue }
    if ($definedAll.Contains($tok)) { continue }
    if (-not (Test-LooksLikeClass $tok)) { continue }
    $checked++

    # Tailwind 在 CSS 选择器里会转义特殊字符：md:grid-cols-3 -> .md\:grid-cols-3
    $esc = $tok -replace '([^a-zA-Z0-9_\-])', '\$1'
    $hit = $false
    foreach ($suffix in @('{', ',', ':', ' ', '>', '~', '+')) {
        if ($css.Contains('.' + $esc + $suffix)) { $hit = $true; break }
    }
    if (-not $hit) { [void]$missing.Add($tok) }
}

Write-Host ("checked {0} classes (candidates {1}; rest were custom or dynamic)" -f $checked, $candidates.Count)
if ($missing.Count -eq 0) {
    Write-Host 'ALL HIT - nest.css covers every class.' -ForegroundColor Green
    exit 0
}

Write-Host ("MISSING {0} classes from nest.css:" -f $missing.Count) -ForegroundColor Red
$missing | Sort-Object | ForEach-Object { Write-Host "  $_" }
exit 1
