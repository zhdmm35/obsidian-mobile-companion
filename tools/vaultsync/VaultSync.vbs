Set fs = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("Wscript.Shell")
folder = fs.GetParentFolderName(WScript.ScriptFullName)
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -File """ & folder & "\gui.ps1""", 0, False
