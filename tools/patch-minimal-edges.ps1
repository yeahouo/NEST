param([switch]$Apply)

# Round 11 "minimal edges" HTML patch: student pages stop carrying hand-written 1px borders.
# ASCII only: Windows PowerShell 5.1 reads a .ps1 without a BOM as ANSI, so Chinese in this
# script would be corrupted on the way back into the HTML files.
#
# All-or-nothing per file: every replacement must match its expected count, otherwise nothing
# is written for that file. Backups go to target\backup-minimal-edges\.

$root = 'D:\project\NEST\src\main\resources\static'
$backup = 'D:\project\NEST\target\backup-minimal-edges'
$enc = [System.Text.UTF8Encoding]::new($false)

# ---- replacement table -------------------------------------------------------------
# kind: 'input'  -> form control becomes a filled field (Material 3 filled, no line)
#       'button' -> secondary button becomes a soft tonal fill (shadcn secondary, no border)
#       'code'   -> big monospace code box becomes a filled field
$edits = @(
  @{ File = 'settings.html'; Kind = 'input'; Expect = 16
     Old = 'border border-primary/10 bg-white/50 dark:bg-slate-900/50'
     New = 'nx-input-fill' },

  @{ File = 'course-detail.html'; Kind = 'code'; Expect = 2
     Old = 'border-2 border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900'
     New = 'nx-input-fill' },
  @{ File = 'checkin.html'; Kind = 'code'; Expect = 1
     Old = 'border-2 border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900'
     New = 'nx-input-fill' },

  @{ File = 'course-detail.html'; Kind = 'button'; Expect = 1
     Old = 'rounded-lg border border-primary/20 text-primary text-sm font-bold hover:bg-primary/5'
     New = 'rounded-lg border-transparent bg-primary/10 text-primary text-sm font-bold hover:bg-primary/15' },
  @{ File = 'course-detail.html'; Kind = 'button'; Expect = 1
     Old = 'rounded-full text-sm font-bold border border-primary/20 text-primary hover:bg-primary/5'
     New = 'rounded-full text-sm font-bold border-transparent bg-primary/10 text-primary hover:bg-primary/15' },
  @{ File = 'course-detail.html'; Kind = 'button'; Expect = 1
     Old = 'rounded-full text-sm font-bold uppercase tracking-wider border border-primary/20 text-primary hover:bg-primary/5'
     New = 'rounded-full text-sm font-bold uppercase tracking-wider border-transparent bg-primary/10 text-primary hover:bg-primary/15' },
  @{ File = 'course-detail.html'; Kind = 'button'; Expect = 2
     Old = 'rounded-2xl border border-primary/10 hover:bg-primary/5 cursor-pointer transition-all'
     New = 'rounded-2xl border-transparent bg-primary/5 hover:bg-primary/10 cursor-pointer transition-all' },

  @{ File = 'index.html'; Kind = 'button'; Expect = 1
     Old = 'border-2 border-primary/20 bg-transparent text-primary text-lg font-bold tracking-tight hover:bg-primary/5'
     New = 'bg-primary/10 text-primary text-lg font-bold tracking-tight hover:bg-primary/15' }
)

# ---- verify every count first, then write -----------------------------------------
$groups = $edits | Group-Object { $_.File }
$problems = @()
$plan = @()

foreach ($g in $groups) {
  $path = Join-Path $root $g.Name
  $text = [System.IO.File]::ReadAllText($path)
  $total = 0
  foreach ($e in $g.Group) {
    $count = ([regex]::Matches($text, [regex]::Escape($e.Old))).Count
    $ok = ($count -eq $e.Expect)
    if (-not $ok) { $problems += ("{0}: expected {1} of [{2}] but found {3}" -f $g.Name, $e.Expect, $e.Kind, $count) }
    $total += $count
    "{0,-22} {1,-7} count={2,-3} expect={3,-3} {4}" -f $g.Name, $e.Kind, $count, $e.Expect, $(if ($ok) { 'ok' } else { 'MISMATCH' })
  }
  $plan += @{ Path = $path; Text = $text; Edits = $g.Group; Total = $total }
}

if ($problems.Count -gt 0) {
  Write-Output ''
  Write-Output 'NOTHING WRITTEN - count mismatch:'
  $problems | ForEach-Object { Write-Output ("  " + $_) }
  exit 1
}

if (-not $Apply) {
  Write-Output ''
  Write-Output 'dry run only (pass -Apply to write)'
  exit 0
}

if (-not (Test-Path $backup)) { New-Item -ItemType Directory -Path $backup -Force | Out-Null }

foreach ($p in $plan) {
  [System.IO.File]::WriteAllText((Join-Path $backup (Split-Path -Leaf $p.Path)), $p.Text, $enc)
  $new = $p.Text
  foreach ($e in $p.Edits) { $new = $new.Replace($e.Old, $e.New) }
  if ($new -eq $p.Text) { Write-Output ("unchanged: " + $p.Path); continue }
  [System.IO.File]::WriteAllText($p.Path, $new, $enc)
  Write-Output ("wrote {0} : {1} replacements, {2} -> {3} bytes" -f (Split-Path -Leaf $p.Path), $p.Total, $p.Text.Length, $new.Length)
}
Write-Output ''
Write-Output 'backups: target\backup-minimal-edges\'
