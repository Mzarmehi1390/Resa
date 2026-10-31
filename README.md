# Resa (رسا)

Resa is a native Android messaging application being developed from the open-source ZemZeme Android project.

> **Project status:** Repository initialization and source audit are in progress. This repository is not yet a buildable application release.

## Technical direction

- Native Android
- Kotlin
- Jetpack Compose and Material 3
- AndroidX, Coroutines, Flow and Navigation Compose
- Gradle Kotlin DSL

Flutter and Dart are out of scope.

## Product goals

- Preserve and carefully integrate the useful messaging and transport capabilities of the upstream project.
- Build a cohesive, accessible Persian/RTL-ready design system.
- Improve security, privacy, reliability and performance before release.
- Maintain automated builds and meaningful regression tests.

## Upstream

- Source project: https://github.com/whisperbit-labs/zemzeme-android
- Upstream license: MIT. Preserve applicable copyright and license notices when integrating source and bundled components.

## Development status

See [docs/TECHNICAL_AUDIT.md](docs/TECHNICAL_AUDIT.md) for confirmed repository observations, known risks, and the staged implementation plan.

No release-readiness or security claim is made until the relevant code has been integrated, tested, and independently reviewed.
