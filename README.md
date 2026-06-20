# LRCGET Android

Modern Android client for downloading and managing synced lyrics using the LRCLIB API.

This project brings the core workflow of the desktop version of LRCGET to Android while following Android-native design patterns, Material 3 styling, and background processing support.

# Features

## Core Workflow

- **SAF Integration**: Choose your music folder using the Android Storage Access Framework for secure, scoped access.
- **Recursive Scanning**: Automatically finds audio files in subdirectories.
- **Enhanced Metadata**: Reads track information (Title, Artist, Album, Artwork) using `MediaMetadataRetriever` and `jaudiotagger`.
- **Two-Step Processing**:
  1. **Search**: Bulk search for lyrics across your library with smart title cleaning.
  2. **Export**: Review found lyrics and export them to your preferred format.
- **Instrumental Detection**: Automatically identifies and marks instrumental tracks.
- **Batch Operations**: Support for multi-selecting tracks for downloading, exporting, or deleting lyrics.

## Export & Embedding

- **LRC Files**: Save synchronized `.lrc` files beside your audio tracks.
- **Plain Text**: Save unsynchronized `.txt` files for simple lyric viewers.
- **Metadata Embedding**: 
  - **MP3**: Embeds `SYLT` (synced) and `USLT` (unsynced) ID3 frames.
  - **Other Formats**: Supports embedding lyrics into FLAC, M4A, OGG, and WAV tags.
- **Overwrite Control**: Toggle whether to overwrite existing lyrics or skip them.

## User Experience

- **Preview & Playback**: Preview lyrics from multiple search results and use the built-in mini-player to verify tracks.
- **Manual Search**: Perform manual searches for tracks with missing or incorrect metadata.
- **Material 3 UI**: Full Material You support with dynamic color surfacing on Android 12+.
- **AMOLED Mode**: Pure black theme for OLED screens.
- **Background Processing**: Foreground service with persistent notifications keeps operations running even when the app is in the background.

---

# Supported Audio Formats

LRCGET Android supports metadata reading and lyric embedding for:

- **MP3** (ID3v2.3/v2.4)
- **FLAC**
- **M4A / AAC** (MP4 containers)
- **OGG / OPUS** (Vorbis comments)
- **WAV**
- **WMA**

---

# How It Works

1. **Select Folder**: Grant access to your music library.
2. **Scan**: Let the app index your tracks and detect existing lyrics.
3. **Search**: Run a bulk search to find matches on LRCLIB.
4. **Review**: Check results, preview lyrics, or manually search if needed.
5. **Export**: Save the found lyrics as files or embed them directly into your music.

---

# Building

## Requirements

- Android Studio Koala or newer
- Android SDK 34+
- Gradle 8.0+
- Android 12+ (API 31) recommended for Material You support

## Open Project

```bash
git clone https://github.com/PARADOXMEG/lrcget-android.git
```

Open the project in Android Studio, sync Gradle, and run the `app` configuration.

---

# Permissions

The app requests the following permissions for core functionality:

- **Notification Permission**: To show progress for long-running operations.
- **Storage Access**: Via SAF to read/write your music files.
- **Foreground Service**: To ensure searches and exports aren't interrupted.
- **Battery Optimization**: (Optional) To prevent the system from killing the app during large library scans.

---

# Credits

## Original LRCGET Desktop Project

This Android version is based on the original desktop implementation of LRCGET.

Huge credit to the original author and contributors of LRCGET for creating the core workflow.

- GitHub: [tranxuanthang/lrcget](https://github.com/tranxuanthang/lrcget)

## LRCLIB

Lyrics are powered by the LRCLIB API. Special thanks for providing a free, high-quality synced lyrics database.

- Website: [lrclib.net](https://lrclib.net)

---

# Disclaimer

This project is an independent Android adaptation and is not officially affiliated with the original desktop project or LRCLIB.

---

# License

This project is licensed under the same terms as the original LRCGET (if applicable) or under the MIT License. Check individual source files for details.
