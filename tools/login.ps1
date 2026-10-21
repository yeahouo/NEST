param(
  [Parameter(Mandatory = $true)][string]$User,
  [string]$Pass = '123456',
  [int]$Port = 8080
)

# 登录并打印 JSESSIONID（只打印 SID 本身，方便赋值给变量）。
# 用 curl.exe + 无 BOM 的 JSON 文件：Invoke-RestMethod 会把中文解成乱码，cookie jar 在本服务上会 401。
$ErrorActionPreference = 'Stop'
$enc = [System.Text.UTF8Encoding]::new($false)
$body = Join-Path $env:TEMP 'nest-login-body.json'
$head = Join-Path $env:TEMP 'nest-login-head.txt'
$out = Join-Path $env:TEMP 'nest-login-out.json'
[System.IO.File]::WriteAllText($body, ('{"username":"' + $User + '","password":"' + $Pass + '"}'), $enc)
& curl.exe -s -D $head -o $out -H 'Content-Type: application/json' --data-binary "@$body" "http://127.0.0.1:$Port/api/auth/login" | Out-Null
$h = [System.IO.File]::ReadAllText($head)
$m = [regex]::Match($h, 'JSESSIONID=([0-9A-Fa-f]+)')
if (-not $m.Success) {
  Write-Output "LOGIN-FAILED $User :: $([System.IO.File]::ReadAllText($out))"
  exit 1
}
$sid = $m.Groups[1].Value
Write-Output $sid
