package app.lrcget.android.data

import app.lrcget.android.model.TrackItem
import androidx.compose.runtime.Immutable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class LyricsLookupResult(
    val lyrics: String,
    val isSynced: Boolean,
    val isInstrumental: Boolean,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val duration: Int = 0
) {
    val lineCount: Int by lazy { lyrics.lines().count { it.isNotBlank() } }
}

class LrclibClient {
    fun findLyrics(track: TrackItem, syncedOnly: Boolean): LyricsLookupResult? {
        return findAllLyrics(track, syncedOnly).firstOrNull()
    }

    fun findAllLyrics(track: TrackItem, syncedOnly: Boolean = false): List<LyricsLookupResult> {
        val results = mutableListOf<LyricsLookupResult>()

        val exact = requestJsonObject(
            "https://lrclib.net/api/get?" + mapOf(
                "track_name" to track.title,
                "artist_name" to track.artist,
                "album_name" to track.album,
                "duration" to track.durationSeconds.toString()
            ).toQuery()
        )

        exact?.lyrics(syncedOnly)?.let { results.add(it) }

        val query = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isNotBlank()) {
            val search = requestJsonArray("https://lrclib.net/api/search?q=${query.urlEncoded()}")
            if (search != null) {
                for (i in 0 until search.length()) {
                    search.optJSONObject(i)?.lyrics(syncedOnly)?.let { result ->
                        if (results.none { it.lyrics == result.lyrics }) {
                            results.add(result)
                        }
                    }
                }
            }
        }

        return results
    }

    fun searchLyricsManual(
        trackName: String,
        artistName: String = "",
        albumName: String = ""
    ): List<LyricsLookupResult> {
        val params = mutableMapOf("track_name" to trackName)
        if (artistName.isNotBlank()) params["artist_name"] = artistName
        if (albumName.isNotBlank()) params["album_name"] = albumName
        
        val url = "https://lrclib.net/api/search?" + params.toQuery()
        val search = requestJsonArray(url) ?: return emptyList()
        val results = mutableListOf<LyricsLookupResult>()
        for (i in 0 until search.length()) {
            search.optJSONObject(i)?.lyrics(syncedOnly = false)?.let { results.add(it) }
        }
        return results
    }

    private fun requestJsonObject(url: String): JSONObject? {
        val body = request(url) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun requestJsonArray(url: String): JSONArray? {
        val body = request(url) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private fun request(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LRCGET-Android/0.1.0")
        }

        return runCatching {
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                android.util.Log.e("LrclibClient", "HTTP error $responseCode for $url")
                null
            }
        }.getOrElse {
            android.util.Log.e("LrclibClient", "Request failed for $url", it)
            null
        }.also {
            connection.disconnect()
        }
    }

    private fun JSONObject.lyrics(syncedOnly: Boolean): LyricsLookupResult? {
        val synced = optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        val isInstrumental = optBoolean("instrumental", false)
        
        val resultLyrics = when {
            isInstrumental -> "[00:00.00] Instrumental"
            synced != null -> synced
            !syncedOnly && plain != null -> plain
            else -> return null
        }

        return LyricsLookupResult(
            lyrics = resultLyrics,
            isSynced = isInstrumental || synced != null,
            isInstrumental = isInstrumental,
            trackName = optString("trackName"),
            artistName = optString("artistName"),
            albumName = optString("albumName"),
            duration = optInt("duration")
        )
    }


    private fun Map<String, String>.toQuery(): String =
        entries.joinToString("&") { (key, value) -> "${key.urlEncoded()}=${value.urlEncoded()}" }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
}
