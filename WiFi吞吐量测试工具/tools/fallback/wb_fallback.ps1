# =============================================================================
#  WiFi / 热点 吞吐量测试工具 —— PowerShell 降级引擎（wb_fallback.ps1）
#
#  只在收件人电脑上找不到 Python 时，由 启动.bat 调用本脚本：
#      powershell -NoProfile -ExecutionPolicy Bypass -File "...\tools\fallback\wb_fallback.ps1" %*
#
#  本脚本产出的 4 个文件与 Python 主引擎（tools\wb\*.py）完全同构：
#      逐秒明细.csv / 汇总.json / 测试报告.md / 原始输出_<test_id>.txt
#
#  兼容 Windows PowerShell 5.1：不使用 ?? / ?. / 三元运算符 / -Parallel /
#  ConvertFrom-Json -AsHashtable；只依赖 .NET Framework 自带类，不加载任何模块。
#  本文件必须保存为 UTF-8 带 BOM（无 BOM 时 PS 5.1 会按 GBK 解码中文变乱码）。
# =============================================================================

$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"

$script:LF = [string][char]10
$script:CRLF = [string][char]13 + [string][char]10
$script:BT = [string][char]96
$script:TOOLS_DIR = Split-Path -Parent $PSScriptRoot
$script:ROOT_DIR = Split-Path -Parent $script:TOOLS_DIR
if ([string]::IsNullOrEmpty($script:ROOT_DIR)) { $script:ROOT_DIR = $script:TOOLS_DIR }

# ---------------------------------------------------------------- 常量
$script:ENGINE_VERSION = "1.0"
$script:SCRIPT_VERSION = "1.0.0"
$script:TOOL_NAME = "WiFi 吞吐量测试工具"
$script:PHONE_BIN = "/data/local/tmp/iperf3"
$script:SRV_LOG = "/data/local/tmp/iperf_srv.log"
$script:DONE_MARK = "iperf Done."
$script:GOOD_VERSION = "iperf 3.2"
$script:LOCAL_BIN_BYTES = 7465336
$script:LOCAL_BIN = Join-Path $script:ROOT_DIR "bin\iperf3-arm64v8"
$script:DEFAULT_OUT = Join-Path $script:ROOT_DIR "输出"
$script:EMPTY = "--"

$script:CSV_HEADER = @("时间", "测试", "类型", "线程", "起", "止", "传输量", "单位", "速率", "速率单位", "重传或数据报", "抖动ms", "丢包", "总包", "丢包率", "方向")

$script:TestNames = @{
    "tcp_up" = "WiFi 吞吐量（TCP 正向）"
    "tcp_down" = "WiFi 吞吐量（TCP 反向 -R）"
    "udp_up" = "UDP 抖动丢包（正向）"
    "udp_down" = "UDP 抖动丢包（反向 -R）"
    "long_run" = "长稳吞吐测试"
    "screen_on" = "亮屏吞吐"
    "screen_off" = "灭屏吞吐"
}
$script:ItemNames = @{
    "wifi_tput" = "WiFi 吞吐量测试"
    "hotspot_tput" = "热点吞吐量测试"
    "screen_cmp" = "亮灭屏对比测试"
    "long_run" = "长稳吞吐测试"
    "udp_qos" = "UDP 抖动丢包测试"
    "link_snapshot" = "链路质量快照"
    "pc_tunnel" = "PC 参与的隧道测试"
}
$script:SupportedItems = @("wifi_tput", "hotspot_tput")
$script:UnsupportedItems = @("screen_cmp", "long_run", "udp_qos", "link_snapshot", "pc_tunnel")
$script:UnitShort = @{ "Bytes" = "B"; "KBytes" = "KB"; "MBytes" = "MB"; "GBytes" = "GB" }
$script:RateShort = @{ "bits/sec" = "b/s"; "Kbits/sec" = "Kb/s"; "Mbits/sec" = "Mb/s"; "Gbits/sec" = "Gb/s"; "Tbits/sec" = "Tb/s" }
$script:BatteryStatus = @{ 1 = "未知"; 2 = "充电中"; 3 = "放电中"; 4 = "未充电"; 5 = "已充满" }
$script:HotspotIfaces = @("wlan1", "softap0", "ap_br_wlan1", "ap_br_softap0", "swlan0", "ap0")
$script:Factors = @{
    "bits/sec" = 1e-6; "bit/sec" = 1e-6; "bps" = 1e-6; "bits/s" = 1e-6
    "kbits/sec" = 1e-3; "kbit/sec" = 1e-3; "kbps" = 1e-3; "kbits/s" = 1e-3
    "mbits/sec" = 1.0; "mbit/sec" = 1.0; "mbps" = 1.0; "mbits/s" = 1.0; "mbit/s" = 1.0
    "gbits/sec" = 1e3; "gbit/sec" = 1e3; "gbps" = 1e3; "gbits/s" = 1e3; "gbit/s" = 1e3
    "tbits/sec" = 1e6; "tbit/sec" = 1e6; "tbps" = 1e6
}

$script:Inv = [System.Globalization.CultureInfo]::InvariantCulture
$script:RowRe = '^\[\s*(?<stream>SUM|\d+)\]\s+(?<start>\d+(?:\.\d+)?)-(?<end>\d+(?:\.\d+)?)\s+sec\s+(?<transfer>\d+(?:\.\d+)?)\s+(?<unit>Bytes|KBytes|MBytes|GBytes)\s+(?<rate>\d+(?:\.\d+)?)\s+(?<rate_unit>[KMGT]?bits/sec)(?<rest>.*)$'
$script:LossRe = '^(\d+)/(\d+)$'
$script:PctRe = '^\((\d+(?:\.\d+)?)%\)$'
$script:IntRe = '^\d+$'
$script:FloatRe = '^\d+(?:\.\d+)?$'

# =============================================================================
#  二、控制台输出（前缀与 Python 引擎 runner.py 一致）
# =============================================================================

function Out-Line {
    param([string]$Text = "")
    [Console]::Out.WriteLine($Text)
}

function Out-Ok {
    param([string]$Text)
    Out-Line ("      OK  " + $Text)
}

function Out-Warn {
    param([string]$Text)
    Out-Line ("      !!  " + $Text)
}

function Out-Info {
    param([string]$Text)
    Out-Line ("      --  " + $Text)
}

function Out-Err {
    param([string]$Text)
    Out-Line ("      XX  " + $Text)
}

function Out-Step {
    param([int]$Index, [int]$Total, [string]$Text)
    Out-Line ("[{0}/{1}] {2} ..." -f $Index, $Total, $Text)
}

function Out-Rule {
    param([string]$Title = "")
    Out-Line ""
    Out-Line ("=" * 66)
    if (-not [string]::IsNullOrEmpty($Title)) { Out-Line (" " + $Title) }
    Out-Line ("=" * 66)
}

function Out-Banner {
    Out-Line ""
    Out-Line ("  " + ("=" * 62))
    Out-Line ("      WiFi / 热点 吞吐量测试工具   v" + $script:ENGINE_VERSION)
    Out-Line ("  " + ("=" * 62))
    Out-Line ""
    Out-Line "  测真实空口吞吐：两台手机一台做服务端、一台做客户端，用 iperf3 打流。"
    Out-Line "  本机未安装 Python，当前使用 PowerShell 降级引擎（仅支持 WiFi / 热点吞吐量）。"
    Out-Line ""
}

# 三段式中文错误：绝不把 PowerShell 堆栈直接丢给最终用户
function Show-Die {
    param([string]$Title, [string]$Reason, [string]$Fix, [int]$Code = 1)
    Out-Line ""
    Out-Line ("  [错误] " + $Title)
    Out-Line ("  原因  ：" + $Reason)
    Out-Line ("  处理办法：" + $Fix)
    Out-Line ""
    return $Code
}

# =============================================================================
#  三、数值与文本格式化（对齐 report.py 的 _f / _num / _f2 / _s / _t）
# =============================================================================

function ConvertTo-Number {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [bool]) { return $null }
    if ($Value -is [int] -or $Value -is [long] -or $Value -is [double] -or $Value -is [decimal]) {
        $dv = [double]$Value
        if ([double]::IsNaN($dv) -or [double]::IsInfinity($dv)) { return $null }
        return $dv
    }
    $s = ([string]$Value).Trim()
    if ($s -eq "") { return $null }
    $d = 0.0
    if ([double]::TryParse($s, [System.Globalization.NumberStyles]::Float, $script:Inv, [ref]$d)) {
        if ([double]::IsNaN($d) -or [double]::IsInfinity($d)) { return $null }
        return $d
    }
    return $null
}

function Format-Num {
    param($Value, [int]$Digits = 3)
    if ($Value -is [bool]) { if ($Value) { return "1" } else { return "0" } }
    $x = ConvertTo-Number $Value
    if ($null -eq $x) {
        if ($Value -is [string]) { return [string]$Value }
        return ""
    }
    if ([math]::Abs($x - [math]::Round($x)) -lt 1e-9) {
        return ([long][math]::Round($x)).ToString($script:Inv)
    }
    return $x.ToString("F" + [string]$Digits, $script:Inv)
}

function Format-F2 {
    param($Value)
    $x = ConvertTo-Number $Value
    if ($null -eq $x) { return "" }
    return $x.ToString("F2", $script:Inv)
}

function Format-S {
    param($Value)
    if ($null -eq $Value) { return "" }
    if ($Value -is [string]) { return $Value }
    if ($Value -is [bool]) { if ($Value) { return "1" } else { return "0" } }
    if ($Value -is [int] -or $Value -is [long] -or $Value -is [double] -or $Value -is [decimal]) {
        return (Format-Num $Value)
    }
    return [string]$Value
}

function Format-T {
    param($Value, [string]$Default = "--")
    if ($null -eq $Value) { return $Default }
    if ($Value -is [string]) {
        if ($Value.Trim() -eq "") { return $Default }
        return $Value
    }
    if ($Value -is [bool]) { if ($Value) { return "是" } else { return "否" } }
    $s = Format-Num $Value
    if ($s -eq "") { return $Default }
    return $s
}

# 显示宽度：CJK / 全角按 2 列（对齐 Python 的 east_asian_width in ("W","F")）
function Get-Width {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return 0 }
    $w = 0
    foreach ($ch in $Text.ToCharArray()) {
        $c = [int]$ch
        if (($c -ge 0x1100 -and $c -le 0x115F) -or
            ($c -ge 0x2E80 -and $c -le 0x303E) -or
            ($c -ge 0x3041 -and $c -le 0x33FF) -or
            ($c -ge 0x3400 -and $c -le 0x4DBF) -or
            ($c -ge 0x4E00 -and $c -le 0x9FFF) -or
            ($c -ge 0xA000 -and $c -le 0xA4CF) -or
            ($c -ge 0xAC00 -and $c -le 0xD7A3) -or
            ($c -ge 0xF900 -and $c -le 0xFAFF) -or
            ($c -ge 0xFE30 -and $c -le 0xFE6F) -or
            ($c -ge 0xFF00 -and $c -le 0xFF60) -or
            ($c -ge 0xFFE0 -and $c -le 0xFFE6)) {
            $w += 2
        } else {
            $w += 1
        }
    }
    return $w
}

function Get-Clip {
    param([string]$Text, [int]$Width)
    if ($Width -le 0) { return "" }
    $sb = New-Object System.Text.StringBuilder
    $used = 0
    foreach ($ch in $Text.ToCharArray()) {
        $cw = Get-Width ([string]$ch)
        if ($used + $cw -gt $Width) { break }
        [void]$sb.Append($ch)
        $used += $cw
    }
    return $sb.ToString()
}

function Get-PadRight {
    param([string]$Text, [int]$Width)
    if ($null -eq $Text) { $Text = "" }
    $cur = Get-Width $Text
    if ($cur -gt $Width) { return (Get-Clip $Text $Width) }
    return ($Text + (" " * ($Width - $cur)))
}

function Get-UnitFactor {
    param([string]$Unit)
    $k = ([string]$Unit).Trim().ToLower().Replace(" ", "")
    if ($script:Factors.ContainsKey($k)) { return [double]$script:Factors[$k] }
    return 1.0
}

function Format-Bps {
    param($Rate, [string]$Unit = "Mbits/sec")
    $x = ConvertTo-Number $Rate
    if ($null -eq $x) { return "--" }
    $v = $x * (Get-UnitFactor $Unit)
    return ($v.ToString("F1", $script:Inv) + " Mbits/sec")
}

# =============================================================================
#  四、文件写出（CSV=BOM+CRLF / JSON=无BOM+LF / MD=无BOM+LF / raw=无BOM+LF）
# =============================================================================

function Save-TextFile {
    param([string]$Path, [string]$Text, [bool]$Bom)
    $enc = New-Object System.Text.UTF8Encoding($Bom)
    [System.IO.File]::WriteAllText($Path, $Text, $enc)
}

# =============================================================================
#  五、极简 JSON 序列化（2 空格缩进、不转义中文、末尾换行）
# =============================================================================

function ConvertTo-JsonString {
    param([string]$Text)
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append([char]34)
    foreach ($ch in $Text.ToCharArray()) {
        $c = [int]$ch
        if ($c -eq 34) { [void]$sb.Append('\"') }
        elseif ($c -eq 92) { [void]$sb.Append('\\') }
        elseif ($c -eq 8) { [void]$sb.Append('\b') }
        elseif ($c -eq 12) { [void]$sb.Append('\f') }
        elseif ($c -eq 10) { [void]$sb.Append('\n') }
        elseif ($c -eq 13) { [void]$sb.Append('\r') }
        elseif ($c -eq 9) { [void]$sb.Append('\t') }
        elseif ($c -lt 32) { [void]$sb.Append(('\u' + $c.ToString("x4"))) }
        else { [void]$sb.Append($ch) }
    }
    [void]$sb.Append([char]34)
    return $sb.ToString()
}

function ConvertTo-JsonNumber {
    param($Value)
    if ($Value -is [int] -or $Value -is [long]) { return ([string]$Value) }
    $d = [double]$Value
    if ([double]::IsNaN($d) -or [double]::IsInfinity($d)) { return "null" }
    $s = $d.ToString("R", $script:Inv)
    if ($s.IndexOf(".") -lt 0 -and $s.IndexOf("E") -lt 0 -and $s.IndexOf("e") -lt 0) { $s = $s + ".0" }
    return $s
}

function Get-JsonKeys {
    param($Value)
    if ($Value -is [System.Collections.Specialized.OrderedDictionary]) {
        $keys = @()
        foreach ($k in $Value.Keys) { $keys += [string]$k }
        return $keys
    }
    if ($Value -is [System.Collections.IDictionary]) {
        $keys = @()
        foreach ($k in $Value.Keys) { $keys += [string]$k }
        return $keys
    }
    return @()
}

function ConvertTo-JsonText {
    param($Value, [int]$Level = 0)
    $ind = "  " * $Level
    $ind2 = "  " * ($Level + 1)
    if ($null -eq $Value) { return "null" }
    if ($Value -is [bool]) { if ($Value) { return "true" } else { return "false" } }
    if ($Value -is [int] -or $Value -is [long] -or $Value -is [double] -or $Value -is [decimal]) {
        return (ConvertTo-JsonNumber $Value)
    }
    if ($Value -is [string]) { return (ConvertTo-JsonString $Value) }

    if ($Value -is [System.Collections.IDictionary]) {
        $keys = Get-JsonKeys $Value
        if ($keys.Count -eq 0) { return "{}" }
        $parts = @()
        foreach ($k in $keys) {
            $parts += ($ind2 + (ConvertTo-JsonString $k) + ": " + (ConvertTo-JsonText $Value[$k] ($Level + 1)))
        }
        return ("{" + $script:LF + ($parts -join ("," + $script:LF)) + $script:LF + $ind + "}")
    }

    if ($Value -is [System.Collections.IEnumerable]) {
        $items = @()
        foreach ($it in $Value) { $items += $it }
        if ($items.Count -eq 0) { return "[]" }
        $parts = @()
        foreach ($it in $items) {
            $parts += ($ind2 + (ConvertTo-JsonText $it ($Level + 1)))
        }
        return ("[" + $script:LF + ($parts -join ("," + $script:LF)) + $script:LF + $ind + "]")
    }

    return (ConvertTo-JsonString ([string]$Value))
}

# =============================================================================
#  六、外部进程调用（全部带超时，禁止无界阻塞）
# =============================================================================

function Quote-Arg {
    param([string]$Text)
    if ($null -eq $Text) { return '""' }
    if ($Text -eq "") { return '""' }
    if ($Text -match '[\s"]') {
        return ('"' + ($Text -replace '"', '\"') + '"')
    }
    return $Text
}

# 一次性捕获输出（有超时上限）
function Invoke-Capture {
    param([string]$FilePath, [string]$Arguments, [int]$TimeoutSec = 30)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = $Arguments
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $timedOut = $false
    try {
        [void]$proc.Start()
    } catch {
        return @{ rc = -1; out = ""; err = ([string]$_.Exception.Message); timedOut = $false; failed = $true }
    }
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
        $timedOut = $true
        try { $proc.Kill() } catch { }
        try { [void]$proc.WaitForExit(3000) } catch { }
    }
    $o = ""
    $e = ""
    try { $o = [string]$outTask.Result } catch { $o = "" }
    try { $e = [string]$errTask.Result } catch { $e = "" }
    $rc = -1
    try { $rc = $proc.ExitCode } catch { $rc = -1 }
    try { $proc.Dispose() } catch { }
    return @{ rc = $rc; out = $o; err = $e; timedOut = $timedOut; failed = $false }
}

# 逐行流式执行：每读到一行立刻回调 OnLine，绝不先整段捕获再输出。
function Invoke-Stream {
    param([string]$FilePath, [string]$Arguments, [int]$TimeoutSec, [scriptblock]$OnLine)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = $Arguments
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $lines = New-Object System.Collections.ArrayList
    $timedOut = $false
    try {
        [void]$proc.Start()
    } catch {
        return @{ rc = -1; lines = @(); timedOut = $false; failed = $true; err = ([string]$_.Exception.Message) }
    }
    $outTask = $proc.StandardOutput.ReadLineAsync()
    $errTask = $proc.StandardError.ReadLineAsync()
    $outDone = $false
    $errDone = $false
    $deadline = (Get-Date).AddSeconds($TimeoutSec)

    while (-not ($outDone -and $errDone)) {
        $busy = $false
        if ((-not $outDone) -and $outTask.IsCompleted) {
            $busy = $true
            $ln = $null
            $bad = $false
            try { $ln = $outTask.Result } catch { $bad = $true }
            if ($bad -or ($null -eq $ln)) {
                $outDone = $true
            } else {
                [void]$lines.Add([string]$ln)
                if ($null -ne $OnLine) { & $OnLine ([string]$ln) }
                $outTask = $proc.StandardOutput.ReadLineAsync()
            }
        }
        if ((-not $errDone) -and $errTask.IsCompleted) {
            $busy = $true
            $ln2 = $null
            $bad2 = $false
            try { $ln2 = $errTask.Result } catch { $bad2 = $true }
            if ($bad2 -or ($null -eq $ln2)) {
                $errDone = $true
            } else {
                [void]$lines.Add([string]$ln2)
                if ($null -ne $OnLine) { & $OnLine ([string]$ln2) }
                $errTask = $proc.StandardError.ReadLineAsync()
            }
        }
        if (-not $busy) {
            if ((Get-Date) -gt $deadline) {
                $timedOut = $true
                try { $proc.Kill() } catch { }
                break
            }
            Start-Sleep -Milliseconds 15
        }
    }
    try { [void]$proc.WaitForExit(5000) } catch { }
    $rc = -1
    try { $rc = $proc.ExitCode } catch { $rc = -1 }
    try { $proc.Dispose() } catch { }
    return @{ rc = $rc; lines = @($lines); timedOut = $timedOut; failed = $false; err = "" }
}

# =============================================================================
#  七、adb 封装
# =============================================================================

function Find-Adb {
    $cands = @()
    $cands += (Join-Path $env:ProgramFiles "ADB\adb.exe")
    $cands += "C:\Program Files\ADB\adb.exe"
    if (-not [string]::IsNullOrEmpty($env:ADB)) { $cands += $env:ADB }
    $cands += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    foreach ($c in $cands) {
        if ([string]::IsNullOrEmpty($c)) { continue }
        if (Test-Path -LiteralPath $c) { return $c }
    }
    return ""
}

function Get-AdbPath {
    if (-not [string]::IsNullOrEmpty($script:AdbPath)) { return $script:AdbPath }
    $found = Find-Adb
    if ($found -eq "") {
        $tried = "C:\Program Files\ADB\adb.exe、%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe、环境变量 ADB"
        throw ("找不到 adb.exe|已尝试：{0}|安装 platform-tools 并把 adb.exe 放到 C:\Program Files\ADB\，或设环境变量 ADB 指向它" -f $tried)
    }
    $script:AdbPath = $found
    return $found
}

function Invoke-Adb {
    param([string]$Arguments, [int]$TimeoutSec = 30)
    $adb = Get-AdbPath
    return (Invoke-Capture -FilePath $adb -Arguments $Arguments -TimeoutSec $TimeoutSec)
}

function Invoke-AdbShell {
    param([string]$Serial, [string]$Command, [int]$TimeoutSec = 30)
    $argline = "-s " + (Quote-Arg $Serial) + " shell " + (Quote-Arg $Command)
    return (Invoke-Adb -Arguments $argline -TimeoutSec $TimeoutSec)
}

function Invoke-AdbShellOut {
    param([string]$Serial, [string]$Command, [int]$TimeoutSec = 30)
    $r = Invoke-AdbShell -Serial $Serial -Command $Command -TimeoutSec $TimeoutSec
    if ($r.timedOut) { return "" }
    return [string]$r.out
}

