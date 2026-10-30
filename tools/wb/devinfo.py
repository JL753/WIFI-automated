# -*- coding: utf-8 -*-
"""设备信息采集与展示：DeviceInfo 探测、设备卡、对照表、角色建议、PC 环境。"""

import json
import os
import platform
import re
import subprocess
import unicodedata

from . import adbkit

CARD_WIDTH = 78
INNER_WIDTH = CARD_WIDTH - 2
SECTION_PREFIX = "#WB:"
PHONE_BIN = "/data/local/tmp/iperf3"

BATTERY_STATUS = {
    1: "未知",
    2: "充电中",
    3: "放电中",
    4: "未充电",
    5: "已充满",
}

HOTSPOT_IFACES = ("wlan1", "softap0", "ap_br_wlan1", "ap_br_softap0", "swlan0", "ap0")

_IFACE_KEEP = re.compile(r"^(wlan\d+|softap\d+|ap_br_\w+|swlan\d+|ap\d+|p2p0)$")
_PROP_RE = re.compile(r"^\[([^\]]+)\]:\s*\[(.*)\]$")
_ADDR_RE = re.compile(r"^\d+:\s+(\S+)\s+inet\s+([0-9.]+)/(\d+)")
# ifconfig 输出同时兼容老式（inet addr:1.2.3.4  Mask:...）与 toybox 新式（inet 1.2.3.4  netmask ...）
_IFC_HEAD_RE = re.compile(r"^(\S+?):?\s+(?:Link encap|flags=)")
_IFC_INET_RE = re.compile(r"\binet (?:addr:)?\s*([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)")
_IFC_MASK_RE = re.compile(r"(?:Mask:|netmask )\s*([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)")
_ROUTE_RE = re.compile(r"^default\s+via\s+([0-9.]+)\s+dev\s+(\S+)")
_ROUTE_GET_RE = re.compile(r"^[0-9.]+\s+via\s+([0-9.]+)\s+dev\s+(\S+)")


# ---------------------------------------------------------------- 基础工具

def _w(text):
    """返回字符串的终端显示宽度（东亚全角字符算 2）。"""
    width = 0
    for ch in str(text):
        if unicodedata.east_asian_width(ch) in ("W", "F"):
            width += 2
        elif unicodedata.combining(ch):
            continue
        else:
            width += 1
    return width


def _trunc(text, width):
    out = ""
    used = 0
    for ch in str(text):
        step = 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1
        if used + step > width:
            break
        out += ch
        used += step
    return out + " " * max(0, width - used)


def _pad(text, width):
    """按显示宽度左对齐补空格，超宽则截断。"""
    text = str(text)
    used = _w(text)
    if used > width:
        return _trunc(text, width)
    return text + " " * (width - used)


def _split_sections(text):
    """把带 #WB:NAME 标记的多段输出拆成 dict。"""
    out = {}
    current = None
    buf = []
    for line in str(text).replace("\r", "").splitlines():
        if line.startswith(SECTION_PREFIX):
            if current is not None:
                out[current] = "\n".join(buf)
            current = line[len(SECTION_PREFIX):].strip()
            buf = []
        elif current is not None:
            buf.append(line)
    if current is not None:
        out[current] = "\n".join(buf)
    return out


def _int(value, default=0):
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


def _float(value, default=0.0):
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return default


def freq_band(freq_mhz):
    """频率转频段：<3000 -> 2.4GHz，>=5925 -> 6GHz，其余 -> 5GHz。"""
    freq = _int(freq_mhz)
    if freq <= 0:
        return ""
    if freq < 3000:
        return "2.4GHz"
    if freq >= 5925:
        return "6GHz"
    return "5GHz"


def freq_channel(freq_mhz):
    """频率转信道号；无法换算返回 0。"""
    freq = _int(freq_mhz)
    if freq <= 0:
        return 0
    if freq == 2484:
        return 14
    if freq < 3000:
        return (freq - 2412) // 5 + 1 if freq >= 2412 else 0
    if freq >= 5925:
        return (freq - 5950) // 5
    return (freq - 5000) // 5


# ---------------------------------------------------------------- 采集

def _new_info(serial):
    return {
        "serial": serial or "",
        "state": "",
        "transport_id": "",
        "model": "",
        "brand": "",
        "device": "",
        "android": "",
        "sdk": "",
        "abi": "",
        "hardware": "",
        "fingerprint": "",
        "screen": "Unknown",
        "battery": {"level": 0, "status": "", "temp_c": 0.0, "voltage_mv": 0},
        "wifi": {
            "enabled": False,
            "connected": False,
            "ssid": "",
            "bssid": "",
            "freq_mhz": 0,
            "band": "",
            "rssi": None,
            "link_tx_mbps": 0,
            "link_rx_mbps": 0,
            "standard": "",
        },
        "hotspot": {"on": False, "ssid": "", "iface": "", "ip": "", "freq_mhz": 0, "clients": 0},
        "addrs": [],
        "ifcfg": [],
        "ifaces": [],
        "default_route": {"gw": "", "iface": ""},
        "ram_mb": 0,
        "cores": 0,
        "storage_free_gb": 0.0,
        "uptime_s": 0,
        "iperf": {"path": PHONE_BIN, "version": "", "ok": False},
    }


