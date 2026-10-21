# NEST 演示脚本：一条命令跑完「导入 → 体检 → 分配 → 审计 → 解释 → 申诉 → 学生端」
#
# 用法（先按 README 起好 8081 上的试点实例）：
#   pwsh -File tools\pilot\demo.ps1
#   pwsh -File tools\pilot\demo.ps1 -Port 8081
#
# 为什么用 curl.exe 而不是 Invoke-RestMethod：
#   Invoke-RestMethod 会把 UTF-8 的中文解码成乱码，而本次演示的价值有很大一部分
#   就在那些中文理由里（体检的错误说明、申诉的放宽原因）。curl.exe 把原始字节
#   落盘，再用 read 工具打开，中文是干净的。
#
# 为什么 cookie 要手工抓而不是用 cookie jar：
#   同一个 jar 上先后用 -c / -b 会被下一次请求覆盖，实测会拿到 401。
#   抓响应头里的 JSESSIONID 再显式带 -H "Cookie: ..." 是唯一稳定的做法。

param(
    [int]$Port = 8081,
    [switch]$SkipImport
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$root    = Split-Path (Split-Path $PSScriptRoot)          # D:\project\NEST
$out     = Join-Path $root 'target\demo'
$base    = "http://127.0.0.1:$Port"
$pilotIn = Join-Path $root 'target\pilot-import.json'
$badIn   = Join-Path $out 'bad-roster.json'

New-Item -ItemType Directory -Force -Path $out | Out-Null

$script:Step = 0
function Say($msg) {
    $script:Step++
    Write-Host ""
    Write-Host ("=" * 78) -ForegroundColor DarkGray
    Write-Host ("[{0}] {1}" -f $script:Step, $msg) -ForegroundColor Cyan
    Write-Host ("=" * 78) -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- HTTP 封装
function Get-Sid([string]$user, [string]$pass) {
    $hdr = Join-Path $out 'login-hdr.txt'
    $req = Join-Path $out 'login-req.json'
    # 登录体必须走文件，不能用 `-d '{...}'` 内联：PowerShell 把参数转交给原生 exe 时会
    # 把 JSON 里的双引号剥掉，服务端收到的是 {username:admin,...}，
    # 报 HTTP 500「JSON parse error: Unexpected character ('u' ...)」，
    # 于是下面 regex 拿不到 JSESSIONID，之后每个请求都 401 —— 症状离原因很远。
    Set-Content -LiteralPath $req -Value ('{"username":"' + $user + '","password":"' + $pass + '"}') -NoNewline -Encoding ascii
    curl.exe -s -D $hdr -o (Join-Path $out 'login-body.json') -X POST "$base/api/auth/login" `
             -H 'Content-Type: application/json' --data-binary "@$req" | Out-Null
    $h = Get-Content $hdr -Raw
    $m = [regex]::Match($h, 'JSESSIONID=([0-9A-Fa-f]+)')
    if (-not $m.Success) { throw "登录失败：$user（响应头里没有 JSESSIONID）" }
    return $m.Groups[1].Value
}

function Call([string]$method, [string]$path, [string]$sid, [string]$outFile, [string]$bodyFile) {
    $a = @('-s', '-o', $outFile, '-w', '%{http_code}', '-X', $method, "$base$path", '-H', "Cookie: JSESSIONID=$sid")
    if ($bodyFile) { $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyFile") }
    return (& curl.exe @a).Trim()
}

function CallJson([string]$name, [string]$method, [string]$path, [string]$sid, [string]$bodyFile) {
    $f = Join-Path $out ("{0:d2}-{1}.json" -f $script:Step, $name)
    $code = Call $method $path $sid $f $bodyFile
    Write-Host ("  {0,-6} {1}  -> HTTP {2}   （{3}）" -f $method, $path, $code, (Split-Path $f -Leaf)) -ForegroundColor Gray
    if ($code -ne '200') { Write-Host "  响应体：" -NoNewline; Write-Host (Get-Content $f -Raw) -ForegroundColor Yellow }
    return @{ Code = $code; File = $f }
}

function Json([string]$file) {
    return (Get-Content $file -Raw -Encoding utf8 | ConvertFrom-Json)
}

# ================================================================ 0. 环境自检
Say "环境自检：$base/api/dorm/health"

$health = Join-Path $out '00-health.json'
try {
    $code = Call 'GET' '/api/dorm/health' '' $health $null
} catch {
    throw "连不上 $base —— 请先启动试点实例（见 docs\结题材料\演示脚本.md 第 0 步）"
}
$h = Json $health
if ($h.data.status -ne 'UP') {
    Write-Host "  健康检查返回：$(Get-Content $health -Raw)" -ForegroundColor Yellow
}
Write-Host ("  HTTP {0}；模块 {1}；状态 {2}；服务自述 {3} 条路由" -f `
    $code, $h.data.module, $h.data.status, $h.data.endpoints.Count) -ForegroundColor Green

# ================================================================ 1. 登录
Say "以管理员身份登录（POST /api/auth/login）"
$sid = Get-Sid 'admin' '123456'
Write-Host "  JSESSIONID = $($sid.Substring(0,8))…   （已获取）" -ForegroundColor Green

# ================================================================ 2. 数据概况
Say "数据概况（GET /api/dorm/summary）"
$r = CallJson 'summary' 'GET' '/api/dorm/summary' $sid $null
$s = Json $r.File
Write-Host ("  学生 {0} / 房间 {1} / 床位 {2} / 志愿 {3} / 带特征 {4} / 批次 {5}" -f `
    $s.data.students, $s.data.rooms, $s.data.capacityTotal, $s.data.preferences, $s.data.features, $s.data.batches) -ForegroundColor Green

# ================================================================ 3. 坏名单体检
Say "导入前体检（一）：故意给一份「什么错都有」的名单 —— 看它逐条点名"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$badJson = @'
{
  "replaceAll": false,
  "students": [
    { "studentNo": "BAD001", "name": "缺年级",   "gender": "女", "grade": null, "major": "工商管理", "majorCluster": "经管类" },
    { "studentNo": "BAD001", "name": "学号重复", "gender": "女", "grade": 2023, "major": "工商管理", "majorCluster": "经管类" },
    { "studentNo": "BAD003", "name": "性别认不出", "gender": "X", "grade": 2023, "major": "工商管理", "majorCluster": "经管类" },
    { "studentNo": "BAD004", "name": "特殊需求非法", "gender": "女", "grade": 2023, "major": "工商管理", "majorCluster": "经管类", "specialNeed": "WHEELCHAIR2" },
    { "studentNo": "BAD005", "name": "特征全零",   "gender": "女", "grade": 2023, "major": "工商管理", "majorCluster": "经管类",
      "feature": [0,0,0,0,0,0,0,0,0,0,0] },
    { "studentNo": "BAD006", "name": "楼栋不存在", "gender": "女", "grade": 2023, "major": "工商管理", "majorCluster": "经管类",
      "buildingPref": "火星1号楼" }
  ],
  "rooms": [
    { "roomNo": "测试楼-101", "buildingId": "测试楼", "gender": "F", "capacity": 4, "forSpecialNeeds": false }
  ]
}
'@
[System.IO.File]::WriteAllText($badIn, $badJson, [System.Text.UTF8Encoding]::new($false))

$r = CallJson 'preflight-bad' 'POST' '/api/dorm/data/preflight' $sid $badIn
$p = Json $r.File
# 字段名实测：data.ok / data.errors（是**条数**不是数组）/ data.warnings（条数）/
# data.students / data.rooms / data.beds / data.withFeature / data.issues[] / data.markdown。
# issues 每项是 {severity, where, message}，同类问题已合并计数并最多举 3 个例子。
Write-Host ("  体检结论 ok={0}；ERROR {1} 条 / WARN {2} 条" -f $p.data.ok, $p.data.errors, $p.data.warnings) -ForegroundColor Yellow
Write-Host "  ---- 逐条点名（原文）----" -ForegroundColor Yellow
foreach ($e in $p.data.issues) {
    $c = if ($e.severity -eq 'ERROR') { 'Yellow' } else { 'DarkYellow' }
    Write-Host ("   · [{0}] （{1}）{2}" -f $e.severity, $e.where, $e.message) -ForegroundColor $c
}
Write-Host "  ※ 注意：体检是只读的，刚才这一下没有写入任何数据。" -ForegroundColor Green

# ================================================================ 4. 好名单体检
Say "导入前体检（二）：真实形态的 324 人名单（POST /api/dorm/data/preflight）"
if (-not (Test-Path $pilotIn)) { throw "缺少 $pilotIn —— 先生成试点名单（见 演示脚本.md 第 0 步）" }
$r = CallJson 'preflight-good' 'POST' '/api/dorm/data/preflight' $sid $pilotIn
$p = Json $r.File
Write-Host ("  体检结论 ok={0}；ERROR {1} 条 / WARN {2} 条；学生 {3} 人；带特征 {4} 人" -f `
    $p.data.ok, $p.data.errors, $p.data.warnings, $p.data.students, $p.data.withFeature) -ForegroundColor Green
foreach ($w in $p.data.issues) { Write-Host ("   · [{0}] {1}" -f $w.severity, $w.message) -ForegroundColor DarkYellow }
if (-not $p.data.ok) { throw "体检未通过，演示中止（避免污染试点库）" }

# ================================================================ 5. 导入
Say "导入名单与房间（POST /api/dorm/data/import，replaceAll=true）"
if ($SkipImport) {
    Write-Host "  （-SkipImport 已指定，跳过）" -ForegroundColor DarkGray
} else {
    $r = CallJson 'import' 'POST' '/api/dorm/data/import' $sid $pilotIn
    $im = Json $r.File
    Write-Host ("  导入：学生 {0} / 房间 {1} / 床位 {2}；体检提醒 {3} 条" -f `
        $im.data.students, $im.data.rooms, $im.data.summary.capacityTotal, $im.data.preflightWarnings) -ForegroundColor Green
    Write-Host ("  {0}" -f $im.data.note) -ForegroundColor DarkGray
}

# ================================================================ 6. 分配
Say "执行分配（POST /api/dorm/assign）"
$assignBody = Join-Path $out 'assign-body.json'
# 必须用 UTF8(no BOM) 写：Set-Content -Encoding ascii 会把「结题演示」写成「????」，
# 存进库里的批次名就是问号（HTTP 200，不报错，只是名字烂掉）。BOM 也不行，
# 服务端会报 JSON parse error。
[System.IO.File]::WriteAllText($assignBody, '{"name":"结题演示","withExplanations":false}', [System.Text.UTF8Encoding]::new($false))
$r = CallJson 'assign' 'POST' '/api/dorm/assign' $sid $assignBody
$a = Json $r.File
# 实测形状：data.batchId / data.name / data.solution（一句人读的摘要字符串）/
# data.metrics{} / data.diagnostics{} / data.warnings[] / data.metricBasisWarning
$bid = $a.data.batchId
$m = $a.data.metrics
$d = $a.data.diagnostics
Write-Host ("  批次 id = {0}（{1}）" -f $bid, $a.data.name) -ForegroundColor Green
Write-Host ("  {0}" -f $a.data.solution) -ForegroundColor Green
Write-Host ("  块 {0} 个（最大 {1} 人）；块内求解：贪心 {2} ms + 局部搜索 {3} ms + 阻塞对修复 {4} ms；接受移动 {5} 次" -f `
    $d.blockCount, $d.maxBlockSize, $d.greedyMsTotal, $d.localSearchMsTotal, $d.blockingRepairMsTotal, $d.localSearchAcceptedMoves) -ForegroundColor Gray
Write-Host ("  指标：平均相容度 {0:N4} （{1}）/ 最差 {2:N4} / 房内方差 {3:N5} / 志愿满足 {4:P1} / 公平差距（专业）{5:N4}" -f `
    $m.meanCompat, $m.basis, $m.worstCompat, $m.meanRoomVariance, $m.preferenceSatisfaction, $m.fairnessSpreadMajor) -ForegroundColor Green
if ($a.data.metricBasisWarning) {
    Write-Host ("  ⚠ {0}" -f $a.data.metricBasisWarning) -ForegroundColor DarkYellow
}

# ================================================================ 7. 审计
Say "公平性审计（GET /api/dorm/batches/$bid/audit）"
$r = CallJson 'audit' 'GET' "/api/dorm/batches/$bid/audit" $sid $null
$au = Json $r.File
# 实测：data 直接就是数组（不是 {items:[...]}），每项 {metric, group_key, group_size, value}。
Write-Host ("  审计记录 {0} 条；字段：{1}" -f $au.data.Count, (($au.data[0].PSObject.Properties.Name) -join ', ')) -ForegroundColor Gray
Write-Host "  ---- 按组汇总 ----" -ForegroundColor Gray
foreach ($it in $au.data) {
    Write-Host ("   · {0,-30} {1,-8} （{2} 人） = {3:N4}" -f $it.metric, $it.group_key, $it.group_size, $it.value) -ForegroundColor Gray
}

# ================================================================ 8. 取一份分配结果
Say "取分配结果（GET /api/dorm/batches/$bid/assignments）—— 后面解释与申诉都要用它"
$r = CallJson 'assignments' 'GET' "/api/dorm/batches/$bid/assignments" $sid $null
$as = Json $r.File
# 实测：data 直接就是数组；行是 snake_case（student_id / room_id / room_label / room_no…）。
$rows = $as.data
if (-not $rows) { $rows = $as.data.items }
Write-Host ("  分配行 {0} 条；字段：{1}" -f $rows.Count, (($rows[0].PSObject.Properties.Name) -join ', ')) -ForegroundColor Gray

# 按语义还原 blockKey（接口出于兼容没有返回它）
$blockOf = @{}
foreach ($row in $rows) {
    $kind = if ($row.special_need -and $row.special_need -ne 'NONE') { 'S' } else { 'N' }
    $row | Add-Member -NotePropertyName blockKey -NotePropertyValue ("{0}|{1}|{2}|{3}" -f $row.gender, $kind, $row.major_cluster, $row.grade) -Force
    $blockOf[[string]$row.student_id] = $row.blockKey
}

# ================================================================ 9. 四层解释
Say "单学生四层解释（GET /api/dorm/batches/$bid/explain/{studentId}）"
# 挑一个「有特征」的人，否则平均相容度是 null（这也是一条要讲的诚实语义：
# 一维都没填的人，系统报「无法给出相容度」，而不是拿 0 或均值代替）。
$probe = $rows | Where-Object { $_.room_id -gt 0 } | Select-Object -First 1
$r = CallJson 'explain' 'GET' "/api/dorm/batches/$bid/explain/$($probe.student_id)" $sid $null
$ex = Json $r.File
# 实测：data.source（ON_DEMAND / STORED）+ data.explanation{...}
$e = $ex.data.explanation
Write-Host ("  对象：{0} 住在 {1}（{2}）" -f $e.studentName, $e.roomLabel, $e.buildingId) -ForegroundColor Green
Write-Host ("  本房间平均相容度 = {0:N4}；最需要留意的一对 = {1:N4}；可评估 {2}/{3} 对" -f `
    $e.roomMeanCompat, $e.roomWorstCompat, $e.evaluatedPairs, $e.totalPairs) -ForegroundColor Green
Write-Host "  叙述：" -ForegroundColor Gray
Write-Host ("   {0}" -f $e.narrative) -ForegroundColor White
if ($e.constraints) {
    Write-Host "  约束层（哪条硬约束在这里起了作用）：" -ForegroundColor Gray
    foreach ($c in $e.constraints) {
        $mark = if ($c.binding) { '生效' } else { '未绑定' }
        Write-Host ("   · [{0}] {1} —— {2}" -f $mark, $c.rule, $c.detail) -ForegroundColor Gray
    }
}
if ($e.dimensions) {
    Write-Host "  特征层（逐维贡献，只列最吃紧的 4 维）：" -ForegroundColor Gray
    foreach ($d in ($e.dimensions | Select-Object -First 4)) {
        Write-Host ("   · {0,-10} 与 {1}：你 {2} / 对方 {3}（差异 {4}）- {5}" -f `
            $d.dimension, $d.otherName, $d.mine, $d.theirs, $d.gap, $d.comment) -ForegroundColor Gray
    }
}

# ================================================================ 10. 三级申诉
Say "反事实申诉三级实测（node tools\pilot\appeal-verify.mjs run $bid）"
Write-Host "  这一节由 appeal-verify.mjs 负责：它会自动在批次里挑出「同块同年级」与「跨年级同大类」两对，"
Write-Host "  先跑房间级、再跑跨块级，并把代价、波及人数、放宽项原样打出来。" -ForegroundColor Gray
Push-Location $root
try {
    node tools\pilot\appeal-verify.mjs run $bid 2>&1 | ForEach-Object { Write-Host "  $_" }
} finally {
    Pop-Location
}

# ================================================================ 11. 学生端
Say "学生端：登录 → 绑定 → 看分配 → 看志愿（/api/dorm/portal/**）"
$portalOk = $false
try {
    $ssid = Get-Sid 'wangxm' '123456'
    $first = $rows | Select-Object -First 1
    $bindBody = Join-Path $out 'bind-body.json'
    [System.IO.File]::WriteAllText($bindBody, ('{"studentNo":"' + $first.student_no + '"}'), [System.Text.UTF8Encoding]::new($false))
    $r = CallJson 'portal-bind' 'POST' '/api/dorm/portal/bind' $ssid $bindBody
    $r = CallJson 'portal-allocation' 'GET' '/api/dorm/portal/allocation' $ssid $null
    $r = CallJson 'portal-preference' 'GET' '/api/dorm/portal/preference' $ssid $null
    $pp = Json $r.File
    Write-Host ("  我的分块：{0}（{1}）" -f $pp.data.myBlock, $pp.data.myBlockText) -ForegroundColor Green
    Write-Host ("  想同住 {0} 人 / 不想同住 {1} 人 / 楼栋意愿 {2} 个；上限 {3}" -f `
        $pp.data.wantRoommates.Count, $pp.data.avoidRoommates.Count, $pp.data.preferredBuildings.Count, `
        ($pp.data.limits | ConvertTo-Json -Compress)) -ForegroundColor Green
    $portalOk = $true
} catch {
    Write-Host "  学生端这一步没有跑通：$($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "  （这不影响前面所有结论——它只是说明该账号在试点库里还没绑定学籍）" -ForegroundColor DarkGray
}

# ================================================================ 12. 汇总
Say "汇总"
Write-Host "  全部原始响应体（含中文原文）已落盘到：" -ForegroundColor Green
Write-Host "    $out" -ForegroundColor White
Write-Host "  建议用 read 工具打开这些文件，而不是用 Invoke-RestMethod —— 后者会把中文解码成乱码。" -ForegroundColor Gray
Write-Host ""
Write-Host "  本次演示覆盖：体检拦截 → 导入 → 分配 → 审计 → 四层解释 → 三级申诉 → 学生端" -ForegroundColor Green
Write-Host ("  试点批次 id = {0}；学生端 {1}" -f $bid, $(if ($portalOk) { '已跑通' } else { '未跑通（见上）' })) -ForegroundColor Green
