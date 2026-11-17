# YPLUS 热更新框架 · YPLUS HotPatch

> 基于 BlackBox 的 **车机 App 虚拟化宿主 + 热更新框架**。
> 宿主 APK 只做容器，业务 APK 装进沙箱运行 —— 业务热更新不需要重新发布宿主。

作者：**Anrays** <Anrays-Berry@users.noreply.github.com> · 版本 1.0.0 · 许可证：[GPL-3.0](LICENSE)（免费使用，保留署名）

---

## 一、这是什么

YPLUS 热更新框架是一个「宿主 + 沙箱业务」的 Android 应用容器。宿主 App（`com.anrays.yplus`）本身不承载业务界面，它基于开源虚拟化引擎 BlackBox 构建沙箱环境，把真正的业务包作为普通 APK 安装进沙箱运行。

带来三个直接好处：

| 好处 | 说明 |
|---|---|
| **壳与业务分离** | 宿主只负责承载、保活、权限与系统能力代理；业务独立迭代 |
| **热更新不动壳** | 业务 APK 在沙箱内覆盖安装即可换版本，宿主无需重新出包、重新签名、重新分发 |
| **系统能力由真实宿主代执行** | 通知监听、无障碍、运行时权限等按「真实安装的宿主 UID」校验的能力，由宿主持有并代理给沙箱业务 |

核心名词：

- **宿主 Host** —— 真实安装到系统的 `com.anrays.yplus`，权限与系统组件的唯一真实载体。
- **业务 Business** —— 被装进沙箱运行的 APK（如「源Plus」），可声明 `frame.consumer` 表示自己接管更新。
- **沙箱 BlackBox** —— `Bcore` 模块，提供进程 / 包管理 / 权限等虚拟化，使业务在隔离环境内运行。
- **FrameApi** —— 宿主与业务之间的跨进程能力协议（消费端 → 宿主 `startService` 调用）。

## 二、功能一览

| 功能 | 说明 | 关键实现 |
|---|---|---|
| 沙箱运行 APK | 把任意 APK 作为虚拟应用安装、启动、卸载、清数据 | `Bcore` / `BoxRepository` |
| 业务热更新 | 按 `seed` 判定版本，流式下载并覆盖安装业务 APK | `HotUpdateManager` |
| 在线 / 本地装包 | 扫描本地 APK、选择文件、输入地址、同步线上版本 | `MainActivity` / `LocalApkScanner` |
| 保活与自启 | 开机 / 上电 / 蓝牙等广播自启 + root 守护脚本 + 60s 心跳 | `HostBootReceiver` / `HostKeepAlive` / `ReviveReceiver` |
| 无障碍代执行 | 宿主真实无障碍服务代执全局动作与坐标手势 | `HostAccessibilityService` / `HostAccGuard` |
| 通知使用权 | 宿主真实通知监听，代查授权状态、代发通知 | `HostNotificationListener` / `HostNotifyHub` |
| 麦克风前台服务 | 宿主持有 microphone FGS，沙箱后台录音不被静音 | `HostMicService` |
| 权限载体 | 宿主声明并持有业务全套权限（危险权限运行时申请） | `AndroidManifest` / `PermissionBootstrap` |
| 悬浮切换球 | 前台时显示「宿主 ⇄ 业务」切换按钮 | `BoxSwitchOverlay` |
| DPI 缩放 | 原生 UI 密度缩放（`createConfigurationContext` 改写 `densityDpi`） | `DpiScale` |
| 更新提示 / 说明页 | 顶部提示条 + 版本说明 html + 立即更新 | `UpdateNoticeOverlay` / `UpdateActivity` |
| FrameApi 能力协议 | 业务跨进程调用宿主能力，版本化、只增不改 | `FrameApi` / `FrameApiHost` / `FrameBusinessLink` |

## 三、架构

### 3.1 模块职责

| 模块 | 职责 |
|---|---|
| `app` | 宿主应用：生命周期、管理界面、热更新、保活、权限、能力代理（`FrameApiHost`） |
| `Bcore` | BlackBox 虚拟化内核：包安装 / 启动 / 卸载、进程与服务、权限伪造、Binder 代理 |
| `black-reflection` | 注解定义：配合注解处理器生成 hidden API 反射代码，供 Bcore 访问系统隐藏接口 |
| `compiler` | 注解处理器（Kotlin / Java 编译期），产出 black-reflection 的反射实现 |

### 3.2 宿主启动链路

