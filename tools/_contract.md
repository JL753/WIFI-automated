# WiFi 吞吐量测试工具 · 模块契约 v1（所有子模块必须严格遵守）

> 本文件是**唯一接口约定**。任何模块不得改动本文件定义的签名/字段名/枚举值。
工作根目录：D:\Users\admin\Desktop\WiFi\WiFi吞吐量测试工具

## 0. 硬性环境约束（违反即不可用）

1. **源码统一 UTF-8 无 BOM**，Python 文件首行写 `# -*- coding: utf-8 -*-`。
2. **只用 Python 标准库**，禁止 pip 安装任何东西。目标解释器最低 3.8。
3. **Python 输出编码**：模块入口统一执行

```python
if hasattr(sys.stdout, "reconfigure") and not sys.stdout.isatty():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
```

真控制台下不要 chcp，中文靠 WriteConsoleW 通路。**禁止在 .bat 里写 chcp**（会让重定向 stdin 变 EOF）。

4. **不要调用 PC 端 `bin\win\iperf3.exe` 做任何验证性运行**：它是 Cygwin 构建，在本机受限环境会 `couldn't create signal pipe, Win32 error 5` 并挂死。只允许静态使用（存在性检查、文件大小）。PC 参与测试的代码路径要写好，但**验收时不实跑 PC 端 iperf3**。
5. adb 路径：优先 `C:\Program Files\ADB\adb.exe`，其次 PATH 上的 `adb`，最后环境变量 `ADB`。**路径含空格**，调用必须用列表形式 `subprocess.run([adb, "-s", serial, "shell", cmd])`，禁止 shell=True。
6. 所有 adb 调用必须有超时（默认 20s，大文件 push 用 180s），超时要抛异常而不是挂死。

## 1. 目录结构（最终形态）

```
WiFi吞吐量测试工具\
  启动.bat                     <- 纯 ASCII 薄启动器，唯一入口
  bin\
    iperf3-arm64v8             <- 手机用静态 arm64 二进制（iperf 3.22，7465336 字节）
    win\iperf3.exe + *.dll     <- PC 用（Cygwin 构建，仅保留分发，本机不实跑）
  tools\
    wb\
      __init__.py
      adbkit.py                <- 【子代理 A】
      devinfo.py               <- 【子代理 A】
      iperf.py                 <- 【子代理 B】
      report.py                <- 【子代理 C】
      runner.py                <- 【调度者】
      cli.py                   <- 【调度者】
    fallback\
      wb_fallback.ps1          <- 【子代理 D】无 Python 时的完整降级引擎
  输出\                        <- 运行产物，按次建子目录
  使用说明.md                  <- 【调度者】
```

## 2. 测试项枚举（test id 固定，禁止改名）

| id | 中文名 | 需要设备数 |
|---|---|---|
| `wifi_tput` | WiFi 吞吐量测试 | 2 |
| `hotspot_tput` | 热点吞吐量测试 | 2 |
| `screen_cmp` | 亮灭屏对比测试 | 2 |
| `long_run` | 长稳吞吐测试 | 2 |
| `udp_qos` | UDP 抖动丢包测试 | 2 |
| `link_snapshot` | 链路质量快照 | 1 |
| `pc_tunnel` | PC 参与的隧道测试 | 2 |

## 3. 数据结构（字段名固定）

### 3.1 Record —— 一行 iperf3 输出解析结果（dict）

```python
{
  "t": "14:03:21",        # str 该行到达时的本地时间 HH:MM:SS
  "elapsed": 3.0,         # float 测试开始到该行的秒数（保留 2 位）
  "test": "tcp_up",       # str 测试轮次 id，见 3.3
  "kind": "TCP",          # "TCP" | "UDP"
  "stream": "SUM",        # "SUM" 或 "#4"（数字流号带 # 前缀）
  "start": 0.0,           # float 区间起点秒
  "end": 1.0,             # float 区间终点秒
  "transfer": 37.1,       # float
  "unit": "MBytes",       # "Bytes"|"KBytes"|"MBytes"|"GBytes"
  "rate": 311.0,          # float
  "rate_unit": "Mbits/sec",
  "retr": "",             # TCP: int 重传次数（可为 0）；非 TCP 或不可解析 -> ""
  "jitter": "",           # UDP: float 毫秒；否则 ""
  "lost": "",             # UDP: int；否则 ""
  "total": "",            # UDP: int；否则 ""
  "lost_pct": "",         # UDP: float；否则 ""
  "role": "receiver",     # "sender" | "receiver" | ""
  "raw": "....",          # str 原始行（不含时间戳前缀）
}
```

