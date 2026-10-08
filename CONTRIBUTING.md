# Contributing to upload-file

Thanks for your interest in contributing! This project is licensed under MIT and follows the
[Contributor Covenant](https://www.contributor-covenant.org/) — please read
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before participating.

> 🇨🇳 [简体中文版](CONTRIBUTING.zh-CN.md)

## How to report

- **Bugs & feature requests**: open an issue. Provide the module and version, a minimal reproducer,
  and the expected vs. actual behaviour.
- **Security vulnerabilities**: do **not** open an issue. Report privately following
  [SECURITY.md](SECURITY.md).

## Development environment

- JDK 17+ — the reactor includes the `jakarta` modules (Spring Boot 4 / Servlet 6), which require
  JDK 17+.
- Maven 3.9+.

## Project conventions

- **Semantic Versioning** with a frozen API at `1.0.0` (GA). Public API signatures must never change
  in a non-additive way: CI runs a binary-compatibility gate (`compat-check` profile) against the
  previous release baseline and fails non-additive changes.
- **Keep a Changelog**: every user-visible change is recorded in `CHANGELOG.md` (newest first) and
  mirrored in `CHANGELOG.zh-CN.md`.
- **Bilingual docs**: English is the source of truth; user-facing docs in `docs/` carry a `.zh-CN.md`
  mirror where applicable.

## Building & testing

```bash
mvn verify   # full build, all tests, binary-compat gate, SBOM
mvn test     # tests only
```

- Every new behaviour needs a test. The JaCoCo report is generated at `*/target/site/jacoco`; keep
  core modules above 90% line coverage.
- Before pushing, make sure the full reactor passes on JDK 17 and 21 (CI enforces this anyway).

## Commit conventions

Use conventional-commit style:

- Types: `feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `build:`, `release:`
- One logical change per commit; explain the *why* in the body when it is not obvious.

## Submitting a pull request

1. Fork the repository and create a branch (`feat/...`, `fix/...`, `docs/...`).
2. Make your change, add or update tests, and run `mvn verify` locally.
3. Update `CHANGELOG.md` (and the `.zh-CN.md` mirror) under an `Unreleased` section, if one exists,
   or note the change for the maintainer to file.
4. Open the pull request against `main` and describe the motivation and the test plan. The CI gate
   (JDK 17/21 matrix, binary compatibility, SBOM) must pass before merge.
