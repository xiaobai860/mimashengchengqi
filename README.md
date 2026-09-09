# 密码生成器（Mima）

一款基于 **LessPass** 确定性算法的离线密码管理器，支持本地生成固定密码，并将生成档案保存进加密 **KDBX** 密码本；同时内置**系统密码键盘**、**密码框自动切换**与**自动填充**能力，可在任意 App 的登录框里快速使用。

> 应用包名：`com.mima.app`（代码包名 namespace 与 applicationId 已统一）
> 当前版本：`versionName = 2026091001` / `versionCode = 64`（发布日期：2026-09-10）

---

# 第一部分：软件介绍

## 1. 核心定位

| 维度 | 说明 |
|---|---|
| 是什么 | 无状态（确定性）密码生成器，核心依据 `网站 + 用户名 + 主密码 + 计数器` 始终生成同一密码 |
| 不是什么 | **不是传统密码库**——密码本身绝不落地，密码本只保存「派生档案」（网站/登录名/长度/计数器/规则） |
| 数据流向 | 全程离线，无任何网络请求，不上传任何数据 |

## 2. 功能特性

### 2.1 密码生成（核心）
- 基于 LessPass 确定性算法：相同输入永远得到相同密码
- 可自定义长度、大小写字母 / 数字 / 符号组合，支持排除易混淆字符（0/O/o、1/l/I/i）
- 计数器（counter）：同一网站多个账号用不同计数器区分
- 指纹图标（fingerprint）：由主密码派生，用于在视觉上区分不同网站
- 一键复制到剪贴板

### 2.2 密码本管理
- 创建无密码明文本，或 AES-256 / Argon2id 加密的 KDBX 4.0 密码本
- 文件可在任意本地目录保存，符合 KDBX 4.0 标准，与 KeePassDX / KeePass / KeePassXC 互通
- 支持导入 / 切换外部 `.kdbx`，实时显示路径与加密状态
- 整库树浏览（分组 / 条目增删改）在「密码库管理」页

### 2.3 历史记录
- 自动记录每次生成的档案（网站、用户名、时间、长度等）
- 记录随密码本持久化，退出不丢失；支持从历史复制

### 2.4 安全特性
- 本地运行，零网络请求
- 主密码仅存内存，绝不落盘
- 生物识别（指纹）解锁；会话超时自动锁定
- 防截屏 / 防录屏（FLAG_SECURE）
- 清除数据：同时重置内存态，避免把新密码本写进失效 URI

### 2.5 密码键盘与自动切换（IME）
- **系统级输入法**：作为 Android 系统 IME 安装，可在任意 App 调起，键盘内列出密码本条目一键填入
- **键盘乱序**：随机打乱键盘按键排列，防肩窥
- **自动切入**（二选一，互斥）：
  - *ADB 直切*：授权 `WRITE_SECURE_SETTINGS` 后，聚焦密码框无感直切到 Mima 键盘（需先启用本键盘）
  - *弹窗选择器*：未授权时，聚焦密码框弹出系统输入法选择器供手动选择
- **自动切出 / 切回**：收起键盘后，再次唤起且焦点不是密码框时，自动切回目标键盘（上一个 / 指定 IME），可选宽限期（秒）
- 检测信号：本应用内由焦点监听触发；**第三方 App** 由系统 **Autofill 服务**的 `onFillRequest` 回调触发（系统明确告知「这里有可填充字段」，比无障碍扫描更可靠）

### 2.6 界面与体验
- Jetpack Compose + Material 3，统一形状系统与品牌渐变
- 亮 / 暗双主题 + 动态取色（关闭时按种子色派生），6 套预设色板
- 底部四标签：生成 → 历史 → 密码本 → 设置

## 3. 技术栈

| 项 | 值 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose（Material 3） |
| 最低 / 编译 / 目标 SDK | minSdk 34 / compileSdk 36 / targetSdk 36 |
| 构建 | AGP 9.3.0 + Gradle 9.5.0（JDK 17，使用 Android Studio 自带 JBR） |
| 加密底层 | 移植自 KeePassDX 的 `crypto` / `database` 模块 |

## 4. 开源引用

- **LessPass**（`app/.../crypto/`）：密码生成算法、指纹派生
- **KeePassDX**（`crypto/`、`database/` 模块）：KDBX 加解密与读写
- **Bouncy Castle**：Argon2id 等密码学原语

（详见文末「致谢」）

---

# 第二部分：开发指南

> 目的：让接手维护的人能快速建立全局认知，避开已知雷区。

## 1. 环境要求

- **JDK 17**：必须使用 Android Studio 自带的 JBR（`C:\Program Files\Android\Android Studio\jbr`），系统无 java，否则构建失败
- 签名：根目录 `keystore.properties`（已 gitignore，含 `RELEASE_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD`），密钥库存于项目外 `D:\Android\paibanrili`，别名 `key0`
- 仓库已配置腾讯云 / 阿里云镜像加速依赖下载

## 2. 项目结构

