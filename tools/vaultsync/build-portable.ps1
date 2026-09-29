# Package installed Node.js and Git for Windows; never include personal configuration.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$node = (Get-Command node).Source
$gitExe = (Get-Command git).Source
$gitRoot = Split-Path (Split-Path $gitExe)
if (!(Test-Path "$gitRoot/cmd/git.exe") -or !(Test-Path "$gitRoot/mingw64")) { throw 'Packaging requires a complete Git for Windows installation.' }
if (!(Test-Path 'node_modules/chokidar')) { throw 'Run npm ci before packaging.' }
$destination = Join-Path $PSScriptRoot ('dist/VaultSync-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path "$destination/runtime" -Force | Out-Null
foreach ($item in @('src', 'node_modules', 'gui.ps1', 'VaultSync.cmd', 'VaultSync.vbs', 'VaultSync-cli.cmd', 'start-vaultsync.ps1', 'package.json', 'package-lock.json', 'README.md')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $item) -Destination $destination -Recurse
}
Copy-Item -LiteralPath $node -Destination "$destination/runtime/node.exe"
New-Item -ItemType Directory -Path "$destination/runtime/git" -Force | Out-Null
foreach ($item in @('bin', 'cmd', 'etc', 'mingw64', 'usr', 'LICENSE.txt')) {
    Copy-Item -LiteralPath (Join-Path $gitRoot $item) -Destination "$destination/runtime/git" -Recurse
}
# Node's distribution license includes notices for its bundled dependencies.
$nodeRoot = Split-Path $node
$nodeLicense = Join-Path $nodeRoot 'LICENSE'
if (!(Test-Path $nodeLicense)) { $nodeLicense = Join-Path $nodeRoot 'LICENSE.txt' }
if (!(Test-Path $nodeLicense)) { throw 'Node distribution LICENSE is required for redistribution.' }
Copy-Item -LiteralPath $nodeLicense -Destination "$destination/runtime/NODE-LICENSE.txt"
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($destination, "$destination.zip", [System.IO.Compression.CompressionLevel]::Fastest, $true)
Write-Output "Portable package: $destination.zip"
