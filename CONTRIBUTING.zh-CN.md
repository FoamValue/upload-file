# 为 upload-file 做贡献

感谢你对本项目的关注与贡献！本项目采用 MIT 许可证，并遵循
[贡献者公约](https://www.contributor-covenant.org/)——参与前请先阅读
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

## 如何反馈

- **Bug 与功能需求**：请开 issue。注明模块与版本、最小复现步骤，以及预期行为与实际行为的差异。
- **安全漏洞**：**不要**开 issue 公开披露。请按 [SECURITY.md](SECURITY.md) 中的渠道私下报告。

## 开发环境

- JDK 17+：reactor 包含 `jakarta` 模块（Spring Boot 4 / Servlet 6），需要 JDK 17+。
- Maven 3.9+。

## 项目约定

- **语义化版本**，且 `1.0.0`（GA）起 API 已冻结。公共 API 签名不得以非增量方式变更：CI 会基于上一发布版本基线运行
  二进制兼容门禁（`compat-check` profile），非增量变更将导致构建失败。
- **Keep a Changelog**：所有用户可见变更都记录在 `CHANGELOG.md`（最新在前），并在 `CHANGELOG.zh-CN.md` 镜像。
- **双语文档**：英文为准；面向用户的 `docs/` 文档尽量提供 `.zh-CN.md` 镜像。

## 构建与测试

```bash
mvn verify   # 全量构建、全部测试、二进制兼容门禁、SBOM
mvn test     # 仅运行测试
```

- 每个新行为都要有对应测试。JaCoCo 覆盖率报告生成在 `*/target/site/jacoco`；核心模块行覆盖率保持在 90% 以上。
- 推送前请在 JDK 17 与 21 下确认全 reactor 通过（CI 也会强制检查）。

## 提交规范

使用 Conventional Commits 风格：

- 类型：`feat:`、`fix:`、`test:`、`docs:`、`refactor:`、`build:`、`release:`
- 一个 commit 只做一件逻辑变更；正文里说明"为什么"（当不显而易见时）。

## 提交流程

1. Fork 本仓库并新建分支（`feat/...`、`fix/...`、`docs/...`）。
2. 完成修改，补充或更新测试，本地运行 `mvn verify`。
3. 如存在 `Unreleased` 小节，在 `CHANGELOG.md`（及 `.zh-CN.md` 镜像）中登记变更；否则在 PR 描述中说明。
4. 向 `main` 分支提交 PR，说明动机与测试计划。CI 门禁（JDK 17/21 矩阵、二进制兼容、SBOM）须通过后方可合并。
