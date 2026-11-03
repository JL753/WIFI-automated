# -*- coding: utf-8 -*-
"""手机 ↔ PC 的结果传输协议。

手机端把一次测试跑完的结果打成一个 bundle（JSON），POST 到 PC 的
`/api/v1/results`。bundle 的字段与 WiFi吞吐量测试工具 的 build_payload()
入参一一对应，PC 收到后不用做任何字段转换就能出报告。
"""

SCHEMA = "wifitput.result/1"
API_PREFIX = "/api/v1"
PATH_RESULTS = API_PREFIX + "/results"
PATH_PING = API_PREFIX + "/ping"

MODES = ("wifi", "hotspot", "p2p")

# report.py 的 Record 18 字段，顺序即 CSV 列顺序
RECORD_FIELDS = ("t", "elapsed", "test", "kind", "stream", "start", "end",
                 "transfer", "unit", "rate", "rate_unit", "retr", "jitter",
                 "lost", "total", "lost_pct", "role", "raw")


class BundleError(ValueError):
    """bundle 结构不合法。消息是给人看的中文说明。"""


def _is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def validate(bundle):
    """校验并补齐 bundle，返回归一化后的新 dict。不合法抛 BundleError。"""
    if not isinstance(bundle, dict):
        raise BundleError("结果包必须是一个 JSON 对象")
    schema = str(bundle.get("schema") or "")
    if schema and schema != SCHEMA:
        raise BundleError("结果包版本不支持：%s（本机只认 %s）" % (schema, SCHEMA))

    devices = bundle.get("devices") or []
    results = bundle.get("results") or []
    records = bundle.get("records") or []
    if not isinstance(devices, list):
        raise BundleError("devices 必须是数组")
    if not isinstance(results, list):
        raise BundleError("results 必须是数组")
    if not isinstance(records, list):
        raise BundleError("records 必须是数组")
    if not results and not [d for d in devices if isinstance(d, dict)]:
        raise BundleError("结果包里既没有 results 也没有 devices，没有可出的报告")

    run = bundle.get("run") if isinstance(bundle.get("run"), dict) else {}
    mode = str(run.get("mode") or "").strip().lower()
    if mode and mode not in MODES:
        raise BundleError("未知测试类型 mode=%s（只接受 %s）" % (mode, " / ".join(MODES)))

    meta = bundle.get("meta") if isinstance(bundle.get("meta"), dict) else {}
    meta = dict(meta)
    if mode:
        meta.setdefault("mode", mode)
    if run.get("started_at"):
        meta.setdefault("started_at", run["started_at"])
    if run.get("finished_at"):
        meta.setdefault("finished_at", run["finished_at"])
    if run.get("id"):
        meta.setdefault("run_id", run["id"])

    out = dict(bundle)
    out.update({
        "schema": SCHEMA,
        "run": run,
        "meta": meta,
        "devices": [d for d in devices if isinstance(d, dict)],
        "results": [r for r in results if isinstance(r, dict)],
        "records": [r for r in records if isinstance(r, dict)],
        "screen": bundle.get("screen") if isinstance(bundle.get("screen"), dict) else None,
        "notes": bundle.get("notes") if isinstance(bundle.get("notes"), list) else [],
    })
    return out