def _fill_identity(info, serial):
    props = {}
    try:
        text = adbkit.shell(serial, "getprop")
        for line in text.splitlines():
            hit = _PROP_RE.match(line.strip())
            if hit:
                props[hit.group(1)] = hit.group(2)
    except Exception:
        pass

    info["model"] = props.get("ro.product.model", "")
    info["brand"] = props.get("ro.product.brand", "")
    info["device"] = props.get("ro.product.device", "")
    info["android"] = props.get("ro.build.version.release", "")
    info["sdk"] = props.get("ro.build.version.sdk", "")
    info["abi"] = props.get("ro.product.cpu.abi", "")
    info["hardware"] = props.get("ro.boot.hardware", "")
    info["fingerprint"] = props.get("ro.build.fingerprint", "")
    if not info["serial"]:
        info["serial"] = props.get("ro.serialno", "") or serial

    try:
        for item in adbkit.list_devices():
            if item.get("serial") == serial:
                info["state"] = item.get("state", "")
                info["transport_id"] = item.get("transport_id", "")
                if not info["model"]:
                    info["model"] = item.get("model", "")
                break
    except Exception:
        pass


def _fill_system(info, serial):
    cmd = (
        'echo "#WB:POWER"; dumpsys power | grep -m1 mWakefulness=; '
        'echo "#WB:BAT"; dumpsys battery | grep -E "level:|status:|voltage:|temperature:"; '
        'echo "#WB:MEM"; grep MemTotal /proc/meminfo; '
        'echo "#WB:CPU"; grep -c processor /proc/cpuinfo; '
        'echo "#WB:DF"; df -k /data; '
        'echo "#WB:UP"; cat /proc/uptime'
    )
    try:
        sections = _split_sections(adbkit.shell(serial, cmd, timeout=30))
    except Exception:
        return

    power = sections.get("POWER", "")
    if "mWakefulness=Awake" in power:
        info["screen"] = "Awake"
    elif "mWakefulness=Asleep" in power or "mWakefulness=Dozing" in power:
        info["screen"] = "Asleep"
    else:
        info["screen"] = "Unknown"

    bat = sections.get("BAT", "")
    level = re.search(r"^\s*level:\s*(-?\d+)", bat, re.M)
    status = re.search(r"^\s*status:\s*(-?\d+)", bat, re.M)
    voltage = re.search(r"^\s*voltage:\s*(-?\d+)", bat, re.M)
    temp = re.search(r"^\s*temperature:\s*(-?\d+)", bat, re.M)
    if level:
        info["battery"]["level"] = _int(level.group(1))
    if status:
        code = _int(status.group(1))
        info["battery"]["status"] = BATTERY_STATUS.get(code, "未知")
    if voltage:
        info["battery"]["voltage_mv"] = _int(voltage.group(1))
    if temp:
        info["battery"]["temp_c"] = round(_int(temp.group(1)) / 10.0, 1)

    mem = re.search(r"MemTotal:\s*(\d+)\s*kB", sections.get("MEM", ""))
    if mem:
        info["ram_mb"] = int(round(_int(mem.group(1)) / 1024.0))

    cpu = sections.get("CPU", "").strip()
    if cpu.isdigit():
        info["cores"] = _int(cpu)

    for line in sections.get("DF", "").splitlines():
        parts = line.split()
        if len(parts) >= 4 and parts[0].startswith("/dev/"):
            free_kb = _int(parts[3])
            if free_kb:
                info["storage_free_gb"] = round(free_kb / 1024.0 / 1024.0, 1)
            break

    up = sections.get("UP", "").split()
    if up:
        info["uptime_s"] = int(_float(up[0]))
    else:
        info["uptime_s"] = 0


def _parse_wifi_info(line, wifi):
    def grab(pattern):
        hit = re.search(pattern, line)
        return hit.group(1) if hit else ""

    ssid = grab(r'SSID: "([^"]*)"')
    if not ssid:
        ssid = grab(r"SSID: ([^,]+)")
    if ssid and ssid not in ("<unknown ssid>", "<none>"):
        wifi["ssid"] = ssid.strip()

    bssid = grab(r"BSSID: ([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})")
    if bssid:
        wifi["bssid"] = bssid.lower()

    status_ip = grab(r"IP: /?([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)")

    freq = _int(grab(r"Frequency: (\d+)MHz"))
    if freq > 0:
        wifi["freq_mhz"] = freq
        wifi["band"] = freq_band(freq)

    rssi = grab(r"RSSI: (-?\d+)")
    if rssi:
        value = _int(rssi, -999)
        if value > -127:
            wifi["rssi"] = value

    tx = _int(grab(r"Tx Link speed: (-?\d+)Mbps"))
    if tx > 0:
        wifi["link_tx_mbps"] = tx
    rx = _int(grab(r"Rx Link speed: (-?\d+)Mbps"))
    if rx > 0:
        wifi["link_rx_mbps"] = rx

    std = grab(r"Wi-Fi standard: (\w+)")
    if std and std != "unknown":
        wifi["standard"] = std
    return status_ip


