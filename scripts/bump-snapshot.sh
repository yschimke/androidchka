#!/usr/bin/env bash
# Update the androidx.dev snapshot build id used by the overlay.
#
# Usage: scripts/bump-snapshot.sh [build-id]
#   - With no argument, fetches the latest published build id from androidx.dev.
#   - With an argument, pins to that exact build id (after sanity-checking it exists).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$REPO_ROOT/snapshots.properties"

latest_build_id() {
  # The index page lists builds as `/snapshots/builds/<id>/artifacts` links — newest first.
  curl -fsSL https://androidx.dev/snapshots/builds \
    | grep -oE '/snapshots/builds/[0-9]+/artifacts' \
    | grep -oE '[0-9]+' \
    | head -1
}

verify_exists() {
  local id="$1"
  # The artifact index page exists as HTML; the Maven repo root itself 404s on HEAD.
  curl -fsSI "https://androidx.dev/snapshots/builds/$id/artifacts" >/dev/null \
    || { echo "build id $id not reachable on androidx.dev" >&2; exit 1; }
}

if [[ $# -ge 1 ]]; then
  NEW_ID="$1"
else
  NEW_ID="$(latest_build_id)"
  [[ -n "$NEW_ID" ]] || { echo "could not determine latest build id" >&2; exit 1; }
fi

verify_exists "$NEW_ID"

OLD_ID=$(grep -E '^androidxSnapshotBuildId=' "$PROPS" | cut -d= -f2)
if [[ "$OLD_ID" == "$NEW_ID" ]]; then
  echo "Already on snapshot $NEW_ID."
  exit 0
fi

# Use a portable in-place sed.
tmp="$(mktemp)"
sed "s/^androidxSnapshotBuildId=.*/androidxSnapshotBuildId=$NEW_ID/" "$PROPS" >"$tmp"
mv "$tmp" "$PROPS"

echo "Bumped androidxSnapshotBuildId: $OLD_ID -> $NEW_ID"
echo "Run a clean build to refresh resolution: ./gradlew --refresh-dependencies build"