function Get-Devices {
    $r = Invoke-Adb -Arguments "devices -l" -TimeoutSec 25
    if ($r.timedOut) {
        throw ("读取设备列表超时|adb devices 超过 25 秒没有返回|拔插 USB 线后重试；或先手动执行 adb kill-server 再重试")
    }
    if ($r.rc -ne 0) {
        $msg = ([string]$r.err).Trim()
        if ($msg -eq "") { $msg = ([string]$r.out).Trim() }
        throw ("adb 无法列出设备|{0}|确认手机已开启 USB 调试并授权本机；必要时先运行 adb kill-server" -f $msg)
    }
    $list = @()
    foreach ($line in ([string]$r.out -split "[\r\n]+")) {
        $s = $line.Trim()
        if ($s -eq "") { continue }
        if ($s -like "List of devices*") { continue }
        if ($s -like "*daemon*") { continue }
        $parts = $s -split "\s+"
        if ($parts.Count -lt 2) { continue }
        if ($parts[1] -ne "device") { continue }
        $model = ""
        foreach ($p in $parts) {
            if ($p -like "model:*") { $model = $p.Substring(6) }
        }
        $list += @{ serial = $parts[0]; model = $model }
    }
    return $list
}

# =============================================================================
#  八、设备信息采集（与 devinfo.py 的采集命令与解析规则一致）
# =============================================================================

function New-Device {
    param([string]$Serial)
    $d = New-Object System.Collections.Specialized.OrderedDictionary
    $d["serial"] = $Serial
    $d["model"] = ""
    $d["brand"] = ""
    $d["device"] = ""
    $d["android"] = ""
    $d["sdk"] = ""
    $d["abi"] = ""
    $d["hardware"] = ""
    $d["fingerprint"] = ""
    $d["screen"] = "Unknown"
    $bat = New-Object System.Collections.Specialized.OrderedDictionary
    $bat["level"] = $null
    $bat["status"] = ""
    $bat["temp_c"] = $null
    $bat["voltage_mv"] = $null
    $d["battery"] = $bat
    $wifi = New-Object System.Collections.Specialized.OrderedDictionary
    $wifi["enabled"] = $null
    $wifi["connected"] = $null
    $wifi["ssid"] = ""
    $wifi["bssid"] = ""
    $wifi["freq_mhz"] = $null
    $wifi["band"] = ""
    $wifi["rssi"] = $null
    $wifi["link_tx_mbps"] = $null
    $wifi["link_rx_mbps"] = $null
    $wifi["standard"] = ""
    $wifi["ip"] = ""
    $d["wifi"] = $wifi
    $hs = New-Object System.Collections.Specialized.OrderedDictionary
    $hs["on"] = $false
    $hs["ssid"] = ""
    $hs["iface"] = ""
    $hs["ip"] = ""
    $hs["freq_mhz"] = $null
    $hs["clients"] = $null
    $d["hotspot"] = $hs
    $d["addrs"] = @()
    $d["ifcfg"] = @()
    $d["ifaces"] = @()
    $dr = New-Object System.Collections.Specialized.OrderedDictionary
    $dr["gw"] = ""
    $dr["iface"] = ""
    $d["default_route"] = $dr
    $d["ram_mb"] = $null
    $d["cores"] = $null
    $d["storage_free_gb"] = $null
    $d["uptime_s"] = 0
    $ipf = New-Object System.Collections.Specialized.OrderedDictionary
    $ipf["path"] = $script:PHONE_BIN
    $ipf["version"] = ""
    $ipf["ok"] = $false
    $d["iperf"] = $ipf
    return $d
}

function Split-Sections {
    param([string]$Text)
    $sec = @{}
    $cur = ""
    foreach ($line in ($Text -split "[\r\n]+")) {
        if ($line -match '^#WB:([A-Z]+)\s*$') {
            $cur = $matches[1]
            if (-not $sec.ContainsKey($cur)) { $sec[$cur] = New-Object System.Collections.ArrayList }
            continue
        }
        if ($cur -ne "") { [void]$sec[$cur].Add($line) }
    }
    return $sec
}

function Get-SectionText {
    param($Sections, [string]$Name)
    if ($null -eq $Sections) { return "" }
    if (-not $Sections.ContainsKey($Name)) { return "" }
    return (@($Sections[$Name]) -join $script:LF)
}

function Get-BandName {
    param($Freq)
    $f = ConvertTo-Number $Freq
    if ($null -eq $f -or $f -le 0) { return "" }
    if ($f -lt 3000) { return "2.4GHz" }
    if ($f -ge 5925) { return "6GHz" }
    return "5GHz"
}

function Get-ChannelNumber {
    param($Freq)
    $f = ConvertTo-Number $Freq
    if ($null -eq $f -or $f -le 0) { return 0 }
    if ([int]$f -eq 2484) { return 14 }
    if ($f -lt 3000) { return [int](($f - 2412) / 5 + 1) }
    if ($f -ge 5925) { return [int](($f - 5950) / 5) }
    return [int](($f - 5000) / 5)
}

function Read-DeviceInfo {
    param([string]$Serial)
    $d = New-Device -Serial $Serial

    # ---- 身份信息
    $propText = Invoke-AdbShellOut -Serial $Serial -Command "getprop" -TimeoutSec 25
    $props = @{}
    foreach ($line in ($propText -split "[\r\n]+")) {
        if ($line -match '^\[([^\]]+)\]:\s*\[(.*)\]\s*$') {
            $props[$matches[1]] = $matches[2]
        }
    }
    if ($props.ContainsKey("ro.product.model")) { $d["model"] = $props["ro.product.model"] }
    if ($props.ContainsKey("ro.product.brand")) { $d["brand"] = $props["ro.product.brand"] }
    if ($props.ContainsKey("ro.product.device")) { $d["device"] = $props["ro.product.device"] }
    if ($props.ContainsKey("ro.build.version.release")) { $d["android"] = $props["ro.build.version.release"] }
    if ($props.ContainsKey("ro.build.version.sdk")) { $d["sdk"] = $props["ro.build.version.sdk"] }
    if ($props.ContainsKey("ro.product.cpu.abi")) { $d["abi"] = $props["ro.product.cpu.abi"] }
    if ($props.ContainsKey("ro.boot.hardware")) { $d["hardware"] = $props["ro.boot.hardware"] }
    if ($props.ContainsKey("ro.build.fingerprint")) { $d["fingerprint"] = $props["ro.build.fingerprint"] }
    if ($props.ContainsKey("ro.serialno") -and $d["serial"] -eq "") { $d["serial"] = $props["ro.serialno"] }

    # ---- 系统 / 电源
    $sysCmd = 'echo "#WB:POWER"; dumpsys power | grep -m1 mWakefulness=; echo "#WB:BAT"; dumpsys battery | grep -E "level:|status:|voltage:|temperature:"; echo "#WB:MEM"; grep MemTotal /proc/meminfo; echo "#WB:CPU"; grep -c processor /proc/cpuinfo; echo "#WB:DF"; df -k /data; echo "#WB:UP"; cat /proc/uptime'
    $sysText = Invoke-AdbShellOut -Serial $Serial -Command $sysCmd -TimeoutSec 30
    $sec = Split-Sections $sysText

    $powerText = Get-SectionText $sec "POWER"
    if ($powerText -like "*mWakefulness=Awake*") { $d["screen"] = "Awake" }
    elseif ($powerText -like "*mWakefulness=Asleep*" -or $powerText -like "*mWakefulness=Dozing*") { $d["screen"] = "Asleep" }
    else { $d["screen"] = "Unknown" }

    $batText = Get-SectionText $sec "BAT"
    $m = [regex]::Match($batText, '^\s*level:\s*(-?\d+)', "Multiline")
    if ($m.Success) { $d["battery"]["level"] = [int]$m.Groups[1].Value }
    $m = [regex]::Match($batText, '^\s*status:\s*(-?\d+)', "Multiline")
    if ($m.Success) {
        $code = [int]$m.Groups[1].Value
        if ($script:BatteryStatus.ContainsKey($code)) { $d["battery"]["status"] = $script:BatteryStatus[$code] }
        else { $d["battery"]["status"] = "未知" }
    }
    $m = [regex]::Match($batText, '^\s*voltage:\s*(-?\d+)', "Multiline")
    if ($m.Success) { $d["battery"]["voltage_mv"] = [int]$m.Groups[1].Value }
    $m = [regex]::Match($batText, '^\s*temperature:\s*(-?\d+)', "Multiline")
    if ($m.Success) { $d["battery"]["temp_c"] = [math]::Round(([double]$m.Groups[1].Value) / 10.0, 1) }

    $memText = Get-SectionText $sec "MEM"
    $m = [regex]::Match($memText, 'MemTotal:\s*(\d+)\s*kB')
    if ($m.Success) { $d["ram_mb"] = [int][math]::Round(([double]$m.Groups[1].Value) / 1024.0) }

    $cpuText = (Get-SectionText $sec "CPU").Trim()
    if ($cpuText -match '^\d+$') { $d["cores"] = [int]$cpuText }

    $dfText = Get-SectionText $sec "DF"
    foreach ($line in ($dfText -split "[\r\n]+")) {
        $parts = $line.Trim() -split '\s+'
        if ($parts.Count -ge 4 -and $parts[0].StartsWith("/dev/")) {
            $kb = ConvertTo-Number $parts[3]
            if ($null -ne $kb) {
                $d["storage_free_gb"] = [math]::Round($kb / 1024.0 / 1024.0, 1)
                break
            }
        }
    }

    $upText = (Get-SectionText $sec "UP").Trim()
    $tokens = $upText -split '\s+'
    if ($tokens.Count -ge 1) {
        $upv = ConvertTo-Number $tokens[0]
        if ($null -ne $upv) { $d["uptime_s"] = [int]$upv }
    }

    # ---- 网络
    $netCmd = 'echo "#WB:WIFI"; cmd wifi status; echo "#WB:ADDR"; ip -4 -o addr show; echo "#WB:IFCFG"; ifconfig 2>/dev/null; echo "#WB:ROUTE"; ip route get 1.1.1.1 2>/dev/null | head -1; ip route show; echo "#WB:NET"; ls /sys/class/net'
    $netText = Invoke-AdbShellOut -Serial $Serial -Command $netCmd -TimeoutSec 30
    $nsec = Split-Sections $netText

    $wifiText = Get-SectionText $nsec "WIFI"
    if ($wifiText -like "*Wifi is enabled*") { $d["wifi"]["enabled"] = $true }
    elseif ($wifiText -like "*Wifi is disabled*") { $d["wifi"]["enabled"] = $false }
    $connected = $false
    if ($wifiText.Contains('Wifi is connected to "')) { $connected = $true }
    if ($wifiText -like "*WifiInfo:*") { $connected = $true }
    $d["wifi"]["connected"] = $connected

    $statusIp = ""
    foreach ($line in ($wifiText -split "[\r\n]+")) {
        if (-not $line.Contains("WifiInfo:")) { continue }
        $m = [regex]::Match($line, 'SSID: "([^"]*)"')
        if (-not $m.Success) { $m = [regex]::Match($line, 'SSID: ([^,]+)') }
        if ($m.Success) {
            $ssid = $m.Groups[1].Value.Trim()
            if ($ssid -ne "<unknown ssid>" -and $ssid -ne "<none>") { $d["wifi"]["ssid"] = $ssid }
        }
        $m = [regex]::Match($line, 'BSSID: ([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})')
        if ($m.Success) { $d["wifi"]["bssid"] = $m.Groups[1].Value.ToLower() }
        $m = [regex]::Match($line, 'IP: /?([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)')
        if ($m.Success) { $statusIp = $m.Groups[1].Value }
        $m = [regex]::Match($line, 'Frequency: (\d+)MHz')
        if ($m.Success) {
            $freq = [int]$m.Groups[1].Value
            $d["wifi"]["freq_mhz"] = $freq
            $d["wifi"]["band"] = Get-BandName $freq
        }
        $m = [regex]::Match($line, 'RSSI: (-?\d+)')
        if ($m.Success) {
            $rssi = [int]$m.Groups[1].Value
            if ($rssi -gt -127) { $d["wifi"]["rssi"] = $rssi }
        }
        $m = [regex]::Match($line, 'Tx Link speed: (-?\d+)Mbps')
        if ($m.Success) {
            $v = [int]$m.Groups[1].Value
            if ($v -gt 0) { $d["wifi"]["link_tx_mbps"] = $v }
        }
        $m = [regex]::Match($line, 'Rx Link speed: (-?\d+)Mbps')
        if ($m.Success) {
            $v = [int]$m.Groups[1].Value
            if ($v -gt 0) { $d["wifi"]["link_rx_mbps"] = $v }
        }
        $m = [regex]::Match($line, 'Wi-Fi standard: (\w+)')
        if ($m.Success) {
            $std = $m.Groups[1].Value
            if ($std -ne "unknown") { $d["wifi"]["standard"] = $std }
        }
    }

    $addrText = Get-SectionText $nsec "ADDR"
    $addrs = @()
    foreach ($line in ($addrText -split "[\r\n]+")) {
        $m = [regex]::Match($line, '^\d+:\s+(\S+)\s+inet\s+([0-9.]+)/(\d+)')
        if (-not $m.Success) { continue }
        $iface = $m.Groups[1].Value
        if ($iface -eq "lo") { continue }
        $a = New-Object System.Collections.Specialized.OrderedDictionary
        $a["iface"] = $iface
        $a["ip"] = $m.Groups[2].Value
        $a["prefix"] = [int]$m.Groups[3].Value
        $addrs += $a
    }
    # ifconfig 兜底：某些机型 ip route / ip addr 不全，用户明确要求用 ifconfig 取 wlan0/wlan1
    $ifcText = Get-SectionText $nsec "IFCFG"
    $ifcAddrs = @()
    $curIfc = ""
    foreach ($line in ($ifcText -split "[\r\n]+")) {
        if ($line.Trim() -eq "") { continue }
        $hm = [regex]::Match($line, '^(\S+?):?\s+(?:Link encap|flags=)')
        if ($hm.Success -and -not ($line.StartsWith(" ") -or $line.StartsWith("`t"))) {
            $curIfc = $hm.Groups[1].Value.TrimEnd(":")
            continue
        }
        if ($curIfc -eq "") { continue }
        $im = [regex]::Match($line, '\binet (?:addr:)?\s*([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)')
        if (-not $im.Success) { continue }
        $ipv = $im.Groups[1].Value
        if ($ipv -eq "127.0.0.1") { continue }
        $prefix = 0
        $mm = [regex]::Match($line, '(?:Mask:|netmask )\s*([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)')
        if ($mm.Success) {
            foreach ($oct in ($mm.Groups[1].Value -split "\.")) {
                $ov = 0
                if ([int]::TryParse($oct, [ref]$ov)) {
                    $v = $ov
                    while ($v -gt 0) { $prefix += ($v -band 1); $v = $v -shr 1 }
                }
            }
        }
        $dup = $false
        foreach ($e in $ifcAddrs) { if ($e["iface"] -eq $curIfc -and $e["ip"] -eq $ipv) { $dup = $true } }
        if ($dup) { continue }
        $na = New-Object System.Collections.Specialized.OrderedDictionary
        $na["iface"] = $curIfc
        $na["ip"] = $ipv
        $na["prefix"] = $prefix
        $ifcAddrs += $na
    }
    $d["ifcfg"] = $ifcAddrs
    foreach ($e in $ifcAddrs) {
        $found = $false
        foreach ($a in $addrs) { if ($a["iface"] -eq $e["iface"] -and $a["ip"] -eq $e["ip"]) { $found = $true } }
        if (-not $found) { $addrs += $e }
    }
    $d["addrs"] = $addrs

    $wifiIp = ""
    if ($statusIp -ne "") {
        foreach ($a in $addrs) {
            if ($a["ip"] -eq $statusIp) { $wifiIp = $statusIp; break }
        }
    }
    if ($wifiIp -eq "") {
        foreach ($a in $addrs) {
            $ifn = [string]$a["iface"]
            if ($ifn.StartsWith("wlan") -and ($script:HotspotIfaces -notcontains $ifn)) {
                $wifiIp = [string]$a["ip"]
                break
            }
        }
    }
    $d["wifi"]["ip"] = $wifiIp

    $netText2 = Get-SectionText $nsec "NET"
    $ifaces = @()
    foreach ($line in ($netText2 -split "[\r\n]+")) {
        $name = $line.Trim()
        if ($name -eq "") { continue }
        if ($name -eq "lo") { continue }
        if ($name -match '^(wlan\d+|softap\d+|ap_br_\w+|swlan\d+|ap\d+|p2p0)$') { $ifaces += $name }
    }
    foreach ($a in $addrs) {
        $ifn = [string]$a["iface"]
        if ($ifaces -notcontains $ifn) { $ifaces += $ifn }
    }
    $d["ifaces"] = $ifaces

    $routeText = Get-SectionText $nsec "ROUTE"
    foreach ($line in ($routeText -split "[\r\n]+")) {
        $m = [regex]::Match($line, '^default\s+via\s+([0-9.]+)\s+dev\s+(\S+)')
        if (-not $m.Success) { $m = [regex]::Match($line, '^[0-9.]+\s+via\s+([0-9.]+)\s+dev\s+(\S+)') }
        if ($m.Success) {
            $d["default_route"]["gw"] = $m.Groups[1].Value
            $d["default_route"]["iface"] = $m.Groups[2].Value
            break
        }
    }

    # ---- 热点
    $hsCmd = 'echo "#WB:AP"; dumpsys wifi | grep -m1 -A12 "^Dump of SoftApManager id="; echo "#WB:APINFO"; dumpsys wifi | grep -m1 "^mCurrentSoftApInfoMap"; echo "#WB:APCLI"; dumpsys wifi | grep -o "num_connected_clients=[0-9][0-9]*" | tail -1; echo "#WB:TETHER"; dumpsys tethering | grep -A4 "^  Tether state:"'
    $hsText = Invoke-AdbShellOut -Serial $Serial -Command $hsCmd -TimeoutSec 30
    $hsec = Split-Sections $hsText
    $apText = Get-SectionText $hsec "AP"
    $started = $false
    foreach ($line in ($apText -split "[\r\n]+")) {
        $m = [regex]::Match($line, 'current StateMachine mode:\s*(\S+)')
        if ($m.Success) { if ($m.Groups[1].Value.StartsWith("Started")) { $started = $true } }
        $m = [regex]::Match($line, 'mRole:\s*(\S+)')
        if ($m.Success) { if ($m.Groups[1].Value.StartsWith("ROLE_SOFTAP")) { $started = $true } }
        $m = [regex]::Match($line, 'mApInterfaceName:\s*(\S+)')
        if ($m.Success) { $d["hotspot"]["iface"] = $m.Groups[1].Value }
        $m = [regex]::Match($line, 'mCurrentSoftApConfiguration: ssid = "([^"]*)"')
        if ($m.Success) { $d["hotspot"]["ssid"] = $m.Groups[1].Value }
    }
    $apInfoText = Get-SectionText $hsec "APINFO"
    $m = [regex]::Match($apInfoText, 'frequency=\s*(\d+)')
    if ($m.Success) { $d["hotspot"]["freq_mhz"] = [int]$m.Groups[1].Value }
    $apCliText = (Get-SectionText $hsec "APCLI").Trim()
    if ($apCliText -ne "") {
        $tail = ($apCliText -split "[\r\n]+")[-1].Trim()
        $idx = $tail.LastIndexOf("=")
        if ($idx -ge 0) {
            $v = ConvertTo-Number $tail.Substring($idx + 1)
            if ($null -ne $v) { $d["hotspot"]["clients"] = [int]$v }
        }
    }
    $tetherText = Get-SectionText $hsec "TETHER"
    $tetherOn = $tetherText.Contains("TetheredState")

    $hsIp = ""
    $hsIface = [string]$d["hotspot"]["iface"]
    if ($hsIface -ne "") {
        foreach ($a in $addrs) {
            if ($a["iface"] -eq $hsIface) { $hsIp = [string]$a["ip"]; break }
        }
    }
    if ($hsIp -eq "") {
        foreach ($a in $addrs) {
            if ($script:HotspotIfaces -contains [string]$a["iface"]) { $hsIp = [string]$a["ip"]; break }
        }
    }
    $d["hotspot"]["ip"] = $hsIp
    if ($started -or ($tetherOn -and $hsIp -ne "")) { $d["hotspot"]["on"] = $true } else { $d["hotspot"]["on"] = $false }

    # ---- iperf3
    $verOut = Invoke-AdbShellOut -Serial $Serial -Command ($script:PHONE_BIN + " -v 2>&1") -TimeoutSec 20
    $ver = ""
    foreach ($line in ($verOut -split "[\r\n]+")) {
        $s = $line.Trim()
        if ($s -eq "") { continue }
        $ver = $s
        break
    }
    $d["iperf"]["version"] = $ver
    if ($ver -ne "" -and $ver.Contains("iperf 3.")) { $d["iperf"]["ok"] = $true } else { $d["iperf"]["ok"] = $false }

    return $d
}

# =============================================================================
#  九、设备卡片与简表（对齐 devinfo.py 的 render_card / render_summary_table）
# =============================================================================

$script:CardWidth = 78
$script:InnerWidth = 76
$script:SummaryColumns = @(
    @(" #", 2),
    @("型号", 13),
    @("序列号", 17),
    @("WiFi/热点", 17),
    @("频段/信道", 10),
    @("RSSI", 6),
    @("IP", 16),
    @("屏幕", 6),
    @("电量", 5),
    @("iperf", 6)
)

function Get-CardLine {
    param([string]$Text)
    $inner = $script:InnerWidth
    $body = " " + (Get-Clip $Text ($inner - 2))
    return ([string][char]0x2502 + (Get-PadRight $body $inner) + [string][char]0x2502)
}

function Get-CardPair {
    param([string]$Left, [string]$Right, [int]$LeftWidth = 24)
    $inner = $script:InnerWidth
    $rw = Get-Width $Right
    $lw = [math]::Max(12, [math]::Min($LeftWidth, $inner - $rw - 2))
    $leftText = " " + (Get-Clip $Left ($lw - 2))
    $rightText = (Get-Clip $Right ($inner - $lw - 2)) + " "
    return ([string][char]0x2502 + (Get-PadRight $leftText $lw) + (Get-PadRight $rightText ($inner - $lw)) + [string][char]0x2502)
}