def _mask_to_prefix(mask):
    """点分掩码转前缀长度；无法识别返回 0。"""
    parts = str(mask or "").split(".")
    if len(parts) != 4:
        return 0
    bits = 0
    for part in parts:
        try:
            value = int(part)
        except ValueError:
            return 0
        bits += bin(value & 0xFF).count("1")
    return bits


def _parse_ifconfig(text):
    """解析 adb shell ifconfig 的全量输出，返回 [{iface, ip, prefix}]。

    老式输出里 inet addr 在下一条缩进行，所以按「网卡头行 + 后续缩进行」分组。
    """
    out = []
    cur = ""
    for raw in str(text).replace("\r", "").splitlines():
        if not raw.strip():
            continue
        head = _IFC_HEAD_RE.match(raw)
        if head and not raw.startswith((" ", "\t")):
            cur = head.group(1)
            cur = cur.rstrip(":")
            continue
        if not cur:
            continue
        ip_hit = _IFC_INET_RE.search(raw)
        if not ip_hit or ip_hit.group(1) == "127.0.0.1":
            continue
        mask_hit = _IFC_MASK_RE.search(raw)
        ip = ip_hit.group(1)
        if any(x["iface"] == cur and x["ip"] == ip for x in out):
            continue
        out.append({"iface": cur, "ip": ip,
                    "prefix": _mask_to_prefix(mask_hit.group(1)) if mask_hit else 0})
    return out


def _merge_addrs(primary, extra):
    """把 ifconfig 里 ip 命令没报的地址并进来（同一 iface+ip 不重复）。"""
    seen = set((a.get("iface"), a.get("ip")) for a in primary)
    out = list(primary)
    for item in extra:
        key = (item.get("iface"), item.get("ip"))
        if key in seen:
            continue
        seen.add(key)
        out.append(item)
    return out


def _fill_network(info, serial):
    cmd = (
        'echo "#WB:WIFI"; cmd wifi status; '
        'echo "#WB:ADDR"; ip -4 -o addr show; '
        'echo "#WB:IFCFG"; ifconfig 2>/dev/null; '
        'echo "#WB:ROUTE"; ip route get 1.1.1.1 2>/dev/null | head -1; ip route show; '
        'echo "#WB:NET"; ls /sys/class/net'
    )
    try:
        sections = _split_sections(adbkit.shell(serial, cmd, timeout=30))
    except Exception:
        return

    wifi_text = sections.get("WIFI", "")
    wifi = info["wifi"]
    if "Wifi is enabled" in wifi_text:
        wifi["enabled"] = True
    elif "Wifi is disabled" in wifi_text:
        wifi["enabled"] = False
    wifi["connected"] = 'Wifi is connected to "' in wifi_text

    status_ip = ""
    for line in wifi_text.splitlines():
        if "WifiInfo:" in line:
            status_ip = _parse_wifi_info(line, wifi)
            break

    addrs = []
    for line in sections.get("ADDR", "").splitlines():
        hit = _ADDR_RE.match(line.strip())
        if not hit:
            continue
        iface, ip, prefix = hit.group(1), hit.group(2), _int(hit.group(3))
        if iface == "lo":
            continue
        addrs.append({"iface": iface, "ip": ip, "prefix": prefix})
    info["ifcfg"] = _parse_ifconfig(sections.get("IFCFG", ""))
    info["addrs"] = _merge_addrs(addrs, info["ifcfg"])

    wifi["ip"] = ""
    if status_ip:
        for item in addrs:
            if item["ip"] == status_ip:
                wifi["ip"] = status_ip
                break
    if not wifi["ip"]:
        for item in addrs:
            if item["iface"].startswith("wlan") and item["iface"] not in HOTSPOT_IFACES:
                wifi["ip"] = item["ip"]
                break

    ifaces = []
    for name in sections.get("NET", "").split():
        if name == "lo":
            continue
        if _IFACE_KEEP.match(name):
            ifaces.append(name)
    for item in addrs:
        if item["iface"] not in ifaces:
            ifaces.append(item["iface"])
    info["ifaces"] = ifaces

    for line in sections.get("ROUTE", "").splitlines():
        line = line.strip()
        hit = _ROUTE_RE.match(line)
        if hit:
            info["default_route"] = {"gw": hit.group(1), "iface": hit.group(2)}
            break
        hit = _ROUTE_GET_RE.match(line)
        if hit:
            info["default_route"] = {"gw": hit.group(1), "iface": hit.group(2)}
            break