def sample_bundle(mode="wifi", serial="SIMULATED-0001"):
    """造一个结构完整的结果包，用来验证「收结果 → 出报告」链路。"""
    import datetime as _dt

    now = _dt.datetime.now()
    t0 = now.strftime("%Y-%m-%d %H:%M:%S")
    recs = []
    # 4 流 × 10 秒，速率先升后稳再抖一下，曲线看起来像真的
    for sec in range(1, 11):
        base = 40.0 + sec * 12.0 if sec <= 5 else 100.0
        if sec == 8:
            base = 46.0            # 故意掉一秒，验证「最低」与停滞提示
        for stream in (4, 6, 8, 10):
            rate = base * 0.25
            recs.append({
                "t": (now).strftime("%H:%M:%S"),
                "elapsed": float(sec),
                "test": "tcp_up",
                "kind": "TCP",
                "stream": "#%d" % stream,
                "start": round(sec - 1.0, 2),
                "end": round(float(sec), 2),
                "transfer": round(rate * 1.0 / 8.0, 3),
                "unit": "MBytes",
                "rate": round(rate, 1),
                "rate_unit": "Mbits/sec",
                "retr": 0,
                "jitter": None,
                "lost": None,
                "total": None,
                "lost_pct": None,
                "role": "sender",
                "raw": "",
            })
    sum_recs = []
    for sec in range(1, 11):
        base = 40.0 + sec * 12.0 if sec <= 5 else 100.0
        if sec == 8:
            base = 46.0
        sum_recs.append({
            "t": now.strftime("%H:%M:%S"),
            "elapsed": float(sec),
            "test": "tcp_up",
            "kind": "TCP",
            "stream": "SUM",
            "start": round(sec - 1.0, 2),
            "end": round(float(sec), 2),
            "transfer": round(base / 8.0, 3),
            "unit": "MBytes",
            "rate": round(base, 1),
            "rate_unit": "Mbits/sec",
            "retr": 12 if sec == 8 else 0,
            "jitter": None,
            "lost": None,
            "total": None,
            "lost_pct": None,
            "role": "sender",
            "raw": "",
        })
    recs.extend(sum_recs)

    tail = {
        "t": now.strftime("%H:%M:%S"),
        "elapsed": 10.0,
        "test": "tcp_up",
        "kind": "TCP",
        "stream": "SUM",
        "start": 0.0,
        "end": 10.0,
        "transfer": 109.8,
        "unit": "MBytes",
        "rate": 92.0,
        "rate_unit": "Mbits/sec",
        "retr": 12,
        "jitter": None,
        "lost": None,
        "total": None,
        "lost_pct": None,
        "role": "sender",
        "raw": "",
    }
    recs.append(tail)

    dev = {
        "serial": serial,
        "state": "device",
        "model": "模拟设备",
        "brand": "SIM",
        "android": "17",
        "sdk": "37",
        "abi": "arm64-v8a",
        "battery": {"level": 78, "temp_c": 33.0},
        "screen": "Awake",
        "ram_mb": 7367,
        "cores": 8,
        "wifi": {"connected": True, "ssid": "模拟热点", "band": "5GHz", "channel": 36,
                 "rssi": -48, "link_tx_mbps": 540, "link_rx_mbps": 540, "phy": "11ax",
                 "freq_mhz": 5180, "addrs": ["10.23.160.23/24"], "ifaces": ["wlan0"]},
        "hotspot": {"on": mode == "hotspot", "ssid": "模拟热点", "iface": "wlan1",
                    "addrs": ["10.23.160.19"], "freq_mhz": 5180, "clients": 1},
        "iperf": {"version": "iperf 3.22 (cJSON 1.7.15)", "path": "/data/local/tmp/iperf3"},
    }
    return {
        "schema": SCHEMA,
        "app": {"name": "WiFi 打流", "version": "1.0", "package": "com.wifitput.android"},
        "run": {"id": "sim_%s" % now.strftime("%Y%m%d_%H%M%S"), "mode": mode,
                "started_at": t0, "finished_at": now.strftime("%Y-%m-%d %H:%M:%S")},
        # 目录名和报告标题都从 items 推出来，热点的包必须报 hotspot_tput，
        # 否则落到 输出\ 下会叫 wifi_tput，看目录分不清跑的是哪种
        "meta": {"items": [{"hotspot": "hotspot_tput", "p2p": "wifi_tput"}.get(mode, "wifi_tput")],
                 "duration": 10, "streams": 4, "reverse": False,
                 "port": 5201, "udp_bw": "", "mode": mode},
        "devices": [dev],
        "results": [{
            "test_id": "tcp_up",
            "name": "WiFi 吞吐量（TCP 正向）",
            "kind": "TCP",
            "params": {"duration": 10, "streams": 4, "reverse": False, "mode": mode},
            "sender": {"rate": 92.0, "unit": "Mbits/sec"},
            "receiver": {"rate": 89.0, "unit": "Mbits/sec"},
            "ok": True,
            "error": "",
            "records_n": len(recs),
        }],
        "records": recs,
        "screen": None,
        "notes": ["这是 PC 端「模拟发送」生成的结果包，不是真机数据。"],
    }
