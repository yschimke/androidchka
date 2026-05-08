#!/usr/bin/env bash
# Find the upstream `androidx-main` commit that corresponds to the pinned androidx.dev snapshot
# build, and (optionally) check it out so the in-tree source matches the snapshot artifacts.
#
# Usage:
#   scripts/sync-androidx-to-snapshot.sh           # report the matching SHA, do nothing else
#   scripts/sync-androidx-to-snapshot.sh --apply   # detach androidx HEAD onto that SHA
#
# Caveats: androidx.dev doesn't publish the source git revision, so we approximate from the
# artifact's `lastUpdated` timestamp by finding the latest `androidx-main` commit on or before
# that time. Snapshots built from un-merged CLs won't match a public commit — those will land
# on the nearest merge commit instead.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROIDX="$REPO_ROOT/androidx"
APPLY=false
[[ "${1:-}" == "--apply" ]] && APPLY=true

SNAPSHOT_ID=$(grep -E '^androidxSnapshotBuildId=' "$REPO_ROOT/snapshots.properties" | cut -d= -f2)
[[ -n "$SNAPSHOT_ID" ]] || { echo "androidxSnapshotBuildId missing from snapshots.properties" >&2; exit 1; }

# A representative published artifact — pick anything that's part of every snapshot.
META_URL="https://androidx.dev/snapshots/builds/$SNAPSHOT_ID/artifacts/repository/androidx/compose/remote/remote-core/1.0.0-SNAPSHOT/maven-metadata.xml"
LAST_UPDATED=$(curl -fsSL "$META_URL" | grep -oE '<lastUpdated>[0-9]+' | grep -oE '[0-9]+' | head -1)
[[ -n "$LAST_UPDATED" ]] || { echo "could not read lastUpdated from $META_URL" >&2; exit 1; }

# YYYYMMDDHHMMSS -> ISO 8601 UTC
ISO="${LAST_UPDATED:0:4}-${LAST_UPDATED:4:2}-${LAST_UPDATED:6:2}T${LAST_UPDATED:8:2}:${LAST_UPDATED:10:2}:${LAST_UPDATED:12:2}Z"

[[ -d "$ANDROIDX/.git" ]] || { echo "$ANDROIDX is not a git checkout" >&2; exit 1; }
git -C "$ANDROIDX" fetch --quiet origin androidx-main
SHA=$(git -C "$ANDROIDX" log --before="$ISO" -1 --format=%H origin/androidx-main)
[[ -n "$SHA" ]] || { echo "no commit found on origin/androidx-main before $ISO" >&2; exit 1; }

CURRENT=$(git -C "$ANDROIDX" rev-parse HEAD)
echo "Snapshot:          $SNAPSHOT_ID  (built $ISO)"
echo "Matching commit:   $SHA"
git -C "$ANDROIDX" log --oneline -1 "$SHA" | sed 's/^/                   /'
echo "Current HEAD:      $CURRENT"
git -C "$ANDROIDX" log --oneline -1 HEAD | sed 's/^/                   /'

if [[ "$SHA" == "$CURRENT" ]]; then
  echo
  echo "Already in sync."
  exit 0
fi

if ! $APPLY; then
  echo
  echo "To check out (will detach HEAD):"
  echo "    scripts/sync-androidx-to-snapshot.sh --apply"
  echo "  or:"
  echo "    git -C $ANDROIDX checkout $SHA"
  exit 0
fi

# Refuse to overwrite uncommitted work.
if [[ -n "$(git -C "$ANDROIDX" status --porcelain -uno)" ]]; then
  echo "Uncommitted changes in $ANDROIDX — commit/stash before --apply." >&2
  exit 1
fi

echo
echo "Detaching $ANDROIDX HEAD onto $SHA…"
git -C "$ANDROIDX" checkout --detach "$SHA"