HOTSPOT_CMD = (
    'echo "#WB:AP"; dumpsys wifi | grep -m1 -A12 "^Dump of SoftApManager id="; '
    'echo "#WB:APINFO"; dumpsys wifi | grep -m1 "^mCurrentSoftApInfoMap"; '
    'echo "#WB:APCLI"; dumpsys wifi | grep -o "num_connected_clients=[0-9][0-9]*" | tail -1; '
    'echo "#WB:TETHER"; dumpsys tethering | grep -A4 "^  Tether state:"'
)


def _hotspot_iface_addrs(info):
    """返回 (iface, ip)：热点接口上已分到地址的那一组。"""
    for name in HOTSPOT_IFACES:
        for item in info["addrs"]:
            if item["iface"] == name:
                return name, item["ip"]
    return "", ""


def _fill_hotspot(info, serial):
    try:
        sections = _split_sections(adbkit.shell(serial, HOTSPOT_CMD, timeout=30))
    except Exception:
        sections = {}

    ap_text = sections.get("AP", "")
    mode = ""
    role = ""
    ap_iface = ""
    ap_ssid = ""
    for line in ap_text.splitlines():
        text = line.strip()
        if text.startswith("current StateMachine mode:"):
            mode = text.split(":", 1)[1].strip()
        elif text.startswith("mRole:"):
            role = text.split(":", 1)[1].strip()
        elif text.startswith("mApInterfaceName:"):
            ap_iface = text.split(":", 1)[1].strip()
        elif text.startswith("mCurrentSoftApConfiguration: ssid ="):
            hit = re.search(r'ssid = "([^"]*)"', text)
            if hit:
                ap_ssid = hit.group(1)

    started = mode.startswith("Started") or role.startswith("ROLE_SOFTAP")

    freq = 0
    hit = re.search(r"frequency=\s*(\d+)", sections.get("APINFO", ""))
    if hit:
        freq = _int(hit.group(1))

    clients = _int(sections.get("APCLI", "").strip().split("=")[-1])

    tether_on = "TetheredState" in sections.get("TETHER", "")

    iface, ip = _hotspot_iface_addrs(info)
    if started and ap_iface and ap_iface not in ("null", "null "):
        iface = iface or ap_iface
    on = bool(started or (tether_on and ip))
    if not iface and on:
        for name in HOTSPOT_IFACES:
            if name in info["ifaces"]:
                iface = name
                break

    info["hotspot"] = {
        "on": on,
        "ssid": ap_ssid if on else "",
        "iface": iface if on else "",
        "ip": ip if on else "",
        "freq_mhz": freq if on else 0,
        "clients": clients if on else 0,
    }


def _fill_iperf(info, serial):
    try:
        rc, out = adbkit.shell_rc(serial, PHONE_BIN + " -v", timeout=20)
    except Exception:
        return
    lines = [l.strip() for l in out.splitlines() if l.strip()]
    version = lines[0] if lines else ""
    info["iperf"] = {
        "path": PHONE_BIN,
        "version": version,
        "ok": rc == 0 and "iperf 3." in version,
    }


def probe(serial):
    """采集完整 DeviceInfo。所有探测独立容错，单项失败不影响其它项。"""
    info = _new_info(serial)
    _fill_identity(info, serial)
    _fill_system(info, serial)
    _fill_network(info, serial)
    _fill_hotspot(info, serial)
    _fill_iperf(info, serial)
    return info


# ---------------------------------------------------------------- 展示

def _wifi_ip(info):
    """WiFi 侧 IPv4：优先 wlan0，其次其它 wlan*，最后取 cmd wifi status 报的 IP。"""
    ap_iface = (info.get("hotspot") or {}).get("iface") or ""
    spare = ""
    for item in info.get("addrs") or []:
        iface = str(item.get("iface", ""))
        if not iface.startswith("wlan") or iface == ap_iface:
            continue
        if iface == "wlan0":
            return item.get("ip") or ""
        spare = spare or (item.get("ip") or "")
    return spare or (info.get("wifi") or {}).get("ip") or ""


def _wifi_headline(info):
    wifi = info.get("wifi") or {}
    if not wifi.get("enabled"):
        return "WiFi 已关闭"
    if not wifi.get("connected"):
        return "WiFi 未连接"
    return "WiFi 已连接"


