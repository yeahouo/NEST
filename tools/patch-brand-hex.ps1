# Replace the old hand-picked brand blue (#0751a6 / #2f7fe0 / rgb(7,81,166)) still written
# directly into the HTML pages with the Radix indigo steps the rest of the site now uses.
#
# The pages that carry nx-* tokens get their colour from --nx-*; this script is only about
# the handful of places where a colour is hard-coded inside a page: the static theme-color
# meta, the inline favicon/logo SVG, the dorm pages' own little style block, the QR code
# module colour and a couple of inline styles.
#
# ASCII only on purpose: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, so Chinese
# comments here would break parsing.
#
# Usage:  & D:\project\NEST\tools\patch-brand-hex.ps1
# Safe to run twice: an operation whose pattern is already gone reports "skip".
# Note: each operation writes the pages first and compares the hit count afterwards, so a
# FAIL line means "the count I expected was wrong", not "nothing was written" - always read
# the follow-up leftover check, do not retry blindly.

$ErrorActionPreference = 'Stop'
$dir = 'D:\project\NEST\src\main\resources\static'
$enc = [System.Text.UTF8Encoding]::new($false)
$fail = 0

function Patch {
  param([string]$Pattern, [string]$Replacement, [string]$Label, [int]$Expect = -1)
  $total = 0
  foreach ($f in (Get-ChildItem (Join-Path $dir '*.html'))) {
    $t = [System.IO.File]::ReadAllText($f.FullName, $enc)
    $rx = [regex]::Escape($Pattern)
    $n = [regex]::Matches($t, $rx).Count
    if ($n -eq 0) { continue }
    $t = [regex]::Replace($t, $rx, $Replacement)
    [System.IO.File]::WriteAllText($f.FullName, $t, $enc)
    $total += $n
  }
  if ($Expect -ge 0) {
    if ($total -eq $Expect) { Write-Output ("ok    {0}  ({1}x)" -f $Label, $total) }
    elseif ($total -eq 0) { Write-Output ("skip  {0}  (already applied)" -f $Label) }
    else { Write-Output ("FAIL  {0}  (expected {1}, found {2})" -f $Label, $Expect, $total); $script:fail++ }
  } else {
    Write-Output ("ok    {0}  ({1}x)" -f $Label, $total)
  }
}

# 1. the static theme-color meta on the four pages that have one -> indigo-9
Patch -Pattern '<meta content="#0751a6" name="theme-color"/>' -Replacement '<meta content="#3e63dd" name="theme-color"/>' -Label 'theme-color meta' -Expect 4
# 2. QR modules -> indigo-12, the high-contrast step of the same scale. A QR code wants a
#    dark module on white, and #3e63dd is lighter than the blue it replaces; step 12 keeps
#    the scannability the school actually depends on.
Patch -Pattern "dark: '#0751a6'," -Replacement "dark: '#1f2d5c'," -Label 'QR module colour' -Expect 2
# 3. the cover-selection outline -> ask the CSS token instead of hard-coding a blue
Patch -Pattern "btn.style.outline = '3px solid #0751a6';" -Replacement "btn.style.outline = '3px solid var(--nx-brand-500)';" -Label 'cover outline' -Expect 1
# 4. everything left: the inline favicon/logo SVG, the dorm style blocks, the file-preview link
Patch -Pattern '#0751a6' -Replacement '#3e63dd' -Label 'remaining brand blue' -Expect 24
# 5. the brighter end of the dashboard cover gradients -> indigo-8
Patch -Pattern '#2f7fe0' -Replacement '#8da4ef' -Label 'cover gradient light end' -Expect 2
# 6. brand tints written as rgb() instead of hex (the dorm pages' own style blocks and a few
#    inline shadows). 7 of them.
Patch -Pattern 'rgba(7,81,166,' -Replacement 'rgba(62,99,221,' -Label 'brand rgb tint' -Expect 7

Write-Output ("failures: {0}" -f $fail)
if ($fail -gt 0) { exit 1 }
