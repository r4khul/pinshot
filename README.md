<div align="center">
  <img src="assets/logo.png" width="128" height="128" alt="Pinshot Logo" />
  <h1>Pinshot</h1>
  <p>Automatic screenshot cleanup and ephemeral media management for Android</p>
</div>

---

## Overview

Pinshot is an open source Android application designed to keep device storage clean by managing temporary screenshots. Most screenshots are taken for immediate sharing or short term reference and are quickly forgotten, filling up storage over time.

Pinshot introduces a 24-hour expiration window for newly captured screenshots. Unpinned items automatically transition to Android's system trash after 24 hours. Screenshots you want to keep long term can be pinned with a single tap to exempt them from auto-expiration.

## Features

- **Automated Expiration Window**: Newly detected screenshots expire after 24 hours and move to trash automatically.
- **One-Tap Pinning**: Pin important screenshots from the gallery or full-screen viewer so they are stored indefinitely.
- **Custom Fast Scrubber**: A custom ballistic timeline scrubber allowing rapid navigation through monthly and yearly media archives.
- **Trash Management**: Review, restore, or permanently remove screenshots within a dedicated 30-day recovery window.
- **Smart Notifications**: Proactive reminders prior to screenshot expiration with quick actions to pin or review media.
- **MediaStore Synchronization**: Integrates directly with Android MediaStore and system trash permissions without requiring root or custom storage access.

## How It Works

1. **Detection**: Pinshot monitors the system gallery for new screenshot additions using Android MediaStore APIs.
2. **Lifecycle Tracking**: Each detected screenshot is assigned a 24-hour expiration timer stored in a local Room database.
3. **Background Expiration**: WorkManager runs periodic tasks to identify expired items and move them to Android's system trash.
4. **User Control**: Users can pin items at any time to keep them permanently or manually manage items in the trash view.

## Technology Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material 3 design system
- **Database**: Room Persistence Library with KSP
- **Background Processing**: Android WorkManager
- **Image Loading & Caching**: Coil Compose
- **Architecture**: MVVM with Kotlin Flow and StateFlow

## Repository Structure

```
pinshot/
├── app/
│   ├── src/main/
│   │   ├── java/com/escapebranch/pinshot/
│   │   │   ├── data/          # Room DB, MediaStore helpers, repository
│   │   │   ├── notifications/ # WorkManager workers and notification channels
│   │   │   ├── ui/           # Compose screens, ViewModel, and custom scrubber
│   │   │   └── MainActivity.kt
│   │   └── res/              # Vector drawables, themes, and layouts
├── assets/                   # README assets and application branding
└── gradle/                   # Gradle wrapper and version catalog (libs.versions.toml)
```

## Getting Started

### Prerequisites

- Android Studio Ladybug (2024.2.1) or newer
- JDK 17 or higher
- Android SDK (Compile SDK 37, Min SDK 24)

### Building and Running

1. Clone the repository:
   ```bash
   git clone https://github.com/escapebranch/pinshot.git
   cd pinshot
   ```

2. Open the project in Android Studio.

3. Allow Gradle to sync project dependencies.

4. Select an Android Virtual Device (AVD) or connected physical device running Android 7.0 (API level 24) or higher.

5. Click **Run** or press `Shift + F10`.

## Contributing

Contributions are welcome. If you find a bug or have a feature proposal:

1. Open an issue describing the bug or suggested feature.
2. Fork the repository and create a feature branch.
3. Submit a pull request with clear commit messages describing your changes.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