def _wifi_radio_line(info):
    """WiFi 状态 + 频段 + 信道 + RSSI + 协商速率，一行放下。"""
    wifi = info.get("wifi") or {}
    head = _wifi_headline(info)
    if not wifi.get("connected"):
        return "%s  频段 --  信道 --  RSSI --  速率 --" % head
    band = wifi.get("band") or "--"
    channel = freq_channel(wifi.get("freq_mhz"))
    rssi = wifi.get("rssi")
    rssi_txt = "--" if rssi is None else "%d dBm" % rssi
    tx = wifi.get("link_tx_mbps") or 0
    rx = wifi.get("link_rx_mbps") or 0
    if tx or rx:
        speed = "Tx %s / Rx %s Mbps" % (tx or "--", rx or "--")
    else:
        speed = "速率 --"
    return "%s  %s 信道 %s  RSSI %s  %s  %s" % (
        head,
        band,
        channel if channel else "--",
        rssi_txt,
        speed,
        wifi.get("standard") or "--",
    )


def _wifi_ip_text(info):
    ip = _wifi_ip(info)
    if not ip:
        return ""
    for item in info.get("addrs") or []:
        if item.get("ip") == ip and item.get("prefix"):
            return "%s/%s" % (ip, item.get("prefix"))
    return ip


def _wifi_id_line(info):
    """SSID + IP，放在设备卡第二行，一眼认出是哪台手机。

    WiFi 已连接就报 WiFi 的 SSID/IP；本机开热点则报热点的 SSID/IP。
    """
    wifi = info.get("wifi") or {}
    ap = info.get("hotspot") or {}
    if wifi.get("enabled") and wifi.get("connected"):
        label = "SSID"
        ssid = wifi.get("ssid") or "--"
        ip = _wifi_ip_text(info) or "--"
    elif ap.get("on"):
        label = "热点 SSID"
        ssid = ap.get("ssid") or "--"
        ip = ap.get("ip") or "--"
    else:
        label = "SSID"
        ssid = "WiFi 已关闭" if not wifi.get("enabled") else "未连接"
        ip = _wifi_ip_text(info) or "--"
    if _w(ssid) > 26:
        ssid = _trunc(ssid, 25).rstrip() + "…"
    if _w(ip) > 18:
        ip = _trunc(ip, 18).rstrip()
    return "%s %s  IP %s" % (label, ssid, ip)




def _hotspot_line(info):
    ap = info.get("hotspot") or {}
    if not ap.get("on"):
        return "热点 关  本机不做服务端"
    tail = []
    if ap.get("iface") or ap.get("ip"):
        tail.append((" ".join(x for x in (ap.get("iface"), ap.get("ip")) if x)).strip())
    if ap.get("freq_mhz"):
        tail.append("%s/ch%s" % (freq_band(ap["freq_mhz"]) or "--", freq_channel(ap["freq_mhz"]) or "--"))
    if ap.get("clients"):
        tail.append("客户端 %d" % _int(ap.get("clients")))
    parts = ["热点 开"]
    ssid = ap.get("ssid") or ""
    if ssid:
        room = INNER_WIDTH - 2 - _w("  ".join(parts + ["SSID "] + tail)) - 2
        if room >= 4:
            parts.append("SSID %s" % _fit(ssid, room))
    parts.extend(tail)
    return "  ".join(parts)


def _iface_line(info):
    """wlan0 / wlan1 的 IPv4 地址，来源 adb shell ifconfig（设备卡加载即可见）。"""
    ifcfg = [a for a in (info.get("ifcfg") or []) if a.get("ip")]
    addrs = [a for a in (info.get("addrs") or []) if a.get("ip")]
    parts = []
    for name in ("wlan0", "wlan1"):
        hit = [a for a in ifcfg if a.get("iface") == name]
        if not hit:
            hit = [a for a in addrs if a.get("iface") == name]
        if hit:
            prefix = hit[0].get("prefix") or 0
            parts.append("%s %s" % (name, ("%s/%d" % (hit[0]["ip"], prefix)) if prefix else hit[0]["ip"]))
        else:
            parts.append("%s --" % name)
    return "网卡 " + "   ".join(parts)


def _system_line(info):
    ram = info.get("ram_mb") or 0
    storage = info.get("storage_free_gb") or 0
    cores = info.get("cores") or 0
    android = info.get("android") or "--"
    sdk = info.get("sdk") or "--"
    return "内存 %s  存储 %s  %s 核  Android %s (SDK %s)  %s" % (
        "%dMB" % ram if ram else "--",
        "%sGB" % storage if storage else "--",
        cores or "--",
        android,
        sdk,
        info.get("abi") or "--",
    )


def _status_line(info):
    bat = info.get("battery") or {}
    level = bat.get("level") or 0
    temp = bat.get("temp_c") or 0
    screen = {"Awake": "Awake", "Asleep": "Asleep"}.get(info.get("screen"), "Unknown")
    route = info.get("default_route") or {}
    gw = route.get("gw") or ""
    gw_txt = "%s(%s)" % (gw, route.get("iface") or "--") if gw else "--"
    return "屏幕 %s  电量 %s  温度 %s  网关 %s" % (
        screen,
        "%d%%" % level if level else "--",
        "%.1fC" % temp if temp else "--",
        gw_txt,
    )