解析规则（必须实现，已被真机数据验证）：

- 逐秒行形如 `[  4]   0.00-1.00   sec  37.1 MBytes  311 Mbits/sec  0  3.2 MBytes`
  -> stream=`#4`, start=0.0, end=1.0, transfer=37.1, unit=MBytes, rate=311, rate_unit=Mbits/sec, retr=0（TCP 时）
- 汇总行形如 `[SUM]   0.00-6.00   sec   265 MBytes  368 Mbits/sec  receiver` -> role=receiver，retr=""
- UDP 逐秒/汇总行末尾多出 `0.041 ms  1504/79796 (1.9%)` 三段 -> jitter=0.041, lost=1504, total=79796, lost_pct=1.9
- UDP **sender** 行的第 11 个字段是 `Total Datagrams`（总包数），**不是重传**：把它填进 `total`，`retr` 留空，`lost`/`lost_pct` 留空（UDP sender 没有丢包率）。
- 无法解析的行（含 `iperf3: error`、`Server listening`、`Accepted connection`）不进 Record，原样交给 `on_raw(line)` 回调。

### 3.2 DeviceInfo —— 设备信息（dict）

```python
{
  "serial": "179402567R000037",
  "state": "device",
  "transport_id": "22",
  "model": "Infinix X6878",
  "brand": "Infinix",
  "device": "Infinix-X6878",
  "android": "17",
  "sdk": "37",
  "abi": "arm64-v8a",
  "hardware": "qcom",
  "fingerprint": "Infinix/X6878-OP/...",
  "screen": "Awake",            # "Awake" | "Asleep" | "Unknown"
  "battery": {"level": 74, "status": "充电中", "temp_c": 24.0, "voltage_mv": 4100},
  "wifi": {"enabled": True, "connected": True, "ssid": "QR123", "bssid": "06:05:88:95:b7:76",
           "freq_mhz": 5805, "band": "5GHz", "rssi": -61, "link_tx_mbps": 65, "link_rx_mbps": 86,
           "standard": "11ac"},
  "hotspot": {"on": False, "ssid": "", "iface": "", "ip": "", "freq_mhz": 0, "clients": 0},
  "addrs": [{"iface": "wlan0", "ip": "172.168.1.118", "prefix": 21}],
  "ifaces": ["wlan0", "wlan1"],
  "default_route": {"gw": "172.168.1.1", "iface": "wlan0"},   # 无则 gw="" iface=""
  "ram_mb": 7823,
  "cores": 8,
  "storage_free_gb": 41.2,
  "uptime_s": 12345,
  "iperf": {"path": "/data/local/tmp/iperf3", "version": "iperf 3.22 (cJSON 1.7.15)", "ok": True}
}
```
未知值一律用 `None` / `""` / `0`，**不得抛异常**（某台手机不支持的探测项要静默跳过）。

### 3.3 test 轮次 id 枚举（禁止新增）
`tcp_up`（正向 TCP，客户端->服务端）、`tcp_down`（`-R` 反向）、`udp_up`、`udp_down`、`long_run`、`screen_on`、`screen_off`。

## 4. 模块 API（签名固定）

### 4.1 wb/adbkit.py（子代理 A）

