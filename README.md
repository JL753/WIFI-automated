# WiFiTput · WiFi / 热点 / P2P 吞吐量测试工具集

一套测手机 WiFi 真实空口吞吐的工具，四种形态，可以单独用，也可以串起来用：

| 目录 | 是什么 | 跑在哪 | 需要什么 |
| --- | --- | --- | --- |
| [`AndroidApp`](AndroidApp) | 手机 App（Android），双机互打流，WiFi / 热点 / P2P 三模式；控制台式界面、深色模式、历史对比、CSV 导出 | 两台 Android 手机 | 装 `AndroidApp/APK/WiFiTput.apk` |
| [`PC端工具`](PC端工具) | PC 端接收器：收手机推来的结果，落盘 CSV/JSON/MD 并直接看报告 | Windows + Python 3.8+ | PySide6（需自行安装，见下） |
| [`WiFi吞吐量测试工具`](WiFi吞吐量测试工具) | 命令行版，7 个测试项合一，自带报告渲染 | Windows + Python 3.8+ | 纯标准库，零依赖 |
| [`WiFiTput`](WiFiTput) | 上面那个命令行工具打包好的独立 exe | Windows | 什么都不用装，双击即用 |
<img width="226" height="495" alt="image" src="https://github.com/user-attachments/assets/7223bb1f-cd9a-429f-b07d-32a1b380c647" />
<img width="230" height="491" alt="image" src="https://github.com/user-attachments/assets/db0c1ac2-a9c0-4a68-a1aa-e38c6785d3a1" />
<img width="224" height="494" alt="image" src="https://github.com/user-attachments/assets/575f873c-bbee-45f2-ad7d-dd93006391e6" />
<img width="223" height="492" alt="image" src="https://github.com/user-attachments/assets/f5570ccc-a9e4-4b86-b73f-54469b367734" />
<img width="577" height="347" alt="image" src="https://github.com/user-attachments/assets/7bcbfbaa-0a8c-44c2-84b7-a09ee3a8d7a2" />

---

## 一分钟上手

### 只想快速测一次（不装任何东西）

把 `WiFiTput` 整个目录拷到目标电脑，双击 `WiFiTput.exe`，按菜单选测试项。

### 想用手机 App 测（推荐，能测 P2P）

1. 两台 Android 手机（8.0 以上、arm64）都装 `AndroidApp/APK/WiFiTput.apk`。
2. A 打开 App → 点「当服务端」。
3. B 打开 App → 点「扫描对端」→ 在弹窗里点 A → 点「开始打流」。
4. 打流过程中**两台手机的日志同步逐秒滚动**，跑完各自留一条历史记录（顶栏「⋯ → 查看历史记录」）。
5. 想把结果汇总到 PC 出报告：PC 上跑 `PC端工具`，手机「⋯ → 设置结果回传 PC」填上 PC 地址即可自动上传。

### 想用命令行批量测

```
cd WiFi吞吐量测试工具
启动.bat
```

---

## 四种形态怎么选

- **要测 P2P（Wi-Fi Direct）** → 只能用 `AndroidApp`。命令行版和 exe 版都做不了 P2P。
- **要测灭屏对比、长稳、UDP QoS、链路快照** → 用 `WiFi吞吐量测试工具`（7 个测试项都在里面）。
- **要在没有 Python 的电脑上跑** → 用 `WiFiTput` 的 exe。
- **要归档格式统一的报告** → 三者产出同一套 `逐秒明细.csv` / `汇总.json` / `测试报告.md`，
  渲染用的是同一份 `report.py`，所以报告长得一模一样。

---

## 目录结构

