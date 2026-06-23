# LRCGET Android

[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**LRCGET Android** is a modern, lightweight utility for downloading and managing synced lyrics for your local music library. Inspired by the [original desktop version](https://github.com/tranxuanthang/lrcget), it brings high-quality lyric fetching and metadata embedding to your Android device with a native, Material 3 experience.

---

## 🚀 Features

### 🔍 Smart Lyric Discovery
*   **LRCLIB Integration:** Fetches high-quality synced and plain text lyrics from the [LRCLIB](https://lrclib.net) database.
*   **Smart Fallback:** Automatically cleans track titles (removes "feat.", "Remastered", "Live", etc.) and retries searches to improve match rates.
*   **Instrumental Detection:** Automatically identifies instrumental tracks to skip unnecessary searches.
*   **Manual Search:** Hand-tune search queries (Track, Artist, Album) for those hard-to-find songs.

### 💾 Flexible Output & Embedding
*   **External Files:** Save lyrics as `.lrc` (synced) or `.txt` (plain text) files alongside your music.
*   **Deep Tag Embedding:**
    *   **MP3:** Writes high-compatibility `SYLT` (synchronized) and `USLT` (unsynchronized) ID3v2 frames.
    *   **Modern Formats:** Full support for embedding lyrics into **FLAC, M4A, OGG, OPUS, WAV, and WMA** tags.
*   **Batch Operations:** Search for or download lyrics for your entire library or selected tracks in one click.

### 📱 Premium User Experience
*   **Material 3 UI:** Clean interface with full **Dynamic Color** (Material You) support on Android 12+.
*   **AMOLED Mode:** A pure black theme optimized for OLED screens.
*   **Lyrics Preview:** Browse multiple search results and preview lyrics before saving.
*   **Background Processing:** A dedicated foreground service ensures large library operations finish even if you leave the app.
*   **Performance:** Scans and processes metadata efficiently with parallel coroutines.

---

## 📂 Supported Formats

LRCGET Android handles metadata reading and lyric embedding for:
*   **MP3** (ID3v2.3/v2.4)
*   **FLAC**
*   **M4A / AAC** (MP4 containers)
*   **OGG** (Vorbis comments)
*   **WAV**
*   **WMA**

---

## 🛠️ How It Works

1.  **Select Music Folder:** Grant access using the Android Storage Access Framework (SAF).
2.  **Scan Library:** The app indexes your music, extracting metadata and detecting existing lyrics.
3.  **Search & Match:** Run a bulk search. The app uses smart matching to find the best lyrics on LRCLIB.
4.  **Review & Download:** Verify results with the built-in previewer and save them to your preferred format.

---

## 🏗️ Building

### Prerequisites
*   Android Studio Koala (2024.1.1) or newer
*   Android SDK 35
*   JDK 17

### Setup
```bash
git clone https://github.com/PARADOXMEG/lrcget-android.git
```
Open in Android Studio, wait for Gradle sync, and run the `app` configuration.

---

## 📜 Credits

*   **Original LRCGET:** This project is an Android adaptation of the excellent [lrcget](https://github.com/tranxuanthang/lrcget) desktop app.
*   **LRCLIB:** All lyrics are provided by the [LRCLIB API](https://lrclib.net).
*   **jaudiotagger:** Used for robust audio tagging support.

---

## ⚖️ License

This project is licensed under the **MIT License**.

---

## ⚠️ Disclaimer

This is an independent community project and is not officially affiliated with the original LRCGET desktop project or LRCLIB.