def _iperf_line(info):
    ipf = info.get("iperf") or {}
    version = ipf.get("version") or ""
    if not ipf.get("ok"):
        return "iperf3 未就绪（%s）" % (ipf.get("path") or PHONE_BIN)
    if not version:
        return "iperf3 已就绪"
    text = re.sub(r"^iperf3?\s+", "", version.strip())
    return "iperf3 " + text


def _fit(text, width):
    """截断到 width 列，超宽时末尾用省略号，不补空格。"""
    text = str(text)
    if _w(text) <= width:
        return text
    return _trunc(text, width - 1).rstrip() + "…"


def _card_line(text):
    return "│" + _pad(" " + _fit(text, INNER_WIDTH - 2), INNER_WIDTH) + "│"


def _card_pair(left, right, left_width=24):
    """左侧型号、右侧序列号；右侧优先保住，左侧超宽时截断加省略号。"""
    right = _fit(right, INNER_WIDTH - left_width - 2)
    left_width = max(12, min(left_width, INNER_WIDTH - _w(right) - 2))
    return "│" + _pad(" " + _fit(left, left_width - 2), left_width) + _pad(right + " ", INNER_WIDTH - left_width) + "│"


def render_card(info, index=0):
    """返回 78 列方框设备卡。

    首行 型号 + 序列号，第二行 WiFi SSID + IP —— 一眼能分辨是哪台手机。
    """
    label = "设备 %d" % (index if index and index > 0 else 1)
    head = "┌─ " + label + " "
    lines = [head + "─" * (CARD_WIDTH - _w(head) - 1) + "┐"]

    serial = info.get("serial") or "--"
    state = info.get("state") or ""
    serial_txt = serial if state in ("", "device") else "%s (%s)" % (serial, state)
    lines.append(_card_pair(info.get("model") or "--", serial_txt))
    lines.append(_card_line(_wifi_id_line(info)))
    lines.append(_card_line(_status_line(info)))
    lines.append(_card_line(_wifi_radio_line(info)))
    lines.append(_card_line(_hotspot_line(info)))
    lines.append(_card_line(_iface_line(info)))
    lines.append(_card_line(_system_line(info)))
    lines.append(_card_line(_iperf_line(info)))
    lines.append("└" + "─" * INNER_WIDTH + "┘")
    return "\n".join(lines)


SUMMARY_COLUMNS = (
    ("#", 2),
    ("型号", 13),
    ("序列号", 17),
    ("WiFi/热点", 17),
    ("频段/信道", 10),
    ("RSSI", 6),
    ("IP", 16),
    ("屏幕", 6),
    ("电量", 5),
    ("iperf", 6),
)


def _summary_cells(info, index):
    wifi = info.get("wifi") or {}
    ap = info.get("hotspot") or {}
    if wifi.get("enabled") and wifi.get("connected"):
        ssid = wifi.get("ssid") or "?"
    elif ap.get("on"):
        ssid = "热点 " + (ap.get("ssid") or "?")
    elif not wifi.get("enabled"):
        ssid = "已关闭"
    else:
        ssid = "未连接"
    band = (wifi.get("band") or "") if wifi.get("connected") else ""
    channel = freq_channel(wifi.get("freq_mhz")) if wifi.get("connected") else 0
    if not band and ap.get("on") and ap.get("freq_mhz"):
        band = freq_band(ap["freq_mhz"])
        channel = freq_channel(ap["freq_mhz"])
    band_txt = "%s/ch%s" % (band, channel) if band and channel else "--"
    rssi = wifi.get("rssi") if wifi.get("connected") else None
    ip = _wifi_ip(info) or (ap.get("ip") or "")
    screen = {"Awake": "亮", "Asleep": "灭"}.get(info.get("screen"), "--")
    level = (info.get("battery") or {}).get("level") or 0
    version = ""
    ipf = info.get("iperf") or {}
    if ipf.get("ok"):
        hit = re.search(r"iperf\s+([0-9.]+)", ipf.get("version") or "")
        version = hit.group(1) if hit else "OK"
    else:
        version = "--"
    return [
        str(index),
        info.get("model") or "--",
        info.get("serial") or "--",
        ssid,
        band_txt,
        "--" if rssi is None else str(rssi),
        ip or "--",
        screen,
        "%d%%" % level if level else "--",
        version,
    ]


def _summary_row(cells):
    out = []
    for text, (_, width) in zip(cells, SUMMARY_COLUMNS):
        text = str(text)
        if _w(text) > width:
            text = _trunc(text, width - 1).rstrip() + "…"
        out.append(_pad(text, width))
    return "  ".join(out).rstrip()


