# androidx-mini

A slim Gradle overlay that builds a hand-picked subset of the AOSP `androidx`
checkout against an `androidx.dev` snapshot for everything else. The upstream
checkout is referenced via the `androidx` symlink and **never modified** — all
overlay-specific files live here.

## Layout

- `androidx` &mdash; symlink to `/home/yuri/workspace/androidx` (the upstream
  checkout). Build files are read from there directly.
- `settings.gradle.kts` &mdash; declares the **source** projects (built from the
  upstream tree) and the **stub** projects (resolved to androidx.dev artifacts).
- `build-logic/` &mdash; included build providing thin shims for the
  `AndroidXPlugin` / `AndroidXComposePlugin` plugin ids and the `androidx { }`
  DSL extension. Just enough for upstream `build.gradle` files to apply
  cleanly; no publishing, lint, API tracking, samples plumbing, etc.
- `stubs/` &mdash; empty directories backing the stub projects. They exist so
  that `project(":foo:bar")` references resolve at configuration time;
  `substitutions.properties` swaps each one for a Maven coordinate at
  resolution time.
- `snapshots.properties` &mdash; the pinned androidx.dev build id.
- `scripts/bump-snapshot.sh` &mdash; bumps `snapshots.properties` to the latest
  (or a specific) androidx.dev build.

Stub project &rarr; Maven coordinate mapping is **automatic**, derived inside
the AndroidXPlugin shim by a `dependencySubstitution { all { ... } }` rule (see
the [Gradle docs](https://docs.gradle.org/current/dsl/org.gradle.api.artifacts.DependencySubstitutions.html)
for the pattern). For each `project(":a:b:c")` dependency the rule checks
whether the target project lives under `stubs/`; if so it substitutes
`androidx.a.b:c:1.0.0-SNAPSHOT`. Coordinates that don't follow the convention
are listed in `SnapshotConfig.overrides` in
[`AndroidXPlugin.kt`](build-logic/src/main/kotlin/androidx/build/AndroidXPlugin.kt).

## What's wired up today

Source projects:
- `:wear:compose:remote:remote-material3`

Stub projects (resolved from the snapshot repo):
- `:compose:remote:remote-creation`
- `:compose:remote:remote-creation-compose`
- `:compose:remote:remote-player-core`
- `:compose:remote:remote-player-compose-testutils`
- `:compose:ui:ui-test`
- `:test:screenshot:screenshot`
- `:test:uiautomator:uiautomator`
- `:wear:compose:remote:remote-material3-samples`

## Adding a project to the overlay (build from source)

1. In `settings.gradle.kts`, add a `source(":x:y:z", "x/y/z")` line.
2. Remove its `stub()` line if present.
3. If the project's `build.gradle` references new project paths that aren't
   yet in scope, add them as `stub()` entries — they'll be auto-substituted
   to androidx.dev snapshot modules. Any path that doesn't follow the
   `:a:b:c` &rarr; `androidx.a.b:c` convention needs an entry in
   `SnapshotConfig.overrides`.

## Bumping the snapshot

```sh
scripts/bump-snapshot.sh           # latest
scripts/bump-snapshot.sh 13530000  # specific build id
./gradlew --refresh-dependencies :wear:compose:remote:remote-material3:assembleDebug
```

## Caveats

- The `androidx { }` extension is a no-op; samples wiring, golden-image
  ingestion, API lockdown, publishing, and KMP setup are intentionally out of
  scope. Build outputs are debug AARs only.
- Some stub coordinates (e.g. `compose-remote-material3-samples`) may not
  publish to androidx.dev; if a snapshot resolution fails, switch the entry to
  build from source instead.
- AGP `release(N)` / `compileSdk { version = ... }` DSL is native AGP 9.x — we
  rely on AGP, not on a re-implementation.
