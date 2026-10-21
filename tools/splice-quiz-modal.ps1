$ErrorActionPreference = 'Stop'
$f = 'D:\project\NEST\src\main\resources\static\teacher-course-detail.html'
$fragPath = 'D:\project\NEST\target\frag-quiz-modal.html'
$backup = 'D:\project\NEST\target\teacher-course-detail.backup-before-quiz-ui.html'

$t = [System.IO.File]::ReadAllText($f)
if (-not (Test-Path $backup)) { [System.IO.File]::WriteAllText($backup, $t, [System.Text.UTF8Encoding]::new($false)) }

$crlf = $t.Contains("`r`n")
Write-Output ("source: {0} bytes, {1} lines, CRLF={2}" -f $t.Length, ($t -split "`n").Count, $crlf)

$frag = [System.IO.File]::ReadAllText($fragPath)
$frag = $frag -replace "`r`n", "`n"
if ($crlf) { $frag = $frag -replace "`n", "`r`n" }

$pattern = '(?s)<!-- Quiz Create/Edit Modal -->.*?(?=<!-- AI Question Generation Modal -->)'
$hits = [regex]::Matches($t, $pattern).Count
Write-Output "pattern matches: $hits"
if ($hits -ne 1) { throw "expected exactly 1 match, got $hits" }

$repl = $frag.Replace('$', '$$')
$t2 = [regex]::Replace($t, $pattern, $repl)
[System.IO.File]::WriteAllText($f, $t2, [System.Text.UTF8Encoding]::new($false))
Write-Output ("result: {0} bytes, {1} lines (delta {2})" -f $t2.Length, ($t2 -split "`n").Count, ($t2.Length - $t.Length))

foreach ($id in @('quiz-modal','quiz-title','quiz-state-chip','quiz-total-score','quiz-nav-count','quiz-nav-progress','quiz-nav-settings','quiz-nav-list','ai-quiz-btn','quiz-settings-panel','quiz-question-panel','quiz-description','quiz-due-time','quiz-time-limit','quiz-allow-retake','quiz-max-attempts-wrap','quiz-max-attempts','quiz-shuffle','quiz-show-result')) {
  $c = ([regex]::Matches($t2, ('id="' + $id + '"'))).Count
  $flag = if ($c -eq 1) { 'ok' } else { 'FAIL' }
  Write-Output ("  {0,-24} {1}  {2}" -f $id, $c, $flag)
}
Write-Output ("closing quiz-modal div count: " + ([regex]::Matches($t2, 'id="quiz-modal"')).Count)