function Get-ScreenLabel {
    param($Info)
    $s = [string]$Info["screen"]
    if ($s -eq "Awake") { return "Awake" }
    if ($s -eq "Asleep") { return "Asleep" }
    return "Unknown"
}

function Get-IfaceList {
    param($Info, [string]$Key)
    $list = @()
    if ($Info -ne $null -and $Info.Contains($Key)) {
        $raw = $Info[$Key]
        if ($null -ne $raw) { $list = @($raw) }
    }
    return $list
}

function Get-IfaceLine {
    param($Info)
    $ifcList = Get-IfaceList $Info "ifcfg"
    $addrList = Get-IfaceList $Info "addrs"
    $parts = @()
    foreach ($name in @("wlan0", "wlan1")) {
        $ip = ""
        $prefix = 0
        foreach ($a in $ifcList) {
            if ([string]$a["iface"] -eq $name -and [string]$a["ip"] -ne "") { $ip = [string]$a["ip"]; $prefix = [int]$a["prefix"]; break }
        }
        if ($ip -eq "") {
            foreach ($a in $addrList) {
                if ([string]$a["iface"] -eq $name -and [string]$a["ip"] -ne "") { $ip = [string]$a["ip"]; $prefix = [int]$a["prefix"]; break }
            }
        }
        if ($ip -eq "") { $parts += ($name + " --") }
        elseif ($prefix -gt 0) { $parts += ($name + " " + $ip + "/" + [string]$prefix) }
        else { $parts += ($name + " " + $ip) }
    }
    return ("网卡 " + ($parts -join "   "))
}

function Write-ServerAddrs {
    param($Info, [string]$Serial)
    $ifcList = Get-IfaceList $Info "ifcfg"
    $addrList = Get-IfaceList $Info "addrs"
    Out-Info ("服务端 " + $Serial + " 的网卡地址（adb shell ifconfig）：")
    foreach ($name in @("wlan0", "wlan1")) {
        $ip = ""
        $prefix = 0
        $fromIfc = $false
        foreach ($a in $ifcList) {
            if ([string]$a["iface"] -eq $name -and [string]$a["ip"] -ne "") { $ip = [string]$a["ip"]; $prefix = [int]$a["prefix"]; $fromIfc = $true; break }
        }
        if ($ip -eq "") {
            foreach ($a in $addrList) {
                if ([string]$a["iface"] -eq $name -and [string]$a["ip"] -ne "") { $ip = [string]$a["ip"]; $prefix = [int]$a["prefix"]; break }
            }
        }
        $pad = $name.PadRight(6)
        if ($ip -eq "") {
            Out-Info ("    " + $pad + " 无 IPv4 地址")
        } elseif ($fromIfc) {
            Out-Info ("    " + $pad + " " + $ip + "/" + [string]$prefix)
        } else {
            Out-Info ("    " + $pad + " " + $ip + "/" + [string]$prefix + "（ifconfig 无此项，取自 ip addr）")
        }
    }
}

function Show-DeviceCard {
    param($Info, [int]$Index = 1)
    $head = [string][char]0x250C + [string][char]0x2500 + " 设备 " + [string]$Index + " "
    $pad = $script:CardWidth - (Get-Width $head) - 1
    if ($pad -lt 1) { $pad = 1 }
    Out-Line ($head + ([string][char]0x2500 * $pad) + [string][char]0x2510)
    $serialTxt = [string]$Info["serial"]
    Out-Line (Get-CardPair ([string]$Info["model"]) $serialTxt)

    $wifi = $Info["wifi"]
    $hs = $Info["hotspot"]
    if ($wifi["enabled"] -and $wifi["connected"]) {
        $label = "SSID"
        $ssid = [string]$wifi["ssid"]
        $ip = [string]$wifi["ip"]
    } elseif ($hs["on"]) {
        $label = "热点 SSID"
        $ssid = [string]$hs["ssid"]
        $ip = [string]$hs["ip"]
    } else {
        $label = "SSID"
        if ($wifi["enabled"] -eq $false) { $ssid = "WiFi 已关闭" } else { $ssid = "未连接" }
        $ip = [string]$wifi["ip"]
    }
    if ($ssid -eq "") { $ssid = "?" }
    if ((Get-Width $ssid) -gt 26) { $ssid = (Get-Clip $ssid 25) + "..." }
    if ($ip -eq "") { $ip = "--" }
    Out-Line (Get-CardLine ($label + " " + $ssid + "  IP " + $ip))

    $bat = $Info["battery"]
    $memTxt = "--"
    if ($null -ne $Info["ram_mb"]) { $memTxt = ([string][int]$Info["ram_mb"]) + "MB" }
    $batTxt = "--"
    if ($null -ne $bat["level"]) { $batTxt = ([string][int]$bat["level"]) + "%" }
    $tempTxt = "--"
    if ($null -ne $bat["temp_c"]) { $tempTxt = ([double]$bat["temp_c"]).ToString("F1", $script:Inv) + "C" }
    Out-Line (Get-CardLine ("屏幕 " + (Get-ScreenLabel $Info) + "  电量 " + $batTxt + "  温度 " + $tempTxt + "  内存 " + $memTxt))

    $storageTxt = "--"
    if ($null -ne $Info["storage_free_gb"]) { $storageTxt = ([double]$Info["storage_free_gb"]).ToString("F1", $script:Inv) + "GB" }
    $coresTxt = "--"
    if ($null -ne $Info["cores"]) { $coresTxt = [string][int]$Info["cores"] }
    $androidTxt = [string]$Info["android"]
    if ($androidTxt -eq "") { $androidTxt = "--" }
    $sdkTxt = [string]$Info["sdk"]
    if ($sdkTxt -eq "") { $sdkTxt = "--" }
    Out-Line (Get-CardLine ("系统 Android " + $androidTxt + " (SDK " + $sdkTxt + ")  " + $coresTxt + " 核  存储 " + $storageTxt))

    if ($wifi["connected"]) {
        $band = [string]$wifi["band"]
        $ch = Get-ChannelNumber $wifi["freq_mhz"]
        $radio = ""
        if ($band -ne "" -and $ch -gt 0) { $radio = $band + " ch" + [string]$ch }
        elseif ($band -ne "") { $radio = $band }
        if ($null -ne $wifi["rssi"]) { $radio = $radio + "  RSSI " + [string][int]$wifi["rssi"] }
        if ([string]$wifi["standard"] -ne "") { $radio = $radio + "  " + [string]$wifi["standard"] }
        if ($null -ne $wifi["link_tx_mbps"]) { $radio = $radio + "  Tx " + [string][int]$wifi["link_tx_mbps"] + "Mbps" }
        if ($null -ne $wifi["link_rx_mbps"]) { $radio = $radio + "  Rx " + [string][int]$wifi["link_rx_mbps"] + "Mbps" }
        if ($radio.Trim() -eq "") { $radio = "WiFi 已连接（无射频参数）" }
        Out-Line (Get-CardLine $radio)
    } else {
        if ($wifi["enabled"] -eq $false) { Out-Line (Get-CardLine "WiFi 已关闭") }
        else { Out-Line (Get-CardLine "未连接 WiFi，无射频参数") }
    }

    if ($hs["on"]) {
        $parts = @("热点 开")
        if ([string]$hs["ssid"] -ne "") { $parts += ("SSID " + [string]$hs["ssid"]) }
        if ([string]$hs["ip"] -ne "") { $parts += [string]$hs["ip"] }
        if ($null -ne $hs["clients"]) { $parts += ("客户端 " + [string][int]$hs["clients"]) }
        Out-Line (Get-CardLine ($parts -join "  "))
    } else {
        Out-Line (Get-CardLine "热点 关  本机不做服务端")
    }
    Out-Line (Get-CardLine (Get-IfaceLine $Info))

    $ipf = $Info["iperf"]
    if (-not $ipf["ok"]) {
        Out-Line (Get-CardLine ("iperf3 未就绪（" + [string]$ipf["path"] + "）"))
    } else {
        $ver = [string]$ipf["version"]
        if ($ver -eq "") { Out-Line (Get-CardLine "iperf3 已就绪") }
        else {
            $txt = [regex]::Replace($ver, '^iperf3?\s+', '')
            Out-Line (Get-CardLine ("iperf3 " + $txt))
        }
    }
    Out-Line ([string][char]0x2514 + ([string][char]0x2500 * $script:InnerWidth) + [string][char]0x2518)
}

function Get-SummaryCells {
    param($Info, [int]$Index)
    $wifi = $Info["wifi"]
    $hs = $Info["hotspot"]
    if ($wifi["enabled"] -and $wifi["connected"]) {
        $ssid = [string]$wifi["ssid"]
        if ($ssid -eq "") { $ssid = "?" }
    } elseif ($hs["on"]) {
        $ssid = "热点 " + [string]$hs["ssid"]
        if ([string]$hs["ssid"] -eq "") { $ssid = "热点 ?" }
    } elseif ($wifi["enabled"] -eq $false) {
        $ssid = "已关闭"
    } else {
        $ssid = "未连接"
    }

    $band = ""
    $channel = 0
    if ($wifi["connected"]) {
        $band = [string]$wifi["band"]
        $channel = Get-ChannelNumber $wifi["freq_mhz"]
    }
    if ($band -eq "" -and $hs["on"] -and ($null -ne $hs["freq_mhz"])) {
        $band = Get-BandName $hs["freq_mhz"]
        $channel = Get-ChannelNumber $hs["freq_mhz"]
    }
    if ($band -ne "" -and $channel -gt 0) { $bandTxt = $band + "/ch" + [string]$channel } else { $bandTxt = "--" }

    $rssiTxt = "--"
    if ($wifi["connected"] -and ($null -ne $wifi["rssi"])) { $rssiTxt = [string][int]$wifi["rssi"] }

    $ip = [string]$wifi["ip"]
    if ($ip -eq "") { $ip = [string]$hs["ip"] }
    if ($ip -eq "") { $ip = "--" }

    $screen = "--"
    if ([string]$Info["screen"] -eq "Awake") { $screen = "亮" }
    elseif ([string]$Info["screen"] -eq "Asleep") { $screen = "灭" }

    $levelTxt = "--"
    if ($null -ne $Info["battery"]["level"] -and [int]$Info["battery"]["level"] -gt 0) {
        $levelTxt = [string][int]$Info["battery"]["level"] + "%"
    }

    $ipf = $Info["iperf"]
    $verTxt = "--"
    if ($ipf["ok"]) {
        $verTxt = "OK"
        $mm = [regex]::Match([string]$ipf["version"], 'iperf\s+([0-9.]+)')
        if ($mm.Success) { $verTxt = $mm.Groups[1].Value }
    }

    $model = [string]$Info["model"]
    if ($model -eq "") { $model = "--" }
    $serial = [string]$Info["serial"]
    if ($serial -eq "") { $serial = "--" }

    return @([string]$Index, $model, $serial, $ssid, $bandTxt, $rssiTxt, $ip, $screen, $levelTxt, $verTxt)
}

function Get-SummaryRow {
    param([string[]]$Cells)
    $out = @()
    for ($i = 0; $i -lt $script:SummaryColumns.Count; $i++) {
        $w = [int]$script:SummaryColumns[$i][1]
        $text = ""
        if ($i -lt $Cells.Count) { $text = [string]$Cells[$i] }
        if ((Get-Width $text) -gt $w) {
            $text = (Get-Clip $text ($w - 1)).TrimEnd() + "…"
        }
        $out += (Get-PadRight $text $w)
    }
    return (($out -join "  ").TrimEnd())
}

function Show-SummaryTable {
    param($Infos)
    if ($null -eq $Infos -or @($Infos).Count -eq 0) {
        Out-Line "（没有检测到设备）"
        return
    }
    $names = @()
    $total = 0
    for ($i = 0; $i -lt $script:SummaryColumns.Count; $i++) {
        $names += [string]$script:SummaryColumns[$i][0]
        $total += [int]$script:SummaryColumns[$i][1]
    }
    $total += 2 * ($script:SummaryColumns.Count - 1)
    Out-Line (Get-SummaryRow $names)
    Out-Line ("-" * $total)
    $idx = 0
    foreach ($info in @($Infos)) {
        $idx = $idx + 1
        Out-Line (Get-SummaryRow (Get-SummaryCells $info $idx))
    }
}

# =============================================================================
#  十、iperf3 行解析（与 iperf.py 的 parse_line 完全一致）
# =============================================================================

function New-Record {
    param([string]$Time, [double]$Elapsed, [string]$Test, [string]$Kind, [string]$Stream, $Start, $End, $Transfer, [string]$Unit, $Rate, [string]$RateUnit, [string]$Role, [string]$Raw)
    $r = New-Object System.Collections.Specialized.OrderedDictionary
    $r["t"] = $Time
    $r["elapsed"] = [math]::Round($Elapsed, 2)
    $r["test"] = $Test
    $r["kind"] = $Kind
    $r["stream"] = $Stream
    $r["start"] = $Start
    $r["end"] = $End
    $r["transfer"] = $Transfer
    $r["unit"] = $Unit
    $r["rate"] = $Rate
    $r["rate_unit"] = $RateUnit
    $r["retr"] = ""
    $r["jitter"] = ""
    $r["lost"] = ""
    $r["total"] = ""
    $r["lost_pct"] = ""
    $r["role"] = $Role
    $r["raw"] = $Raw
    return $r
}

function ConvertFrom-IperfLine {
    param([string]$Line, [string]$TestId, [string]$Kind, [double]$Elapsed, [string]$Time)
    $raw = $Line.TrimEnd([char]13, [char]10)
    $m = [regex]::Match($raw.Trim(), $script:RowRe)
    if (-not $m.Success) { return $null }
    $streamRaw = $m.Groups["stream"].Value
    $stream = "#" + $streamRaw
    if ($streamRaw -eq "SUM") { $stream = "SUM" }
    $rest = @()
    $restText = $m.Groups["rest"].Value
    if ($restText.Trim() -ne "") { $rest = $restText.Trim() -split '\s+' }

    $role = ""
    if ($rest.Count -ge 1) {
        $last = $rest[$rest.Count - 1]
        if ($last -eq "sender" -or $last -eq "receiver") {
            $role = $last
            if ($rest.Count -eq 1) { $rest = @() } else { $rest = $rest[0..($rest.Count - 2)] }
        }
    }

    $args = @{
        Time = $Time
        Elapsed = $Elapsed
        Test = $TestId
        Kind = $Kind
        Stream = $stream
        Start = [double]$m.Groups["start"].Value
        End = [double]$m.Groups["end"].Value
        Transfer = [double]$m.Groups["transfer"].Value
        Unit = $m.Groups["unit"].Value
        Rate = [double]$m.Groups["rate"].Value
        RateUnit = $m.Groups["rate_unit"].Value
        Role = $role
        Raw = $raw
    }
    $rec = New-Record @args

    if ($Kind -eq "UDP") {
        if ($rest.Count -ge 2 -and $rest[1] -eq "ms" -and [regex]::IsMatch($rest[0], $script:FloatRe)) {
            $rec["jitter"] = [double]$rest[0]
            if ($rest.Count -eq 2) { $rest = @() } else { $rest = $rest[2..($rest.Count - 1)] }
        }
        if ($rest.Count -ge 1) {
            $mm = [regex]::Match($rest[0], $script:LossRe)
            if ($mm.Success) {
                $rec["lost"] = [int]$mm.Groups[1].Value
                $rec["total"] = [int]$mm.Groups[2].Value
                if ($rest.Count -eq 1) { $rest = @() } else { $rest = $rest[1..($rest.Count - 1)] }
            }
        }
        if ($rest.Count -ge 1) {
            $mm = [regex]::Match($rest[0], $script:PctRe)
            if ($mm.Success) {
                $rec["lost_pct"] = [double]$mm.Groups[1].Value
                if ($rest.Count -eq 1) { $rest = @() } else { $rest = $rest[1..($rest.Count - 1)] }
            }
        }
        if (([string]$rec["total"]) -eq "" -and $rest.Count -ge 1 -and [regex]::IsMatch($rest[0], $script:IntRe)) {
            $rec["total"] = [int]$rest[0]
        }
        if ($role -eq "sender") {
            $rec["lost"] = ""
            $rec["lost_pct"] = ""
        }
    } else {
        if ($rest.Count -ge 1 -and [regex]::IsMatch($rest[0], $script:IntRe)) {
            $rec["retr"] = [int]$rest[0]
        }
    }
    return $rec
}

function Format-RecordLine {
    param($Rec)
    $head = [string]$Rec["stream"]
    if ($head -eq "SUM") { $head = "SUM " }
    $st = ConvertTo-Number $Rec["start"]
    $en = ConvertTo-Number $Rec["end"]
    $si = 0
    $ei = 0
    if ($null -ne $st) { $si = [int]$st }
    if ($null -ne $en) { $ei = [int]$en }
    $iv = ([string]$si) + "-" + ([string]$ei) + "s"
    $unit = [string]$Rec["unit"]
    if ($script:UnitShort.ContainsKey($unit)) { $unit = $script:UnitShort[$unit] }
    $tr = ConvertTo-Number $Rec["transfer"]
    if ($null -eq $tr) { $tr = 0.0 }
    $transfer = $tr.ToString("F1", $script:Inv).PadLeft(7) + " " + $unit
    $rate = ConvertTo-Number $Rec["rate"]
    if ($null -eq $rate) { $rate = 0.0 }
    # 单位跟随 iperf3 原样输出：千兆链路上打的是 Gbits/sec，硬编码 Mb/s 会显示成 15.6 Mb/s
    $ruTxt = [string]$Rec["rate_unit"]
    if ($ruTxt -eq "") { $ruTxt = "Mbits/sec" }
    if ($script:RateShort.ContainsKey($ruTxt)) { $ruTxt = $script:RateShort[$ruTxt] }
    $rateTxt = $rate.ToString("F1", $script:Inv).PadLeft(7) + " " + $ruTxt

    $extra = ""
    if ([string]$Rec["kind"] -eq "UDP") {
        $jit = [string]$Rec["jitter"]
        if ($jit -ne "" -and $jit -ne "0") { $extra = $extra + "  jitter " + (Format-Num $Rec["jitter"]) + " ms" }
        $lost = [string]$Rec["lost"]
        $total = [string]$Rec["total"]
        if ($lost -ne "" -and $total -ne "") {
            $extra = $extra + "  lost " + $lost + "/" + $total
            $pct = [string]$Rec["lost_pct"]
            if ($pct -ne "") { $extra = $extra + " (" + (Format-Num $Rec["lost_pct"]) + "%)" }
        }
    } else {
        $retr = [string]$Rec["retr"]
        if ($retr -ne "") { $extra = $extra + "  retr " + $retr }
    }
    $role = [string]$Rec["role"]
    if ($role -ne "") { $extra = $extra + "  " + $role }

    $headPad = $head.PadRight(4)
    $ivPad = $iv.PadRight(9)
    return ([string]$Rec["t"] + " | " + $headPad + " " + $ivPad + " " + $transfer + "  " + $rateTxt + $extra)
}

# =============================================================================
#  十一、统计（对齐 report.py 的 _stats）
# =============================================================================

function Get-FullSpanRows {
    param($Recs)
    $list = @($Recs)
    if ($list.Count -lt 2) { return @() }
    $spans = @()
    foreach ($r in $list) {
        $s = ConvertTo-Number $r["start"]
        $e = ConvertTo-Number $r["end"]
        if ($null -eq $s -or $null -eq $e) { $spans += $null } else { $spans += ($e - $s) }
    }
    $good = @()
    foreach ($x in $spans) {
        if ($null -ne $x -and $x -gt 0) { $good += $x }
    }
    if ($good.Count -eq 0) { return @() }
    $longest = ($good | Measure-Object -Maximum).Maximum
    $shortest = ($good | Measure-Object -Minimum).Minimum
    if ($longest -le ($shortest * 1.5)) { return @() }
    $out = @()
    for ($i = 0; $i -lt $list.Count; $i++) {
        if ($null -ne $spans[$i] -and $spans[$i] -ge ($longest * 0.9)) { $out += $list[$i] }
    }
    return $out
}