`CarBoxApp` 在 `attachBaseContext` 阶段完成 BlackBox 注入，之后才允许创建虚拟环境：

```text
attachBaseContext(base)
  ├─ DpiScale.wrap(base)                          // 原生 UI 密度缩放
  ├─ BlackBoxCore.onBeforeMainApplicationAttach(...)
  ├─ BlackBoxCore.doAttachBaseContext(base, clientConfiguration)
  └─ BlackBoxCore.onAfterMainApplicationAttach(...)

onCreate()
  ├─ BlackBoxCore.doCreate()                      // 创建沙箱环境
  ├─ PermissionBootstrap.ensure()                 // 宿主真实持有录音/悬浮窗等权限（有 root 时静默授予）
  ├─ HostKeepAlive.ensureRunning()                // root 守护脚本，force-stop 后拉活
  ├─ HostMicService.start()                       // microphone 前台服务
  ├─ HostAccGuard.install()                       // 无障碍保活
  ├─ BoxSwitchOverlay.start()                     // 右上角「宿主 ⇄ 业务」切换球
  └─ 60s 心跳：HostBridge.onReviveBroadcast() + HostAccGuard.ensure()
```

### 3.3 宿主与业务的关系

业务在沙箱内与宿主同 UID，系统按「宿主」校验权限与系统组件，因此宿主必须真实声明并持有相应权限。业务自带的无障碍 / 通知监听服务在沙箱里绑不上系统，只能由宿主真实组件代执行（见 FrameApi 的 `access.*` / `notify.*`）。业务与宿主的通信统一走 FrameApi：业务 `startService` 到宿主真实组件 `FrameUpdateHostService`。

## 四、热更新原理

目标：**在不改动宿主 APK 的前提下，把沙箱内的业务 APK 更新到新版本**。入口只有一份远端清单 `update.json`。

### 4.1 清单字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `seed` | 是 | 版本种子。**唯一**更新判定依据：与上次装成功记录的 seed 不同即视为新版本 |
| `apkUrl` | 是 | APK 下载地址（仓库路径 / http(s) 直链 / `file://`）。只决定从哪下，不参与判定 |
| `version` | 否 | 展示用版本号 |
| `releaseHtml` | 否 | 更新说明 html 路径；缺省用内置默认说明页 |
| `firstRunHtml` | 否 | 首次运行（无业务包）页面用的 html |
| `pushTime` | 否 | 发布时间（展示用） |
| `mode` | 否 | 安装模式：`0` / `1` + `clear` / `auto` |

```json
{
  "seed": "20261030-1",
  "apkUrl": "com.anrays.sourceplus/app/sourceplus-latest.apk",
  "version": "1.1.8",
  "releaseHtml": "com.anrays.sourceplus/resources/web/info/update.html",
  "firstRunHtml": "com.anrays.sourceplus/resources/web/info/first.html",
  "pushTime": "2026-10-30 09:00",
  "mode": "1"
}
```

### 4.2 判定口径：只比 seed

`seed` 与「上次装成功时记录的种子」不一致就更新，一致则什么都不做。`apkUrl` 可以长期不变；**装成功才记种子，失败不记**（下次重试）。

检查只由用户主动打开 App 触发（延迟约 `0.8s`，`5s` 节流），进程被开机广播 / 保活脚本拉起时不会主动检查 —— 避免后台被反复拉起来跑网络。

### 4.3 为什么沙箱可以不涨 versionCode

沙箱安装走 `BlackBoxCore.installPackageAsUser`，整条路径没有真实 `PackageManager` 的 versionCode 比较（不存在 `INSTALL_FAILED_VERSION_DOWNGRADE`），且只清理 `data/app/<pkg>` 与 lib 目录，`data/user/0/<pkg>` 下的 prefs / files / databases 原地保留。因此同一 versionCode 也能反复覆盖安装，业务数据默认保留。

### 4.4 安装模式 mode

| mode | force | clear | auto | 含义 |
|---|---|---|---|---|
| `0` / 空 | 否 | 否 | 否 | 标准更新，保留业务数据 |
| `1` | 是 | 否 | 否 | 强制自动更新，保留数据 |
| `0clear` / `1clear` | 视 1 | 是 | 否 | 装包前清掉业务数据 |
| `auto` / `1auto` | 视 1 | 否 | 是 | 同包名不清，异包名直接清数据更新 |

不区分大小写；`clear` 与 `auto` 同时出现时 `clear` 优先。`auto` 需要等下载完读到新包包名才能决定，因此在安装阶段落地。

