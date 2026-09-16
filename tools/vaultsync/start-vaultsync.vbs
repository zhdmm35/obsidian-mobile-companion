' VaultSync hidden launcher template (called from Startup folder / shell:startup)
' ??????????? start-vaultsync.ps1 ???????
Set sh = CreateObject("Wscript.Shell")
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -File ""C:\path\to\vaultsync\start-vaultsync.ps1""", 0, False