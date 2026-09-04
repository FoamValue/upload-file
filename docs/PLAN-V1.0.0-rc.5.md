# V1.0.0-rc.5 Task Plan (Spring Boot 4 / jakarta Starter)

> 🇨🇳 [简体中文](PLAN-V1.0.0-rc.5.zh-CN.md)
>
> Closes the last top integration blocker from the consumer feedback
> (`doc/user-feedback/upload-file-usage-feedback.md`, item **P0-1**) that rc.4 deferred: an official
> **jakarta** servlet module and a Spring Boot **4.0.0+** auto-configuration starter. Today the official
> `upload-file-servlet` / `upload-file-spring-boot-starter` artifacts depend on `javax.servlet`, so a
> Spring Boot 3/4 consumer (e.g. path-finder on JDK 26 / Spring Boot 4.1.1) cannot use them and is forced
> into manual core wiring. rc.5 ships `-jakarta` twins that are drop-in source-compatible.
>
> ⏳ **Status: planned, not yet implemented.** Targeted at `1.0.0-rc.5`.

## 1. Goals & Scope

rc.4 documented the cleanup / manual-wiring semantics but the underlying blocker stays open: the servlet and
starter artifacts are `javax.servlet`-based. rc.5 removes that blocker.

| # | Gap | Description | rc.5 task |
| --- | --- | --- | --- |
| G1 | `javax.servlet` on the official servlet artifact | `UploadFileContext` / `UploadServlet` / `DownloadServlet` only compile against Servlet 3/4 (`javax`); Servlet 5/6 containers (Tomcat 10/11, Boot 3/4) reject them | T20 |
| G2 | `javax.servlet` on the Boot starter | `UploadFileAutoConfiguration` imports `javax.servlet` + `MultipartConfigElement` and `@ConditionalOnClass(name={"javax.servlet.*"})`; registered via Boot-2 style `META-INF/spring.factories`, which Boot 3/4 ignore | T21, T22 |
| G3 | No Spring Boot 4 artifact / verified baseline | no published coordinate for Spring Boot 4.0.0+; no example or CI proof on Boot 4 | T23, T24 |
| G4 | Build does not support two servlet generations | one BOM (Boot 2.7) + one JDK baseline (`[8,)`) in the parent; jakarta artifacts need Boot 4 BOM and a JDK ≥ 17 build | T19 |
| G5 | Docs only describe javax coordinates | README / API / DESIGN / demo POMs all reference the javax artifacts; no javax↔jakarta mapping or Boot-4 quickstart | T24 |

## 2. Architecture & Constraints

New artifacts are **parallel twins**, not a replacement of the existing coordinates (existing Boot 2 / Servlet 3.1
consumers keep their dependencies and behavior):

```
upload-file (parent POM / aggregator, version 1.0.0-rc.5)
├── upload-file-core                        # unchanged, pure Java (JDK 8) — shared by both generations
├── upload-file-store-jdbc / -redis         # unchanged — shared by both generations (no servlet deps)
├── upload-file-servlet          [javax]    # unchanged (Boot 2 / Servlet 3.1, maintenance)
├── upload-file-servlet-jakarta [NEW]       # jakarta port (T20)
├── upload-file-spring-boot-starter         [javax]   # unchanged (Boot 2, maintenance)
├── upload-file-spring-boot-starter-jakarta [NEW]     # Boot 3/4 jakarta starter (T21/T22)
├── example/upload-file-demo                # unchanged (Boot 2 javax demo)
├── example/upload-file-boot4-demo [NEW]    # Boot 4.0.0+ jakarta demo (T23)
└── example/upload-file-servlet-demo        # javax demo (unchanged)
```

Constraints & decisions:
- **Same FQCNs in the jakarta twins** (`cn.chenxinjie.uploadfile.servlet.*`,
  `cn.chenxinjie.uploadfile.springboot.*`): a consumer only swaps the Maven coordinate — no code/import changes.
  Consequence: the javax and jakarta artifacts must never be on one classpath (mutually exclusive, enforced by docs).