```python
class AdbError(RuntimeError): ...
class AdbTimeout(AdbError): ...

def find_adb() -> str
    """返回 adb.exe 绝对路径；找不到抛 AdbError，消息里带三个已尝试位置。"""

def adb(args: list, timeout: float = 20, serial: str = None, binary: bool = False) -> str
    """执行 adb，返回 stdout 文本（binary=True 时返回 bytes）。非 0 退出抛 AdbError，
       消息含完整命令行与 stderr。超时抛 AdbTimeout。"""

def shell(serial: str, cmd: str, timeout: float = 20) -> str
    """adb -s <serial> shell <cmd>，返回 stdout 文本。"""

def shell_rc(serial: str, cmd: str, timeout: float = 20) -> tuple
    """返回 (rc:int, out:str)。用 sh -c 'cmd; echo __RC__$?' 取真实退出码。"""

def list_devices() -> list
    """解析 `adb devices -l`，返回
       [{"serial","state","model","product","device","transport_id"}]，
       保持 adb 输出顺序。state 为 "device"/"unauthorized"/"offline"。"""

def push(serial: str, local: str, remote: str, timeout: float = 180) -> None
def chmod(serial: str, remote: str) -> None
def forward(serial: str, local_port: int, remote_port: int) -> None
def reverse(serial: str, remote_port: int, local_port: int) -> None
def remove_forwards(serial: str = None) -> None
```

### 4.2 wb/devinfo.py（子代理 A）

```python
def probe(serial: str) -> dict
    """采集完整 DeviceInfo（3.2）。所有探测独立 try，单项失败不影响其它项。"""

def render_card(info: dict, index: int = 0) -> str
    """返回给人看的「设备卡」多行文本，用于加载脚本后立刻显示。
       要求：一眼能区分是哪台手机（型号+序列号+WiFi SSID/IP 必须在首两行）；
       含 屏幕/电量/温度/内存/存储/CPU/系统版本/ABI/已装 iperf 版本。
       含 WiFi 频段、信道、RSSI、协商速率、IP、网关；热点状态与 IP。
       未知项显示 --。宽度按 78 列排版，中文对齐要用显示宽度计算
       （实现一个 _w(s) 计算东亚宽字符占 2 的函数）。"""

def render_summary_table(infos: list) -> str
    """多台设备的对照表，一行一台，用于选机时快速分辨。"""

def guess_roles(infos: list) -> tuple
    """返回 (server_serial, client_serial) 建议：
       开热点的那台当服务端；否则同一 SSID 的两台里 IP 小的当服务端；
       无法判断时返回 (第一台, 第二台)。"""

def collect_pc_env() -> dict
    """采集 PC 环境：{"has_wireless": bool, "wireless_adapters": [...], "wired": [{"name","ip"}],
       "os": "Windows 11 ...", "python": "3.15.0", "powershell": "7.x", "admin": bool}。
       has_wireless 判定：枚举网卡且名称/描述含 Wireless|Wi-Fi|WLAN|802.11。
       本机实测应返回 has_wireless=False。"""
```

### 4.3 wb/iperf.py（子代理 B）

```python
PHONE_BIN = "/data/local/tmp/iperf3"
LOCAL_BIN_NAME = "iperf3-arm64v8"

class IperfError(RuntimeError): ...

def ensure_phone_bin(serial: str, local_path: str, log=None) -> dict
    """确保手机上有 3.22 的 iperf3：
       1) 检查 /data/local/tmp/iperf3 是否存在且 -v 输出含 "iperf 3.2"
       2) 不满足则 push local_path 并 chmod 755，再复验
       返回 {"path","version","pushed":bool,"ok":bool}。
       本地文件不存在或版本不对 -> 抛 IperfError（消息里给出应放位置与字节数 7465336）。"""

def start_server(serial: str, port: int, log=None) -> dict
    """pkill -9 -f iperf3 -> 等 2s -> nohup 后台起 -s -> 等 3s -> netstat 确认监听。
       返回 {"ok":bool, "pid":"", "detail":""}。
       注意：pkill 与 nohup 必须是两次独立的 shell 调用（合在一条会失败）。"""

def stop_server(serial: str) -> None
def is_listening(serial: str, port: int) -> bool

def run_client(serial: str, server_ip: str, port: int, *, duration: int, streams: int = 1,
               reverse: bool = False, udp: bool = False, udp_bw: str = "50M",
               test_id: str = "tcp_up", on_record=None, on_raw=None, log=None) -> dict
    """在手机上跑 iperf3 客户端，**逐行实时回调**。
       实现要点（已被真机验证，不要改）：
       - 命令行必须带 --forceflush，否则逐秒行会被块缓冲、几秒后才一次性涌出
       - 用 subprocess.Popen([adb, "-s", serial, "shell", cmd], stdout=PIPE, stderr=STDOUT)
         逐行 readline，**不要用 communicate()**（那会整段缓冲）
       - 每行先打本地时间戳再交 on_record/on_raw
       - 手机侧同时 | tee /data/local/tmp/iperf_<test_id>.txt 落盘
       - **退出码不可信**（管道吞掉），成败判定改为：结束后 cat 落盘文件，
         含 "iperf Done." 视为成功
       返回 {"ok":bool,"test_id":str,"raw_path_phone":str,"raw_text":str,
             "summary":{"sender":{"rate":float,"unit":str},"receiver":{...}},"error":str}"""

def parse_line(line: str, test_id: str, kind: str, elapsed: float, tstamp: str) -> dict
    """把一行 iperf3 输出解析成 3.1 的 Record；不是数据行返回 None。"""

def probe_version(serial: str, path: str = PHONE_BIN) -> str
```

