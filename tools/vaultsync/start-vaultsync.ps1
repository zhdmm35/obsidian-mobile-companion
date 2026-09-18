# 用 cmd /c 追加写日志：PowerShell 5.1 的 >> 会写成 UTF-16，破坏纯文本日志
Set-Location $PSScriptRoot
cmd /c "node src\index.js >> vaultsync.log 2>> vaultsync.err.log"