def render_summary_table(infos):
    """多台设备对照表，一行一台。"""
    if not infos:
        return "（没有检测到设备）"
    lines = [_summary_row([name for name, _ in SUMMARY_COLUMNS])]
    total = sum(width for _, width in SUMMARY_COLUMNS) + 2 * (len(SUMMARY_COLUMNS) - 1)
    lines.append("-" * total)
    for index, info in enumerate(infos, 1):
        lines.append(_summary_row(_summary_cells(info, index)))
    return "\n".join(lines)


def _ip_key(ip):
    parts = str(ip or "").split(".")
    if len(parts) != 4:
        return None
    try:
        return tuple(int(p) for p in parts)
    except ValueError:
        return None


def guess_roles(infos):
    """返回 (server_serial, client_serial) 建议。"""
    infos = list(infos or [])
    if not infos:
        return ("", "")
    if len(infos) == 1:
        return (infos[0].get("serial") or "", "")

    for info in infos:
        if (info.get("hotspot") or {}).get("on"):
            others = [i for i in infos if i is not info]
            server = info.get("serial") or ""
            client = (others[0].get("serial") or "") if others else ""
            return (server, client)

    same_ssid = []
    first_ssid = ((infos[0].get("wifi") or {}).get("ssid") or "")
    if first_ssid:
        same_ssid = [
            i
            for i in infos
            if (i.get("wifi") or {}).get("connected")
            and (i.get("wifi") or {}).get("ssid") == first_ssid
        ]
    if len(same_ssid) >= 2:
        ranked = sorted(
            same_ssid,
            key=lambda i: (
                _ip_key(_wifi_ip(i)) is None,
                _ip_key(_wifi_ip(i)) or (999, 999, 999, 999),
            ),
        )
        server = ranked[0].get("serial") or ""
        client = ranked[1].get("serial") or ""
        if server and client and server != client:
            return (server, client)

    return (infos[0].get("serial") or "", infos[1].get("serial") or "")


# ---------------------------------------------------------------- PC 环境

def _run(cmd, timeout=25):
    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError):
        return ("", "")
    return (_decode_console(proc.stdout), _decode_console(proc.stderr))


def _decode_console(raw):
    """Windows 控制台输出可能是 GBK，先试 UTF-8 再回落。"""
    for encoding in ("utf-8", "gbk"):
        try:
            return raw.decode(encoding)
        except (UnicodeDecodeError, AttributeError):
            continue
    return raw.decode("utf-8", "replace")


def _powershell_exe():
    for name in ("powershell", "pwsh"):
        path = None
        for ext in (".exe", ""):
            candidate = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"),
                                     "System32", "WindowsPowerShell", "v1.0", name + ext)
            if os.path.isfile(candidate):
                path = candidate
                break
        if path:
            return path
    return "powershell"


def _ps_json(script):
    exe = _powershell_exe()
    out, _err = _run([exe, "-NoProfile", "-NonInteractive", "-Command", script])
    out = out.strip()
    if not out:
        return None
    start = out.find("[")
    obj_start = out.find("{")
    if start < 0 and obj_start < 0:
        return None
    begin = start if (start >= 0 and (obj_start < 0 or start < obj_start)) else obj_start
    try:
        return json.loads(out[begin:])
    except ValueError:
        return None


_WIRELESS_RE = re.compile(r"wireless|wi-?fi|wlan|802\.11|无线", re.I)


def _adapters_via_ps():
    script = (
        "$ProgressPreference='SilentlyContinue';"
        "Get-NetAdapter | Select-Object Name,InterfaceDescription,Status | ConvertTo-Json -Compress"
    )
    data = _ps_json(script)
    if data is None:
        return None
    if isinstance(data, dict):
        data = [data]
    adapters = []
    for item in data:
        adapters.append(
            {
                "name": str(item.get("Name") or ""),
                "desc": str(item.get("InterfaceDescription") or ""),
                "status": str(item.get("Status") or ""),
            }
        )
    return adapters


def _ipv4_map_via_ps():
    script = (
        "$ProgressPreference='SilentlyContinue';"
        "Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceAlias,IPAddress | ConvertTo-Json -Compress"
    )
    data = _ps_json(script)
    if data is None:
        return {}
    if isinstance(data, dict):
        data = [data]
    mapping = {}
    for item in data:
        alias = str(item.get("InterfaceAlias") or "")
        ip = str(item.get("IPAddress") or "")
        if alias and ip:
            mapping[alias] = ip
    return mapping


def _adapters_via_wmic():
    out, _err = _run(["wmic", "nic", "where", "NetEnabled=true", "get",
                      "Name,NetConnectionID", "/format:list"])
    adapters = []
    current = {}
    for line in out.splitlines():
        line = line.strip()
        if not line:
            if current:
                adapters.append(current)
                current = {}
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if key == "Name":
            current["desc"] = value
        elif key == "NetConnectionID":
            current["name"] = value
    if current:
        adapters.append(current)
    for item in adapters:
        item.setdefault("name", "")
        item.setdefault("desc", "")
        item["status"] = "Up"
    return adapters