- **Source duplication is minimal and deliberate**: only the servlet layer (3 classes) and the starter
  (auto-config + properties) differ in servlet imports. All logic stays in `upload-file-core`, reused as-is.
- **Boot version isolation**: the jakarta starter/demo import the Spring Boot 4 BOM **module-locally**
  (`spring-boot-dependencies`, property e.g. `spring.boot4.version`) instead of the parent's Boot 2.7 import, so the
  javax and jakarta modules compile against different Spring/Servlet generations in one reactor.
- **JDK baseline**: reading Servlet 6 / Boot 4 class files requires a JDK ≥ 17 toolchain, so the root `mvn verify`
  now effectively requires JDK 17+. All artifacts keep bytecode target `--release 8` (JDK 8 consumers of the javax
  line still build the subset they need via `-pl upload-file-core,upload-file-servlet,...`). The Maven enforcer stays
  `[8,)` but the README documents the JDK-17 build requirement for the full reactor.
- **New artifacts are published** to Maven Central alongside the existing ones under the same `1.0.0-rc.5` version
  (parent `dependencyManagement` + `central-publishing-maven-plugin` on each new module).
- **Boot 4.0.0+ is the verified/supported target**; the jakarta artifacts are also expected to run on Boot 3.x
  (same `jakarta.servlet` stack) but only Boot 4.x is CI-verified in rc.5.

## 3. Task Breakdown

### T19 Build topology: jakarta modules, BOM isolation, publication (G4)

- **Files**: `pom.xml` (parent), new module POMs, `.flattened-pom.xml` regeneration.
- **Approach**:
  - register `upload-file-servlet-jakarta`, `upload-file-spring-boot-starter-jakarta`,
    `example/upload-file-boot4-demo` in the parent `<modules>` and `<dependencyManagement>` (same `${project.version}`);
  - jakarta modules and the Boot-4 demo import `spring-boot-dependencies` locally (own property
    `spring.boot4.version`); the servlet-jakarta module overrides its `javax.servlet-api` dep with
    `jakarta.servlet:jakarta.servlet-api` (provided);
  - keep bytecode `--release 8`; document the JDK-17 build baseline and the `-pl` recipe for JDK-8-only builds;
  - add `central-publishing-maven-plugin` to the two published jakarta modules.
- **Acceptance**: `mvn -q clean install` on JDK 17+ builds javax + jakarta lines green; the new artifacts resolve as
  `1.0.0-rc.5`; javax modules still compile on a JDK 8 subset build (`-pl`); `mvn install -DskipTests` on JDK 8 root
  fails with a clear javac "bad class file version" message that README points to.
- **Estimate**: 1.5 person-days.

### T20 `upload-file-servlet-jakarta`: jakarta port of the servlet layer (G1)

- **Files**: new module mirroring `upload-file-servlet`; ported sources `UploadFileContext`, `UploadServlet`,
  `DownloadServlet`; ported tests `UploadServletTest`, `DownloadServletTest`, `UploadFileContextTest`.
- **Approach**: copy the three classes and the three tests, changing the servlet imports to `jakarta.servlet.*`
  (`HttpServlet`, `Part`, `@WebServlet`, `@MultipartConfig`, `ServletContext`/`ServletConfig`, ...). No logic change;
  keep FQCNs identical for drop-in. Test dependency `spring-test` moves to the Boot 4 / Spring 7 line so the
  `org.springframework.mock.web.*` mocks are jakarta-based (test imports unchanged — the mock classes keep their
  package). `javax.servlet-api` → `jakarta.servlet-api` (provided).
- **Acceptance**: all three ported test classes pass against `jakarta.servlet` mocks with behavior identical to the
  javax twins; `action=cancel`, `UploadErrorCode` mapping (400/401/404/409/507), Range download and init-param
  parsing behave the same.
- **Estimate**: 1 person-day.

### T21 `upload-file-spring-boot-starter-jakarta`: Boot 4 auto-configuration (G2)

