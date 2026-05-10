<img src="docs/icon.png" align="right" width="96" height="96" alt="androidchka icon">

# androidchka

androidchka is a small Gradle workspace for testing your app against local
AndroidX changes. It is useful when you can reproduce a bug in your app and
want to fix or validate the relevant AndroidX modules from source without
pulling every dependency into the active build.

Point the `androidx` symlink at a local AndroidX checkout, list the source
modules you want in `local.properties`, and androidchka builds just those
projects. Everything else resolves from a pinned `androidx.dev` snapshot, so
your local build stays focused and reasonably quick.

Use this when you want a nearby app to run against selected source-built
AndroidX artifacts while the rest of the dependency graph stays on snapshots.
For one-line app integration, see [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md).

> [!CAUTION]
> **Not a valid setup for upstream AndroidX PRs.** androidchka is a
> reproduction / local-validation harness only. It deliberately skips the
> AndroidX quality gates — **no API tracking, no API/binary compatibility
> checks, no lint baselines, no Metalava, no sample/golden wiring, no
> publishing or KMP plumbing** — and the convention plugins it ships are
> stubs, not the real `AndroidXPlugin`. On top of that, **most AndroidX
> modules don't accept GitHub PR contributions** and must go through Gerrit
> on `android.googlesource.com` against the full AndroidX build. Use
> androidchka to validate a fix against your app, then re-apply and submit
> the change in the official AndroidX checkout.

## Layout

- `androidx` &mdash; symlink to `/home/yuri/workspace/androidx`, or wherever your
  local AndroidX checkout lives. Build files are read from there directly.
- `local.properties.example` &mdash; committed default `androidx.sources=...`
  list (Gradle project paths to build from source).
- `local.properties` &mdash; per-clone override, git-ignored. Copy from
  `.example` and edit if you want a different subset.
- `settings.gradle.kts` &mdash; reads the source list from `local.properties`
  (or `.example` as fallback). **Stubs are auto-populated** by scanning each
  source project's `build.gradle` for `project(":path")` references — anything
  not already a source becomes a stub. The list is logged at configuration:
  `[androidchka] auto-stubs: N -> [...]`.
- [`build-logic/src/main/kotlin/androidchka.extras.gradle.kts`](build-logic/src/main/kotlin/androidchka.extras.gradle.kts) &mdash;
  user-editable convention plugin applied to every source project. Add extra
  Gradle plugins, dependencies, task tweaks here.
- `build-logic/` &mdash; included build providing thin shims for the
  `AndroidXPlugin` / `AndroidXComposePlugin` plugin ids and the `androidx { }`
  DSL extension. Just enough for AndroidX `build.gradle` files to apply
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

## Adding extra Gradle plugins / config

Edit [`build-logic/src/main/kotlin/androidchka.extras.gradle.kts`](build-logic/src/main/kotlin/androidchka.extras.gradle.kts).
It's a normal Kotlin DSL convention plugin — anything that's valid in a
`build.gradle.kts` works (plugins, dependencies, task config, etc.).

Gradle quirk: precompiled script plugins forbid `version` in their `plugins {}`
block, so each plugin is added in two places:

1. `build-logic/build.gradle.kts` — `implementation("group:artifact:version")`
   line for the plugin marker.
2. `androidchka.extras.gradle.kts` — `id("...")` (no version) inside `plugins {}`.

To restrict a plugin to specific source projects, swap the `plugins {}` form
for a conditional `apply(plugin = ...)` in `androidchka.extras.gradle.kts`.

## Configuring which projects to build

Edit `local.properties` (copy from `local.properties.example` if you don't
have one yet):

```properties
androidx.sources=:wear:compose:remote:remote-material3,:compose:remote:remote-creation-compose
```

The relative directory under `androidx/` is derived by replacing `:` with
`/`. Override with `:path = relative/dir/path` for the rare case where the
project path doesn't match its directory.

Stubs for everything those projects reference are generated automatically. If
a stub's path doesn't follow the `:a:b:c` &rarr; `androidx.a.b:c` Maven
convention, add an entry to `SnapshotConfig.overrides` in
[`AndroidXPlugin.kt`](build-logic/src/main/kotlin/androidx/build/AndroidXPlugin.kt).

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
