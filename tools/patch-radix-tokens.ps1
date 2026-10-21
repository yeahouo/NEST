# Move the remaining hand-picked colours in tools/webbuild/input.css onto the Radix
# token scale, so that no nx-* rule keeps a hard-coded accent colour.
#
# ASCII only on purpose: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, so a
# script with Chinese comments in it would fail to parse. The CSS comments that end up
# in input.css are written in Chinese by the edit tool, not by this script.
#
# Usage:  & D:\project\NEST\tools\patch-radix-tokens.ps1
# Every operation asserts how many times its pattern must occur; a mismatch writes
# nothing for that operation and is reported as FAIL at the end.

$ErrorActionPreference = 'Stop'
$path = 'D:\project\NEST\tools\webbuild\input.css'
$enc = [System.Text.UTF8Encoding]::new($false)
$text = [System.IO.File]::ReadAllText($path, $enc)
$fail = 0

function Patch {
  param([string]$Pattern, [string]$Replacement, [string]$Label, [int]$Expect = -1, [switch]$Literal)
  $rx = if ($Literal) { [regex]::Escape($Pattern) } else { $Pattern }
  $m = [regex]::Matches($script:text, $rx)
  if ($Expect -ge 0 -and $m.Count -ne $Expect) {
    if ($m.Count -eq 0) {
      # the operation already ran (this script is safe to run twice)
      Write-Output ("skip  {0}  (already applied)" -f $Label)
      return
    }
    Write-Output ("FAIL  {0}  (expected {1}, found {2})" -f $Label, $Expect, $m.Count)
    $script:fail++
    return
  }
  if ($m.Count -eq 0) { Write-Output ("skip  {0}  (0 occurrences)" -f $Label); return }
  $script:text = [regex]::Replace($script:text, $rx, $Replacement)
  Write-Output ("ok    {0}  ({1}x)" -f $Label, $m.Count)
}

# --- brand-tinted text that only appears inside .dark rules -> the bright brand step
Patch -Pattern '#9ec8ff' -Replacement 'var(--nx-brand-400)' -Label 'dark brand text' -Expect 4 -Literal
# --- the bright end of the two gradients -> the same bright step
Patch -Pattern '#7cc4ff' -Replacement 'var(--nx-brand-400)' -Label 'gradient bright end' -Expect 2 -Literal
# --- dark-mode chip text
Patch -Pattern '#bcd9ff' -Replacement 'var(--nx-brand-400)' -Label 'dark chip text' -Expect 1 -Literal
# --- a leftover tailwind-ish slate -> the Radix muted step
Patch -Pattern 'color: #94a3b8;' -Replacement 'color: var(--nx-muted);' -Label 'muted text' -Expect 1 -Literal
# --- translucent brand tints: was rgb(47,127,224), now indigo-9 rgb(62,99,221)
Patch -Pattern 'rgba\(47, 127, 224,' -Replacement 'rgba(62, 99, 221,' -Label 'brand tint' -Expect 15
# --- shadow tints: was rgb(7,81,166), now indigo-9
Patch -Pattern 'rgba\(7, 81, 166,' -Replacement 'rgba(62, 99, 221,' -Label 'brand shadow tint' -Expect 10
# --- ink tints: was rgb(15,23,42) (slate-900), now slate-12 rgb(28,32,36)
Patch -Pattern 'rgba\(15, 23, 42,' -Replacement 'rgba(28, 32, 36,' -Label 'ink shadow tint' -Expect 3
# --- dark-mode border tint: was rgb(147,197,253), now indigo-11 rgb(158,177,255)
Patch -Pattern 'rgba\(147, 197, 253,' -Replacement 'rgba(158, 177, 255,' -Label 'dark border tint'

[System.IO.File]::WriteAllText($path, $text, $enc)

# --- leftovers: any hex colour still sitting in the nx-* layer, for review
$left = [regex]::Matches($text, '#[0-9a-fA-F]{3,8}\b') | ForEach-Object { $_.Value.ToLower() } | Group-Object | Sort-Object Name
$leftStr = ($left | ForEach-Object { "$($_.Name) x$($_.Count)" }) -join ' '
Write-Output "--- remaining hex colours in input.css: $leftStr"
Write-Output ("failures: {0}" -f $fail)
if ($fail -gt 0) { exit 1 }
