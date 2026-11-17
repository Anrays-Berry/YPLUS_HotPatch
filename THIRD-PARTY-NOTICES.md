# 第三方组件声明 / Third-Party Notices

本仓库包含或依赖以下第三方组件。它们**不适用**本项目的 YPLUS HotPatch License，
而是遵循各自原始许可证。分发本项目（尤其是以 APK / 二进制形式）时，请一并履行下列义务。

## 1. BlackBox —— 虚拟化内核

| 项目 | 内容 |
|---|---|
| 组件 | `Bcore/`、`black-reflection/`、`compiler/`（包名 `top.niunaijun.*`） |
| 上游 | BlackBox · <https://github.com/niunaijun/BlackBox> |
| 作者 | niunaijun 及贡献者 |
| 许可证 | GPL-3.0（以该仓库当前声明为准） |
| 本项目改动 | 裁剪宿主权限集、适配 targetSdk 28 旧行为、适配 JDK 17 / AGP 8 构建、修正部分虚拟化路径 |

> ⚠️ GPL-3.0 具有传染性：若你将包含上述模块的整体以二进制形式对外分发，
> 需按 GPL-3.0 提供对应完整源代码，并保留原始版权声明。本项目自有代码
> （`app` 模块）采用 YPLUS HotPatch License，不改变上述模块的上游许可。

## 2. Dobby —— 内联 Hook 库

| 项目 | 内容 |
|---|---|
| 组件 | `Bcore/src/main/cpp/Dobby/`（`libdobby.a`、`dobby.h`） |
| 上游 | Dobby · <https://github.com/jmpews/Dobby> |
| 作者 | jmpews |
| 许可证 | Apache-2.0 |

## 3. AndroidX / Kotlin / 其他构建依赖

`app` 与各模块的依赖（AndroidX、Kotlin stdlib、JavaPoet、AutoService、Gradle / AGP 等）
均为 Apache-2.0 或兼容许可证，随 Gradle 依赖解析获取，未在本仓库内二次分发源码。

分发包时请保留各依赖自带的许可声明（APK 内 `META-INF/` 或关于页）。

---

如你是上述项目的权利人，认为本仓库的使用方式不妥，请通过项目 issue 联系，我们会及时处理。