_ADAPTER_PREFIXES = (
    "Ethernet adapter ",
    "Wireless LAN adapter ",
    "Tunnel adapter ",
    "Unknown adapter ",
    "以太网适配器 ",
    "无线局域网适配器 ",
    "未知适配器 ",
    "隧道适配器 ",
)


def _clean_adapter_name(name):
    text = name.strip()
    for prefix in _ADAPTER_PREFIXES:
        if text.startswith(prefix):
            return text[len(prefix):].strip()
    return text


def _adapters_via_ipconfig():
    """最后兜底：ipconfig 不需要管理员权限，中英文都能解析。"""
    out, _err = _run(["ipconfig"])
    adapters = []
    current = None
    for raw in out.replace("\r", "").splitlines():
        line = raw.rstrip()
        if not line.strip():
            continue
        stripped = line.strip()
        if not raw[:1].isspace() and stripped.endswith(":"):
            if current:
                adapters.append(current)
            current = {"name": _clean_adapter_name(stripped[:-1]), "desc": "",
                       "status": "Up", "ip": ""}
            continue
        if current is None or ":" not in stripped:
            continue
        key, value = stripped.split(":", 1)
        key = key.strip().rstrip(".").strip()
        value = value.strip()
        if key.lower().startswith("ipv4") or "IPv4" in key:
            hit = re.search(r"([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)", value)
            if hit and not hit.group(1).startswith("127."):
                current["ip"] = hit.group(1)
        elif "Media State" in key or "媒体状态" in key:
            if "disconnected" in value.lower() or "断开" in value:
                current["status"] = "Down"
    if current:
        adapters.append(current)
    return adapters


def _ipv4_map_via_wmic():
    out, _err = _run(["wmic", "nicconfig", "where", "IPEnabled=true", "get",
                      "Description,IPAddress", "/format:list"])
    mapping = {}
    current = {}
    for line in out.splitlines():
        line = line.strip()
        if not line:
            if current:
                _merge_ip(mapping, current)
                current = {}
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if key == "Description":
            current["desc"] = value
        elif key == "IPAddress":
            hit = re.search(r"([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)", value)
            if hit:
                current["ip"] = hit.group(1)
    if current:
        _merge_ip(mapping, current)
    return mapping


def _merge_ip(mapping, current):
    desc = current.get("desc") or ""
    ip = current.get("ip") or ""
    if desc and ip:
        mapping[desc] = ip


def _windows_name():
    release = platform.release()
    version = platform.version()
    build = 0
    parts = version.split(".")
    if len(parts) >= 3 and parts[2].isdigit():
        build = int(parts[2])
    if build >= 22000:
        friendly = "Windows 11"
    elif build >= 10240:
        friendly = "Windows 10"
    elif release:
        friendly = "Windows %s" % release
    else:
        friendly = "Windows"
    return "%s (%s)" % (friendly, version)


def _powershell_version():
    exe = _powershell_exe()
    out, _err = _run([exe, "-NoProfile", "-NonInteractive", "-Command",
                      "$PSVersionTable.PSVersion.ToString()"])
    text = out.strip().splitlines()
    return text[0].strip() if text else ""


def _is_admin():
    try:
        import ctypes

        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def collect_pc_env():
    """采集 PC 环境信息。"""
    adapters = None
    try:
        adapters = _adapters_via_ps()
    except Exception:
        adapters = None
    if adapters is None:
        try:
            adapters = _adapters_via_wmic()
        except Exception:
            adapters = None
    if not adapters:
        try:
            adapters = _adapters_via_ipconfig()
        except Exception:
            adapters = adapters or []

    ip_map = {}
    try:
        ip_map = _ipv4_map_via_ps() or {}
    except Exception:
        ip_map = {}
    if not ip_map:
        try:
            ip_map = _ipv4_map_via_wmic() or {}
        except Exception:
            ip_map = {}
    if not ip_map:
        for item in adapters:
            ip = item.get("ip") or ""
            if ip and item.get("name"):
                ip_map[item["name"]] = ip

    wireless = []
    wired = []
    for item in adapters:
        name = item.get("name") or ""
        desc = item.get("desc") or ""
        label = "%s (%s)" % (name, desc) if name and desc else (name or desc)
        ip = ip_map.get(name) or ip_map.get(desc) or ""
        if _WIRELESS_RE.search(name) or _WIRELESS_RE.search(desc):
            wireless.append(label)
        elif ip and not ip.startswith("127."):
            wired.append({"name": label, "ip": ip})

    return {
        "has_wireless": bool(wireless),
        "wireless_adapters": wireless,
        "wired": wired,
        "os": _windows_name(),
        "python": platform.python_version(),
        "powershell": _powershell_version(),
        "admin": _is_admin(),
    }
