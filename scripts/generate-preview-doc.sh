#!/usr/bin/env bash
# Generate a browsable Markdown gallery of the rendered Compose previews.
#
# Walks the Gradle build output for compose-preview render PNGs and writes a
# single index (README.md) plus copied images under <out-dir>. Deliberately
# schema-agnostic: it keys off the on-disk PNG layout
#   build/androidx-builds/<dashed-module>/compose-previews/renders/**/*.png
# (the build dir is redirected to build/androidx-builds/<dashed-module> for
# sourced AndroidX projects — see settings.gradle.kts `gradle.beforeProject`),
# so it survives changes to previews.json's internal shape. The `Preview Doc`
# workflow force-pushes <out-dir> to the docs branch.
#
# Usage: scripts/generate-preview-doc.sh [out-dir]   (default: _site)
set -euo pipefail

OUT_DIR="${1:-_site}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_ROOT="$ROOT/build/androidx-builds"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/images"

INDEX="$OUT_DIR/README.md"
{
  echo "# RemoteCompose previews"
  echo
  echo "Rendered by the \`Preview Doc\` GitHub Actions workflow via \`compose-preview\`."
  echo "Source modules: \`:compose:remote\`, \`:wear:compose:remote\` (see \`local.properties.example\`)."
  echo
  echo "_Generated $(date -u +%Y-%m-%dT%H:%M:%SZ) from commit ${GITHUB_SHA:-local}._"
  echo
} > "$INDEX"

shopt -s nullglob globstar
total=0
modules=0
for renders_dir in "$BUILD_ROOT"/*/compose-previews/renders; do
  [ -d "$renders_dir" ] || continue
  # build/androidx-builds/<dashed-module>/compose-previews/renders -> <dashed-module>
  dashed="$(basename "$(dirname "$(dirname "$renders_dir")")")"
  pngs=("$renders_dir"/**/*.png)
  [ "${#pngs[@]}" -gt 0 ] || continue
  modules=$((modules + 1))
  {
    echo "## \`$dashed\` (${#pngs[@]} preview(s))"
    echo
  } >> "$INDEX"
  for png in "${pngs[@]}"; do
    name="$(basename "$png" .png)"
    dest="images/${dashed}__${name}.png"
    cp "$png" "$OUT_DIR/$dest"
    printf '### %s\n\n![%s](%s)\n\n' "$name" "$name" "$dest" >> "$INDEX"
    total=$((total + 1))
  done
done

echo "[generate-preview-doc] wrote $total preview(s) across $modules module(s) to $OUT_DIR" >&2
if [ "$total" -eq 0 ]; then
  echo "[generate-preview-doc] WARNING: no preview PNGs found under $BUILD_ROOT — did the render step run and discover modules?" >&2
fi
