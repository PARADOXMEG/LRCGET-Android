# Feature Parity Plan

Implemented in this Android foundation:

- Material You / Monet dynamic colors on Android 12+
- folder picker using Android Storage Access Framework
- persisted read/write access to the selected music folder
- recursive scan for `mp3`, `flac`, `m4a`, `ogg`, `opus`, `wav`, `aac`, and `wma`
- metadata extraction through Android `MediaMetadataRetriever`
- LRCLIB exact metadata lookup with search fallback
- synced-only lyrics lookup for downloads
- instrumental track handling
- batch `.lrc` writing beside each audio file
- optional MP3 ID3 `SYLT` embedded synced lyrics writing
- skip or overwrite existing `.lrc` files
- per-track status reporting
- per-track manual synced lyrics search
- Tracks and Settings tabs
- notification permission request on Android 13+
- battery optimization exemption request at app startup
- foreground background download service
- notification panel progress while lyrics are downloading

Still needed for closer desktop parity:

- manual match picker when LRCLIB returns multiple candidates
- lyrics preview and edit before saving
- embedded audio playback with synced lyric preview
- saved app settings and last selected library restore
- richer error reporting for provider-specific SAF write failures
- optional background worker for very large libraries
- release signing, Play Store metadata, and privacy policy
