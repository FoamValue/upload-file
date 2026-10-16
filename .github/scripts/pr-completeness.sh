#!/usr/bin/env bash
# PR completeness check.
#
# Layer 1 — bilingual doc sync (WARNING, non-blocking):
#   Reminds when a doc is updated without its .zh-CN counterpart
#   (or vice versa). Kept non-blocking so occasional contributors
#   are not scared off; the maintainer resolves it at merge time.
#
# Layer 2 — release PR hygiene (BLOCKING):
#   For "chore(main): release x.y.z" PRs, verifies the manual steps
#   from the release-please PR footer checklist were actually done:
#   README version references, CHANGELOG.zh-CN entry, revapi baseline.
#
# Required env: GITHUB_REPOSITORY, PR_NUMBER, GH_TOKEN.
# Results are written to $GITHUB_STEP_SUMMARY; exit 1 only on blocking issues.

set -uo pipefail

REPO="${GITHUB_REPOSITORY}"
PR_NUM="${PR_NUMBER}"
API="repos/$REPO"

PR_JSON=$(gh api "$API/pulls/$PR_NUM")
TITLE=$(echo "$PR_JSON" | jq -r '.title')
BASE_SHA=$(echo "$PR_JSON" | jq -r '.base.sha')
CHANGED=$(gh api "$API/pulls/$PR_NUM/files?per_page=100" --paginate --jq '.[].filename')

echo "PR #$PR_NUM: $TITLE"

WARNINGS=()
ERRORS=()

# ---------- Layer 1: bilingual sync (warning only) ----------
pair_missing() { # $1=changed file, $2=expected counterpart
  echo "$CHANGED" | grep -qx "$1" && ! echo "$CHANGED" | grep -qx "$2" && [ -f "$2" ]
}

for pair in "README.md README.zh-CN.md" "README.zh-CN.md README.md" \
            "CHANGELOG.md CHANGELOG.zh-CN.md" "CHANGELOG.zh-CN.md CHANGELOG.md" \
            "SECURITY.md SECURITY.zh-CN.md" "SECURITY.zh-CN.md SECURITY.md"; do
  set -- $pair
  if pair_missing "$1" "$2"; then
    WARNINGS+=("Updated \`$1\` but not \`$2\` — consider syncing the bilingual counterpart.")
  fi
done

for f in $(echo "$CHANGED" | grep '^docs/.*\.md$' || true); do
  case "$f" in
    *.zh-CN.md)
      base="${f%.zh-CN.md}.md"
      if pair_missing "$f" "$base"; then
        WARNINGS+=("Updated \`$f\` but not \`$base\`.")
      fi
      ;;
    *)
      zh="${f%.md}.zh-CN.md"
      if pair_missing "$f" "$zh"; then
        WARNINGS+=("Updated \`$f\` but not \`$zh\`.")
      fi
      ;;
  esac
done

# ---------- Layer 2: release PR checks (blocking) ----------
if [[ "$TITLE" =~ chore\(main\):\ release\ ([0-9]+\.[0-9]+\.[0-9]+) ]]; then
  VERSION="${BASH_REMATCH[1]}"
  echo "Release PR detected — target version: $VERSION"

  # Previous published version = manifest value on the base branch.
  PREV=$(gh api "$API/contents/.release-please-manifest.json?ref=$BASE_SHA" \
        --jq '.content' 2>/dev/null | base64 -d 2>/dev/null | jq -r '."."' 2>/dev/null || true)

  for f in README.md README.zh-CN.md; do
    grep -q "upload-file:$VERSION" "$f" || \
      ERRORS+=("\`$f\` coordinates table does not reference \`$VERSION\`.")
    grep -q "upload-file-bom:$VERSION" "$f" || \
      ERRORS+=("\`$f\` BOM reference does not reference \`upload-file-bom:$VERSION\`.")
  done

  grep -q "^## \[$VERSION\]" CHANGELOG.zh-CN.md || \
    ERRORS+=("\`CHANGELOG.zh-CN.md\` is missing the \`## [$VERSION]\` entry (sync the English changelog).")

  if [ -n "$PREV" ]; then
    # Version-bump guard: the release version must be exactly one patch
    # increment above the previous published version (e.g. 1.0.2 -> 1.0.3).
    EXPECTED=$(awk -F. '{printf "%d.%d.%d", $1, $2, $3+1}' <<< "$PREV")
    [ "$VERSION" = "$EXPECTED" ] || \
      ERRORS+=("Release version \`$VERSION\` is not one patch increment above the previously published version \`$PREV\` (expected \`$EXPECTED\`).")

    BASELINE=$(sed -n 's:.*<compat.baseline.version>\(.*\)</compat.baseline.version>.*:\1:p' pom.xml | head -1)
    [ "$BASELINE" = "$PREV" ] || \
      ERRORS+=("pom.xml \`compat.baseline.version\` is \`${BASELINE:-<missing>}\`, expected \`$PREV\` (previous published version).")
  else
    echo "No manifest on base branch — skipping baseline check (pre-automation release PR)."
  fi
fi

# ---------- report ----------
{
  echo "## PR Completeness Check"
  echo ""
  if [ ${#ERRORS[@]} -gt 0 ]; then
    echo "### ❌ Blocking issues"
    echo ""
    for e in "${ERRORS[@]}"; do echo "- $e"; done
    echo ""
  fi
  if [ ${#WARNINGS[@]} -gt 0 ]; then
    echo "### ⚠️ Sync suggestions (non-blocking)"
    echo ""
    for w in "${WARNINGS[@]}"; do echo "- $w"; done
    echo ""
  fi
  if [ ${#ERRORS[@]} -eq 0 ] && [ ${#WARNINGS[@]} -eq 0 ]; then
    echo "✅ All completeness checks passed."
  fi
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"

if [ ${#ERRORS[@]} -gt 0 ]; then
  echo "BLOCKING: ${#ERRORS[@]} issue(s). See step summary."
  exit 1
fi

echo "OK: ${#WARNINGS[@]} suggestion(s)."