```
.
├─ AndroidApp              Android App 完整 Gradle 工程
│  ├─ APK                  交付产物：WiFiTput.apk（release）+ WiFiTput-debug.apk
│  ├─ appsrcmain         java 源码 + jniLibs（iperf3 三件套）+ res
│  ├─ 打包apk.bat           一键 assembleDebug + assembleRelease
│  ├─ 使用说明.md           安装、双机怎么测、三模式、历史记录、三条硬性限制
│  └─ 测试原理与数据说明.md  打流原理、每个数字的来源、可信度边界、行业覆盖
│
├─ PC端工具                 PC 接收器（PySide6 三 Tab：收结果 / 出报告 / 设置）
│  ├─ pcapp                receiver / reports / protocol / ui
│  ├─ vendor               ← 不进仓库（PySide6 约 215 MB），见「关于 PySide6」
│  ├─ 启动.bat              设好 PYTHONPATH 后拉起 main.py
│  ├─ 使用说明.md           使用与排错
│  └─ 协议说明.md           手机 → PC 的结果包 HTTP 协议
│
├─ WiFi吞吐量测试工具        命令行版（纯标准库）
│  ├─ 启动.bat              薄启动器：找 python → py -3 → 自带 python → 降级引擎
│  ├─ 打包exe.bat           用 PyInstaller 打成 exe（产物默认在 ..WiFiTput）
│  ├─ bin                  手机端 iperf3-arm64v8 + win 的 Cygwin iperf3.exe
│  ├─ toolsmain.py         包入口；toolswb 是引擎
│  ├─ toolsallback       无 Python 时的 PowerShell 5.1 降级引擎
│  ├─ 输出示例            一份完整的样例产出（7 项全跑）
│  ├─ README.md             功能清单与报告格式
│  └─ 使用说明.md           面向没用过的人的十节说明
│
└─ WiFiTput                 exe 版（PyInstaller onedir 产物，44 文件 / 约 38 MB）
   ├─ WiFiTput.exe
   ├─ _internal             Python 运行时与依赖 DLL
   └─ bin                   iperf3 二进制
```

---

## 关于 PySide6（PC端工具）

`PC端工具` 的界面库 PySide6 约 **215 MB / 2624 个文件**，没有放进这个仓库。
克隆下来之后，在 `PC端工具` 目录里装一次就行：

```
python -m pip install --target vendor PySide6==6.12.0
```

装完 `vendor\PySide6\__init__.py` 存在，`启动.bat` 就能正常拉起界面。
没装的话 `启动.bat` 会明确报 `[ERROR] PySide6 is missing in the vendor folder.`。

`PC端工具` 出报告时优先用 `..\WiFi吞吐量测试工具\tools\wb\report.py`（改一处两边同步），
找不到才退回 `pcapp\_vendor_report.py` 副本。所以这两个目录保持同级放置最省事。

---

## 关于 `_android_sdk`（重新打 APK 才需要）

`AndroidApp/打包apk.bat` 需要 JDK 17 + Gradle 8.7 + build-tools 34 + platform 34。
仓库里不带这套工具链（约 1.2 GB）。两种办法：

1. 自己装 Android Studio / SDK，然后设环境变量 `ANDROID_SDK_DIR` 指向它。
2. 把便携工具链放到 `AndroidApp\..\_android_sdk` 或 `AndroidApp\..\..\_android_sdk`。

脚本按 `ANDROID_SDK_DIR` → `..\_android_sdk` → `..\..\_android_sdk` 的顺序找。

---

## 已知限制

- **Android App 只支持 arm64-v8a / Android 8.0（API 26）以上**。x86 模拟器装不了。
- iperf3 必须在 App 自己的 nativeLibraryDir 里跑（App 的文件目录是 noexec）。
  `libiperf3.so` / `libiperf.so` / `libcrypto.so` 三件套靠 `$ORIGIN` 找依赖，升级要一起换。
- release APK 用的是本机的 debug 签名证书（`CN=Android Debug`），不是正式发布证书。
  自己用没问题，上应用商店必须换密钥重签。
- `PC端工具` 和 `WiFi吞吐量测试工具` 都只在 Windows 上验证过。
- 三者的报告都靠 `report.py` 渲染，改它要同时跑一遍命令行版和 PC 端的自检：
  `启动.bat --selftest`（PC 端）、`python tools\main.py`（命令行版）。

---

## 版本

- iperf3 **3.22**（手机端 Termux 动态链接构建 / PC 端 Cygwin 构建）
- Android App **1.0**，`WiFiTput.apk` release 4,307,132 字节
- 结果包 schema：`wifitput.result/1`
