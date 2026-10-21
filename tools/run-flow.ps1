param(
  [Parameter(Mandatory = $true)][string]$Flow,
  [Parameter(Mandatory = $true)][string]$Sid,
  [int]$Port = 9222,
  [string]$Out = '',
  [string]$Code = '',
  [string]$Phone = ''
)

# 跑一个受控流程：读 tools\flows\<Flow>.json，把 __SID__ 换成真会话，交给 probe.mjs 执行。
# 用法：& tools\run-flow.ps1 -Flow shared-ui -Sid <JSESSIONID>
$ErrorActionPreference = 'Continue'
$enc = [System.Text.UTF8Encoding]::new($false)
$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$flowPath = Join-Path $repo "tools\flows\$Flow.json"
if (-not (Test-Path $flowPath)) { Write-Output "找不到流程文件：$flowPath"; exit 1 }

$raw = [System.IO.File]::ReadAllText($flowPath, [System.Text.Encoding]::UTF8)
$raw = $raw.Replace('__SID__', $Sid).Replace('__PORT__', [string]$Port)
$raw = $raw.Replace('__CODE__', $Code).Replace('__PHONE__', $Phone)
$tmp = Join-Path $env:TEMP "nest-flow-$Flow.json"
[System.IO.File]::WriteAllText($tmp, $raw, $enc)

$log = @()
$log += "===== flow: $Flow"
$probeOut = & node (Join-Path $repo 'tools\probe.mjs') $tmp 2>&1
$log += $probeOut
$log | ForEach-Object { Write-Output $_ }
if ($Out) { $p = Join-Path $repo $Out; [System.IO.File]::AppendAllText($p, (($log -join [Environment]::NewLine) + [Environment]::NewLine), [System.Text.UTF8Encoding]::new($true)) }
