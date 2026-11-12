# WiFiTput · WiFi / 热点 / P2P 吞吐量测试工具集

一套测手机 WiFi 真实空口吞吐的工具，保留两种形态，可以单独用，更推荐串起来用：

| 目录 | 是什么 | 跑在哪 | 需要什么 |
| --- | --- | --- | --- |
| [`AndroidApp`](AndroidApp) | 手机 App（Android），双机互打流 WiFi / 热点 / P2P + 一台手机对着 PC 的「连PC」模式；控制台式界面、深色模式、历史对比、CSV 导出 | 两台 Android 手机 | 装 `AndroidApp/APK/WiFiTput.apk` |
| [`PC端工具`](PC端工具) | PC 端接收器 + 「本机服务端」（给连PC 模式一键起 iperf3）；收手机推来的结果，落盘 CSV/JSON/MD 并直接看报告 | Windows + Python 3.8+ | PySide6（需自行安装，见下） |
<img width="423" height="927" alt="aaa353b7-cb39-47a7-81e8-750597d67890" src="https://github.com/user-attachments/assets/469f6af0-7fde-43d8-805e-80767eafd288" />
<img width="421" height="930" alt="4db9909c-6f5b-4060-a800-b09e675478a3" src="https://github.com/user-attachments/assets/4b90bd0e-1a5b-481b-81ee-28b97bda81cd" />
<img width="1072" height="746" alt="image" src="https://github.com/user-attachments/assets/3e2028e5-97a1-4064-bbef-0e4af3a77dcb" />


---

## 一分钟上手

### 双机互测（手机 App，能测 P2P）

1. 两台 Android 手机（8.0 以上、arm64）都装 `AndroidApp/APK/WiFiTput.apk`。
2. A 打开 App → 点「当服务端」。
3. B 打开 App → 点「扫描对端」→ 在弹窗里点 A → 点「开始测试」。
4. 打流过程中**两台手机的日志同步逐秒滚动**，跑完各自留一条历史记录（顶栏「⋯ → 查看历史记录」）。
5. 想把结果汇总到 PC 出报告：PC 上跑 `PC端工具`，手机「⋯ → 设置结果回传 PC」填上 PC 地址即可自动上传。

### 手机对 PC 测（连PC 模式）

1. PC 上装好 PySide6 并双击 `PC端工具\启动.bat`，在「本机服务端」页点「启动服务端」。
2. 手机打开 App 的「连PC」页 → 点「扫描 PC」→ 选中这台 PC → 点「开始测试」。
3. 依次跑空闲 ping、下载（PC→手机）、上传（手机→PC），结果自动回传 PC 并在「收结果 / 出报告」页查看。

---

## 两种形态怎么配合

- **要测 P2P（Wi-Fi Direct）** → 用 `AndroidApp` 的 P2P 页，两台手机扫码即连。
- **要测 WiFi / 热点双机互打流** → 只用 `AndroidApp`，结果留在手机历史里，也能回传 PC。
- **要测一台手机对着 PC 的下载 / 上传 / ping** → `AndroidApp` 的连PC 页配合 `PC端工具` 的「本机服务端」。
- **要归档格式统一的报告** → 由 `PC端工具` 落盘 `逐秒明细.csv` / `汇总.json` / `测试报告.md` / `原始输出_<测试项>.txt`，渲染用 `PC端工具/pcapp/_vendor_report.py`；手机端也按同一套字段和 CSV 列序出结果卡与本地 CSV 导出。

> 原命令行版 `WiFi吞吐量测试工具` 及其打包的 `WiFiTput` exe 已从本工作区移除，这里不再提供这两种形态；报告渲染随 `PC端工具` 自带的副本保留，功能不受影响。

---

## 目录结构

```
.
├─ AndroidApp              Android App 完整 Gradle 工程
│  ├─ APK                  交付产物：WiFiTput.apk（release）+ WiFiTput-debug.apk
│  ├─ app\src\main        java 源码 + jniLibs（iperf3 三件套）+ res
│  ├─ 打包apk.bat           一键 assembleDebug + assembleRelease
│  ├─ 使用说明.md           安装、双机怎么测、四模式、历史记录、三条硬性限制
│  └─ 测试原理与数据说明.md  打流原理、每个数字的来源、可信度边界、行业覆盖
│
├─ PC端工具                 PC 接收器（PySide6 四 Tab：本机服务端 / 收结果 / 出报告 / 设置）
│  ├─ pcapp                receiver / reports / protocol / ui + _vendor_report.py（报告渲染）
│  ├─ bin\win              iperf3.exe + Cygwin DLL（本机服务端用）
│  ├─ vendor               ← 不进仓库（PySide6 约 215 MB），见「关于 PySide6」
│  ├─ 启动.bat              pythonw 无窗口启动，必要时自动装 PySide6
│  ├─ 使用说明.md           使用与排错
│  ├─ 协议说明.md           手机 → PC 的结果包 HTTP 协议
│  └─ 输出                  按次建子目录的运行结果（示例目录保留）
│
└─ docs                    设计文档（连PC 吞吐量方案与实现偏差）
```

---

## 关于 PySide6（PC端工具）

`PC端工具` 的界面库 PySide6 约 **215 MB / 2624 个文件**，没有放进这个仓库。
双击 `PC端工具\启动.bat` 会**无命令窗口**打开界面：启动器自动挑选与依赖 ABI
匹配的 Python，缺 `vendor\` 时联网执行一次
`python -m pip install --target vendor PySide6==6.12.0`（只装一次，被 git 忽略）。
也可以手动在 `PC端工具` 目录执行上面这条命令。

若双击后没有界面，看 `PC端工具\启动错误.log`，或跑
`PC端工具\启动.bat --selftest` 看控制台自检输出。

`PC端工具` 出报告时用随包的 `pcapp\_vendor_report.py`（原命令行版 `report.py` 的完整副本，57,372 字节）。
代码仍会先探测同级目录里已移除的命令行版 `report.py`，找不到就自动回退到这份副本，
所以克隆后无需补装任何东西，改报告渲染也只需要维护这一份。

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
- `PC端工具` 只在 Windows 上验证过；手机端需 arm64-v8a 真机，未在 x86 模拟器上验证。
- `PC端工具` 的 HTTP 结果接收监听 `0.0.0.0:8737` 且无鉴权，请只在可信局域网内使用。
- 环境自检：`PC端工具\启动.bat --selftest`（PC 端，离线检查 vendor / 报告模块 / 端口 / 输出目录）。

---

## 版本

- iperf3 **3.22**（手机端 Termux 动态链接构建 / PC 端 Cygwin 构建）
- Android App **1.0**，`WiFiTput.apk` release 4,370,808 字节
- 结果包 schema：`wifitput.result/1`
