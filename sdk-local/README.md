# Lawnchair SDK publishing

This repository can be released through JitPack.

## Published modules

- `launcher-sdk-api`
- `launcher-core`

## Local verification

From the repo root:

```bash
./gradlew :launcher-sdk-api:publishToMavenLocal :launcher-core:publishToMavenLocal
```

Optional custom coordinates:

```bash
./gradlew \
  -PPUBLISH_GROUP=com.github.vihuela.lawnchair-sdk \
  -PPUBLISH_VERSION=0.1.0-local \
  :launcher-sdk-api:publishToMavenLocal \
  :launcher-core:publishToMavenLocal
```

## JitPack release

1. Push your release branch to GitHub.
2. Create and push a tag:

```bash
git tag v0.1.4
git push origin v0.1.4
```

3. Wait for JitPack to finish building the tag.

Dependency coordinates:

```text
com.github.vihuela.lawnchair-sdk:launcher-sdk-api:v0.1.4
com.github.vihuela.lawnchair-sdk:launcher-core:v0.1.4
```

Consumer repositories:

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url "https://jitpack.io" }
    }
}
```