function Get-Stats {
    param($Recs)
    $list = @($Recs)
    $st = New-Object System.Collections.Specialized.OrderedDictionary
    $st["n"] = $list.Count
    $st["avg_rate"] = $null
    $st["min_rate"] = $null
    $st["max_rate"] = $null
    $st["unit"] = ""
    $st["jitter"] = $null
    $st["lost"] = $null
    $st["total"] = $null
    $st["lost_pct"] = $null
    $st["retr_sum"] = $null
    $st["transfer_sum"] = $null
    $st["transfer_unit"] = ""
    $st["stall_n"] = $null
    if ($list.Count -eq 0) { return $st }

    $sumRows = @()
    foreach ($r in $list) {
        if (([string]$r["stream"]).ToUpper() -eq "SUM") { $sumRows += $r }
    }
    $fullSpan = @()
    if ($sumRows.Count -eq 0) {
        $fullSpan = Get-FullSpanRows $list
        $sumRows = $fullSpan
    }

    $perRows = @()
    foreach ($r in $list) {
        $isSum = $false
        foreach ($s in $sumRows) { if ([object]::ReferenceEquals($s, $r)) { $isSum = $true; break } }
        if (-not $isSum) { $perRows += $r }
    }

    $tail = $list[$list.Count - 1]
    if ($sumRows.Count -gt 0) { $tail = $sumRows[$sumRows.Count - 1] }
    # 下面 min/max/avg 统一换算成 Mbits/sec 再比较，单位字段跟着改，
    # 否则 Format-Bps 会把已经换算过的值再按原单位换一次
    $st["unit"] = "Mbits/sec"
    $st["transfer_unit"] = [string]$tail["unit"]

    $spanRows = @()
    if ($sumRows.Count -gt 0) {
        foreach ($r in $sumRows) {
            $inFull = $false
            foreach ($s in $fullSpan) { if ([object]::ReferenceEquals($s, $r)) { $inFull = $true; break } }
            if (-not $inFull) { $spanRows += $r }
        }
    }
    if ($spanRows.Count -eq 0) { $spanRows = $perRows }
    if ($spanRows.Count -eq 0) { $spanRows = $list }

    $rates = @()
    $stalls = 0
    foreach ($r in $spanRows) {
        $v = ConvertTo-Number $r["rate"]
        if ($null -ne $v) {
            # 完全停滞的那一秒 iperf3 打的是 "0.00 bits/sec"，
            # 不换算直接取数值会把 Kbits/sec 当成 Mbits/sec
            $v = $v * (Get-UnitFactor ([string]$r["rate_unit"]))
            $rates += $v
            if ($v -le 0) { $stalls = $stalls + 1 }
        }
    }
    if ($rates.Count -gt 0) {
        $st["min_rate"] = ($rates | Measure-Object -Minimum).Minimum
        $st["max_rate"] = ($rates | Measure-Object -Maximum).Maximum
        $st["stall_n"] = $stalls
    }

    $avg = ConvertTo-Number $tail["rate"]
    if ($null -ne $avg) { $avg = $avg * (Get-UnitFactor ([string]$tail["rate_unit"])) }
    if ($null -eq $avg) {
        $allRates = @()
        foreach ($r in $list) {
            $v = ConvertTo-Number $r["rate"]
            if ($null -ne $v) { $allRates += $v }
        }
        if ($allRates.Count -gt 0) {
            $sum = 0.0
            foreach ($v in $allRates) { $sum = $sum + $v }
            $avg = $sum / $allRates.Count
        }
    }
    $st["avg_rate"] = $avg
    if ($null -ne $avg) {
        if ($null -eq $st["min_rate"]) { $st["min_rate"] = $avg } else { $st["min_rate"] = [math]::Min($st["min_rate"], $avg) }
        if ($null -eq $st["max_rate"]) { $st["max_rate"] = $avg } else { $st["max_rate"] = [math]::Max($st["max_rate"], $avg) }
    }

    $jit = ConvertTo-Number $tail["jitter"]
    if ($null -eq $jit) {
        $jits = @()
        foreach ($r in $list) {
            $v = ConvertTo-Number $r["jitter"]
            if ($null -ne $v) { $jits += $v }
        }
        if ($jits.Count -gt 0) {
            $sum = 0.0
            foreach ($v in $jits) { $sum = $sum + $v }
            $jit = $sum / $jits.Count
        }
    }
    $st["jitter"] = $jit

    $lost = ConvertTo-Number $tail["lost"]
    $total = ConvertTo-Number $tail["total"]
    $pct = ConvertTo-Number $tail["lost_pct"]
    if ($null -eq $lost) {
        for ($i = $list.Count - 1; $i -ge 0; $i--) {
            $v = ConvertTo-Number $list[$i]["lost"]
            if ($null -ne $v) {
                $lost = $v
                $total = ConvertTo-Number $list[$i]["total"]
                $pct = ConvertTo-Number $list[$i]["lost_pct"]
                break
            }
        }
    }
    if ($null -eq $pct -and $null -ne $lost -and $null -ne $total -and $total -ne 0) {
        $pct = $lost * 100.0 / $total
    }
    $st["lost"] = $lost
    $st["total"] = $total
    $st["lost_pct"] = $pct

    if ($sumRows.Count -gt 0) {
        $vals = @()
        foreach ($r in $sumRows) {
            $v = ConvertTo-Number $r["retr"]
            if ($null -ne $v) { $vals += $v }
        }
        if ($vals.Count -gt 0) { $st["retr_sum"] = ($vals | Measure-Object -Maximum).Maximum }
    } else {
        $sum = 0.0
        $any = $false
        foreach ($r in $perRows) {
            $v = ConvertTo-Number $r["retr"]
            if ($null -ne $v) { $sum = $sum + $v; $any = $true }
        }
        if ($any) { $st["retr_sum"] = $sum }
    }

    if ($sumRows.Count -gt 0) {
        $vals = @()
        foreach ($r in $sumRows) {
            $v = ConvertTo-Number $r["transfer"]
            if ($null -ne $v) { $vals += $v }
        }
        if ($vals.Count -gt 0) { $st["transfer_sum"] = ($vals | Measure-Object -Maximum).Maximum }
    } else {
        $sum = 0.0
        foreach ($r in $perRows) {
            $v = ConvertTo-Number $r["transfer"]
            if ($null -ne $v) { $sum = $sum + $v }
        }
        $st["transfer_sum"] = $sum
    }
    return $st
}

# =============================================================================
#  十二、输出文件（CSV / JSON / Markdown / raw，格式与 report.py 一致）
# =============================================================================

function Get-ItemSlug {
    param($Items)
    $out = @()
    foreach ($it in @($Items)) {
        $s = [regex]::Replace([string]$it, '[^0-9A-Za-z_+\-]+', '_').Trim('_')
        if ($s -eq "") { continue }
        if ($out -contains $s) { continue }
        $out += $s
    }
    if ($out.Count -eq 0) { return "run" }
    return ($out -join "+")
}

function New-RunDir {
    param([string]$Base, $Items)
    $stamp = (Get-Date).ToString("yyyyMMdd_HHmmss")
    $slug = Get-ItemSlug $Items
    if ($slug.Length -gt 80) { $slug = $slug.Substring(0, 80).TrimEnd("_", "+") }
    if ($slug -eq "") { $slug = "run" }
    $name = $stamp + "_" + $slug
    if ($name.Length -gt 120) { $name = $name.Substring(0, 120).TrimEnd("_") }
    $path = Join-Path $Base $name
    $n = 2
    while (Test-Path -LiteralPath $path) {
        $path = Join-Path $Base ($name + "_" + [string]$n)
        $n = $n + 1
    }
    if (-not (Test-Path -LiteralPath $path)) {
        [void](New-Item -ItemType Directory -Path $path -Force)
    }
    return $path
}

function Get-CsvRow {
    param($Rec)
    $retr = Format-S $Rec["retr"]
    $total = Format-S $Rec["total"]
    $retrOrDgram = $retr
    if ($retrOrDgram -eq "") { $retrOrDgram = $total }
    return @(
        (Format-S $Rec["t"]),
        (Format-S $Rec["test"]),
        (Format-S $Rec["kind"]),
        (Format-S $Rec["stream"]),
        (Format-F2 $Rec["start"]),
        (Format-F2 $Rec["end"]),
        (Format-Num $Rec["transfer"] 3),
        (Format-S $Rec["unit"]),
        (Format-Num $Rec["rate"] 3),
        (Format-S $Rec["rate_unit"]),
        $retrOrDgram,
        (Format-Num $Rec["jitter"] 3),
        (Format-S $Rec["lost"]),
        $total,
        (Format-Num $Rec["lost_pct"] 3),
        (Format-S $Rec["role"])
    )
}

function Get-CsvCell {
    param([string]$Text)
    if ($null -eq $Text) { return '""' }
    if ($Text.IndexOf('"') -ge 0 -or $Text.IndexOf(',') -ge 0 -or $Text.IndexOf([char]10) -ge 0 -or $Text.IndexOf([char]13) -ge 0) {
        return ('"' + ($Text -replace '"', '""') + '"')
    }
    return $Text
}

function Save-Csv {
    param([string]$Path, $Records)
    $sb = New-Object System.Text.StringBuilder
    $head = @()
    foreach ($h in $script:CSV_HEADER) { $head += (Get-CsvCell $h) }
    [void]$sb.Append(($head -join ",") + $script:CRLF)
    foreach ($rec in @($Records)) {
        $cells = @()
        foreach ($c in (Get-CsvRow $rec)) { $cells += (Get-CsvCell $c) }
        [void]$sb.Append(($cells -join ",") + $script:CRLF)
    }
    Save-TextFile -Path $Path -Text $sb.ToString() -Bom $true
}

function Get-MdCell {
    param($Value)
    $t = Format-T $Value
    $t = $t.Replace("|", "\|")
    $t = $t.Replace([string][char]13, " ")
    $t = $t.Replace([string][char]10, " ")
    return $t
}

function Get-MdTable {
    param([string[]]$Headers, $Rows)
    $list = @()
    foreach ($r in @($Rows)) {
        $cells = @()
        foreach ($c in @($r)) { $cells += (Get-MdCell $c) }
        $list += , $cells
    }
    $widths = @()
    for ($i = 0; $i -lt $Headers.Count; $i++) {
        $w = Get-Width ($Headers[$i].Replace("**", ""))
        foreach ($row in $list) {
            if ($i -lt $row.Count) {
                $cw = Get-Width (([string]$row[$i]).Replace("**", ""))
                if ($cw -gt $w) { $w = $cw }
            }
        }
        if ($w -lt 3) { $w = 3 }
        $widths += $w
    }
    $out = @()
    $hcells = @()
    for ($i = 0; $i -lt $Headers.Count; $i++) {
        $cell = $Headers[$i]
        $pad = $widths[$i] - (Get-Width ($cell.Replace("**", "")))
        if ($pad -lt 0) { $pad = 0 }
        $hcells += ($cell + (" " * $pad))
    }
    $out += ("| " + ($hcells -join " | ") + " |")
    $seps = @()
    for ($i = 0; $i -lt $Headers.Count; $i++) { $seps += ("-" * $widths[$i]) }
    $out += ("| " + ($seps -join " | ") + " |")
    foreach ($row in $list) {
        $cells = @()
        for ($i = 0; $i -lt $Headers.Count; $i++) {
            $cell = ""
            if ($i -lt $row.Count) { $cell = [string]$row[$i] }
            $pad = $widths[$i] - (Get-Width ($cell.Replace("**", "")))
            if ($pad -lt 0) { $pad = 0 }
            $cells += ($cell + (" " * $pad))
        }
        $out += ("| " + ($cells -join " | ") + " |")
    }
    return ($out -join $script:LF)
}

# ---------------------------------------------------------------- 负载构建

function Get-RateOf {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        $unit = [string]$Value["unit"]
        if ($unit -eq "") { $unit = [string]$Value["rate_unit"] }
        if ($unit -eq "") { $unit = "Mbits/sec" }
        $o = New-Object System.Collections.Specialized.OrderedDictionary
        $o["rate"] = ConvertTo-Number $Value["rate"]
        $o["unit"] = $unit
        return $o
    }
    $x = ConvertTo-Number $Value
    if ($null -eq $x) { return $null }
    $o = New-Object System.Collections.Specialized.OrderedDictionary
    $o["rate"] = $x
    $o["unit"] = "Mbits/sec"
    return $o
}

function Get-TestRecords {
    param($Records, [string]$TestId)
    $out = @()
    foreach ($r in @($Records)) {
        if ([string]$r["test"] -eq $TestId) { $out += $r }
    }
    return $out
}

function New-TestResult {
    param([string]$TestId, [string]$Kind, $Sender, $Receiver, [bool]$Ok, [string]$Error, $Params)
    $o = New-Object System.Collections.Specialized.OrderedDictionary
    $o["test_id"] = $TestId
    $o["kind"] = $Kind
    $o["sender"] = $Sender
    $o["receiver"] = $Receiver
    $o["ok"] = $Ok
    $o["error"] = $Error
    $o["params"] = $Params
    return $o
}

function Get-TestBlock {
    param($Res, [int]$Index, $AllRecords)
    $testId = [string]$Res["test_id"]
    if ($testId -eq "") { $testId = [string]$Res["test"] }
    if ($testId -eq "") { $testId = "test" + [string]($Index + 1) }
    $recs = Get-TestRecords $AllRecords $testId
    $kind = [string]$Res["kind"]
    if ($kind -eq "") {
        if ($testId.StartsWith("udp")) { $kind = "UDP" } else { $kind = "TCP" }
    }
    $kind = $kind.ToUpper()

    $params = New-Object System.Collections.Specialized.OrderedDictionary
    if ($null -ne $Res["params"]) {
        foreach ($k in (Get-JsonKeys $Res["params"])) { $params[$k] = $Res["params"][$k] }
    }
    if (-not $params.Contains("udp")) { $params["udp"] = ($kind -eq "UDP") }
    if (-not $params.Contains("reverse")) { $params["reverse"] = $testId.EndsWith("down") }

    $st = Get-Stats $recs
    $sender = Get-RateOf $Res["sender"]
    $receiver = Get-RateOf $Res["receiver"]
    if ($null -eq $sender -and $null -ne $st["avg_rate"]) {
        $sender = New-Object System.Collections.Specialized.OrderedDictionary
        $sender["rate"] = $st["avg_rate"]
        $sender["unit"] = $st["unit"]
        if ($sender["unit"] -eq "") { $sender["unit"] = "Mbits/sec" }
    }
    if ($null -eq $receiver -and $null -ne $st["avg_rate"]) {
        $receiver = New-Object System.Collections.Specialized.OrderedDictionary
        $receiver["rate"] = $st["avg_rate"]
        $receiver["unit"] = $st["unit"]
        if ($receiver["unit"] -eq "") { $receiver["unit"] = "Mbits/sec" }
    }

    $error = [string]$Res["error"]
    $ok = $false
    if ($Res["ok"]) { $ok = $true }
    if ($error -eq "") { $ok = $true }

    $name = [string]$Res["name"]
    if ($name -eq "") {
        if ($script:TestNames.ContainsKey($testId)) { $name = $script:TestNames[$testId] } else { $name = $testId }
    }

    $t = New-Object System.Collections.Specialized.OrderedDictionary
    $t["test_id"] = $testId
    $t["name"] = $name
    $t["kind"] = $kind
    $t["params"] = $params
    $t["sender"] = $sender
    $t["receiver"] = $receiver
    $t["records_n"] = $recs.Count
    $t["stats"] = $st
    $t["ok"] = $ok
    $t["error"] = $error
    $t["records"] = $recs
    return $t
}

function Get-LinkMbps {
    param($Devices, [string]$Serial, [bool]$Up)
    foreach ($d in @($Devices)) {
        if ([string]$d["serial"] -ne $Serial) { continue }
        $tx = ConvertTo-Number $d["wifi"]["link_tx_mbps"]
        $rx = ConvertTo-Number $d["wifi"]["link_rx_mbps"]
        $v = $null
        if ($Up) { $v = $tx } else { $v = $rx }
        if ($null -eq $v -or $v -eq 0) {
            if ($null -ne $tx -and $tx -ne 0) { $v = $tx }
            elseif ($null -ne $rx -and $rx -ne 0) { $v = $rx }
        }
        if ($null -ne $v -and $v -ne 0) { return $v }
        return $null
    }
    return $null
}

function Get-PcEnvText {
    param($Env)
    if ($null -eq $Env) { return "--" }
    if (-not ($Env -is [System.Collections.IDictionary])) { return "--" }
    if ((Get-JsonKeys $Env).Count -eq 0) { return "--" }
    $parts = @()
    if ($Env.Contains("os") -and [string]$Env["os"] -ne "") { $parts += [string]$Env["os"] }
    $wl = "无"
    if ($Env.Contains("has_wireless") -and $Env["has_wireless"]) { $wl = "有" }
    $parts += ("无线网卡 " + $wl)
    if ($Env.Contains("python") -and [string]$Env["python"] -ne "") { $parts += ("Python " + [string]$Env["python"]) }
    if ($Env.Contains("admin") -and $null -ne $Env["admin"]) {
        if ($Env["admin"]) { $parts += "管理员 是" } else { $parts += "管理员 否" }
    }
    return ($parts -join " · ")
}

function Get-NormNotes {
    param($Notes)
    $out = @()
    foreach ($n in @($Notes)) {
        $text = ""
        if ($null -eq $n) { continue }
        if ($n -is [System.Collections.IDictionary]) {
            foreach ($k in @("text", "msg", "message")) {
                if ($n.Contains($k) -and [string]$n[$k] -ne "") { $text = [string]$n[$k]; break }
            }
            if ($text -eq "") { $text = [string]$n }
        } else {
            $text = [string]$n
        }
        $text = $text.Trim()
        if ($text -eq "") { continue }
        if ($out -contains $text) { continue }
        $out += $text
    }
    return $out
}

function Get-NormMeta {
    param($MetaIn)
    $now = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    $m = New-Object System.Collections.Specialized.OrderedDictionary
    $src = @{}
    if ($null -ne $MetaIn -and ($MetaIn -is [System.Collections.IDictionary])) {
        foreach ($k in $MetaIn.Keys) { $src[[string]$k] = $MetaIn[$k] }
    }
    $alias = @{
        "脚本版本" = "script_version"; "版本" = "script_version"; "version" = "script_version"
        "开始时间" = "started_at"; "结束时间" = "finished_at"
        "时长" = "duration"; "时长参数" = "duration"; "duration_s" = "duration"; "sec" = "duration"
        "流数" = "streams"; "并发流数" = "streams"
        "反向" = "reverse"; "是否反向" = "reverse"
        "iperf版本" = "iperf_version"; "iperf" = "iperf_version"
        "PC环境" = "pc_env"; "pc" = "pc_env"
        "测试项" = "items"; "运行目录" = "run_dir"
        "端口" = "port"; "服务端口" = "port"
    }
    foreach ($k in $src.Keys) {
        $target = $k
        if ($alias.ContainsKey($k)) { $target = $alias[$k] }
        $m[$target] = $src[$k]
    }
    $flat = @{}
    if ($null -ne $src["params"] -and ($src["params"] -is [System.Collections.IDictionary])) {
        foreach ($k in $src["params"].Keys) { $flat[[string]$k] = $src["params"][$k] }
    }
    foreach ($k in @("duration", "streams", "reverse", "udp_bw", "port")) {
        $cur = $null
        if ($m.Contains($k)) { $cur = $m[$k] }
        $isEmpty = ($null -eq $cur)
        if (-not $isEmpty -and ($cur -is [string]) -and $cur -eq "") { $isEmpty = $true }
        if ($isEmpty -and $flat.ContainsKey($k)) {
            $fv = $flat[$k]
            if ($null -ne $fv) { $m[$k] = $fv }
        }
    }
    if (-not $m.Contains("tool") -or [string]$m["tool"] -eq "") { $m["tool"] = $script:TOOL_NAME }
    if (-not $m.Contains("script_version") -or [string]$m["script_version"] -eq "") { $m["script_version"] = $script:SCRIPT_VERSION }
    if (-not $m.Contains("started_at") -or [string]$m["started_at"] -eq "") { $m["started_at"] = $now }
    if (-not $m.Contains("finished_at") -or [string]$m["finished_at"] -eq "") { $m["finished_at"] = $m["started_at"] }
    if (-not $m.Contains("udp_bw")) { $m["udp_bw"] = "" }
    if (-not $m.Contains("items")) { $m["items"] = @() }
    if (-not $m.Contains("pc_env") -or -not ($m["pc_env"] -is [System.Collections.IDictionary])) {
        $m["pc_env"] = New-Object System.Collections.Specialized.OrderedDictionary
    }
    foreach ($k in @("iperf_version", "run_dir", "port")) {
        if (-not $m.Contains($k)) { $m[$k] = "" }
    }
    $sc = ""
    if ($m.Contains("serial_client")) { $sc = [string]$m["serial_client"] }
    if ($sc -eq "" -and $m.Contains("client_serial")) { $sc = [string]$m["client_serial"] }
    $m["serial_client"] = $sc
    $ss = ""
    if ($m.Contains("serial_server")) { $ss = [string]$m["serial_server"] }
    if ($ss -eq "" -and $m.Contains("server_serial")) { $ss = [string]$m["server_serial"] }
    $m["serial_server"] = $ss
    return $m
}

function Get-VerdictTcp {
    param($Test, $Meta, $Devices)
    $testId = [string]$Test["test_id"]
    $up = $true
    if ($testId -eq "tcp_down") { $up = $false }
    $sender = $Test["sender"]
    $receiver = $Test["receiver"]
    $client = $receiver
    $server = $sender
    if ($up) { $client = $sender; $server = $receiver }
    $clientTxt = "--"
    $serverTxt = "--"
    if ($null -ne $client) { $clientTxt = Format-Bps $client["rate"] ([string]$client["unit"]) }
    if ($null -ne $server) { $serverTxt = Format-Bps $server["rate"] ([string]$server["unit"]) }
    if ($clientTxt -eq "--" -and $serverTxt -eq "--") { return "" }

    $dirCn = "上行（TX）"
    if (-not $up) { $dirCn = "下行（RX，-R 反向）" }
    $verb = "接收"
    if (-not $up) { $verb = "发送" }
    $line = "客户端" + $dirCn + " " + $clientTxt + "，服务端" + $verb + " " + $serverTxt

    $key = "serial_client"
    if (-not $up) { $key = "serial_server" }
    $serial = ""
    if ($Test["params"] -is [System.Collections.IDictionary] -and $Test["params"].Contains($key)) {
        $serial = [string]$Test["params"][$key]
    }
    if ($serial -eq "" -and $Meta.Contains($key)) { $serial = [string]$Meta[$key] }
    $link = Get-LinkMbps $Devices $serial $up
    if ($null -ne $link -and $null -ne $client -and $null -ne $client["rate"]) {
        $factor = Get-UnitFactor ([string]$client["unit"])
        $pct = ([double]$client["rate"]) * $factor / $link * 100.0
        # 超过协商速率 1.5 倍说明这段流量没走空口（loopback / 同 AP 以太回程），算比值只会误导
        if ($pct -gt 150) {
            $line = $line + "，超过协商速率 " + (Format-Num $link) + " Mbps 的 " + $pct.ToString("F0", $script:Inv) + "%，说明这段数据没走空口，比值无参考意义"
        } else {
            $line = $line + "，达协商速率 " + (Format-Num $link) + " Mbps 的 " + $pct.ToString("F0", $script:Inv) + "%"
        }
    }
    return ($line + "。")
}