```
Mima/
├── app/                  主应用模块（com.mima.app）
│   └── src/main/kotlin/com/mima/app/
│       ├── MainActivity.kt           宿主：底部四标签 + 密码库管理 + 自动填充触发入口
│       ├── UnlockScreen.kt           解锁 / 创建密码本
│       ├── AddEntryDialog.kt         新增条目
│       ├── PasswordBookScreen.kt     密码本页（含搜索 / Folder 入口）
│       ├── HistoryScreen.kt          历史页
│       ├── VaultManagerScreen.kt     整库树浏览（分组/条目 CRUD）
│       ├── KeyboardSettingsScreen.kt 密码键盘设置页（启用/自动填充/乱序/切入/切出）
│       ├── ImeAutoSwitch.kt          输入法切换核心（切入/切出/当前 IME 检测）
│       ├── MimaKeyboardService.kt    系统 IME 实现
│       ├── MimaAutofillService.kt    自动填充服务（第三方密码框检测信号源）
│       ├── SwitchPickerActivity.kt   透明中转页（PICKER 模式弹选择器）
│       ├── PasswordFieldWatcherService.kt
│       ├── crypto/                   LessPass 算法（Entropy/Pbkdf2/ConsumeEntropy/RenderPassword/Chars/LessPassEngine）
│       ├── data/                      DatabaseManager / PasswordEntry / CredentialStore / TimeoutManager
│       └── ui/theme/                  Theme / MimaStyle / ThemePrefs（主题与种子色派生）
├── crypto/               加密模块（KeePassDX 移植：AES / Argon2 / HMAC）
├── database/             KDBX 数据库读写模块（KeePassDX 移植）
├── settings.gradle.kts   模块声明（:app :crypto :database）
├── keystore.properties   签名配置（gitignore）
└── README.md             本文档
```

## 3. 构建与运行

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleRelease --no-daemon
```
- 产物：`app/build/outputs/apk/release/app-release.apk`
- 安装：`adb install -r <apk>`（**降级覆盖会失败**：需先卸载，或用已装版本号更高的包）
- ⚠️ **务必以 `BUILD SUCCESSFUL` 区分大小写 + APK 的 LastWriteTime 双重确认**；`adb install` 的 `Success` 不代表构建成功（失败时会装旧包）

## 4. 关键架构

- **密码生成链路**：`熵(PBKDF2-HMAC-SHA256: site+login+counter+主密码)` → `ConsumeEntropy 大数除法消费` → `RenderPassword 满足约束/排除易混` → `Chars 字符集`
- **键盘数据通道**：`DatabaseManager` 静态 `keyboardEntries`（内存快照）由 `getPasswordBookEntries()` 同步、解锁时填充、锁定时清空；IME 内**勿用 ComposeView**（无 LifecycleOwner），用传统 View
- **自动切换**：
  - 切入二选一：`MODE_ADB`（直写 `DEFAULT_INPUT_METHOD`）/ `MODE_PICKER`（本应用内直弹；第三方经 `SwitchPickerActivity` 中转 + 重试状态机）
  - 切出：独立开关（默认关），`IME 侧 switchInputMethod` 实现，零权限
  - 检测：本应用内 = 焦点监听；第三方 = `MimaAutofillService.onFillRequest`
- **主题**：`Theme.kt` 的 `material3SchemeFromSeed` 自实现种子色派生（Material 3 1.3.1 无 `ColorScheme.fromSeed`）

## 5. 版本与发布

- **versionName** = `YYYYMMDDNN`（展示用，年月日 + 两位迭代版号，当天 01 / 02…）
- **versionCode** = 简单递增整数（系统用，每次发版 +1；当前 64）
- 二者语义不同，勿混用

## 6. 维护注意事项（雷区清单）

**必须保留的兼容逻辑**
- `crypto` 的 `NativeLib.init()` 被改为永远 `false`，KDF 走 Java 回退（AES=`AES/ECB/NoPadding`+SHA-256；Argon2=BouncyCastle）。改 `AESTransformer`/`Argon2Transformer` 必须保留 `native 缺失 → Java` 分支（`catch` 覆盖 `UnsatisfiedLinkError`/`Throwable`），否则 `UnsatisfiedLinkError` 闪退
- 主线程不可跑 KDF + 文件 IO，重操作须 `suspend` + `Dispatchers.IO`

**新 DSL 约束**
- `@Parcelize` 失效（编译过但缺 `writeToParcel`）→ 新增数据类手写 Parcelable
- 模块无 `org.jetbrains.kotlin.android`（与新 DSL 冲突），KGP 由插件内置
- 删 / 改名 res 后若 stale：加 `--rerun-tasks --no-build-cache` 重跑；`delete_file` 删 res 可能清空不删，改用 `Remove-Item -Force`

**UI / 资源**
- 矢量 drawable（`res/drawable/*.xml`）只能用平台属性 `?android:attr/...`（colorPrimary 等），因应用主题基类是 `android:Theme.Material.*`，引用 M3 属性会 inflate 失败；多色插画优先用 Compose `Canvas` + `MaterialTheme.colorScheme`
- 未提交改动 / 调试日志：出正式包前清理 `ImeAutoSwitch` / `SwitchPickerActivity` / `MimaAutofillService` 的 `Log.d`
- 所有用户可见文案走 string 资源（默认 `values/strings.xml` 英文、`values-zh/strings.xml` 中文），新增须两处都补

**设备 / ADB 操作**
- 改设备状态（授权、settings put、appops、卸载等）属高风险操作，**需用户明确确认后再做**，不擅自动手
- `WRITE_SECURE_SETTINGS` 用 `adb shell pm grant <包> android.permission.WRITE_SECURE_SETTINGS`；HyperOS 可能要求先开开发者选项「USB 调试（安全设置）」
- 改 namespace / applicationId 后组件类全名会变，需同步重写系统 `enabled_input_methods` 与 `autofill_service` 里的条目

## 7. 致谢

- **[LessPass](https://github.com/lesspass/lesspass)** — 无状态密码管理理念与算法
- **[KeePassDX](https://github.com/Kunzisoft/KeePassDX)** — 工业级 KDBX 实现
- **[Bouncy Castle](https://www.bouncycastle.org/)** — 密码学原语

## 8. 许可证

本项目基于 MIT 许可证开源；LessPass 算法与 KeePassDX 数据库库分别遵循其原始许可证。
