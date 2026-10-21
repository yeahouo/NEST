$ErrorActionPreference = 'Stop'
$f = 'D:\project\NEST\src\main\resources\static\teacher-course-detail.html'
$backup = 'D:\project\NEST\target\teacher-course-detail.backup-before-quiz-js.html'

$t = [System.IO.File]::ReadAllText($f)
if (-not (Test-Path $backup)) { [System.IO.File]::WriteAllText($backup, $t, [System.Text.UTF8Encoding]::new($false)) }
Write-Output ("source: {0} bytes, {1} lines" -f $t.Length, ($t -split "`n").Count)

function Splice([string]$text, [string]$pattern, [string]$fragPath, [string]$label) {
  $frag = [System.IO.File]::ReadAllText($fragPath)
  $frag = $frag -replace "`r`n", "`n"
  if ($text.Contains("`r`n")) { $frag = $frag -replace "`n", "`r`n" }
  $n = [regex]::Matches($text, $pattern).Count
  if ($n -ne 1) { throw "$label : expected 1 match, got $n" }
  $out = [regex]::Replace($text, $pattern, $frag.Replace('$', '$$'))
  Write-Host ("  {0}: 1 -> done (delta {1} bytes)" -f $label, ($out.Length - $text.Length))
  return $out
}

$t = Splice $t '(?s)    function renderQuizzes\(\) \{.*?(?=    function openQuizModal\(quizId\) \{)' 'D:\project\NEST\target\frag-quiz-js-a.js' 'renderQuizzes'
$t = Splice $t '(?s)    // Render the full quiz editor: sidebar nav \+ current step content.*?(?=    function toggleMultiCorrect\(qIdx, letter\) \{)' 'D:\project\NEST\target\frag-quiz-js-b.js' 'renderQuizEditor'

# 载入 vendor 的两个库：SortableJS（UMD，全局 Sortable）与 auto-animate（ESM 包装）
$scriptTags = "  <script defer src=`"/libs/sortablejs/Sortable.min.js`"></script>`n  <script type=`"module`" src=`"/js/nx-quiz-vendor.js`"></script>`n"
$anchor = '  <script defer src="/js/nest-ui.js"></script>'
if ($t.Contains($anchor)) {
  if (-not $t.Contains('/libs/sortablejs/Sortable.min.js')) {
    $t = $t.Replace($anchor, $scriptTags + $anchor)
    Write-Output '  vendor script tags: inserted before nest-ui.js'
  } else { Write-Output '  vendor script tags: already present' }
} else {
  throw 'anchor <script defer src="/js/nest-ui.js"></script> not found'
}

[System.IO.File]::WriteAllText($f, $t, [System.Text.UTF8Encoding]::new($false))
Write-Output ("result: {0} bytes, {1} lines" -f $t.Length, ($t -split "`n").Count)

foreach ($needle in @('function renderQuizzes()','function renderQuizEditor()','syncQuizChrome','toggleSingleCorrect','initQuizSortable','initQuizAutoAnimate','nx-qed-item','/libs/sortablejs/Sortable.min.js','/js/nx-quiz-vendor.js')) {
  $c = ([regex]::Matches($t, [regex]::Escape($needle))).Count
  Write-Output ("  {0,-38} {1}" -f $needle, $c)
}