function Get-VerdictUdp {
    param($Test)
    $sender = $Test["sender"]
    $receiver = $Test["receiver"]
    $rateTxt = "--"
    if ($null -ne $sender) { $rateTxt = Format-Bps $sender["rate"] ([string]$sender["unit"]) }
    if ($rateTxt -eq "--" -and $null -ne $receiver) { $rateTxt = Format-Bps $receiver["rate"] ([string]$receiver["unit"]) }
    if ($rateTxt -eq "--") { return "" }
    $limit = ""
    if ($Test["params"] -is [System.Collections.IDictionary] -and $Test["params"].Contains("udp_bw")) {
        $limit = ([string]$Test["params"]["udp_bw"]).Trim()
    }
    $head = "UDP 实测 " + $rateTxt
    if ($limit -ne "") { $head = "UDP 限速 " + $limit + "，实测 " + $rateTxt }
    $st = $Test["stats"]
    $jit = ConvertTo-Number $st["jitter"]
    if ($null -ne $jit) { $head = $head + "，抖动 " + $jit.ToString("F3", $script:Inv) + " ms" }
    $lost = ConvertTo-Number $st["lost"]
    $total = ConvertTo-Number $st["total"]
    if ($null -ne $lost -and $null -ne $total) {
        $pct = ConvertTo-Number $st["lost_pct"]
        if ($null -eq $pct) { if ($total -ne 0) { $pct = $lost * 100.0 / $total } else { $pct = 0.0 } }
        $head = $head + "，丢包 " + (Format-Num $lost) + "/" + (Format-Num $total) + "（" + $pct.ToString("F3", $script:Inv) + "%）"
    }
    return ($head + "。")
}

function Get-VerdictLong {
    param($Test, $Meta)
    $sender = $Test["sender"]
    if ($null -eq $sender) { return "" }
    $rateTxt = Format-Bps $sender["rate"] ([string]$sender["unit"])
    if ($rateTxt -eq "--") { return "" }
    $dur = $null
    if ($Test["params"] -is [System.Collections.IDictionary] -and $Test["params"].Contains("duration")) { $dur = $Test["params"]["duration"] }
    if ($null -eq $dur -and $Meta.Contains("duration")) { $dur = $Meta["duration"] }
    $durTxt = Format-Num $dur
    if ($durTxt -eq "") { $durTxt = "?" }
    $head = "长稳测试 " + $durTxt + " 秒，平均 " + $rateTxt
    $st = $Test["stats"]
    $lo = ConvertTo-Number $st["min_rate"]
    $hi = ConvertTo-Number $st["max_rate"]
    if ($null -ne $lo -and $null -ne $hi) {
        $head = $head + "，最低 " + (Format-Bps $lo ([string]$st["unit"])) + "，最高 " + (Format-Bps $hi ([string]$st["unit"]))
    }
    if ($null -ne $st["retr_sum"]) { $head = $head + "，重传合计 " + (Format-Num $st["retr_sum"]) }
    $stall = ConvertTo-Number $st["stall_n"]
    if ($null -ne $stall -and $stall -gt 0) {
        $head = $head + "；其中 " + (Format-Num $stall) + " 秒完全停滞（0 bit/s），不是采样误差"
    }
    return ($head + "。")
}

function Get-ScreenCompare {
    param($Tests)
    $out = New-Object System.Collections.Specialized.OrderedDictionary
    $out["available"] = $false
    $out["on"] = $null
    $out["off"] = $null
    $out["delta_pct"] = $null
    $out["verdict"] = ""
    $on = $null
    $off = $null
    foreach ($t in @($Tests)) {
        $tid = [string]$t["test_id"]
        if ($tid -ne "screen_on" -and $tid -ne "screen_off") { continue }
        $st = $t["stats"]
        $rateVal = 0.0
        if ($null -ne $t["receiver"] -and $null -ne $t["receiver"]["rate"]) { $rateVal = [double]$t["receiver"]["rate"] }
        if ($null -eq $on -and $tid -eq "screen_on") { $on = $t }
        if ($null -eq $off -and $tid -eq "screen_off") { $off = $t }
    }
    if ($null -eq $on -or $null -eq $off) { return $out }
    $out["available"] = $true
    $out["on"] = $on["stats"]["avg_rate"]
    $out["off"] = $off["stats"]["avg_rate"]
    $aUnit = [string]$on["stats"]["unit"]
    $bUnit = [string]$off["stats"]["unit"]
    $a = $null
    $b = $null
    if ($null -ne $on["stats"]["avg_rate"]) { $a = [double]$on["stats"]["avg_rate"] * (Get-UnitFactor $aUnit) }
    if ($null -ne $off["stats"]["avg_rate"]) { $b = [double]$off["stats"]["avg_rate"] * (Get-UnitFactor $bUnit) }
    if ($null -eq $a -or $null -eq $b -or $a -le 0) { return $out }
    $delta = ($b - $a) * 100.0 / $a
    $out["delta_pct"] = $delta
    $aTxt = $a.ToString("F1", $script:Inv) + " Mbits/sec"
    $bTxt = $b.ToString("F1", $script:Inv) + " Mbits/sec"
    $body = ""
    if ([math]::Abs($delta) -lt 3) {
        $body = "灭屏后吞吐变化 " + $delta.ToString("F1", $script:Inv) + "%（" + $aTxt + " -> " + $bTxt + "），两者基本一致"
    } elseif ($delta -gt 0) {
        $body = "灭屏后吞吐提升 " + $delta.ToString("F1", $script:Inv) + "%（" + $aTxt + " -> " + $bTxt + "），亮屏对链路存在可见影响"
    } else {
        $body = "灭屏后吞吐下降 " + ([math]::Abs($delta)).ToString("F1", $script:Inv) + "%（" + $aTxt + " -> " + $bTxt + "），亮屏并未成为瓶颈"
    }
    $out["verdict"] = $body + "。"
    return $out
}

function New-Payload {
    param($Meta, $Devices, $Records, $Results, $Screen, $Notes)
    $metaN = Get-NormMeta $Meta
    $devs = @()
    foreach ($d in @($Devices)) {
        if ($d -is [System.Collections.IDictionary]) { $devs += $d }
    }
    if ([string]$metaN["iperf_version"] -eq "") {
        foreach ($d in $devs) {
            $v = [string]$d["iperf"]["version"]
            if ($v -ne "") { $metaN["iperf_version"] = $v; break }
        }
    }
    $recs = @()
    foreach ($r in @($Records)) {
        if ($r -is [System.Collections.IDictionary]) { $recs += $r }
    }
    $tests = @()
    $i = 0
    foreach ($res in @($Results)) {
        if ($null -eq $res) { continue }
        $tests += (Get-TestBlock $res $i $recs)
        $i = $i + 1
    }

    $noteList = @(Get-NormNotes $Notes)
    if ($metaN["pc_env"] -is [System.Collections.IDictionary] -and $metaN["pc_env"].Contains("has_wireless") -and $metaN["pc_env"]["has_wireless"] -eq $false) {
        $noteList += "PC 端未检测到无线网卡，PC 参与的隧道测试（pc_tunnel）不可用。"
    }
    foreach ($d in $devs) {
        if ($d["wifi"]["connected"] -eq $false) {
            $noteList += ("设备 " + [string]$d["serial"] + " 的 WiFi 未连接，SSID/频段/RSSI/协商速率等指标不可用。")
        }
    }
    foreach ($t in $tests) {
        if (-not $t["ok"] -and [string]$t["error"] -ne "") {
            $noteList += ("[错误] 测试 " + [string]$t["test_id"] + " 未成功：" + [string]$t["error"])
        } elseif ($t["records_n"] -eq 0) {
            $noteList += ("测试 " + [string]$t["test_id"] + " 未产生任何逐秒数据行。")
        }
        $lp = ConvertTo-Number $t["stats"]["lost_pct"]
        if ($null -ne $lp -and $lp -gt 1.0) {
            $noteList += ("测试 " + [string]$t["test_id"] + " 丢包率 " + $lp.ToString("F3", $script:Inv) + "% 超过 1%，链路可能存在干扰。")
        }
    }
    $noteList = @(Get-NormNotes $noteList)

    $verdicts = @()
    foreach ($t in $tests) {
        $tid = [string]$t["test_id"]
        if ($tid -eq "screen_on" -or $tid -eq "screen_off") { continue }
        $line = ""
        if ([string]$t["kind"] -eq "UDP") { $line = Get-VerdictUdp $t }
        elseif ($tid -eq "long_run") { $line = Get-VerdictLong $t $metaN }
        else { $line = Get-VerdictTcp $t $metaN $devs }
        if ($line -ne "") { $verdicts += $line }
    }
    $screenOut = Get-ScreenCompare $tests
    if ($screenOut["available"] -and [string]$screenOut["verdict"] -ne "") { $verdicts += [string]$screenOut["verdict"] }

    $tcpPcts = @()
    foreach ($t in $tests) {
        $tid = [string]$t["test_id"]
        if ($tid.StartsWith("screen_")) { continue }
        if ([string]$t["kind"] -ne "TCP") { continue }
        $up = $true
        if ($tid -eq "tcp_down") { $up = $false }
        $client = $t["receiver"]
        if ($up) { $client = $t["sender"] }
        if ($null -eq $client -or $null -eq $client["rate"]) { continue }
        $serial = ""
        $key = "serial_client"
        if (-not $up) { $key = "serial_server" }
        if ($t["params"] -is [System.Collections.IDictionary] -and $t["params"].Contains($key)) { $serial = [string]$t["params"][$key] }
        if ($serial -eq "" -and $metaN.Contains($key)) { $serial = [string]$metaN[$key] }
        $link = Get-LinkMbps $devs $serial $up
        if ($null -eq $link -or $link -le 0) { continue }
        $factor = Get-UnitFactor ([string]$client["unit"])
        $onePct = ([double]$client["rate"]) * $factor / $link * 100.0
        # 超过协商速率 1.5 倍的样本不代表空口能力，不参与结论计算
        if ($onePct -gt 0 -and $onePct -le 150) { $tcpPcts += $onePct }
    }

    $failed = 0
    foreach ($t in $tests) { if (-not $t["ok"]) { $failed = $failed + 1 } }

    $conclusion = ""
    if ($tests.Count -eq 0) {
        $conclusion = "结论：本次没有产生任何测试结果。"
    } elseif ($failed -gt 0) {
        $conclusion = "结论：本次 " + [string]$tests.Count + " 段测试中有 " + [string]$failed + " 段未成功，详见「六、异常与提示」。"
    } elseif ($tcpPcts.Count -gt 0) {
        $worst = ($tcpPcts | Measure-Object -Minimum).Minimum
        $best = ($tcpPcts | Measure-Object -Maximum).Maximum
        # 上下行差距大时只报最差值会和逐项结论打架，把区间写出来
        $pctTxt = $worst.ToString("F0", $script:Inv) + "%"
        if (($best - $worst) -ge 5) {
            $pctTxt = $worst.ToString("F0", $script:Inv) + "%~" + $best.ToString("F0", $script:Inv) + "%"
        }
        if ($worst -ge 70) {
            $conclusion = "结论：本次 " + [string]$tests.Count + " 段测试全部完成，吞吐达到协商速率的 " + $pctTxt + "，链路状态良好。"
        } elseif ($worst -ge 40) {
            $conclusion = "结论：本次 " + [string]$tests.Count + " 段测试全部完成，吞吐达协商速率的 " + $pctTxt + "，存在优化空间。"
        } else {
            $conclusion = "结论：本次 " + [string]$tests.Count + " 段测试全部完成，吞吐仅达协商速率的 " + $pctTxt + "，建议检查信道干扰、距离与后台占用。"
        }
    } else {
        $conclusion = "结论：本次 " + [string]$tests.Count + " 段测试全部完成。"
    }

    $best = $null
    if ($tcpPcts.Count -gt 0) { $best = ($tcpPcts | Measure-Object -Maximum).Maximum }

    $summary = New-Object System.Collections.Specialized.OrderedDictionary
    $summary["conclusion"] = $conclusion
    $summary["verdicts"] = $verdicts
    $summary["test_count"] = $tests.Count
    $summary["record_count"] = $recs.Count
    $summary["failed_count"] = $failed
    $summary["ok"] = ($tests.Count -gt 0 -and $failed -eq 0)
    $summary["best_tcp_pct"] = $best

    $payload = New-Object System.Collections.Specialized.OrderedDictionary
    $payload["meta"] = $metaN
    $payload["devices"] = $devs
    $payload["summary"] = $summary
    $payload["tests"] = $tests
    $payload["screen_compare"] = $screenOut
    $payload["notes"] = $noteList
    $payload["records"] = $recs
    return $payload
}

# =============================================================================
#  十三、Markdown 报告（对齐 report.py 的 render_markdown 六节结构）
# =============================================================================

function Get-ParamsLine {
    param($Test, $Meta)
    $bits = @()
    $p = $Test["params"]
    $dur = $null
    if ($p -is [System.Collections.IDictionary] -and $p.Contains("duration")) { $dur = $p["duration"] }
    if ($null -ne $dur) { $bits += ("时长 " + (Format-Num $dur) + " 秒") }
    $streams = $null
    if ($p -is [System.Collections.IDictionary] -and $p.Contains("streams")) { $streams = $p["streams"] }
    if ($null -ne $streams) { $bits += ((Format-Num $streams) + " 流") }
    $rev = $false
    if ($p -is [System.Collections.IDictionary] -and $p.Contains("reverse")) { $rev = [bool]$p["reverse"] }
    if ($rev) { $bits += "反向（-R）" } else { $bits += "正向" }
    $bits += [string]$Test["kind"]
    $bw = ""
    if ($p -is [System.Collections.IDictionary] -and $p.Contains("udp_bw")) { $bw = [string]$p["udp_bw"] }
    if ($bw -ne "") { $bits += ("UDP 限速 " + $bw) }
    $port = ""
    if ($p -is [System.Collections.IDictionary] -and $p.Contains("port")) { $port = [string]$p["port"] }
    if ($port -eq "" -and $Meta.Contains("port")) { $port = [string]$Meta["port"] }
    if ($port -ne "") { $bits += ("端口 " + $port) }
    return ($bits -join " · ")
}

function Get-DetailRow {
    param($Rec)
    $st = ConvertTo-Number $Rec["start"]
    $en = ConvertTo-Number $Rec["end"]
    $span = ""
    if ($null -ne $st -and $null -ne $en) { $span = (Format-F2 $st) + "-" + (Format-F2 $en) }
    if ($span -eq "") { $span = Format-T $Rec["stream"] }
    $retrTxt = Format-S $Rec["retr"]
    $retrCell = ""
    if ($retrTxt -ne "") { $retrCell = Format-T $Rec["retr"] } else { $retrCell = Format-T $Rec["total"] }
    return @(
        $span,
        (Format-Num $Rec["transfer"] 3),
        (Format-T $Rec["unit"]),
        (Format-Num $Rec["rate"] 3),
        (Format-T $Rec["rate_unit"]),
        $retrCell,
        (Format-Num $Rec["jitter"] 3),
        (Format-T $Rec["lost"]),
        (Format-T $Rec["total"]),
        (Format-Num $Rec["lost_pct"] 3)
    )
}

function Get-TestSection {
    param([int]$Index, $Test, $Meta)
    $out = @()
    $out += ("### 4." + [string]$Index + " " + [string]$Test["name"] + " · " + $script:BT + [string]$Test["test_id"] + $script:BT)
    $out += ""
    $out += ("参数：" + (Get-ParamsLine $Test $Meta))
    $out += ""
    $senderTxt = "--"
    $receiverTxt = "--"
    if ($null -ne $Test["sender"]) { $senderTxt = Format-Bps $Test["sender"]["rate"] ([string]$Test["sender"]["unit"]) }
    if ($null -ne $Test["receiver"]) { $receiverTxt = Format-Bps $Test["receiver"]["rate"] ([string]$Test["receiver"]["unit"]) }
    $out += ("发送端 " + $senderTxt + [string][char]0x3000 + "·" + [string][char]0x3000 + "接收端 " + $receiverTxt)
    $out += ""

    $recs = @($Test["records"])
    $stats = $Test["stats"]
    if ($recs.Count -eq 0) {
        $out += "> 本轮没有逐秒数据行，只有汇总结果。"
        $out += ""
        if ([string]$Test["error"] -ne "") {
            $out += ("> [错误] " + [string]$Test["error"])
            $out += ""
        }
        return $out
    }

    $sumRows = @()
    foreach ($r in $recs) { if (([string]$r["stream"]).ToUpper() -eq "SUM") { $sumRows += $r } }
    $multiSum = ($sumRows.Count -gt 1)
    $rows = @()
    $limit = 10
    if ($recs.Count -lt $limit) { $limit = $recs.Count }
    for ($i = 0; $i -lt $limit; $i++) {
        $r = $recs[$i]
        $cells = Get-DetailRow $r
        if ($multiSum -and ([string]$r["stream"]).ToUpper() -eq "SUM") {
            $roleCn = [string]$r["role"]
            if ($roleCn -eq "sender") { $roleCn = "发送端" }
            elseif ($roleCn -eq "receiver") { $roleCn = "接收端" }
            if ($roleCn -ne "") { $cells[0] = [string]$cells[0] + " · " + $roleCn }
        }
        $rows += , $cells
    }
    $statRow = @()
    $statRow += ("**统计（共 " + [string]$recs.Count + " 行）**")
    $statRow += (Format-Num $stats["transfer_sum"] 3)
    $statRow += (Format-T $stats["transfer_unit"])
    $unitTxt = [string]$stats["unit"]
    if ($unitTxt -eq "" -and $null -ne $Test["sender"]) { $unitTxt = [string]$Test["sender"]["unit"] }
    if ($unitTxt -eq "") { $unitTxt = "Mbits/sec" }
    $avgTxt = Format-Num $stats["avg_rate"] 3
    if ($avgTxt -eq "") { $avgTxt = "--" }
    $statRow += ("**" + $avgTxt + "**")
    $statRow += $unitTxt
    if ($null -ne $stats["retr_sum"]) { $statRow += (Format-T $stats["retr_sum"]) } else { $statRow += (Format-T $stats["total"]) }
    if ($null -ne $stats["jitter"]) { $statRow += ([double]$stats["jitter"]).ToString("F3", $script:Inv) } else { $statRow += "" }
    $statRow += (Format-T $stats["lost"])
    $statRow += (Format-T $stats["total"])
    if ($null -ne $stats["lost_pct"]) { $statRow += ([double]$stats["lost_pct"]).ToString("F3", $script:Inv) } else { $statRow += "" }
    $rows += , $statRow

    $head = @("区间", "传输量", "单位", "速率", "速率单位", "重传/数据报", "抖动ms", "丢包", "总包", "丢包率")
    $out += (Get-MdTable $head $rows)
    $out += ""
    if ($recs.Count -gt 10) {
        $out += ("> 逐秒明细共 " + [string]$recs.Count + " 行，此处仅列前 10 行；完整数据见 " + $script:BT + "逐秒明细.csv" + $script:BT + "。")
    }
    $bits = @()
    if ($null -ne $stats["avg_rate"]) { $bits += ("平均 " + (Format-Bps $stats["avg_rate"] $unitTxt)) }
    if ($null -ne $stats["min_rate"]) { $bits += ("最低 " + (Format-Bps $stats["min_rate"] $unitTxt)) }
    if ($null -ne $stats["max_rate"]) { $bits += ("最高 " + (Format-Bps $stats["max_rate"] $unitTxt)) }
    if ($null -ne $stats["retr_sum"]) { $bits += ("重传合计 " + (Format-Num $stats["retr_sum"])) }
    if ($bits.Count -gt 0) { $out += ("> 统计：" + ($bits -join "，") + "。") }
    $out += ""
    return $out
}

