# 测试覆盖报告

> 🇬🇧 [English](TEST-COVERAGE.md)

本报告记录 **`upload-file` `1.0.0` (GA)** 测试套件的覆盖率状况、针对性的覆盖薄弱点分析、为补齐薄弱点新增的测试，以及剩余未覆盖的防御性代码。覆盖率通过 **JaCoCo**（行 / 分支计数器）在一次完整的 `mvn verify` 中测量；测试执行使用 **JUnit 4/5 + Surefire**。

- [1. 测试规模](#1-测试规模)
- [2. 模块覆盖率](#2-模块覆盖率)
- [3. 薄弱点分析与修复](#3-薄弱点分析与修复)
- [4. 新增测试明细](#4-新增测试明细)
- [5. 剩余未覆盖代码](#5-剩余未覆盖代码)
- [6. 结论](#6-结论)

## 1. 测试规模

| 模块 | 测试数 | 失败 | 错误 |
| --- | ---: | ---: | ---: |
| upload-file-core | 256 | 0 | 0 |
| upload-file-servlet | 92 | 0 | 0 |
| upload-file-servlet-jakarta | 87 | 0 | 0 |
| upload-file-spring-boot-starter | 56 | 0 | 0 |
| upload-file-spring-boot-starter-jakarta | 58 | 0 | 0 |
| upload-file-store-jdbc | 20 | 0 | 0 |
| upload-file-store-redis | 38 | 0 | 0 |
| example/upload-file-demo | 2 | 0 | 0 |
| example/upload-file-boot4-demo | 4 | 0 | 0 |
| **合计** | **613** | **0** | **0** |

## 2. 模块覆盖率

| 模块 | 行 | 行覆盖率 | 分支 | 分支覆盖率 |
| --- | ---: | ---: | ---: | ---: |
| upload-file-core | 1258 / 1301 | 96.7% | 451 / 514 | 87.7% |
| upload-file-servlet | 447 / 460 | 97.2% | 180 / 202 | 89.1% |
| upload-file-servlet-jakarta | 436 / 460 | 94.8% | 165 / 202 | 81.7% |
| upload-file-spring-boot-starter | 431 / 458 | 94.1% | 100 / 127 | 78.7% |
| upload-file-spring-boot-starter-jakarta | 416 / 458 | 90.8% | 100 / 127 | 78.7% |
| upload-file-store-jdbc | 93 / 94 | 98.9% | 26 / 36 | 72.2% |
| upload-file-store-redis | 213 / 223 | 95.5% | 69 / 98 | 70.4% |

所有模块行覆盖率均在 **90% 以上**；风险最高的两个模块——核心服务层与 Servlet / Spring Boot 集成层——分别达到 **96.7%** 与 **94.1–97.2%**。

## 3. 薄弱点分析与修复

解析 JaCoCo 报告定位覆盖率最低的类，共有 5 个类行覆盖率介于 66% 与 90.3% 之间：

| 类 | 模块 | 补充前（行） | 补充后（行） | 补充后（分支） |
| --- | --- | ---: | ---: | ---: |
| TrustedUploadService | core | 约 80% | **100%**（8/8） | 100% |
| TaskStoreQuotaStore | core | 约 66% | **100%**（19/19） | 92.9% |
| ResumableUploadService | core | 约 90.3% | **98.4%**（311/316） | 85.2% |
| UploadFileContext | servlet | 约 90% | **99.2%**（123/124） | 94.6% |
| UploadFileProperties（含 `Lock`） | starter | 约 80% | **100%**（66/66） | 100% |

由报告精确定位并由新增测试覆盖的未覆盖行：

- **TrustedUploadService** — `getMergeStatus(identifier)`（任务缺失与任务存在两条路径）以及 `getTaskStore()` 读操作，行为级测试套件从未触达。
- **TaskStoreQuotaStore** — 非空存储下的 `usedBytes()`：进行中任务的声明大小、已合并任务的最终大小，以及负数声明大小钳制为 0。
- **ResumableUploadService** — SPI setter 的空值拒绝（`setIdentifierLockProvider`、`setQuotaStore`）、负数声明 `fileSize`、不含限额消息的存储 IO 失败、保存后才超限的分块（删除并拒绝路径）、`require-checksum` 拒绝错误 MD5、`cancelUpload` 的两条 IO 失败路径（删除失败与目录遍历失败）。
- **UploadFileContext** — `sanitizeLog` 控制字符替换、两参 `build` 在无界配置下的 fail-fast、异步合并线程池 fail-fast、访问决策监听器（旧版 4 参桥接、净化后的审计日志行，以及默认 `task` 作用域内重复 `UPLOAD` 允许仅记录首个分块）。
- **UploadFileProperties.Lock** — `identifierLock` / `acquireTimeout` 的 setter-getter 往返，用于配置属性绑定。

## 4. 新增测试明细

| 测试类 | 模块 | 测试数 | 覆盖目标 |
| --- | ---: | --- | --- |
| `CoreCoverageGapTest` | core | 11 | TrustedUploadService 读操作、TaskStoreQuotaStore 用量统计、ResumableUploadService 的 SPI / 校验 / IO 失败 / 校验和 / 取消路径 |
| `UploadFileContextCoverageGapTest` | servlet | 5 | sanitizeLog、两参 build fail-fast、异步线程池 fail-fast、访问日志监听器 |
| `UploadFilePropertiesTest`（扩展） | starter | +2 | `Lock` 内部类 setter/getter 往返 |

## 5. 剩余未覆盖代码

共 6 行防御性代码仍未覆盖，均为环境依赖或逻辑上不可达：

| 类 | 行号 | 原因 |
| --- | --- | --- |
| ResumableUploadService | 727–728 | `deleteDirectoryQuietly` 对目录流*打开*失败的 catch —— 需要文件系统在打开遍历时失败，无法可移植地构造 |
| ResumableUploadService | 791、793 | 文件系统不支持 `ATOMIC_MOVE` 时的 `atomicMove` 回退（如部分网络挂载）—— macOS APFS 始终支持 |
| ResumableUploadService | 805 | `checkQuota` 中 `maxTotalBytes <= 0` 的纵深防御提前返回 —— 调用方（第 530 行）已以 `maxTotalBytes > 0` 门控，分支逻辑上不可达 |
| UploadFileContext | 258 | 两参 `build` 的转发行 —— 测试确实调用它（经 fail-fast 路径），但 JaCoCo 字节码探针未记录这一单行调用 |

## 6. 结论

测试套件全部通过：**613 个测试，0 失败，0 错误**。所有模块行覆盖率均在 90% 以上，此前 5 个薄弱类现已达到 **97.2–100%**。剩余 6 行未覆盖代码属于防御性或环境依赖分支，其覆盖需要合成文件系统行为或触发不可达的冗余检查——维持不覆盖是可接受的，具体原因已在上文记录。
