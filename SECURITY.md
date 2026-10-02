# Security Policy

> 🇨🇳 [中文](SECURITY.zh-CN.md)

## Reporting a Vulnerability

Please report security issues **privately** — do not open a public issue first.

**Preferred channel:** the repository's private vulnerability report at
<https://github.com/FoamValue/upload-file/security/advisories/new> (GitHub "Report a vulnerability",
visible only to the maintainer).

Please include:

- the affected version(s);
- a minimal reproducer or a step-by-step description;
- the observed impact (data loss, DoS, quota bypass, …);
- any fix suggestion you may have.

## Response commitment

1. Acknowledgment within **3 business days**.
2. Triage and confirmation; for a confirmed issue on the current GA line (`1.0.0.x`) a patch
   release is targeted within **14 days**.
3. Credit in the changelog unless you prefer to stay anonymous.

## Supported versions

| Version | Supported |
| --- | --- |
| `1.0.0.x` (current GA) | ✅ security fixes |
| `1.0.0-rc.*` | ❌ pre-release — migrate to `1.0.0` |

## Disclosure

We ask that details stay private until a fixed release is published, so users have a chance to
upgrade before the issue is publicly known.

## Security hardening in 1.0.0

The GA release includes a security closure that fixed 7 issues: quota and size limits now count
actual bytes instead of trusting declared values; `max-chunk-size` defaults to 10 MB and startup
fails when all limits are unbounded; checksum verification is non-skippable
(`require-checksum`); download paths are canonical-prefix validated; access logs filter injection
characters; orphan cleanup isolates single-entry failures; chunk writes are byte-bounded and abort
mid-stream. See the [security fix report](security-fix-report/security-fix-report.html) and the
[changelog](CHANGELOG.md).
