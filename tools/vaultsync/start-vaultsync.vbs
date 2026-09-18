' VaultSync hidden launcher - TEMPLATE (comments kept ASCII-only: wscript reads .vbs as ANSI)
' "npm run setup" generates the real launcher inside the Startup folder
' (shell:startup) with this machine's absolute path filled in - no manual editing needed.
' Manual use: replace the path below with your start-vaultsync.ps1 absolute path,
' then copy this file into the Startup folder.
Set sh = CreateObject("Wscript.Shell")
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -File ""C:\path\to\vaultsync\start-vaultsync.ps1""", 0, False
