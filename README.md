# LRCGET Android

Native Android adaptation of [tranxuanthang/lrcget](https://github.com/tranxuanthang/lrcget).

This first Android version keeps the core desktop workflow:

- choose a music folder with Android's Storage Access Framework
- recursively scan common audio formats
- read embedded track metadata when Android can decode it
- fetch synced lyrics from LRCLIB
- write `.lrc` files next to each audio file
- optionally embed synced lyrics into MP3 ID3 `SYLT` frames
- skip existing lyrics unless overwrite is enabled
- manually search LRCLIB for scanned tracks
- use Material You dynamic color on Android 12 and newer
- request notification permission and battery optimization exemption at startup
- run lyric downloads in a foreground background service with notification progress

Open the folder in Android Studio, sync Gradle, then run the `app` configuration.
