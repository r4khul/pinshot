# Contributing to Pinshot

Thank you for helping improve Pinshot. Contributions are welcome when they are focused, tested, and documented clearly.

## Before you start

- Search existing issues and pull requests before opening a new one.
- For significant product or architecture changes, open an issue first so the scope can be agreed on.
- Keep changes focused. Avoid mixing unrelated refactors with a feature or bug fix.

## Development setup

1. Install Android Studio Ladybug (2024.2.1) or newer, JDK 17+, and Android SDK 37.
2. Clone the repository and open it in Android Studio.
3. Allow Gradle to sync, then select an emulator or device running Android 7.0 (API 24) or newer.
4. Grant photo and notification permissions when testing the app lifecycle.

## Validation

Run the relevant checks before opening a pull request:

```bash
./gradlew testDebugUnitTest lintDebug
./gradlew assembleDebug
```

For release-related changes, also run:

```bash
./gradlew assembleRelease
```

Verify UI changes on an emulator, including empty states, permission denial, dark mode, and the affected interaction path. Do not commit generated build outputs or local signing keys.

## Pull requests

- Use a clear title that describes the change.
- Explain the user-visible behavior, implementation approach, and any trade-offs.
- Include reproduction steps for bug fixes and screenshots or recordings for meaningful UI changes.
- Mention the validation commands you ran.
- Keep commits small and logically grouped. Use imperative, descriptive commit subjects such as `fix(ui): preserve viewer state`.
- Be responsive to review feedback and update documentation when behavior or setup changes.

## Commit and review expectations

A pull request should be ready to build from a clean checkout. Reviewers may request additional tests, documentation, accessibility checks, or device coverage when the change affects those areas.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