- **Files**: new module mirroring `upload-file-spring-boot-starter`; ported `UploadFileAutoConfiguration`,
  `UploadFileProperties`; new `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- **Approach**:
  - servlet imports `javax.*` → `jakarta.*`; `@ConditionalOnClass(name = {"jakarta.servlet.Servlet",
    "jakarta.servlet.MultipartConfigElement"})`; `MultipartConfigElement` now from `jakarta.servlet`;
  - mark the config with `@AutoConfiguration` (Boot 3+) and register it through the `AutoConfiguration.imports`
    file instead of `spring.factories` (Boot 2-style discovery is ignored by Boot 3/4); remove `spring.factories`;
  - `UploadFileProperties` unchanged (same `upload-file.*` prefix and defaults, same nested groups, same
    configuration-processor metadata) — zero configuration migration for consumers;
  - compile against Boot 4 `spring-boot-autoconfigure` / `spring-boot` / `spring-boot-configuration-processor`
    (provided/optional); `spring-boot-starter-test` + `spring-boot-starter-web` (test) on the Boot 4 line.
- **Acceptance**: the class only references the jakarta + Boot-4 stable API; `spring-configuration-metadata.json`
  lists the same property names as the javax starter; on a Boot 4 classpath the auto-config is discovered through the
  imports file.
- **Estimate**: 1 person-day.

### T22 Behavior parity of the jakarta starter (G2)

- **Files**: ported starter test suite + fixes in the ported auto-config.
- **Approach**: mirror the whole javax starter test suite (defaults/off-by-default, store selection `auto|memory|file|
  jdbc|redis` incl. missing-DataSource/classpath fallback, cleanup scheduling + startup pass, async-merge executor +
  state machine, access-control fail-fast without a token, migration bean gating, servlet registrations at
  `/upload` `/download` incl. `action=cancel` parity, `getTask`/`cancelUpload` through the wired
  `ResumableUploadService`). Assert drop-in equality: every bean, property and default that the javax starter wires is
  wired identically by the jakarta starter.
- **Acceptance**: the jakarta module's tests are green on Boot 4; property set and bean graph are equivalent to the
  javax starter (verified by the ported tests, not by code comparison alone); custom beans are still overridable via
  `@ConditionalOnMissingBean`.
- **Estimate**: 1.5 person-days.

### T23 Spring Boot 4.0.0+ example / POC (G3)

- **Files**: new `example/upload-file-boot4-demo` (Boot 4 BOM, JDK ≥ 17, embedded Tomcat) with a frontend page reused
  from the Boot 2 demo; README walkthrough.
- **Approach**: build the demo on the jakarta starter to prove the whole flow on a real Boot 4.0.0+ runtime:
  chunked upload → progress/resume → `mergeAsync`/`mergeStatus` → confirm via `getTask(...).getFinalPath()` →
  Range download; exercise `action=cancel` + `UploadErrorCode` statuses; one run with `metadata-store=file` and one
  optional Docker run with `metadata-store=redis` (mirrors the path-finder scenario; demo is not published).
- **Acceptance**: `mvn -pl example/upload-file-boot4-demo spring-boot:run` on JDK 17+ serves `/upload`, `/download`
  and the frontend; the manual walkthrough completes on Boot 4.0.0+; the jakarta starter is the only upload-file
  dependency in the demo.
- **Estimate**: 1.5 person-days.

### T24 Documentation & javax↔jakarta compatibility matrix (G5)

- **Files**: `README(.zh-CN).md`, `docs/API(.zh-CN).md`, `docs/DESIGN(.zh-CN).md`, `docs/ROADMAP(.zh-CN).md`,
  `CHANGELOG(.zh-CN).md`.
- **Approach**:
  - artifact matrix: `javax` line (Boot 2 / Servlet 3.1) vs `jakarta` line (Boot 3/4) with exact coordinates and the
    "pick one, never both" rule;
  - Boot 4.0.0+ quickstart: Maven dependency, `application.yml` sample, JDK 17+ requirement, property table reference;
  - note that core manual wiring (the rc.4-documented path-finder recipe) remains valid on Boot 4 and that the new
    starter makes it optional;
  - upgrade guide: Boot 2 → Boot 4 (javax → jakarta) coordinate swap; Boot-2 consumers who stay keep the javax line;
  - update DESIGN module diagram and the README constraint that root `mvn verify` needs JDK 17+ while the javax line
    stays JDK 8-buildable via `-pl`.
- **Acceptance**: reading the README tells a Boot 4 consumer exactly which coordinate to use; the matrix and upgrade
  steps are complete; CHANGELOG marks the jakarta deferral as resolved in rc.5.
- **Estimate**: 1 person-day.

## 4. New Maven Artifacts & Build Baseline

| Artifact | Servlet namespace | Compile-time deps | Supported runtimes | Drop-in for |
| --- | --- | --- | --- | --- |
| `upload-file-servlet-jakarta` | `jakarta.servlet` | `jakarta.servlet-api` (provided), core | Servlet 5/6 containers, Boot 3/4 embedded Tomcat | `upload-file-servlet` |
| `upload-file-spring-boot-starter-jakarta` | `jakarta.servlet` | Boot 4 `spring-boot(-autoconfigure)` (provided), servlet-jakarta, core, stores (optional) | Spring Boot 4.0.0+ (3.x expected) | `upload-file-spring-boot-starter` |

- No new `upload-file.*` configuration properties in rc.5 — the jakarta starter keeps the exact property set and
  defaults of the javax starter.
- Build baseline: root `mvn verify` / release requires JDK 17+; bytecode target stays `--release 8` for all
  artifacts; JDK-8 consumers build the javax subset with `-pl`.

## 5. Compatibility

- Existing `upload-file-servlet` and `upload-file-spring-boot-starter` (javax) are **unchanged** — Boot 2 /
  Servlet 3.1 consumers keep their coordinates and behavior;
- jakarta twins are **source drop-ins** (same FQCN, same properties): migrating = swapping the coordinate; javax and
  jakarta artifacts are mutually exclusive on a classpath;
- core and both store modules are shared, servlet-free and unchanged — manual core wiring on Boot 4 keeps working;
- no SPI / core API changes; rc.5 is additive at the packaging level only.

## 6. Test Plan

- Ported servlet tests (3) and the full starter test suite to the jakarta modules; all green against Boot 4 /
  jakarta mocks on JDK 17+;
- `example/upload-file-boot4-demo` manual E2E (chunk → resume → async merge → confirm via `finalPath` → download;
  plus `cancel` and `UploadErrorCode` 400/404/409/401/507 paths);
- javax modules re-run as regression; full reactor `mvn verify` on JDK 17+ green (JaCoCo reports kept per module);
- release command runs on JDK 17+ with the existing `release` profile (gpg) and publishes the two new artifacts to
  Maven Central.

## 7. Docs & Example Updates

- New `example/upload-file-boot4-demo`; Boot 2 / javax demo untouched;
- `README(.zh-CN).md`: artifact matrix, Boot 4.0.0+ quickstart, JDK-17 build note, coordinate-swap upgrade guide;
- `docs/API(.zh-CN).md` / `docs/DESIGN(.zh-CN).md`: jakarta module roles and dependency edges;
- `docs/ROADMAP(.zh-CN).md`: mark the jakarta adapter (feedback P0-1) as the rc.5 plan;
- `CHANGELOG(.zh-CN).md`: rc.5 entry resolving the jakarta deferral.

## 8. Milestones & Release

1. **M1**: T19 — build topology, BOM isolation, modules registered and publishing-ready;
2. **M2**: T20 + T21 — servlet-jakarta and starter-jakarta compile with tests green;
3. **M3**: T22 — behavior parity suite on Boot 4;
4. **M4**: T23 + T24 — Boot 4 demo/E2E and documentation matrix;
5. **M5**: version `1.0.0-rc.4 → 1.0.0-rc.5`, full `mvn verify` on JDK 17+, publish to Maven Central with the
   existing release profile; announcement notes Boot 4.0.0+ support as the headline.