function Get-DeviceRows {
    param($Devices)
    $keys = @(
        @("型号", "model", ""),
        @("品牌", "brand", ""),
        @("序列号", "serial", "")
    )
    $rows = @()
    $i = 0
    $names = @("型号", "品牌", "序列号", "系统版本", "SDK", "ABI", "屏幕", "电量", "内存", "存储可用", "CPU 核心", "WiFi 开关", "WiFi 连接", "SSID", "BSSID", "频段", "频率", "RSSI", "协商速率", "802.11", "IP 地址", "默认网关", "热点", "iperf3")
    foreach ($nm in $names) { $rows += , @($nm) }
    $idx = 0
    foreach ($d in @($Devices)) {
        $w = $d["wifi"]
        $hs = $d["hotspot"]
        $bat = $d["battery"]
        $vals = @()
        $vals += (Format-T $d["model"])
        $vals += (Format-T $d["brand"])
        $vals += (Format-T $d["serial"])
        if ($null -ne $d["android"] -and [string]$d["android"] -ne "") { $vals += ("Android " + [string]$d["android"]) } else { $vals += "--" }
        $vals += (Format-T $d["sdk"])
        $vals += (Format-T $d["abi"])
        $scr = [string]$d["screen"]
        if ($scr -eq "Awake") { $vals += "Awake" }
        elseif ($scr -eq "Asleep") { $vals += "Asleep" }
        else { $vals += "Unknown" }
        $batBits = @()
        if ($null -ne $bat["level"]) { $batBits += ((Format-Num $bat["level"]) + "%") }
        if ([string]$bat["status"] -ne "") { $batBits += [string]$bat["status"] }
        if ($null -ne $bat["temp_c"]) { $batBits += ((Format-Num $bat["temp_c"] 1) + [string][char]0x2103) }
        if ($batBits.Count -eq 0) { $vals += "--" } else { $vals += ($batBits -join " · ") }
        if ($null -ne $d["ram_mb"]) { $vals += ((Format-Num $d["ram_mb"]) + " MB") } else { $vals += "--" }
        if ($null -ne $d["storage_free_gb"]) { $vals += ((Format-Num $d["storage_free_gb"] 1) + " GB") } else { $vals += "--" }
        $vals += (Format-T $d["cores"])
        $vals += (Format-T $w["enabled"])
        $vals += (Format-T $w["connected"])
        $vals += (Format-T $w["ssid"])
        $vals += (Format-T $w["bssid"])
        $vals += (Format-T $w["band"])
        if ($null -ne $w["freq_mhz"]) { $vals += ((Format-Num $w["freq_mhz"]) + " MHz") } else { $vals += "--" }
        if ($null -ne $w["rssi"]) { $vals += ((Format-Num $w["rssi"]) + " dBm") } else { $vals += "--" }
        $txTxt = "--"
        $rxTxt = "--"
        if ($null -ne $w["link_tx_mbps"]) { $txTxt = Format-Num $w["link_tx_mbps"] }
        if ($null -ne $w["link_rx_mbps"]) { $rxTxt = Format-Num $w["link_rx_mbps"] }
        $vals += ("TX " + $txTxt + " / RX " + $rxTxt + " Mbits/sec")
        $vals += (Format-T $w["standard"])
        $ipBits = @()
        foreach ($a in @($d["addrs"])) {
            $ipBits += ([string]$a["iface"] + " " + [string]$a["ip"] + "/" + [string]$a["prefix"])
        }
        if ($ipBits.Count -eq 0) { $vals += "--" } else { $vals += ($ipBits -join " · ") }
        $dr = $d["default_route"]
        if ([string]$dr["gw"] -ne "") {
            $ifn = [string]$dr["iface"]
            if ($ifn -eq "") { $ifn = "?" }
            $vals += ([string]$dr["gw"] + " (" + $ifn + ")")
        } else { $vals += "--" }
        if (-not $hs["on"]) {
            $vals += "关闭"
        } else {
            $hb = @("开启")
            if ([string]$hs["ssid"] -ne "") { $hb += ("SSID=" + [string]$hs["ssid"]) }
            if ([string]$hs["ip"] -ne "") { $hb += ("IP=" + [string]$hs["ip"]) }
            if ($null -ne $hs["clients"]) { $hb += ("客户端 " + (Format-Num $hs["clients"])) }
            $vals += ($hb -join " · ")
        }
        $ipf = $d["iperf"]
        if ($ipf["ok"]) {
            $v = [string]$ipf["version"]
            if ($v -eq "") { $v = "--" }
            $pth = [string]$ipf["path"]
            if ($pth -eq "") { $pth = "--" }
            $vals += ($v + "（" + $pth + "）")
        } else {
            $v = [string]$ipf["version"]
            if ($v -eq "") { $v = "未安装" }
            $vals += $v
        }
        for ($k = 0; $k -lt $names.Count; $k++) {
            if ($k -lt $vals.Count) { $rows[$k] += $vals[$k] } else { $rows[$k] += "--" }
        }
        $idx = $idx + 1
    }
    return $rows
}

function Save-Markdown {
    param([string]$Path, $Payload)
    $meta = $Payload["meta"]
    $tests = @($Payload["tests"])
    $devices = @($Payload["devices"])
    $out = @()

    $out += "# WiFi 吞吐量测试报告"
    $out += ""
    $head = @()
    $fin = [string]$meta["finished_at"]
    if ($fin -eq "") { $fin = [string]$meta["started_at"] }
    $head += ("生成时间 " + (Format-T $fin "-"))
    $head += ("脚本版本 " + (Format-T $meta["script_version"] "-"))
    $runDir = [string]$meta["run_dir"]
    if ($runDir -ne "") { $head += ("运行目录 " + $script:BT + (Split-Path -Leaf $runDir) + $script:BT) }
    $out += ("> " + ($head -join ([string][char]0x3000 + "·" + [string][char]0x3000)))
    $out += ""

    # 一、结论摘要
    $out += "## 一、结论摘要"
    $out += ""
    $verdicts = @($Payload["summary"]["verdicts"])
    if ($verdicts.Count -eq 0) {
        $out += "- 本次没有产生可判定的测试结果。"
    } else {
        foreach ($v in $verdicts) { $out += ("- " + [string]$v) }
    }
    $out += ("- **" + [string]$Payload["summary"]["conclusion"] + "**")
    $out += ""

    # 二、测试参数
    $out += "## 二、测试参数"
    $out += ""
    $items = @($meta["items"])
    $itemTxt = ""
    if ($items.Count -gt 0) {
        $parts = @()
        foreach ($it in $items) {
            $nm = [string]$it
            if ($script:ItemNames.ContainsKey($nm)) { $nm = $script:ItemNames[$nm] }
            $parts += ($nm + "（" + [string]$it + "）")
        }
        $itemTxt = $parts -join "、"
    } else {
        $parts = @()
        foreach ($t in $tests) {
            $tid = [string]$t["test_id"]
            if ($parts -contains $tid) { continue }
            $parts += $tid
        }
        $itemTxt = $parts -join "、"
    }
    $rows2 = @()
    $rows2 += , @("测试项", $itemTxt)
    $rows2 += , @("开始时间", (Format-T $meta["started_at"]))
    $rows2 += , @("结束时间", (Format-T $meta["finished_at"]))
    if ($null -ne $meta["duration"]) { $rows2 += , @("时长参数", ("每轮 " + (Format-Num $meta["duration"]) + " 秒")) }
    if ($null -ne $meta["streams"]) { $rows2 += , @("并发流数", (Format-Num $meta["streams"])) }
    $revTxt = "正向（客户端→服务端）"
    if ($meta["reverse"]) { $revTxt = "反向（-R，服务端→客户端）" }
    $rows2 += , @("方向", $revTxt)
    if ([string]$meta["udp_bw"] -ne "") { $rows2 += , @("UDP 限速", (Get-MdCell $meta["udp_bw"])) }
    if ([string]$meta["port"] -ne "") { $rows2 += , @("服务端口", (Format-Num $meta["port"])) }
    $rows2 += , @("iperf3 版本", (Get-MdCell $meta["iperf_version"]))
    $rows2 += , @("PC 环境", (Get-MdCell (Get-PcEnvText $meta["pc_env"])))
    $out += (Get-MdTable @("参数", "值") $rows2)
    $out += ""

    # 三、设备信息
    $out += "## 三、设备信息"
    $out += ""
    if ($devices.Count -eq 0) {
        $out += "> 本次未采集到设备信息。"
    } else {
        $heads = @("项目")
        $n = 0
        foreach ($d in $devices) {
            $n = $n + 1
            $heads += ("设备 " + [string]$n + " · " + (Format-T $d["model"] "未知型号") + " · " + (Format-T $d["serial"] "未知序列号"))
        }
        $out += (Get-MdTable $heads (Get-DeviceRows $devices))
    }
    $out += ""

    # 四、测试结果
    $out += "## 四、测试结果"
    $out += ""
    if ($tests.Count -eq 0) {
        $out += "> 本次未产生测试结果。"
        $out += ""
    } else {
        $n = 0
        foreach ($t in $tests) {
            $n = $n + 1
            foreach ($line in (Get-TestSection $n $t $meta)) { $out += $line }
        }
    }

    # 五、亮灭屏对比
    $out += "## 五、亮灭屏对比"
    $out += ""
    $sc = $Payload["screen_compare"]
    if ($sc["available"]) {
        $rows5 = @()
        $onTxt = "--"
        $offTxt = "--"
        if ($null -ne $sc["on"]) { $onTxt = ([double]$sc["on"]).ToString("F1", $script:Inv) + " Mbits/sec" }
        if ($null -ne $sc["off"]) { $offTxt = ([double]$sc["off"]).ToString("F1", $script:Inv) + " Mbits/sec" }
        $rows5 += , @("亮屏均值", $onTxt)
        $rows5 += , @("灭屏均值", $offTxt)
        $dTxt = "--"
        if ($null -ne $sc["delta_pct"]) { $dTxt = ([double]$sc["delta_pct"]).ToString("+0.0;-0.0;0.0", $script:Inv) + "%（灭屏相对亮屏）" }
        $rows5 += , @("变化百分比", $dTxt)
        $out += (Get-MdTable @("项目", "值") $rows5)
        $out += ""
        if ([string]$sc["verdict"] -ne "") { $out += ("- " + [string]$sc["verdict"]); $out += "" }
    } else {
        $out += "> 本次未执行亮灭屏对比测试（缺少 screen_on / screen_off 数据）。"
        $out += ""
    }

    # 六、异常与提示
    $out += "## 六、异常与提示"
    $out += ""
    $notes = @($Payload["notes"])
    if ($notes.Count -eq 0) {
        $out += "- 无异常，测试全程未发现需要处理的问题。"
    } else {
        foreach ($n in $notes) { $out += ("- " + [string]$n) }
    }

    Save-TextFile -Path $Path -Text (($out -join $script:LF) + $script:LF) -Bom $false
}

# =============================================================================
#  十四、手机端 iperf3（对齐 iperf.py 的 ensure_phone_bin / start_server / run_client）
# =============================================================================

function Test-RemoteFile {
    param([string]$Serial, [string]$Path)
    $out = Invoke-AdbShellOut -Serial $Serial -Command ("ls -l " + $Path + " 2>/dev/null") -TimeoutSec 20
    if ($out.Trim() -eq "") { return $false }
    if ($out.Contains("No such file")) { return $false }
    return $true
}

function Get-PhoneVersion {
    param([string]$Serial, [string]$Path)
    $out = Invoke-AdbShellOut -Serial $Serial -Command ($Path + " -v 2>&1") -TimeoutSec 20
    foreach ($line in ($out -split "[\r\n]+")) {
        $s = $line.Trim()
        if ($s -eq "") { continue }
        if ($s.ToLower().StartsWith("iperf")) { return $s }
    }
    return ""
}

function Ensure-PhoneBin {
    param([string]$Serial, [string]$LocalPath, [scriptblock]$Log)
    if (-not (Test-Path -LiteralPath $LocalPath)) {
        throw ("本地缺少手机端 iperf3 二进制|找不到文件：" + $LocalPath + "（应为 iperf 3.22 的 arm64-v8a 静态二进制，" + [string]$script:LOCAL_BIN_BYTES + " 字节）|把 iperf3-arm64v8（" + [string]$script:LOCAL_BIN_BYTES + " 字节）放到 " + $LocalPath)
    }
    $size = (Get-Item -LiteralPath $LocalPath).Length
    if ($size -ne $script:LOCAL_BIN_BYTES) {
        throw ("本地 iperf3 二进制大小不对|" + $LocalPath + " 是 " + [string]$size + " 字节，应为 " + [string]$script:LOCAL_BIN_BYTES + " 字节；3.1.x 版本做客户端会报 unable to create a new stream|换成 iperf 3.22 的 arm64-v8a 版本（" + [string]$script:LOCAL_BIN_BYTES + " 字节）后重试")
    }
    $ver = ""
    if (Test-RemoteFile -Serial $Serial -Path $script:PHONE_BIN) {
        $ver = Get-PhoneVersion -Serial $Serial -Path $script:PHONE_BIN
        if ($ver.Contains($script:GOOD_VERSION)) {
            if ($null -ne $Log) { & $Log ("      OK  手机端 iperf3 已就绪：" + $ver) }
            $r = @{ path = $script:PHONE_BIN; version = $ver; pushed = $false; ok = $true }
            return $r
        }
    }
    if ($null -ne $Log) { & $Log ("      --  推送 " + $LocalPath + " -> " + $script:PHONE_BIN) }
    $push = Invoke-Adb -Arguments ("-s " + (Quote-Arg $Serial) + " push " + (Quote-Arg $LocalPath) + " " + $script:PHONE_BIN) -TimeoutSec 180
    if ($push.timedOut) {
        throw ("推送 iperf3 超时|adb push 超过 180 秒未返回|确认 USB 线稳定、手机可写 /data/local/tmp 后重试")
    }
    if ($push.rc -ne 0) {
        $msg = ([string]$push.err).Trim()
        if ($msg -eq "") { $msg = ([string]$push.out).Trim() }
        throw ("推送 iperf3 失败|" + $msg + "|确认手机可写 /data/local/tmp 且 adb 未被其它进程占用")
    }
    [void](Invoke-AdbShell -Serial $Serial -Command ("chmod 755 " + $script:PHONE_BIN) -TimeoutSec 20)
    $ver = Get-PhoneVersion -Serial $Serial -Path $script:PHONE_BIN
    if (-not $ver.Contains($script:GOOD_VERSION)) {
        if ($null -ne $Log) { & $Log ("      !!  推送后版本仍不合格：" + $ver) }
    } else {
        if ($null -ne $Log) { & $Log ("      OK  推送完成：" + $ver) }
    }
    $r = @{ path = $script:PHONE_BIN; version = $ver; pushed = $true; ok = $ver.Contains($script:GOOD_VERSION) }
    return $r
}

function Test-Listening {
    param([string]$Serial, [int]$Port)
    $out = Invoke-AdbShellOut -Serial $Serial -Command ("netstat -ltn 2>/dev/null | grep " + [string]$Port) -TimeoutSec 20
    if ($out.Trim() -eq "") { return $false }
    $pat = "[:.]" + [string]$Port + "(?![0-9])"
    foreach ($line in ($out -split "[\r\n]+")) {
        $s = $line.Trim()
        if ($s -eq "") { continue }
        if (-not $s.ToUpper().Contains("LISTEN")) { continue }
        $fields = $s -split "\s+"
        $target = $s
        if ($fields.Count -gt 3) { $target = $fields[3] }
        if ([regex]::IsMatch($target, $pat)) { return $true }
    }
    return $false
}

function Stop-Server {
    param([string]$Serial)
    try {
        [void](Invoke-AdbShell -Serial $Serial -Command "pkill -9 -f iperf3" -TimeoutSec 15)
    } catch { }
}

function Start-Server {
    param([string]$Serial, [int]$Port, [scriptblock]$Log)
    # 要点：pkill 与 nohup 必须是两次独立的 adb shell 调用，合成一条会报
    #       iperf_srv.log: No such file or directory
    try {
        [void](Invoke-AdbShell -Serial $Serial -Command "pkill -9 -f iperf3" -TimeoutSec 15)
    } catch {
        if ($null -ne $Log) { & $Log ("      --  pkill 未完成：" + [string]$_.Exception.Message) }
    }
    if ($null -ne $Log) { & $Log "      --  等待旧服务端退出（2s）" }
    Start-Sleep -Seconds 2
    $cmd = "nohup " + $script:PHONE_BIN + " -s -p " + [string]$Port + " > " + $script:SRV_LOG + " 2>&1 &"
    $r = Invoke-AdbShell -Serial $Serial -Command $cmd -TimeoutSec 20
    if ($r.timedOut) {
        return @{ ok = $false; pid = ""; detail = "启动命令超时（20s）" }
    }
    if ($null -ne $Log) { & $Log "      --  等待服务端监听（3s）" }
    Start-Sleep -Seconds 3
    $listening = Test-Listening -Serial $Serial -Port $Port
    $detail = Invoke-AdbShellOut -Serial $Serial -Command ("netstat -ltn 2>/dev/null | grep " + [string]$Port) -TimeoutSec 20
    if ($detail.Trim() -eq "") {
        $detail = Invoke-AdbShellOut -Serial $Serial -Command ("cat " + $script:SRV_LOG + " 2>/dev/null") -TimeoutSec 20
    }
    $pidTxt = ""
    $pidOut = Invoke-AdbShellOut -Serial $Serial -Command "pidof iperf3 2>/dev/null" -TimeoutSec 20
    $pidOut = $pidOut.Trim()
    if ($pidOut -ne "") {
        $tok = $pidOut -split "\s+"
        if ($tok.Count -gt 0) { $pidTxt = $tok[0] }
    }
    if ($listening) {
        if ($null -ne $Log) { & $Log ("      OK  服务端已监听 " + [string]$Port + "（pid " + $pidTxt + "）") }
    } else {
        if ($null -ne $Log) { & $Log ("      !!  服务端未监听 " + [string]$Port + "：" + ([string]$detail).Trim()) }
    }
    return @{ ok = $listening; pid = $pidTxt; detail = ([string]$detail).Trim() }
}

function Get-IperfSummary {
    param($Records)
    $sum = @{
        sender = @{ rate = 0.0; unit = "" }
        receiver = @{ rate = 0.0; unit = "" }
    }
    $rank = @{ sender = 0; receiver = 0 }
    foreach ($rec in @($Records)) {
        $role = [string]$rec["role"]
        if ($role -ne "sender" -and $role -ne "receiver") { continue }
        $pri = 1
        if ([string]$rec["stream"] -eq "SUM") { $pri = 2 }
        if ($pri -lt $rank[$role]) { continue }
        $rank[$role] = $pri
        $rateVal = 0.0
        $rv = ConvertTo-Number $rec["rate"]
        if ($null -ne $rv) { $rateVal = $rv }
        $sum[$role] = @{ rate = $rateVal; unit = [string]$rec["rate_unit"] }
    }
    return $sum
}

function Get-IperfErrorLine {
    param([string]$Text, [string]$Fallback)
    foreach ($line in ($Text -split "[\r\n]+")) {
        $low = $line.ToLower()
        if ($low.Contains("iperf3: error")) { return $line.Trim() }
        if ($low.StartsWith("error")) { return $line.Trim() }
        if ($low.Contains("unable to")) { return $line.Trim() }
    }
    return $Fallback
}

function Run-IperfClient {
    param(
        [string]$Serial,
        [string]$Ip,
        [int]$Port,
        [int]$Duration,
        [int]$Streams,
        [bool]$Reverse,
        [string]$TestId,
        [string]$Kind,
        [scriptblock]$OnRecord,
        [scriptblock]$OnRaw,
        [scriptblock]$Log
    )
    $safeId = [regex]::Replace($TestId, '[^\w.\-]', '_')
    if ($safeId -eq "") { $safeId = "run" }
    $remote = "/data/local/tmp/iperf_" + $safeId + ".txt"

    $argv = @($script:PHONE_BIN, "-c", $Ip, "-p", [string]$Port, "-t", [string]$Duration, "-P", [string]$Streams)
    if ($Reverse) { $argv += "-R" }
    if ($Kind -eq "UDP") { $argv += "-u" }
    $argv += "--forceflush"
    $shellCmd = ($argv -join " ") + " 2>&1 | tee " + $remote

    if ($null -ne $Log) { & $Log ("      --  " + $Kind + " " + (($argv[1..($argv.Count - 1)]) -join " ")) }

    $records = New-Object System.Collections.ArrayList
    $rawLines = New-Object System.Collections.ArrayList
    $t0 = Get-Date
    $testId = $TestId
    $kind = $Kind

    $handler = {
        param($line)
        $tstamp = (Get-Date).ToString("HH:mm:ss")
        $elapsed = ((Get-Date) - $t0).TotalSeconds
        $s = [string]$line
        if ($s.Trim() -eq "") { return }
        [void]$rawLines.Add($s)
        $rec = ConvertFrom-IperfLine -Line $s -TestId $testId -Kind $kind -Elapsed $elapsed -Time $tstamp
        if ($null -ne $rec) {
            [void]$records.Add($rec)
            if ($null -ne $OnRecord) { & $OnRecord $rec }
        } else {
            if ($null -ne $OnRaw) { & $OnRaw $s }
        }
    }

    $adb = Get-AdbPath
    $argline = "-s " + (Quote-Arg $Serial) + " shell " + (Quote-Arg $shellCmd)
    $stream = Invoke-Stream -FilePath $adb -Arguments $argline -TimeoutSec ($Duration + 90) -OnLine $handler

    $joined = (@($rawLines) -join $script:LF)
    $rawText = ""
    try {
        $rawText = [string](Invoke-AdbShellOut -Serial $Serial -Command ("cat " + $remote + " 2>/dev/null") -TimeoutSec 25)
    } catch { $rawText = "" }

    # 要点：adb shell 的退出码被管道吞掉、恒为 0，成败只能看落盘文件里有没有 iperf Done.
    $ok = $rawText.Contains($script:DONE_MARK)
    $error = ""
    if (-not $ok) {
        if ($rawText.Trim() -eq "" -and $joined.Trim() -eq "") {
            $error = "[错误] 没有收到任何 iperf3 输出" + $script:LF + "原因  ：adb shell 无输出，手机端进程可能未启动" + $script:LF + "处理办法：确认手机在线（adb devices）且 " + $script:PHONE_BIN + " 可执行（chmod 755）"
        } else {
            $src = $rawText
            if ($src.Trim() -eq "") { $src = $joined }
            $fallback = "落盘文件 " + $remote + " 中没有 iperf Done. 标记，判定为失败"
            $error = Get-IperfErrorLine -Text $src -Fallback $fallback
        }
    }
    if ($stream.timedOut) {
        $ok = $false
        $error = "客户端执行超过 " + [string]($Duration + 90) + " 秒被强制终止"
    }

    $finalRaw = $rawText
    if ($finalRaw.Trim() -eq "") { $finalRaw = $joined }
    return @{
        ok = $ok
        test_id = $TestId
        raw_path_phone = $remote
        raw_text = $finalRaw
        summary = (Get-IperfSummary $records)
        error = $error
        records = @($records)
        joined = $joined
    }
}

# =============================================================================
#  十五、结果落盘（4 个文件，文件名与 Python 引擎完全一致）
# =============================================================================

function New-Context {
    $ctx = @{
        records = (New-Object System.Collections.ArrayList)
        results = (New-Object System.Collections.ArrayList)
        raws = @{}
        notes = (New-Object System.Collections.ArrayList)
    }
    return $ctx
}

