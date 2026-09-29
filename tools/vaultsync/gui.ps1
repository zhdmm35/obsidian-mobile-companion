param([string]$PreviewPath)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
Set-Location $PSScriptRoot
$runtime = Join-Path $PSScriptRoot 'runtime'
if (Test-Path "$runtime/node.exe") { $node = "$runtime/node.exe" } else { $node = (Get-Command node -ErrorAction SilentlyContinue).Source }
if (Test-Path "$runtime/git/cmd/git.exe") { $env:PATH = "$runtime/git/cmd;$env:PATH" }
if (!$node -or !(Get-Command git -ErrorAction SilentlyContinue)) {
    [System.Windows.Forms.MessageBox]::Show('缺少 Node.js 或 Git。请使用包含运行环境的 VaultSync 便携包；源码版需先安装 Node.js 和 Git。', 'VaultSync') | Out-Null
    exit 1
}
if (!(Test-Path "$PSScriptRoot/node_modules/chokidar")) {
    [System.Windows.Forms.MessageBox]::Show('源码版请先在 tools/vaultsync 执行 npm install。便携包应已包含依赖。', 'VaultSync') | Out-Null
    exit 1
}
$form = New-Object System.Windows.Forms.Form
$form.Text = 'VaultSync · 笔记自动同步'
$form.ClientSize = New-Object System.Drawing.Size(720, 700)
$form.StartPosition = 'CenterScreen'
$form.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 10)
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
function Label($text, $x, $y, $w = 670, $h = 28) {
    $c = New-Object System.Windows.Forms.Label
    $c.Text = $text; $c.SetBounds($x, $y, $w, $h); $form.Controls.Add($c)
}
function InputBox($x, $y, $w) {
    $c = New-Object System.Windows.Forms.TextBox
    $c.SetBounds($x, $y, $w, 28); $form.Controls.Add($c)
    return $c
}
$script:buttons = @()
function Button($text, $x, $y, $w, $handler) {
    $c = New-Object System.Windows.Forms.Button
    $c.Text = $text; $c.SetBounds($x, $y, $w, 34); $c.Add_Click($handler)
    $form.Controls.Add($c); $script:buttons += $c
}
Label '让电脑和手机使用同一个 GitHub 笔记仓库' 24 16
Label '1  选择 Obsidian 笔记文件夹' 24 54
$vault = InputBox 24 86 560
Button '选择…' 594 84 100 {
    $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
    $dialog.Description = '选择笔记目录；下载已有笔记时请选择空文件夹'
    $dialog.ShowNewFolderButton = $true
    if ($dialog.ShowDialog() -eq 'OK') { $vault.Text = $dialog.SelectedPath }
    $dialog.Dispose()
}
Label '2  连接 GitHub 仓库' 24 130
$mode = New-Object System.Windows.Forms.ComboBox
$mode.DropDownStyle = 'DropDownList'; $mode.SetBounds(24, 162, 670, 28)
$null = $mode.Items.Add('电脑已有笔记：连接空仓库 / 使用目录原有的仓库关联')
$null = $mode.Items.Add('从 GitHub 下载已有笔记：需要空文件夹')
$mode.SelectedIndex = 0; $form.Controls.Add($mode)
$repo = InputBox 24 200 670
Label '仓库地址：https://github.com/用户名/仓库名（已关联的目录可留空）' 24 232 670 25
Label '首次连接可能弹出 Git 登录窗口。不要把密码或令牌填入仓库地址。' 24 260 670 25
Label '3  提交者信息（已有 Git 配置可留空）' 24 296
Label '姓名' 24 332 60
$name = InputBox 80 328 215
Label '邮箱' 310 332 60
$email = InputBox 365 328 329
Label '默认：停止编辑 30 秒后上传，每 5 分钟获取手机更新。' 24 368
Label '启用后会上传目录内未被 Git 忽略的文件，请核对目录和仓库。' 24 396
Button '检查并保存配置' 24 432 190 { Run-Action 'configure' @{ vaultPath = $vault.Text; repository = $repo.Text; mode = @('existing', 'download')[$mode.SelectedIndex]; name = $name.Text; email = $email.Text } }
Button '4  启用并验证同步' 224 432 210 { Run-Action 'enable' }
Button '刷新状态' 444 432 115 { Run-Action 'status' }
Button '立即同步' 569 432 125 { Run-Action 'sync' }
Button '暂停' 24 480 100 { Run-Action 'pause' }
Button '恢复' 134 480 100 { Run-Action 'resume' }
Button '退出后台' 244 480 130 { Run-Action 'stop' }
Button '关闭开机自启' 384 480 170 { Run-Action 'startup-off' }
Button '打开日志' 564 480 130 { Start-Process notepad.exe -ArgumentList ('"' + "$PSScriptRoot/vaultsync.log" + '"') }
$output = New-Object System.Windows.Forms.TextBox
$output.Multiline = $true; $output.ReadOnly = $true; $output.ScrollBars = 'Vertical'
$output.SetBounds(24, 534, 670, 138); $form.Controls.Add($output)
$script:task = $null
$script:verifiedConfig = $false
function Show-State($state) {
    if ($state.config) {
        if (!$vault.Text) { $vault.Text = $state.config.vaultPath }
        if (!$repo.Text) { $repo.Text = $state.config.repository }
        $script:verifiedConfig = $vault.Text -eq $state.config.vaultPath -and (!$repo.Text -or $repo.Text -eq $state.config.repository)
    }
    $running = if ($state.paused) { '已暂停（当前同步会先完成）' } elseif ($state.running) { '后台运行中' } else { '后台未运行' }
    $startup = if ($state.startup) { '已开启' } else { '已关闭' }
    $lines = @("状态：$running    开机启动：$startup")
    if ($state.config) { $lines += "已保存目录：$($state.config.vaultPath)" }
    if ($state.result) {
        $time = ([DateTime]::Parse($state.result.time)).ToLocalTime().ToString('yyyy-MM-dd HH:mm:ss')
        if ($state.result.ok) { $lines += "最近同步成功：$time" }
        else { $lines += "最近同步失败：$time`r`n$($state.result.error)`r`n请检查网络、GitHub 登录或仓库冲突，修复后重试。" }
    } else { $lines += '尚未确认同步成功，请点击启用并验证同步。' }
    if ($state.error) { $lines += '请先完成上方配置。' }
    $output.Text = $lines -join "`r`n"
}
function Run-Action($action, $data = $null) {
    if ($script:task) { return }
    if ($action -eq 'enable' -and !$script:verifiedConfig) { $output.Text = '请先检查并保存配置。'; return }
    if ($action -eq 'configure') { $script:verifiedConfig = $false }
    $script:temp = Join-Path ([System.IO.Path]::GetTempPath()) ('vaultsync-gui-' + [guid]::NewGuid())
    [System.IO.Directory]::CreateDirectory($script:temp) | Out-Null
    $processArgs = @('"' + "$PSScriptRoot/src/manage.js" + '"', $action)
    if ($null -ne $data) {
        $inputFile = Join-Path $script:temp 'input.json'
        [System.IO.File]::WriteAllText($inputFile, ($data | ConvertTo-Json -Compress), (New-Object System.Text.UTF8Encoding($false)))
        $processArgs += '"' + $inputFile + '"'
    }
    $script:action = $action
    $script:task = Start-Process -FilePath $node -ArgumentList $processArgs -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$script:temp/out.txt" -RedirectStandardError "$script:temp/err.txt"
    foreach ($b in $script:buttons) { $b.Enabled = $false }
    foreach ($c in @($vault, $repo, $mode, $name, $email)) { $c.Enabled = $false }
    $output.Text = '正在处理，请稍候。首次连接可能需要完成 GitHub 登录；网络操作可能需要几分钟。'
}
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 300
$timer.Add_Tick({
    if (!$script:task -or !$script:task.HasExited) { return }
    $action = $script:action
    $result = $null
    try {
        $raw = [System.IO.File]::ReadAllText("$script:temp/out.txt", [System.Text.Encoding]::UTF8)
        $result = $raw | ConvertFrom-Json
        if (!$result.ok) { $output.Text = "操作未完成：$($result.error)`r`n认证失败请重新登录 GitHub；网络失败可重试。" }
        elseif ($action -eq 'configure') {
            $script:verifiedConfig = $true
            $output.Text = "配置检查通过。`r`n目录：$($result.data.vaultPath)`r`n仓库：$($result.data.repository)`r`n分支：$($result.data.branch)`r`n核对后点击启用并验证同步。"
        } else { Show-State $result.data }
    } catch { $output.Text = "无法读取操作结果：$($_.Exception.Message)" }
    finally {
        $script:task.Dispose(); $script:task = $null
        # 仅删除本次随机创建且已核对位于系统临时目录内的文件夹。
        $resolved = [System.IO.Path]::GetFullPath($script:temp)
        $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        if ($resolved.StartsWith($tempRoot) -and [System.IO.Path]::GetFileName($resolved).StartsWith('vaultsync-gui-')) { Remove-Item -LiteralPath $resolved -Recurse -Force }
        foreach ($b in $script:buttons) { $b.Enabled = $true }
        foreach ($c in @($vault, $repo, $mode, $name, $email)) { $c.Enabled = $true }
    }
    if ($action -eq 'enable' -and $result.ok) { Run-Action 'sync' }
})
$form.Add_FormClosing({
    if ($script:task) { $_.Cancel = $true; $output.Text += "`r`n请等待当前操作结束再关闭窗口。" }
})
foreach ($c in @($vault, $repo, $name, $email)) { $c.Add_TextChanged({ $script:verifiedConfig = $false }) }
$mode.Add_SelectedIndexChanged({ $script:verifiedConfig = $false })
if ($PreviewPath) {
    $form.Show(); $form.Refresh()
    $bitmap = New-Object System.Drawing.Bitmap($form.Width, $form.Height)
    $form.DrawToBitmap($bitmap, (New-Object System.Drawing.Rectangle(0, 0, $form.Width, $form.Height)))
    $bitmap.Save($PreviewPath); $bitmap.Dispose(); $form.Dispose()
} else {
    $timer.Start()
    $form.Add_Shown({ Run-Action 'status' })
    [System.Windows.Forms.Application]::Run($form)
    $timer.Dispose(); $form.Dispose()
}