### 4.5 传输通道

- **仓库 API 模式**（私有仓库可用）：清单走 `GET /api/v5/repos/<owner>/<repo>/contents/<path>?ref=<branch>` → base64 解出 JSON；下载先查父目录列表拿 `sha`，再 `GET .../git/blobs/<sha>` 流式解 base64 写盘（`contents` 对 >10MB 会截断，`blob` 不会；流式避免把上百 MB 塞进内存）。
- **普通直链模式**：其他绝对地址（自家服务器 / 公开直链）直接普通 GET，带进度回调。
- 代码里的 `REPO_OWNER` / `REPO_NAME` / `REPO_BRANCH` 均为**占位符**（`your-gitee-owner` / `your-business-repo`），使用前请改成你自己的仓库，否则检查 `update.json` 会直接失败。

### 4.6 安装与恢复

下载完成后由沙箱执行覆盖安装，安装会 kill 掉业务进程（悬浮窗 / 服务短暂中断）。装完若 App 在前台则 `launchApk` 把界面拉回；在后台则走 `HostBridge.onReviveBroadcast` 静默恢复（只拉进程 + 起保活服务），不会把界面弹到前台。

## 五、FrameApi 协议（v3）

「消费端（沙箱业务）→ 宿主」的跨进程能力协议。设计铁律：**新增能力一律加新 api，不改老 api 的语义 / 参数**；宿主不认识的新 api 返回 `UNSUPPORTED_API`，消费端据此降级。

```text
请求：消费端 startService 到宿主 FrameUpdateHostService
      cmd="api", extras: id(UUID) / api(字符串) / params(JSON 字符串)

响应：{ "action":"api_result", "id":"9f1c...", "ok":true,  "data":"{...}" }
      { "action":"api_result", "id":"9f1c...", "ok":false, "error":"BAD_PARAMS", "message":"..." }
事件：{ "action":"api_event",  "id":"9f1c...", "event":"progress", "data":"{\"downloaded\":1024,\"total\":4096}" }
```

错误码：`UNSUPPORTED_API`、`BAD_PARAMS`、`NOT_IN_FRAME`、`BUSY`、`IO`、`INSTALL_FAILED`、`PERMISSION`、`INTERNAL`；消费端侧还有 `TIMEOUT`、`CANCELED`。

能力一览（节选，完整清单以 `app/src/main/java/com/anrays/yplus/FrameApi.kt` 为准）：

| API | since | 作用 |
|---|---|---|
| `frame.capabilities` | v1 | `{v, apis:[{name,since}], names, frameVersion, hostPkg}` |
| `frame.ping` / `frame.log` | v2 | 轻量探活 / 写入宿主 logcat |
| `device.info` | v1 | 框架版本、宿主包名、userId、sdkInt |
| `app.install` / `app.installStatus` | v1 | 装包（事件：started / progress / done / failed）、查询上次结果 |
| `app.list` / `app.launch` / `app.uninstall` | v1 | 沙箱应用列表 / 启动 / 卸载 |
| `app.info` / `app.clearData` | v2 | 应用信息 / 清数据 |
| `perms.get` / `perms.openSettings` | v1 | 权限状态 / 跳设置页 |
| `perms.request` | v2 | 批量申请运行时权限 |
| `notify.state` / `notify.toast` / `notify.post` / `notify.cancel` | v1 / v1 / v1 / v2 | 通知使用权与通知代理 |
| `access.state` / `access.perform` / `access.gesture` | v1 / v1 / v2 | 无障碍状态、全局动作、坐标手势 |
| `overlay.setVisible` / `frame.openBox` | v1 | 悬浮球显隐 / 打开沙箱业务 |
| `update.check` / `update.apply` | v1 | 触发检查 / 执行更新 |
| `power.whitelist` | v2 | 电池优化白名单 |
| `frame.clipboard.get` / `frame.clipboard.set` | v2 | 剪贴板 |
| `frame.kv` | v3 | 宿主侧键值存储（命名空间＝业务包名，单值 ≤32KB、键数 ≤256） |

`app.install` 的 `extra` 是**不透明回显**：宿主不理解其中语义，原样带回，便于业务把「哪次点击」与「哪次安装」对应。

## 六、快速开始

环境要求：

- JDK **17**（Gradle 8.x / AGP）
- Android SDK：`compileSdk 35`、`targetSdk 28`、`minSdk 23`
- ABI：`arm64-v8a`、`armeabi-v7a`
- NDK / CMake（`Bcore` 含 native 代码）

