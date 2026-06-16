# LRCGET Android

Modern Android client for downloading and managing synced lyrics using the LRCLIB API.

This project brings the core workflow of the desktop version of LRCGET to Android while following Android-native design patterns, Material You styling, and background processing support.

# Features

## workflow

- Choose a music folder using Android Storage Access Framework
- Recursively scan music libraries
- Detect common audio formats automatically
- Read embedded track metadata when supported by Android decoders
- Download synced lyrics directly from LRCLIB
- Save `.lrc` files beside audio tracks
- Skip already existing lyrics unless overwrite mode is enabled
- Manual LRCLIB search for scanned tracks
- Background lyric downloading with foreground service notifications

---

## Android-Specific Features

- Material You dynamic colors on Android 12+
- Foreground service with persistent progress notifications
- Notification permission request on startup
- Battery optimization exemption request for uninterrupted processing
- Optimized for large music libraries
- Mobile-friendly workflow based on the original desktop application

---

# Supported Audio Formats

The scanner supports common audio formats including:

- MP3
- FLAC
- M4A
- AAC
- OGG
- OPUS
- WAV

Additional formats may work depending on Android codec support.

---

# Embedded Lyrics Support

LRCGET Android can optionally embed synced lyrics directly into MP3 files using:

- ID3 SYLT frames

This allows compatible music players to display synchronized lyrics without separate `.lrc` handling.

---

# How It Works

1. Select your music folder
2. Scan your library
3. Review detected tracks
4. Search or automatically fetch synced lyrics
5. Save `.lrc` files beside songs
6. Optionally embed synced lyrics into MP3 metadata

---

# Building

## Requirements

- Android Studio
- Android SDK
- Gradle
- Android 12+ recommended for full Material You support

---

## Open Project

```bash
git clone https://github.com/PARADOXMEG/lrcget-android.git
```

Open the folder in Android Studio, sync Gradle, then run the app configuration.

---

# Permissions

The app may request:

- Notification permission
- Storage access permission
- Battery optimization exemption

These permissions are used for long-running lyric download operations and background reliability.

---

# Credits

## Original LRCGET Desktop Project

This Android version is based on the original desktop implementation of LRCGET.

Huge credit to the original author and contributors of LRCGET for creating the core workflow and desktop application.

- GitHub: https://github.com/tranxuanthang/lrcget
- Project Page: https://github.com/tranxuanthang/lrcget

---

## LRCLIB

Lyrics are powered by the LRCLIB API.

Special thanks to the LRCLIB project for providing a free synced lyrics database and public API.

- Website: https://lrclib.net
- API Documentation: https://lrclib.net/docs

---

# Disclaimer

This project is an independent Android adaptation inspired by the original desktop version of LRCGET.

This repository is not officially affiliated with the original desktop project.

---

# License

Please ensure compliance with:

- Original LRCGET licensing
- LRCLIB API terms and usage policies

---


