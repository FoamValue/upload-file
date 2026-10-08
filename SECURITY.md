# Security Policy

> 🇨🇳 [中文](SECURITY.zh-CN.md)

## Supported Versions

| Version | Supported |
| --- | --- |
| `1.0.x` | ✅ Supported (GA — security fixes on the frozen API) |
| `< 1.0.0` (rc.*) | ❌ Unsupported (release candidates, superseded) |

## Reporting a Vulnerability

**Preferred channel: a public GitHub issue.** Open an issue at
<https://github.com/FoamValue/upload-file/issues/new> with a `[SECURITY]` title prefix, so the
report is transparent, filterable and trackable for the whole community.

If you need full privacy until a fix is released (e.g. a sensitive exploit), you can alternatively
use the repository's private vulnerability report at
<https://github.com/FoamValue/upload-file/security/advisories/new> (GitHub "Report a vulnerability",
visible only to the maintainer).

Please include:

- the affected version(s);
- a minimal reproducer or a step-by-step description;
- the observed impact (data loss, DoS, quota bypass, …);
- any fix suggestion you may have.

## Scope

A security issue is anything that affects the confidentiality, integrity or availability of the
component: data loss, unauthorized access (access-control or quota bypass), denial of service,
path traversal, checksum/verification bypass, log injection, and similar OWASP Top-10 categories.
Bugs, feature requests and usage questions are not security reports — please open a regular issue.

## How We Handle Reports

As an open-source project we work on a best-effort basis and do not commit to a fixed response or
patch timeline. The usual flow is:

1. **Acknowledge** — the report is confirmed on the issue (label and/or reply).
2. **Triage & reproduce** — severity is assessed and the impact confirmed.
3. **Fix & test** — the fix is implemented with a regression test.
4. **Release** — the fix ships with the next applicable version of the current GA line (`1.0.x`).
5. **Credit** — the fix is credited in the changelog unless you prefer to stay anonymous.

## Disclosure

For public-issue reports the thread itself is visible to everyone. To give users a chance to upgrade
before an exploit becomes public, please keep full attack details (e.g. a ready-to-run PoC) out of
the issue until a fixed release is published — a summary and the impact in the issue are enough, and
the private advisory channel is there if you need complete secrecy in the meantime.

## Bug Bounty

This project does not offer a bug bounty program.

## Security hardening in 1.0.0

The GA release includes a security closure that fixed 7 issues: quota and size limits now count
actual bytes instead of trusting declared values; `max-chunk-size` defaults to 10 MB and startup
fails when all limits are unbounded; checksum verification is non-skippable
(`require-checksum`); download paths are canonical-prefix validated; access logs filter injection
characters; orphan cleanup isolates single-entry failures; chunk writes are byte-bounded and abort
mid-stream. See the [security fix report](security-fix-report/security-fix-report.html) and the
[changelog](CHANGELOG.md).
