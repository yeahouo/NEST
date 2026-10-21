# 一条命令重建前端静态资源。
#
# 什么时候必须跑：改了任一页面的 class、改了 tailwind.config.js 的主题、新增了页面。
# 什么时候不用跑：只改页面里的 JS 逻辑 / 文案 —— CSS 不受影响。
#
# 做两件事：
#   1) 用 Tailwind 扫源码，生成 src/main/resources/static/css/nest.css（压缩版）
#   2) 反查四个页面里用到的每个 class 是否都在生成的 CSS 里，少一个就报错退出
#
# 第 2 步不是多余的：Tailwind 是「扫到才生成」，漏扫不会报错、只会静默少一条样式，
# 表现成「某块布局莫名不对」，在浏览器里非常难查。
#
# 用法：cd D:\project\NEST\tools\webbuild ; .\build-web-assets.ps1
#
# 注意 $ErrorActionPreference 必须是 Continue：npm 会把 "npm warn ..." 写到 stderr，
# PowerShell 5.1 把它变成 error record，配 Stop 就会让整个构建在 npx 那一步直接终止，
# 而且只留下一行 npm 警告、看不到真实原因。这里改为按 $LASTEXITCODE 显式判错。
$ErrorActionPreference = 'Continue'

$out = Join-Path $PSScriptRoot '..\..\src\main\resources\static\css\nest.css'

if (-not (Test-Path (Join-Path $PSScriptRoot 'node_modules'))) {
    throw "node_modules 不存在 —— 先在这个目录跑：npm install"
}

Push-Location $PSScriptRoot
try {
    Write-Host 'tailwind: 正在扫描页面并生成 nest.css ...'
    & npx tailwindcss -c tailwind.config.js -i input.css -o $out --minify
    if ($LASTEXITCODE -ne 0) { throw "tailwindcss 退出码 $LASTEXITCODE" }

    $size = (Get-Item $out).Length
    Write-Host ("tailwind: 完成，nest.css = {0:N0} 字节" -f $size) -ForegroundColor Green

    Write-Host '正在校验 class 覆盖 ...'
    & (Join-Path $PSScriptRoot 'verify-css.ps1')
    if ($LASTEXITCODE -ne 0) { throw '有 class 没被生成 —— 上面的清单就是漏掉的，先解决再继续。' }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host '下一步：cd D:\project\NEST ; mvn -B -ntp compile -DskipTests（把静态资源复制到 target/classes）' -ForegroundColor Cyan
