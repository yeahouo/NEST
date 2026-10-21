# =============================================================================
# demo-seed.ps1 - one-command idempotent demo-data seeder for the NEST app.
#
# Usage (Windows PowerShell 5.1):
#   & .\tools\demo-seed.ps1
#   & .\tools\demo-seed.ps1 -MyCnf C:\path\my.cnf -Db nest -SkipReset
#
# What it does, in order:
#   1. Runs sql\nest_demo_reset.sql  (removes E2E / vprobe / SMOKE residue)
#   2. Runs every sql\nest_*_demo.sql in dependency order (each idempotent)
#   3. Runs tools\verify-demo.sql and prints the per-module counts
#
# Every step is idempotent: re-running the whole script restores a clean
# demo state without duplicating rows.
# =============================================================================
param(
    [string]$MyCnf = (Join-Path $env:TEMP 'nest_my.cnf'),
    [string]$Db     = 'nest',
    [switch]$SkipReset
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot          # tools\.. == repo root
$Mysql    = 'D:\develop\mysql-9.5.0-winx64\bin\mysql.exe'
if (-not (Test-Path $Mysql)) { $Mysql = 'mysql.exe' }  # fall back to PATH

if (-not (Test-Path $MyCnf)) {
    throw "MySQL defaults file not found: $MyCnf`nCreate it with: [client]`nuser=root`npassword=...`n"
}

# Ordered list of demo files (dependency-safe). Each file DELETEs its own
# id range before INSERTing, so the order only matters for cross-file deps.
$DemoFiles = @(
    'nest_xuegong_demo.sql',        # student profiles + counselor assignment
    'nest_community_demo.sql',      # P15 community (civil room / inspection / activity)
    'nest_culture_demo.sql',        # P17 volunteer + club (annual review / star rating)
    'nest_secondclass_demo.sql',    # P18 second-class transcript
    'nest_rank_demo.sql',           # P13 ranking source data + batches
    'nest_workstudy_demo.sql',      # work-study posts / applications
    'nest_aid_review_demo.sql',     # P5 financial aid
    'nest_honor_demo.sql',          # P12 honor application
    'nest_party_demo.sql',          # party membership stages
    'nest_psyche_work_demo.sql',    # P14 psychometrics / appointments
    'nest_xuegong_eval_demo.sql',   # evaluation scores
    'nest_loan_demo.sql'            # P19 student loan (optional, self-defined fields)
)

function Invoke-SqlFile([string]$Path) {
    $full = Join-Path $RepoRoot $Path
    if (-not (Test-Path $full)) { throw "SQL file not found: $Path" }
    # `<` redirection must go through cmd /c (PowerShell reserves `<`).
    cmd /c "`"$Mysql`" --defaults-extra-file=`"$MyCnf`" --default-character-set=utf8mb4 -D $Db -t < `"$full`""
    if ($LASTEXITCODE -ne 0) { throw "mysql failed ($LASTEXITCODE) on $Path" }
}

$failures = @()

Write-Host ('== demo-seed: repo={0} db={1} ==' -f $RepoRoot, $Db)

if (-not $SkipReset) {
    Write-Host '[1/3] reset (clear test residue)...'
    try { Invoke-SqlFile 'sql\nest_demo_reset.sql' } catch { $failures += $_; Write-Host ('  FAIL: {0}' -f $_) }
}

Write-Host '[2/3] load demo files...'
foreach ($f in $DemoFiles) {
    try {
        Invoke-SqlFile ('sql\' + $f)
        Write-Host ('  OK   {0}' -f $f)
    } catch {
        $failures += $_
        Write-Host ('  FAIL {0} : {1}' -f $f, $_.Exception.Message)
    }
}

Write-Host '[3/3] verify demo counts...'
try { Invoke-SqlFile 'tools\verify-demo.sql' } catch { $failures += $_; Write-Host ('  FAIL verify: {0}' -f $_) }

if ($failures.Count -gt 0) {
    Write-Host ('demo-seed finished with {0} failure(s).' -f $failures.Count) -ForegroundColor Red
    exit 1
}
Write-Host 'demo-seed: ALL DONE.' -ForegroundColor Green
exit 0
