# Persistent native-image Kotlin DSL compiler

Assumes matching Kotlin/Gradle sources and toolchains are ready, with
`GRAALVM_HOME` pointing to GraalVM with Crema support. Use the same shell throughout:

```bash
export KOTLIN_REPO=/absolute/path/to/kotlin
export GRADLE_REPO=/absolute/path/to/gradle
```

### 1. Build the native image

```bash
cd "$KOTLIN_REPO"
./gradlew :kotlin-compiler-native-image:kotlincNativeImageDist \
  -Pbuild.number=2.5.0-native-image \
  -Pkotlin.build.useBootstrapStdlib=true \
  -Pkotlin.build.native-image.dynamic-plugins=true \
  --dependency-verification off
```

Output: `prepare/compiler-native-image/build/dist`. The `dynamic-plugins` flag
is required for loading Gradle script templates.

### 2. Publish Kotlin artifacts

```bash
cd "$KOTLIN_REPO"
./gradlew publish \
  -Pbuild.number=2.5.0-native-image \
  -Pkotlin.build.useBootstrapStdlib=true \
  --dependency-verification off
```

Output: `build/repo` (the full Maven repository, not just the modified jars).

### 3. Copy the artifacts to Gradle

```bash
mkdir -p "$GRADLE_REPO/env/kotlin/build/repo" \
  "$GRADLE_REPO/env/kotlin/prepare/compiler-native-image/build/dist"
cp -R "$KOTLIN_REPO/build/repo/." "$GRADLE_REPO/env/kotlin/build/repo/"
cp -R "$KOTLIN_REPO/prepare/compiler-native-image/build/dist/." \
  "$GRADLE_REPO/env/kotlin/prepare/compiler-native-image/build/dist/"
```

Gradle reads artifacts from `./env/kotlin/build/repo` by default. Keep the whole
native distribution, including `lib`. Skip copying if `env/kotlin` already is
(or links to) your Kotlin checkout.

### 4. Build the Gradle distribution

```bash
cd "$GRADLE_REPO"
./gradlew :distributions-full:binInstallation --dependency-verification off
```

Output: `packaging/distributions-full/build/bin distribution/`.
Use `:distributions-full:binDistributionZip` instead for a ZIP.

When running this Gradle, set `KOTLIN_NATIVE_IMAGE_HOME` to
`$GRADLE_REPO/env/kotlin/prepare/compiler-native-image/build/dist` and pass
`-Dorg.gradle.kotlin.dsl.compiler.execution.strategy=native-image` to enable
native script compilation (the default is JVM in-process).