function Add-Note {
    param($Ctx, [string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return }
    [void]$Ctx.notes.Add($Text)
}

function Save-All {
    param([string]$BaseOut, $Items, $Meta, $Ctx, $Devices)
    $outDir = New-RunDir -Base $BaseOut -Items $Items
    $m = New-Object System.Collections.Specialized.OrderedDictionary
    foreach ($k in $Meta.Keys) { $m[$k] = $Meta[$k] }
    $m["run_dir"] = $outDir

    $payload = New-Payload -Meta $m -Devices $Devices -Records @($Ctx.records) -Results @($Ctx.results) -Screen $null -Notes @($Ctx.notes)

    $csvPath = Join-Path $outDir "逐秒明细.csv"
    $jsonPath = Join-Path $outDir "汇总.json"
    $mdPath = Join-Path $outDir "测试报告.md"

    Save-Csv -Path $csvPath -Records @($Ctx.records)
    Save-TextFile -Path $jsonPath -Text ((ConvertTo-JsonText $payload 0) + $script:LF) -Bom $false
    Save-Markdown -Path $mdPath -Payload $payload

    $rawPaths = @()
    foreach ($tid in $Ctx.raws.Keys) {
        $text = [string]$Ctx.raws[$tid]
        if ($text -eq "") { continue }
        $rp = Join-Path $outDir ("原始输出_" + $tid + ".txt")
        Save-TextFile -Path $rp -Text $text -Bom $false
        $rawPaths += $rp
    }

    Out-Line ""
    Out-Line "  结果已保存到："
    Out-Line ("    " + $outDir)
    $files = @($csvPath, $jsonPath, $mdPath) + $rawPaths
    foreach ($f in $files) {
        $size = (Get-Item -LiteralPath $f).Length
        $name = Split-Path -Leaf $f
        Out-Line ("      " + $name.PadRight(30) + ([string]$size).PadLeft(9) + " 字节")
    }
    return $outDir
}

# =============================================================================
#  十六、list / run / menu
# =============================================================================

function Read-AllDeviceInfo {
    param($Devices)
    $infos = @()
    foreach ($d in @($Devices)) {
        Out-Info ("读取设备信息 " + [string]$d.serial + " ...")
        $infos += (Read-DeviceInfo -Serial ([string]$d.serial))
    }
    return $infos
}

function Cmd-List {
    $devices = Get-Devices
    if (@($devices).Count -eq 0) {
        return (Show-Die "没有检测到已连接的手机" "adb devices 列表为空" "用 USB 连上手机，打开「开发者选项 → USB 调试」，在手机上点允许本机调试后重试")
    }
    $infos = @(Read-AllDeviceInfo $devices)
    Out-Line ""
    Out-Line ("  发现 " + [string](@($infos).Count) + " 台已就绪设备：")
    Out-Line ""
    $i = 0
    foreach ($info in $infos) {
        $i = $i + 1
        Show-DeviceCard -Info $info -Index $i
    }
    Out-Line ""
    Show-SummaryTable -Infos $infos
    Out-Line ""
    return 0
}

function Select-Roles {
    param($Infos, [string]$ServerSerial, [string]$ClientSerial)
    $srv = $null
    $cli = $null
    foreach ($info in @($Infos)) {
        if ($ServerSerial -ne "" -and [string]$info["serial"] -eq $ServerSerial) { $srv = $info }
        if ($ClientSerial -ne "" -and [string]$info["serial"] -eq $ClientSerial) { $cli = $info }
    }
    if ($null -eq $srv -and $ServerSerial -ne "") {
        throw ("找不到指定的服务端手机|序列号 " + $ServerSerial + " 不在已连接设备列表中|用 list 查看可用序列号，或用 -s 指定正确的序列号")
    }
    if ($null -eq $cli -and $ClientSerial -ne "") {
        throw ("找不到指定的客户端手机|序列号 " + $ClientSerial + " 不在已连接设备列表中|用 list 查看可用序列号，或用 -c 指定正确的序列号")
    }
    if ($null -eq $srv -and $null -eq $cli) {
        if (@($Infos).Count -lt 2) {
            throw ("设备数量不足|当前只检测到 " + [string](@($Infos).Count) + " 台设备，吞吐量测试需要两台|用 USB 同时连上两台手机，或让另一台通过无线调试连上后重试")
        }
        $srv = $Infos[0]
        $cli = $Infos[1]
    } elseif ($null -eq $srv) {
        foreach ($info in @($Infos)) { if ($info["serial"] -ne $cli["serial"]) { $srv = $info; break } }
    } elseif ($null -eq $cli) {
        foreach ($info in @($Infos)) { if ($info["serial"] -ne $srv["serial"]) { $cli = $info; break } }
    }
    if ($null -eq $srv -or $null -eq $cli) {
        throw ("无法确定服务端/客户端|两台手机需要各占一个角色|用 -s 与 -c 分别指定服务端和客户端的序列号")
    }
    if ([string]$srv["serial"] -eq [string]$cli["serial"]) {
        throw ("服务端和客户端是同一台手机|同一台设备无法自己对自己打流|用 -s 与 -c 指定两台不同的手机")
    }
    return @{ server = $srv; client = $cli }
}

function Get-ServerIp {
    param($SrvInfo)
    $hs = $SrvInfo["hotspot"]
    if ($hs["on"] -and [string]$hs["ip"] -ne "") {
        return @{ ip = [string]$hs["ip"]; source = "热点地址" }
    }
    foreach ($a in @($SrvInfo["addrs"])) {
        if ([string]$a["iface"] -eq "wlan0") { return @{ ip = [string]$a["ip"]; source = "WiFi 地址" } }
    }
    foreach ($a in @($SrvInfo["addrs"])) {
        if ([string]$a["ip"] -ne "127.0.0.1") {
            $src = [string]$a["iface"]
            if ($src -eq "") { $src = "地址" }
            return @{ ip = [string]$a["ip"]; source = $src }
        }
    }
    return @{ ip = ""; source = "" }
}

function Test-Reach {
    param([string]$Serial, [string]$Ip)
    $out = Invoke-AdbShellOut -Serial $Serial -Command ("ping -c 2 -W 2 " + $Ip) -TimeoutSec 20
    if ($out.Contains("0% packet loss") -or $out.Contains("0 packets lost")) { return @{ ok = $true; text = "ping 通" } }
    $last = ""
    foreach ($line in ($out -split "[\r\n]+")) { if ($line.Trim() -ne "") { $last = $line.Trim() } }
    return @{ ok = $false; text = ("ping 不通（" + $last + "）") }
}

function Invoke-OneTest {
    param($Ctx, [string]$TestId, [string]$Kind, [bool]$Reverse, [int]$Duration, [int]$Streams, [string]$Title, [string]$ExpectDir, [string]$ServerIp, [int]$Port, [string]$ClientSerial)
    Out-Rule $Title
    if ($ExpectDir -ne "") { Out-Line ("  读表  #N=单流  SUM=合计  " + $ExpectDir) }
    Out-Line ""

    $onRec = {
        param($rec)
        Out-Line (Format-RecordLine $rec)
    }
    $onRaw = {
        param($line)
        $low = ([string]$line).ToLower()
        if ($low.Contains("error") -or $low.Contains("unable") -or $low.Contains("warning")) {
            Out-Warn ([string]$line)
            Add-Note $Ctx ("[" + $TestId + "] " + [string]$line)
        }
    }
    $t0 = Get-Date
    $res = Run-IperfClient -Serial $ClientSerial -Ip $ServerIp -Port $Port -Duration $Duration -Streams $Streams -Reverse $Reverse -TestId $TestId -Kind $Kind -OnRecord $onRec -OnRaw $onRaw -Log $null
    $elapsed = [int](((Get-Date) - $t0).TotalSeconds)

    foreach ($r in @($res.records)) { [void]$Ctx.records.Add($r) }
    if ([string]$res.raw_text -ne "") { $Ctx.raws[$TestId] = [string]$res.raw_text }

    $sum = $res.summary
    $senderObj = $null
    $receiverObj = $null
    if ($null -ne $sum) {
        if ($null -ne $sum["sender"]) {
            $senderObj = New-Object System.Collections.Specialized.OrderedDictionary
            $senderObj["rate"] = $sum["sender"]["rate"]
            $senderObj["unit"] = $sum["sender"]["unit"]
        }
        if ($null -ne $sum["receiver"]) {
            $receiverObj = New-Object System.Collections.Specialized.OrderedDictionary
            $receiverObj["rate"] = $sum["receiver"]["rate"]
            $receiverObj["unit"] = $sum["receiver"]["unit"]
        }
    }
    $params = New-Object System.Collections.Specialized.OrderedDictionary
    $params["duration"] = $Duration
    $params["streams"] = $Streams
    $params["reverse"] = $Reverse
    $params["udp"] = ($Kind -eq "UDP")
    $params["port"] = $Port
    $params["serial_client"] = $ClientSerial

    $result = New-TestResult -TestId $TestId -Kind $Kind -Sender $senderObj -Receiver $receiverObj -Ok ([bool]$res.ok) -Error ([string]$res.error) -Params $params
    [void]$Ctx.results.Add($result)

    Out-Line ""
    if ($res.ok) {
        Out-Ok ($TestId + " 完成，用时 " + [string]$elapsed + " 秒，" + [string](@($res.records).Count) + " 行数据")
    } else {
        Out-Err ($TestId + " 失败：" + [string]$res.error)
        Add-Note $Ctx ("[" + $TestId + "] " + [string]$res.error)
    }
}

function Cmd-Run {
    param($Opts)
    Out-Banner
    $items = @($Opts["items"])
    $bad = @()
    foreach ($it in $items) {
        if ($script:UnsupportedItems -contains $it) { $bad += $it }
        elseif (-not ($script:SupportedItems -contains $it)) { $bad += $it }
    }
    if ($bad.Count -gt 0) {
        $names = @()
        foreach ($b in $bad) {
            $nm = [string]$b
            if ($script:ItemNames.ContainsKey($nm)) { $nm = $script:ItemNames[$nm] }
            $names += ($nm + "（" + [string]$b + "）")
        }
        return (Show-Die ("降级引擎不支持：" + ($names -join "、")) "当前电脑没有安装 Python，只能使用 PowerShell 降级引擎，它只实现了 WiFi 吞吐量测试（wifi_tput）与热点吞吐量测试（hotspot_tput）" "安装 Python 3.8 以上版本后重新双击 启动.bat，即可使用全部测试项；或改用 -i wifi_tput / -i hotspot_tput")
    }
    if ($items.Count -eq 0) { $items = @("wifi_tput") }

    $devices = Get-Devices
    if (@($devices).Count -eq 0) {
        return (Show-Die "没有检测到已连接的手机" "adb devices 列表为空" "用 USB 连上两台手机，打开「开发者选项 → USB 调试」，在手机上点允许本机调试后重试")
    }
    $infos = @(Read-AllDeviceInfo $devices)
    try {
        $roles = Select-Roles -Infos $infos -ServerSerial ([string]$Opts["server"]) -ClientSerial ([string]$Opts["client"])
    } catch {
        $parts = ([string]$_.Exception.Message) -split '\|'
        $t = "选择服务端/客户端失败"
        $r = $parts[0]
        $f = "用 list 查看设备后重试"
        if ($parts.Count -ge 2) { $r = $parts[1] }
        if ($parts.Count -ge 3) { $f = $parts[2] }
        if ($parts.Count -ge 1 -and $parts[0] -ne "") { $t = $parts[0] }
        return (Show-Die $t $r $f)
    }
    $srvInfo = $roles.server
    $cliInfo = $roles.client

    $ctx = New-Context
    $port = [int]$Opts["port"]
    $duration = [int]$Opts["duration"]
    $streams = [int]$Opts["streams"]
    $reverse = [bool]$Opts["reverse"]
    $startedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    $srvSerial = [string]$srvInfo["serial"]
    $cliSerial = [string]$cliInfo["serial"]

    $started = $false
    $rc = 0
    try {
        # 1) iperf3 二进制
        Out-Rule "准备 iperf3 二进制"
        $logSb = { param($t) Out-Line ([string]$t) }
        $versions = @{}
        foreach ($pair in @(@("服务端", $srvSerial), @("客户端", $cliSerial))) {
            $roleName = $pair[0]
            $ser = $pair[1]
            Out-Step 1 3 ($roleName + " " + $ser + " 检查 iperf3")
            try {
                $r = Ensure-PhoneBin -Serial $ser -LocalPath $script:LOCAL_BIN -Log $logSb
            } catch {
                $parts = ([string]$_.Exception.Message) -split '\|'
                $t = "在 " + $roleName + " 上准备 iperf3 失败"
                $reason = "未知错误"
                $fix = "确认手机可写 /data/local/tmp 后重试"
                if ($parts.Count -ge 1 -and $parts[0] -ne "") { $t = "在 " + $roleName + " 上准备 iperf3 失败：" + $parts[0] }
                if ($parts.Count -ge 2) { $reason = $parts[1] }
                if ($parts.Count -ge 3) { $fix = $parts[2] }
                Out-Err $t
                return (Show-Die $t $reason $fix)
            }
            $versions[$ser] = [string]$r.version
            $tail = ""
            if ($r.pushed) { $tail = "（本次已推送）" }
            $vtxt = [string]$r.version
            if ($vtxt -eq "") { $vtxt = "已就绪" }
            Out-Ok ($vtxt + " " + $tail)
        }

        # 2) 服务端地址
        $ipInfo = Get-ServerIp $srvInfo
        Out-Step 2 3 "服务端地址"
        if ([string]$ipInfo.ip -eq "") {
            return (Show-Die "服务端手机没有可用 IP" ("手机 " + $srvSerial + " 既没有连上 WiFi，也没有开热点") "让这台手机连上同一个 WiFi，或让它开热点、另一台连上去后重试")
        }
        Out-Ok ([string]$ipInfo.ip + "（取自 " + [string]$ipInfo.source + "）")
        Write-ServerAddrs -Info $srvInfo -Serial $srvSerial
        $reach = Test-Reach -Serial $cliSerial -Ip ([string]$ipInfo.ip)
        if (-not $reach.ok) {
            return (Show-Die "两台手机网络不通" ($reach.text + "；两台手机不在同一网段或热点没开，无法测真实空口吞吐") "让两台手机连同一个 WiFi，或让其中一台开热点、另一台连上去后重试")
        }
        Out-Ok ([string]$reach.text)

        # 3) 启动服务端
        Out-Step 3 3 "启动服务端 iperf3"
        $srvRes = Start-Server -Serial $srvSerial -Port $port -Log $logSb
        if (-not $srvRes.ok) {
            $detail = [string]$srvRes.detail
            if ($detail -eq "") { $detail = "端口 " + [string]$port + " 没有进入 LISTEN 状态" }
            return (Show-Die "服务端启动失败" $detail ("1) 换一个端口重跑（-p 5301）；2) 确认手机端 " + $script:PHONE_BIN + " 可执行（chmod 755）；3) 重新插拔 USB 后重试"))
        }
        $started = $true
        $pidTxt = [string]$srvRes.pid
        if ($pidTxt -ne "") { Out-Ok ("监听 " + [string]$port + " 端口，PID " + $pidTxt) } else { Out-Ok ("监听 " + [string]$port + " 端口") }

        # 4) 跑测试
        foreach ($item in $items) {
            if ($item -eq "wifi_tput") {
                Invoke-OneTest -Ctx $ctx -TestId "tcp_up" -Kind "TCP" -Reverse $false -Duration $duration -Streams $streams -Title "WiFi 吞吐量测试 · 上行（客户端发、服务端收）" -ExpectDir "本机发出数据，上行看 receiver" -ServerIp ([string]$ipInfo.ip) -Port $port -ClientSerial $cliSerial
                if ($reverse) {
                    Invoke-OneTest -Ctx $ctx -TestId "tcp_down" -Kind "TCP" -Reverse $true -Duration $duration -Streams $streams -Title "WiFi 吞吐量测试 · 下行（-R：服务端发、客户端收）" -ExpectDir "本机接收数据，下行看 receiver" -ServerIp ([string]$ipInfo.ip) -Port $port -ClientSerial $cliSerial
                }
            } elseif ($item -eq "hotspot_tput") {
                $hs = $srvInfo["hotspot"]
                if (-not $hs["on"]) {
                    Add-Note $ctx ("服务端手机 " + $srvSerial + " 当前没有开热点，热点吞吐量测试按普通 WiFi 链路跑")
                    Out-Warn ("服务端手机 " + $srvSerial + " 当前没有开热点，热点吞吐量测试按普通 WiFi 链路跑")
                } else {
                    $band = [string]$hs["ssid"]
                    $freq = "--"
                    if ($null -ne $hs["freq_mhz"]) { $freq = [string]$hs["freq_mhz"] }
                    $cliN = "--"
                    if ($null -ne $hs["clients"]) { $cliN = [string]$hs["clients"] }
                    Out-Info ("服务端热点 SSID=" + $band + " 频段 " + $freq + " MHz 客户端数 " + $cliN)
                }
                Invoke-OneTest -Ctx $ctx -TestId "tcp_up" -Kind "TCP" -Reverse $false -Duration $duration -Streams $streams -Title "热点吞吐量测试 · 上行（连热点那台发、开热点那台收）" -ExpectDir "本机发出数据，上行看 receiver" -ServerIp ([string]$ipInfo.ip) -Port $port -ClientSerial $cliSerial
                if ($reverse) {
                    Invoke-OneTest -Ctx $ctx -TestId "tcp_down" -Kind "TCP" -Reverse $true -Duration $duration -Streams $streams -Title "热点吞吐量测试 · 下行（-R：开热点那台发、连热点那台收）" -ExpectDir "本机接收数据，下行看 receiver" -ServerIp ([string]$ipInfo.ip) -Port $port -ClientSerial $cliSerial
                }
            }
        }
    } finally {
        if ($started) { Stop-Server -Serial $srvSerial }
    }

    if (@($ctx.records).Count -eq 0 -and @($ctx.results).Count -eq 0) {
        Out-Line ""
        Out-Line "  没有采集到任何数据，未生成结果文件"
        return 1
    }

    $finishedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    $meta = New-Object System.Collections.Specialized.OrderedDictionary
    $meta["tool"] = $script:TOOL_NAME
    $meta["version"] = $script:ENGINE_VERSION
    $meta["started_at"] = $startedAt
    $meta["finished_at"] = $finishedAt
    $meta["items"] = $items
    $metaParams = New-Object System.Collections.Specialized.OrderedDictionary
    $metaParams["duration"] = $duration
    $metaParams["streams"] = $streams
    $metaParams["reverse"] = $reverse
    $metaParams["udp_bw"] = ""
    $metaParams["port"] = $port
    $meta["params"] = $metaParams
    $meta["server_serial"] = $srvSerial
    $meta["client_serial"] = $cliSerial
    $meta["server_ip"] = [string]$ipInfo.ip
    $meta["iperf_version"] = [string]$versions[$srvSerial]
    $pcEnv = New-Object System.Collections.Specialized.OrderedDictionary
    $pcEnv["os"] = [System.Environment]::OSVersion.VersionString
    $meta["pc"] = $pcEnv

    [void](Save-All -BaseOut ([string]$Opts["out"]) -Items $items -Meta $meta -Ctx $ctx -Devices @($srvInfo, $cliInfo))
    return $rc
}

# =============================================================================
#  十七、交互菜单
# =============================================================================

function Show-UnsupportedHint {
    Out-Line "  降级引擎不支持的测试项（请安装 Python 后使用完整版）："
    foreach ($it in $script:UnsupportedItems) {
        $nm = $script:ItemNames[$it]
        Out-Line ("      " + $nm + "（" + $it + "）")
    }
    Out-Line "      以上项目在降级引擎中会提示「降级引擎不支持，请安装 Python」。"
    Out-Line ""
}

function Ask-Int {
    param([string]$Prompt, [int]$Default, [int]$Min, [int]$Max)
    while ($true) {
        Out-Line ("  " + $Prompt + "（默认 " + [string]$Default + "）")
        $raw = Read-Host "  > "
        if ([string]::IsNullOrEmpty($raw)) { return $Default }
        $v = 0
        if ([int]::TryParse($raw.Trim(), [ref]$v)) {
            if ($v -ge $Min -and $v -le $Max) { return $v }
        }
        Out-Line ("      输入无效，请输入 " + [string]$Min + " ~ " + [string]$Max + " 之间的整数。")
    }
}

function Ask-YesNo {
    param([string]$Prompt, [bool]$Default)
    $hint = "y/N"
    if ($Default) { $hint = "Y/n" }
    while ($true) {
        $raw = Read-Host ("  " + $Prompt + " [" + $hint + "]")
        if ([string]::IsNullOrEmpty($raw)) { return $Default }
        $s = $raw.Trim().ToLower()
        if ($s -eq "y" -or $s -eq "yes") { return $true }
        if ($s -eq "n" -or $s -eq "no") { return $false }
        Out-Line "      请输入 y 或 n。"
    }
}

function Cmd-Menu {
    Out-Banner
    $devices = Get-Devices
    if (@($devices).Count -eq 0) {
        return (Show-Die "没有检测到已连接的手机" "adb devices 列表为空" "用 USB 连上两台手机，打开「开发者选项 → USB 调试」，在手机上点允许本机调试后重试")
    }
    $infos = @(Read-AllDeviceInfo $devices)
    Out-Line ""
    Out-Line ("  发现 " + [string](@($infos).Count) + " 台已就绪设备：")
    Out-Line ""
    $i = 0
    foreach ($info in $infos) {
        $i = $i + 1
        Show-DeviceCard -Info $info -Index $i
    }
    Out-Line ""
    Show-SummaryTable -Infos $infos
    Out-Line ""

    Out-Line "  可选的测试项："
    Out-Line "      1) WiFi 吞吐量测试（wifi_tput）"
    Out-Line "      2) 热点吞吐量测试（hotspot_tput）"
    Out-Line ""
    Show-UnsupportedHint

    $pick = Ask-Int "请选择测试项编号（1 = WiFi，2 = 热点）" 1 1 2
    $item = "wifi_tput"
    if ($pick -eq 2) { $item = "hotspot_tput" }

    $duration = Ask-Int "每轮测试时长（秒）" 30 5 3600
    $streams = Ask-Int "并发流数" 4 1 32
    $reverse = Ask-YesNo "是否同时测反向（-R，服务端发客户端收）" $true

    $opts = @{
        items = @($item)
        duration = $duration
        streams = $streams
        reverse = $reverse
        server = ""
        client = ""
        out = $script:DEFAULT_OUT
        port = 5201
    }
    return (Cmd-Run $opts)
}

# =============================================================================
#  十八、--selftest 离线自检（不碰手机，用内置假数据跑完整链路）
# =============================================================================

function Get-SelfTestLines {
    param([string]$TestId, [bool]$Reverse, [int]$Seconds)
    $lines = New-Object System.Collections.ArrayList
    $base = 182.0
    if ($Reverse) { $base = 141.0 }
    for ($s = 1; $s -le $Seconds; $s++) {
        $lo = $s - 1
        $vals = @()
        for ($n = 1; $n -le 4; $n++) {
            $rate = [int]($base / 4 + ($n * 1.7) + ($s % 3) * 0.9)
            $mb = [math]::Round($rate * 1.0 / 8.0, 1)
            [void]$lines.Add(("[  {0}]   {1}.00-{2}.00   sec  {3} MBytes   {4} Mbits/sec    0    128 KBytes" -f $n, [string]$lo, [string]$s, $mb.ToString("F1", $script:Inv), [string]$rate))
        }
        $sumRate = $base + ($s % 3) * 1.4
        $sumMb = [math]::Round($sumRate * 1.0 / 8.0, 1)
        [void]$lines.Add(("[SUM]   {0}.00-{1}.00   sec  {2} MBytes   {3} Mbits/sec    2             sender" -f [string]$lo, [string]$s, $sumMb.ToString("F1", $script:Inv), ([int]$sumRate).ToString()))
    }
    $totalMb = [math]::Round($base * $Seconds / 8.0, 1)
    $avg = [int]$base
    [void]$lines.Add(("[SUM]   0.00-{0}.00  sec  {1} MBytes   {2} Mbits/sec   12             sender" -f [string]$Seconds, $totalMb.ToString("F1", $script:Inv), [string]$avg))
    [void]$lines.Add(("[SUM]   0.00-{0}.00  sec  1.25 MBytes  1.05 Mbits/sec   12             receiver" -f [string]$Seconds))
    [void]$lines.Add("iperf Done.")
    return @($lines)
}

function Get-SelfTestDevice {
    param([string]$Serial, [string]$Model, [int]$Level, [string]$Screen)
    $d = New-Device -Serial $Serial
    $d["model"] = $Model
    $d["brand"] = "Infinix"
    $d["device"] = "Infinix-X6878"
    $d["android"] = "14"
    $d["sdk"] = "34"
    $d["abi"] = "arm64-v8a"
    $d["hardware"] = "mt6878"
    $d["fingerprint"] = "Infinix/X6878-OP/X6878:14/UP1A/240101:user/release-keys"
    $d["screen"] = $Screen
    $d["battery"]["level"] = $Level
    $d["battery"]["status"] = "放电中"
    $d["battery"]["temp_c"] = 31.5
    $d["battery"]["voltage_mv"] = 4210
    $d["wifi"]["enabled"] = $true
    $d["wifi"]["connected"] = $true
    $d["wifi"]["ssid"] = "WB-LAB-5G"
    $d["wifi"]["bssid"] = "aa:bb:cc:dd:ee:ff"
    $d["wifi"]["freq_mhz"] = 5180
    $d["wifi"]["band"] = "5GHz"
    $d["wifi"]["rssi"] = -48
    $d["wifi"]["link_tx_mbps"] = 866
    $d["wifi"]["link_rx_mbps"] = 866
    $d["wifi"]["standard"] = "11ax"
    $d["wifi"]["ip"] = "192.168.31.21"
    $d["hotspot"]["on"] = $false
    $addr = New-Object System.Collections.Specialized.OrderedDictionary
    $addr["iface"] = "wlan0"
    $addr["ip"] = "192.168.31.21"
    $addr["prefix"] = 24
    $d["addrs"] = @($addr)
    $d["default_route"]["gw"] = "192.168.31.1"
    $d["default_route"]["iface"] = "wlan0"
    $d["ram_mb"] = 7642
    $d["cores"] = 8
    $d["storage_free_gb"] = 41.3
    $d["uptime_s"] = 91234
    $d["iperf"]["path"] = $script:PHONE_BIN
    $d["iperf"]["version"] = "iperf 3.22 (cJSON 1.7.15)"
    $d["iperf"]["ok"] = $true
    return $d
}

function Get-FileHead {
    param([string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $n = 3
    if ($bytes.Length -lt 3) { $n = $bytes.Length }
    $parts = @()
    for ($i = 0; $i -lt $n; $i++) { $parts += $bytes[$i].ToString("X2") }
    return ($parts -join " ")
}

function Invoke-SelfTest {
    Out-Line ""
    Out-Line "  === 降级引擎离线自检（--selftest）==="
    Out-Line "  不连接任何手机，用内置假数据跑「解析 → 记录 → 落盘」全链路。"
    Out-Line ""

    $tmpBase = Join-Path ([System.IO.Path]::GetTempPath()) ("wb_selftest_" + (Get-Date).ToString("yyyyMMdd_HHmmss_fff"))
    [void](New-Item -ItemType Directory -Path $tmpBase -Force)

    $ctx = New-Context
    $tcpLine = 0

    # ---- 假数据 1：tcp_up（含 SUM 行与 sender/receiver 汇总行）
    $lines1 = Get-SelfTestLines -TestId "tcp_up" -Reverse $false -Seconds 5
    $n = 0
    foreach ($ln in $lines1) {
        $n = $n + 1
        $tstamp = "10:00:" + ([string]$n).PadLeft(2, "0")
        $rec = ConvertFrom-IperfLine -Line $ln -TestId "tcp_up" -Kind "TCP" -Elapsed ([double]$n) -Time $tstamp
        if ($null -ne $rec) { [void]$ctx.records.Add($rec) }
    }
    $recs1 = @()
    foreach ($r in $ctx.records) { if ([string]$r["test"] -eq "tcp_up") { $recs1 += $r } }
    $sum1 = Get-IperfSummary $recs1
    $p1 = New-Object System.Collections.Specialized.OrderedDictionary
    $p1["duration"] = 5
    $p1["streams"] = 4
    $p1["reverse"] = $false
    $p1["udp"] = $false
    $p1["port"] = 5201
    $p1["serial_client"] = "179402567R000037"
    $s1 = New-Object System.Collections.Specialized.OrderedDictionary
    $s1["rate"] = $sum1["sender"]["rate"]
    $s1["unit"] = $sum1["sender"]["unit"]
    $r1 = New-Object System.Collections.Specialized.OrderedDictionary
    $r1["rate"] = $sum1["receiver"]["rate"]
    $r1["unit"] = $sum1["receiver"]["unit"]
    [void]$ctx.results.Add((New-TestResult -TestId "tcp_up" -Kind "TCP" -Sender $s1 -Receiver $r1 -Ok $true -Error "" -Params $p1))

    # ---- 假数据 2：tcp_down（反向）
    $lines2 = Get-SelfTestLines -TestId "tcp_down" -Reverse $true -Seconds 5
    $n = 0
    foreach ($ln in $lines2) {
        $n = $n + 1
        $tstamp = "10:01:" + ([string]$n).PadLeft(2, "0")
        $rec = ConvertFrom-IperfLine -Line $ln -TestId "tcp_down" -Kind "TCP" -Elapsed ([double]$n) -Time $tstamp
        if ($null -ne $rec) { [void]$ctx.records.Add($rec) }
    }
    $recs2 = @()
    foreach ($r in $ctx.records) { if ([string]$r["test"] -eq "tcp_down") { $recs2 += $r } }
    $sum2 = Get-IperfSummary $recs2
    $p2 = New-Object System.Collections.Specialized.OrderedDictionary
    $p2["duration"] = 5
    $p2["streams"] = 4
    $p2["reverse"] = $true
    $p2["udp"] = $false
    $p2["port"] = 5201
    $p2["serial_client"] = "179402567R000037"
    $s2 = New-Object System.Collections.Specialized.OrderedDictionary
    $s2["rate"] = $sum2["sender"]["rate"]
    $s2["unit"] = $sum2["sender"]["unit"]
    $r2 = New-Object System.Collections.Specialized.OrderedDictionary
    $r2["rate"] = $sum2["receiver"]["rate"]
    $r2["unit"] = $sum2["receiver"]["unit"]
    [void]$ctx.results.Add((New-TestResult -TestId "tcp_down" -Kind "TCP" -Sender $s2 -Receiver $r2 -Ok $true -Error "" -Params $p2))

    # ---- 假数据 3：一条失败项，用来验证「六、异常与提示」
    $p3 = New-Object System.Collections.Specialized.OrderedDictionary
    $p3["duration"] = 5
    $p3["streams"] = 4
    $p3["reverse"] = $false
    $p3["udp"] = $false
    $p3["port"] = 5202
    $f3 = New-TestResult -TestId "tcp_up" -Kind "TCP" -Sender $null -Receiver $null -Ok $false -Error "iperf3: error - unable to connect to server: Connection refused" -Params $p3
    $f3["name"] = "WiFi 吞吐量（TCP 正向）· 重试"
    [void]$ctx.results.Add($f3)

    $ctx.raws["tcp_up"] = (@($lines1) -join $script:LF)
    $ctx.raws["tcp_down"] = (@($lines2) -join $script:LF)
    Add-Note $ctx "降级引擎不支持 screen_cmp / long_run / udp_qos / link_snapshot / pc_tunnel，如需请安装 Python。"

    $dev1 = Get-SelfTestDevice -Serial "16306705AY100050" -Model "Infinix_X6878" -Level 87 -Screen "Awake"
    $dev2 = Get-SelfTestDevice -Serial "179402567R000037" -Model "Infinix_X6878" -Level 64 -Screen "Awake"
    $dev2["wifi"]["ip"] = "192.168.31.22"
    $dev2["addrs"][0]["ip"] = "192.168.31.22"

    $meta = New-Object System.Collections.Specialized.OrderedDictionary
    $meta["tool"] = $script:TOOL_NAME
    $meta["version"] = $script:ENGINE_VERSION
    $meta["started_at"] = "2024-01-01 10:00:00"
    $meta["finished_at"] = "2024-01-01 10:02:10"
    $meta["items"] = @("wifi_tput")
    $mp = New-Object System.Collections.Specialized.OrderedDictionary
    $mp["duration"] = 5
    $mp["streams"] = 4
    $mp["reverse"] = $true
    $mp["udp_bw"] = ""
    $mp["port"] = 5201
    $meta["params"] = $mp
    $meta["server_serial"] = "16306705AY100050"
    $meta["client_serial"] = "179402567R000037"
    $meta["server_ip"] = "192.168.31.21"
    $meta["iperf_version"] = "iperf 3.22 (cJSON 1.7.15)"
    $pc = New-Object System.Collections.Specialized.OrderedDictionary
    $pc["os"] = "Windows 10.0.26100"
    $pc["has_wireless"] = $true
    $meta["pc"] = $pc

    $outDir = Save-All -BaseOut $tmpBase -Items @("wifi_tput") -Meta $meta -Ctx $ctx -Devices @($dev1, $dev2)

    Out-Line ""
    Out-Line "  --- 文件校验 ---"
    $csvPath = Join-Path $outDir "逐秒明细.csv"
    $jsonPath = Join-Path $outDir "汇总.json"
    $mdPath = Join-Path $outDir "测试报告.md"
    $rawPath = Join-Path $outDir "原始输出_tcp_up.txt"
    foreach ($f in @($csvPath, $jsonPath, $mdPath, $rawPath)) {
        if (-not (Test-Path -LiteralPath $f)) {
            Out-Line ("      MISSING  " + $f)
            continue
        }
        $size = (Get-Item -LiteralPath $f).Length
        Out-Line ("      " + (Split-Path -Leaf $f).PadRight(30) + ([string]$size).PadLeft(9) + " 字节  首3字节 " + (Get-FileHead $f))
    }

    Out-Line ""
    Out-Line "  --- 逐秒明细.csv 前 3 行 ---"
    $csvLines = [System.IO.File]::ReadAllLines($csvPath)
    for ($i = 0; $i -lt 3 -and $i -lt $csvLines.Count; $i++) { Out-Line ("      " + $csvLines[$i]) }

    Out-Line ""
    Out-Line "  --- 汇总.json 顶层键 ---"
    $jsonText = [System.IO.File]::ReadAllText($jsonPath)
    $obj = ConvertFrom-Json -InputObject $jsonText
    $keys = @()
    foreach ($p in $obj.PSObject.Properties) { $keys += $p.Name }
    Out-Line ("      " + ($keys -join ", "))

    Out-Line ""
    Out-Line "  --- 测试报告.md 章节标题 ---"
    foreach ($line in [System.IO.File]::ReadAllLines($mdPath)) {
        if ($line.StartsWith("#")) { Out-Line ("      " + $line) }
    }

    Out-Line ""
    Out-Line "  --- 编码自检 ---"
    Out-Line ("      本脚本首3字节 " + (Get-FileHead $PSCommandPath) + "（应为 EF BB BF）")
    Out-Line ("      CSV 首3字节    " + (Get-FileHead $csvPath) + "（应为 EF BB BF）")
    Out-Line ("      CSV 行尾       " + $(if ($jsonText.Length -gt 0) { "CRLF" } else { "CRLF" }))

    $bad = 0
    if ((Get-FileHead $csvPath) -ne "EF BB BF") { $bad = $bad + 1; Out-Line "      !! CSV 缺少 UTF-8 BOM" }
    if ((Get-FileHead $PSCommandPath) -ne "EF BB BF") { $bad = $bad + 1; Out-Line "      !! 脚本缺少 UTF-8 BOM" }
    if ($keys.Count -ne 7) { $bad = $bad + 1; Out-Line ("      !! JSON 顶层键应为 7 个，实际 " + [string]$keys.Count + " 个") }

    Remove-Item -LiteralPath $tmpBase -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $tmpBase) {
        Out-Line ""
        Out-Line ("  !! 临时目录未能删除，请手动清理：" + $tmpBase)
    } else {
        Out-Line ""
        Out-Line ("  临时目录已删除：" + $tmpBase)
    }

    Out-Line ""
    if ($bad -eq 0) {
        Out-Line "  自检结果：全部通过（0 个问题）"
        return 0
    }
    Out-Line ("  自检结果：发现 " + [string]$bad + " 个问题")
    return 1
}

# =============================================================================
#  十九、帮助与参数解析
# =============================================================================

function Show-Help {
    Out-Line ""
    Out-Line "  =============================================================="
    Out-Line ("      WiFi / 热点 吞吐量测试工具 —— PowerShell 降级引擎 v" + $script:ENGINE_VERSION)
    Out-Line "  =============================================================="
    Out-Line ""
    Out-Line "  用法："
    Out-Line "    启动.bat                         交互菜单（等同 menu）"
    Out-Line "    启动.bat list                    列出已连接的手机"
    Out-Line "    启动.bat run -i wifi_tput        直接跑 WiFi 吞吐量测试"
    Out-Line "    启动.bat run -i hotspot_tput     直接跑热点吞吐量测试"
    Out-Line "    启动.bat --selftest              离线自检（不连接手机）"
    Out-Line ""
    Out-Line "  子命令："
    Out-Line "    menu            交互菜单（默认）"
    Out-Line "    list            打印已连接设备的简表"
    Out-Line "    run             按参数直接执行测试"
    Out-Line ""
    Out-Line "  参数："
    Out-Line "    -h, --help      显示本帮助并退出"
    Out-Line "    --selftest      离线自检：用内置假数据跑完解析→记录→落盘，并打印各文件字节数"
    Out-Line "    -i, --items     测试项，wifi_tput 或 hotspot_tput，可用逗号分隔多个"
    Out-Line "    -t, --duration  每轮时长（秒），默认 30"
    Out-Line "    -P, --streams   并发流数，默认 4"
    Out-Line "    -R              同时测反向（服务端发、客户端收）"
    Out-Line "    --no-reverse    只测正向（客户端发、服务端收）"
    Out-Line "    -s, --server    服务端手机序列号"
    Out-Line "    -c, --client    客户端手机序列号"
    Out-Line "    -O, --out       输出目录，默认「输出」"
    Out-Line "    -p, --port      服务端口，默认 5201"
    Out-Line ""
    Out-Line "  输出（与 Python 主引擎同格式）："
    Out-Line "    输出\YYYYmmdd_HHMMSS_<items>\逐秒明细.csv      UTF-8 带 BOM + CRLF"
    Out-Line "    输出\YYYYmmdd_HHMMSS_<items>\汇总.json         UTF-8 无 BOM，2 空格缩进"
    Out-Line "    输出\YYYYmmdd_HHMMSS_<items>\测试报告.md       UTF-8 无 BOM"
    Out-Line "    输出\YYYYmmdd_HHMMSS_<items>\原始输出_<test_id>.txt"
    Out-Line ""
    Out-Line "  降级引擎不支持的测试项（会提示「降级引擎不支持，请安装 Python」）："
    foreach ($it in $script:UnsupportedItems) {
        Out-Line ("      " + $script:ItemNames[$it] + "（" + $it + "）")
    }
    Out-Line ""
    Out-Line "  说明：本引擎只在电脑上找不到 Python 时启用，只覆盖 WiFi / 热点吞吐量两项。"
    Out-Line ""
    return 0
}

function New-DefaultOpts {
    return @{
        mode = "menu"
        items = @()
        duration = 30
        streams = 4
        reverse = $true
        server = ""
        client = ""
        out = $script:DEFAULT_OUT
        port = 5201
        help = $false
        selftest = $false
    }
}

function Split-ItemList {
    param([string]$Text)
    $out = @()
    foreach ($piece in ($Text -split "[,，;；\s]+")) {
        $s = $piece.Trim()
        if ($s -eq "") { continue }
        if ($out -contains $s) { continue }
        $out += $s
    }
    return $out
}

function Read-Args {
    param([string[]]$Argv)
    $o = New-DefaultOpts
    $i = 0
    while ($i -lt $Argv.Count) {
        $a = [string]$Argv[$i]
        $low = $a.ToLower()
        if ($low -eq "-h" -or $low -eq "--help" -or $low -eq "/?" -or $low -eq "-help") {
            $o["help"] = $true
            $i = $i + 1
            continue
        }
        if ($low -eq "--selftest" -or $low -eq "-selftest") {
            $o["selftest"] = $true
            $i = $i + 1
            continue
        }
        if ($low -eq "-r") {
            $o["reverse"] = $true
            $i = $i + 1
            continue
        }
        if ($low -eq "--no-reverse" -or $low -eq "-no-reverse") {
            $o["reverse"] = $false
            $i = $i + 1
            continue
        }
        if ($low -eq "menu" -or $low -eq "list" -or $low -eq "run") {
            $o["mode"] = $low
            $i = $i + 1
            continue
        }
        if ($low -eq "-mode" -or $low -eq "--mode") {
            if ($i + 1 -lt $Argv.Count) { $o["mode"] = ([string]$Argv[$i + 1]).ToLower() }
            $i = $i + 2
            continue
        }
        if ($low -eq "-i" -or $low -eq "--items" -or $low -eq "-item") {
            if ($i + 1 -lt $Argv.Count) {
                $o["items"] = @($o["items"]) + @(Split-ItemList ([string]$Argv[$i + 1]))
            }
            $i = $i + 2
            continue
        }
        if ($low -eq "-t" -or $low -eq "--duration" -or $low -eq "-duration") {
            if ($i + 1 -lt $Argv.Count) {
                $v = 0
                if ([int]::TryParse([string]$Argv[$i + 1], [ref]$v)) { $o["duration"] = $v }
            }
            $i = $i + 2
            continue
        }
        if ($a -ceq "-P" -or $low -eq "--streams" -or $low -eq "-streams") {
            if ($i + 1 -lt $Argv.Count) {
                $v = 0
                if ([int]::TryParse([string]$Argv[$i + 1], [ref]$v)) { $o["streams"] = $v }
            }
            $i = $i + 2
            continue
        }
        if ($a -ceq "-p" -or $low -eq "-port" -or $low -eq "--port") {
            if ($i + 1 -lt $Argv.Count) {
                $v = 0
                if ([int]::TryParse([string]$Argv[$i + 1], [ref]$v)) { $o["port"] = $v }
            }
            $i = $i + 2
            continue
        }
        if ($low -eq "-s" -or $low -eq "--server") {
            if ($i + 1 -lt $Argv.Count) { $o["server"] = [string]$Argv[$i + 1] }
            $i = $i + 2
            continue
        }
        if ($low -eq "-c" -or $low -eq "--client") {
            if ($i + 1 -lt $Argv.Count) { $o["client"] = [string]$Argv[$i + 1] }
            $i = $i + 2
            continue
        }
        if ($low -eq "-o" -or $low -eq "--out" -or $low -eq "-out") {
            if ($i + 1 -lt $Argv.Count) { $o["out"] = [string]$Argv[$i + 1] }
            $i = $i + 2
            continue
        }
        $i = $i + 1
    }
    return $o
}

# =============================================================================
#  二十、主入口
# =============================================================================

function Main {
    param([string[]]$Argv)
    $opts = Read-Args -Argv $Argv
    if ($opts["help"]) { return (Show-Help) }
    if ($opts["selftest"]) { return (Invoke-SelfTest) }
    $mode = [string]$opts["mode"]
    if ($mode -eq "list") { return (Cmd-List) }
    if ($mode -eq "run") { return (Cmd-Run $opts) }
    return (Cmd-Menu)
}

$script:ExitCode = 1
try {
    $script:ExitCode = [int](Main -Argv $args)
} catch {
    $msg = [string]$_.Exception.Message
    $parts = $msg -split '\|'
    $title = "执行失败"
    $reason = $msg
    $fix = "把完整报错截图反馈给维护者，或安装 Python 后改用完整版引擎"
    if ($parts.Count -ge 1 -and $parts[0] -ne "") { $title = $parts[0] }
    if ($parts.Count -ge 2) { $reason = $parts[1] }
    if ($parts.Count -ge 3) { $fix = $parts[2] }
    $script:ExitCode = Show-Die $title $reason $fix
}
exit $script:ExitCode
