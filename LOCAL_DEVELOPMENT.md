# Using androidchka locally from another project

When you're testing a downstream app against in-flight AndroidX changes (or
just against an `androidx.dev` snapshot) and want to keep your app's Gradle
setup as-is, you can include `androidchka` as a [Gradle composite
build](https://docs.gradle.org/current/userguide/composite_builds.html).
Same idea as the
[androidx/media "Locally" pattern](https://github.com/androidx/media#locally) —
your app keeps its existing
`implementation("androidx.compose.remote:remote-creation:…")`
declarations, and Gradle auto-routes them to the in-source projects the
overlay builds.

## One-line integration

In your project's `settings.gradle[.kts]`:

```kotlin
apply(from = "../androidchka/apply-androidchka.settings.gradle")
```

(or relative to wherever you cloned `androidchka`). That single line is
enough — the script self-locates, calls `includeBuild` on the overlay, and
adds the `androidx.dev` snapshot repo configured in
`androidchka/snapshots.properties` to your project's resolution. Build your
app normally; coordinates that map to a source project (e.g.
`androidx.compose.remote:remote-creation:1.0.0-SNAPSHOT`) resolve
locally, everything else from the snapshot repo.

## Prerequisites

1. Clone the AndroidX checkout you want to test against (Gerrit, GitHub
   mirror, or a `repo` workspace) so the source tree is on disk:
   ```sh
   ~/workspace/androidx/
   ```
2. Clone this repo next to it:
   ```sh
   git clone https://github.com/yschimke/androidchka ~/workspace/androidchka
   cd ~/workspace/androidchka
   # The committed `androidx` symlink defaults to ../androidx — relink if
   # your checkout lives elsewhere.
   ln -sfn ~/workspace/androidx androidx
   ```
3. Pick which projects to build from source. Edit
   `~/workspace/androidchka/local.properties`:
   ```properties
   androidx.sources=:wear:compose:remote,:compose:remote
   ```
   Comma-separated Gradle project paths. A path that points at a group
   (rather than a leaf) recursively picks up every nested project that has
   a `build.gradle`. Anything not listed resolves through the snapshot.
4. (Optional) `cd ~/workspace/androidchka && ./gradlew tasks` to verify
   configuration. The first lines log the source set, the auto-derived
   stubs, and any sources skipped because their build.gradle uses DSL the
   overlay doesn't satisfy.

## Matching your app's AGP version

Gradle allows only one Android Gradle Plugin version in a build. When
`androidchka` is included from your app, set its AGP version to match the app's
version in the host app's `gradle.properties`:

```properties
# host-app/gradle.properties
androidchka.agpVersion=9.2.1
```

`apply-androidchka.settings.gradle` forwards that property into the included
`androidchka` build before Gradle resolves Android plugins. You can also pass it
on the command line:

```sh
./gradlew test -Pandroidchka.agpVersion=9.2.1
```

`androidchka/gradle.properties` only provides the standalone default. Use an
AGP version new enough for the AndroidX modules you are sourcing; many current
AndroidX build files use AGP 9 DSL such as `compileSdk { version = release(37) }`.

## Bumping the snapshot

```sh
cd ~/workspace/androidchka
scripts/bump-snapshot.sh             # latest
scripts/bump-snapshot.sh 15377653    # specific build id
```

Then `./gradlew --refresh-dependencies` in your downstream app to clear
cached metadata.

## What the apply script does

`apply-androidchka.settings.gradle` keeps the host-project integration small:

- finds its own location via `buildscript.sourceFile`,
- reads androidchka's selected source projects and `includeBuild`s the overlay
  with substitutions for their published main Maven coordinates,
- adds the `androidx.dev` snapshot repo (group-restricted to `androidx.*`)
  by reading `snapshots.properties`.

That's the whole integration. No per-project substitution rules to maintain in
your app for published main coordinates.

There are two substitution modes:

- **androidchka local development:** inside this repo, source projects can
  depend on whatever AndroidX projects the overlay needs to make them compile:
  main libraries, samples, tests, demos, and internal helpers.
- **Host-app embedding:** when a downstream app applies
  `apply-androidchka.settings.gradle`, only externally published main artifacts
  are replaced by source projects. Helper projects may still be included inside
  the overlay, but they are not treated as app-facing dependency coordinates.

## Caveats

- **Only published main artifacts are substituted.** The apply script assumes
  host apps depend on public AndroidX coordinates, and maps those coordinates
  to the matching source projects. Samples, demos, benchmarks, test utilities,
  target-specific KMP artifacts, and other internal coordinates are not part of
  that automatic host-app substitution contract.
- **Use root KMP coordinates.** For multiplatform AndroidX libraries, depend on
  the root coordinate, e.g. `androidx.compose.remote:remote-creation`, rather
  than target-specific artifacts like `remote-creation-android` or
  `remote-creation-jvm`. The apply script explicitly substitutes every sourced
  root coordinate to its project, which keeps published target artifacts from
  mixing with source projects. Non-KMP published artifacts, such as
  `androidx.compose.remote:remote-creation-compose`, are still normal published
  main artifacts and can be depended on directly.
- **Plugin / DSL coverage is limited.** `androidchka` reproduces only the
  pieces of the upstream `buildSrc` needed to compile a `build.gradle` file
  end-to-end. Projects that use unsupported plugins (`androidx.benchmark`,
  `com.android.test`, `java-library`, etc.) are skipped automatically and
  resolved from the snapshot repo. Watch the
  `[androidchka] skipped sources (...)` log line at configuration time.
- **Edits to upstream require a Gradle sync** in your app, the same as any
  composite-build setup. Most edits are picked up incrementally; structural
  changes (adding a project, changing a Maven coord) need a full sync.
- **No publishing.** The overlay produces debug AARs and JVM jars locally
  for substitution; it doesn't run upstream's `androidx { }` publishing,
  API tracking, or golden-image plumbing. Don't ship binaries built this
  way.
