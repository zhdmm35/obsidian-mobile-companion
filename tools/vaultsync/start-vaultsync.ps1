# 用 cmd /c 追加写日志：PowerShell 5.1 的 >> 会写成 UTF-16，破坏纯文本日志
Set-Location $PSScriptRoot
if (Test-Path "$PSScriptRoot/runtime/git/cmd/git.exe") { $env:PATH = "$PSScriptRoot/runtime/git/cmd;$env:PATH" }
$node = if (Test-Path "$PSScriptRoot/runtime/node.exe") { "$PSScriptRoot/runtime/node.exe" } else { 'node' }
cmd /c "`"$node`" src\index.js >> vaultsync.log 2>> vaultsync.err.log"
