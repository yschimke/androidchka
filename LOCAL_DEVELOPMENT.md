# Using androidchka locally from another project

When you're testing a downstream app against in-flight AndroidX changes (or
just against an `androidx.dev` snapshot) and want to keep your app's Gradle
setup as-is, you can include `androidchka` as a [Gradle composite
build](https://docs.gradle.org/current/userguide/composite_builds.html).
Same idea as the
[androidx/media "Locally" pattern](https://github.com/androidx/media#locally) —
your app keeps its existing
`implementation("androidx.compose.remote:remote-creation-compose:…")`
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
`androidx.compose.remote:remote-creation-compose:1.0.0-SNAPSHOT`) resolve
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

`apply-androidchka.settings.gradle` is intentionally tiny:

- finds its own location via `buildscript.sourceFile`,
- `includeBuild`s the overlay (Gradle's auto-substitution does the
  `androidx.<group>:<artifact>:1.0.0-SNAPSHOT` &harr; in-source-project
  matching by `group:name`),
- adds the `androidx.dev` snapshot repo (group-restricted to `androidx.*`)
  by reading `snapshots.properties`.

That's the whole integration. No rules to maintain in your app.

## Caveats

- **Naming overrides aren't auto-substituted.** Most upstream paths follow
  the `:a:b:c` &rarr; `androidx.a.b:c` Maven convention and substitute
  automatically. A few don't (e.g. `:wear:compose:remote:remote-material3-samples`
  &rarr; `androidx.wear.compose:compose-remote-material3-samples`); Gradle
  can't infer those. If you need to source-build such a project, add an
  explicit rule yourself:
  ```kotlin
  apply(from = "../androidchka/apply-androidchka.settings.gradle")

  // Override for an unconventionally-named coordinate:
  gradle.allprojects {
      configurations.all {
          resolutionStrategy.dependencySubstitution {
              substitute(module("androidx.wear.compose:compose-remote-material3-samples"))
                  .using(project(":androidx-mini:wear:compose:remote:remote-material3-samples"))
          }
      }
  }
  ```
- **KMP target artifacts need explicit substitution.** The apply script already
  maps Remote Compose's target-specific artifacts, such as
  `androidx.compose.remote:remote-creation-android`, back to the source project.
  If another sourced KMP module is used through a direct `*-android` or `*-jvm`
  coordinate, add the same kind of substitution in your settings file.
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