### 4.4 wb/report.py（子代理 C）

```python
def make_run_dir(base_dir: str, items: list, tag: str = "") -> str
    """建 <base>\YYYYmmdd_HHMMSS_<items简写>，返回绝对路径。重名自动加 _2。"""

def write_csv(path: str, records: list) -> None
    """列固定（顺序固定）：
       时间,测试,类型,线程,起,止,传输量,单位,速率,速率单位,重传或数据报,抖动ms,丢包,总包,丢包率,方向
       编码 utf-8-sig（Excel 直接双击不乱码），行尾 \r\n。"""

def write_json(path: str, payload: dict) -> None
    """utf-8，indent=2，ensure_ascii=False。"""

def write_markdown(path: str, payload: dict) -> None
    """可读报告，必须包含：结论摘要 / 参数 / 设备信息 / 各测试结果表 / 亮灭屏对比 / 异常与提示。
       结论要给出明确判定（如「下行 66.1 Mbits/sec，达到协商速率 433Mbps 的 15%」）。"""

def write_raw(path: str, text: str) -> None
    """iperf3 原始输出原文，utf-8，不做任何改写。"""

def build_payload(*, meta: dict, devices: list, records: list,
                  results: list, screen: dict = None, notes: list = None) -> dict
    """组装写 JSON/Markdown 用的总结构：
       {"meta": {...}, "devices": [...], "summary": {...},
        "tests": [{"test_id","name","kind","params","sender","receiver","records_n"}],
        "screen_compare": {...}, "notes": [...]}"""

def fmt_bps(rate: float, unit: str) -> str
    """归一化显示，统一换算成 Mbits/sec 保留 1 位。"""
```

### 4.5 tools/fallback/wb_fallback.ps1（子代理 D）

无 Python 时的降级引擎，**PowerShell 5.1 兼容**（不能依赖 pwsh 7 特性）。
必须支持：-Mode menu|list|run、-Items、-Duration、-Streams、-Reverse、-Out。
功能子集（明确告知用户是精简版）：设备信息卡、WiFi/热点吞吐（TCP 上下行）、UDP、链路快照；
输出 CSV + 原始输出 + 一个 Markdown 摘要。亮灭屏对比与长稳测试可简化为固定分段。
必须实现与 Python 版一致的 --forceflush + 逐行流式。

## 5. 界面文案规范
- 全程中文；报错必须三段式：`[错误] 现象` / `原因` / `处理办法`。
- 不要出现「场景A/B/C」字样（用户明确要求改名）。
- 输出统一前缀：`[1/7]` 这类步骤号，`  OK  ` / `  !!  ` / `  --  `。

## 6. 验收标准（每个子代理交付时必须给出证据）
1. `python -m py_compile <你的文件>` 通过。
2. 你的模块能在**无手机**情况下被 import 且不报错（顶层不得做设备探测）。
3. 你有真机可用（`adb devices` 里 `179402567R000037` 在线），涉及 adb 的函数必须贴**真实运行输出**。
4. 不要改动别人的文件；不要改本契约。
5. 不要创建 `_` 开头的临时文件，用完即删。