```bash
git clone https://github.com/Anrays-Berry/YPLUS_HotPatch.git
cd YPLUS_HotPatch

# 1) 指定本机 SDK（不要提交这个文件）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2) 构建 debug 包（日常验证建议只出 debug）
./gradlew :app:assembleDebug
```

发布包签名：准备 `keystore.properties` 与 `keystore/*.jks`（均已在 `.gitignore` 中排除），`app/build.gradle` 会自动读取；缺失时 release 产出未签名 APK。产物命名 `YPLUS_v<版本>_<yyyyMMdd_HHmm>.apk`。

> `gradle.properties` 不含任何本机路径；若 Gradle 找不到 JDK 17，用 `-Dorg.gradle.java.home=...` 或在本地 `gradle.properties` 补一行即可。

## 七、目录结构

```text
.
├─ app/                     宿主应用（com.anrays.yplus）
│  └─ src/main/
│     ├─ java/com/anrays/yplus/
│     │  ├─ CarBoxApp.kt          生命周期与 BlackBox 注入
│     │  ├─ MainActivity.kt       管理界面
│     │  ├─ HotUpdateManager.kt   热更新（seed 判定 / 传输 / 沙箱安装）
│     │  ├─ FrameApi.kt           能力协议与清单（唯一出处）
│     │  ├─ FrameApiHost.kt       宿主侧 API 实现
│     │  ├─ FrameBusinessLink.kt  响应 / 事件推送
│     │  ├─ HostKeepAlive.kt      root 守护保活
│     │  ├─ HostAccessibilityService.kt  无障碍代执行
│     │  └─ ...
│     ├─ res/
│     └─ AndroidManifest.xml     权限集与系统组件声明
├─ Bcore/                   BlackBox 虚拟化内核（含 native: Bcore/src/main/cpp）
├─ black-reflection/        hidden API 反射注解
├─ compiler/                注解处理器
├─ build.gradle / settings.gradle / gradle.properties
└─ .gitignore
```

## 八、安全边界

- **不要提交** `keystore/`、`keystore.properties`、`local.properties`（已加入 `.gitignore`）。
- **不要提交** 各模块 `build/`、`*.log`、`hs_err_pid*.log`、`.tmp/`（已加入 `.gitignore`）。
- `HotUpdateManager.GITEE_TOKEN` 默认**空串**：空令牌时请求 URL 不会追加 `access_token`（适用于 public 仓库）。从私有仓库拉取时，只填你自己的**只读**观察者令牌；**可读写令牌严禁打进 APK**。
- 宿主刻意移除了 Bcore 继承的大量无关 / 系统级权限，只保留业务实际需要的普通与危险权限，便于用户逐项授权。

> 本项目涉及虚拟化与系统能力代理，**请仅在合法授权与自担风险的范围内使用**：用于学习、研究与自有车机应用的合法部署，请勿用于未获授权的第三方应用分发或规避系统安全机制。

## 九、许可与致谢

本项目以 **[GNU GPL-3.0](LICENSE)** 授权：**免费使用（含商业用途）、可修改、可再分发；分发（含 APK / 二进制 / 设备预装）时须以同一许可证开放对应源代码，并保留作者署名。**

依据 GPL-3.0 第 7 条(b) 项，作者附加**署名要求** —— 分发时须在 README、软件「关于」页或许可页等显著位置标注：

> 本软件基于 YPLUS 热更新框架，作者：Anrays &lt;Anrays-Berry@users.noreply.github.com&gt;
> 项目地址：<https://github.com/Anrays-Berry/YPLUS_HotPatch>

修改版本请注明「基于 YPLUS 热更新框架修改」，且不得声称原作者对修改版本背书。移除署名即视为违反许可证。完整条款见 [LICENSE](LICENSE) 末尾「附加条款」。

`Bcore`、`black-reflection`、`compiler` 来自开源项目 **BlackBox**（作者 niunaijun 等，GPL-3.0），`Bcore` 内置的 **Dobby** 遵循 Apache-2.0；第三方组件详见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

完整设计文档见 [`docs/YPLUS框架说明.docx`](docs/YPLUS框架说明.docx)（功能 / FrameApi / 热更新原理 / JSON 示例）。

---

**作者**：Anrays <Anrays-Berry@users.noreply.github.com>
**项目地址**：<https://github.com/Anrays-Berry/YPLUS_HotPatch>
