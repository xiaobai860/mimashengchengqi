# 密码生成器

一款基于 **LessPass** 算法的无状态密码管理器，支持离线生成确定性密码，并将生成结果保存至加密的 **KDBX** 密码本文件中。

## 功能特性

### 密码生成
- 基于 LessPass 确定性算法，同一网站+用户名+主密码始终生成相同密码
- 支持自定义密码长度、大小写字母、数字、符号
- 可排除易混淆字符（0/O/o、1/l/I/i 等）
- 计数器功能，支持同一网站多账号密码管理
- 指纹图标可视化，方便通过图标识别网站
- 一键复制到剪贴板

### 密码本管理
- 支持创建无密码保护的明文密码本
- 支持创建 AES-256 / Argon2id 加密的密码本
- 可在任意本地目录保存 `.kdbx` 格式密码本文件
- 支持导入/切换外部 `.kdbx` 密码本文件
- 实时显示密码本文件路径和加密状态
- 密码本文件符合 KDBX 4.0 标准，兼容 KeePass 系列应用

### 历史记录
- 自动记录所有生成的密码
- 每条记录包含网站、用户名、密码、生成时间、主密码及长度
- 历史记录保存在密码本中，退出后不丢失
- 支持从历史记录中复制密码和查看主密码

### 安全特性
- 本地运行，无任何网络请求，不上传任何数据
- 主密码不落地存储，仅保存在内存中
- 支持生物识别（仅指纹）解锁密码本
- 会话超时自动锁定，防止他人在解锁状态下查看
- 密码本文件可存储在本地


### 界面与体验
- 基于 Jetpack Compose + Material 3 设计
- 流畅的动画和交互反馈
- 支持深色/浅色主题自适应
- 清晰的字段提示和错误引导

## 技术栈

- **语言**: Kotlin
- **UI 框架**: Jetpack Compose (Material 3)
- **最低支持**: Android 7.0 (API 24)
- **编译目标**: Android SDK 35 / Java 17
- **生物识别**: androidx.biometric

## 开源项目引用

本项目在开发过程中引用了以下优秀的开源项目，在此深表感谢。

### 1. LessPass

**项目地址**: https://github.com/lesspass/lesspass

**引用内容**:
- **密码生成算法**: 完整实现了 LessPass 核心算法，包括：
  - `Entropy.kt` — 基于 `PBKDF2-HMAC-SHA256` 的熵值计算，将（网站+用户名+计数器）与主密码结合生成确定性的 32 字节熵值
  - `Pbkdf2.kt` — PBKDF2 密钥派生函数实现，使用 100,000 次迭代
  - `ConsumeEntropy.kt` — 基于大数除法的熵值消费算法，将熵值转换为指定字符集内的字符序列
  - `RenderPassword.kt` — 密码渲染逻辑，确保输出密码满足长度、字符类型约束，并排除易混淆字符
  - `Chars.kt` — 字符集定义（小写、大写、数字、符号）及近似字符排除规则
  - `LessPassEngine.kt` — 算法入口，提供 `generatePassword()`、`buildFingerprint()` 及官方自检向量验证
- **指纹生成**: 使用 HMAC-SHA256 对主密码进行摘要，映射为图标和颜色

### 2. KeePassDX

**项目地址**: https://github.com/Kunzisoft/KeePassDX

**引用内容**:
- **加密底层模块 (`crypto`)**: 移植自 KeePassDX 的加密工具库，用于 KDBX 文件的加解密操作：
  - AES-256-CBC 加密/解密
  - Argon2id KDF（密钥派生函数）
  - HMacSHA256/512 签名验证
- **数据库模块 (`database`)**: 移植自 KeePassDX 的 KDBX 数据库读写库，用于密码本的创建、打开、保存：
  - `DatabaseKDBX` — KDBX 数据库核心类，支持 KDBX 3.x 和 4.0 格式
  - `DatabaseInputKDBX` / `DatabaseOutputKDBX` — 数据库读写入口
  - `EntryKDBX` / `GroupKDBX` — 密码条目和分组的数据模型
  - `MasterCredential` — 主密码凭证处理
  - `KdfFactory` — KDF 引擎工厂（支持 Argon2id、AES-KDF）
- **持久化格式**: 密码本采用标准 KDBX 4.0 格式，与 KeePassDX、KeePass (Windows)、KeePassXC 等应用完全兼容

## 致谢

感谢以下开源项目为本项目提供坚实的基础：

- **[LessPass](https://github.com/lesspass/lesspass)** — 优秀的无状态密码管理理念，让确定性密码生成变得简单可靠
- **[KeePassDX](https://github.com/keepassxreboot/keepassdx)** — 强大的 Android 端 KDBX 数据库实现，为密码本存储提供了工业级可靠性
- **[Bouncy Castle](https://www.bouncycastle.org/)** — 为加密操作提供了可靠的密码学原语

开源精神让每一位开发者都能站在巨人的肩膀上，构建出更好的工具。向所有开源贡献者致以诚挚的感谢！

## 许可证

本项目基于 MIT 许可证开源。

LessPass 算法及 KeePassDX 数据库库分别遵循其原始开源许可证。
