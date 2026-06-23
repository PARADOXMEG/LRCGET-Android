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
    val duration: Int = 0,
    val albumArtUrl: String? = null
) {
    val lineCount: Int by lazy { 
        lyrics.lines().count { line ->
            val content = line.replace(Regex("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?]"), "").trim()
            content.isNotBlank()
        }
    }
}

data class Challenge(val prefix: String, val target: String)

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

    fun publishLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        duration: Int,
        plainLyrics: String,
        syncedLyrics: String
    ): Result<Unit> {
        val challenge = requestChallenge() ?: return Result.failure(Exception("Failed to request challenge from LRCLIB"))
        val nonce = solveChallenge(challenge.prefix, challenge.target)
        val publishToken = "${challenge.prefix}:$nonce"

        val jsonBody = JSONObject().apply {
            put("trackName", trackName)
            put("artistName", artistName)
            put("albumName", albumName)
            put("duration", duration)
            put("plainLyrics", plainLyrics)
            put("syncedLyrics", syncedLyrics)
        }

        return post("https://lrclib.net/api/publish", jsonBody, publishToken)
    }

    private fun requestChallenge(): Challenge? {
        val response = postRaw("https://lrclib.net/api/request-challenge", JSONObject(), null) ?: return null
        return runCatching {
            val json = JSONObject(response)
            Challenge(json.getString("prefix"), json.getString("target"))
        }.getOrNull()
    }

    private fun solveChallenge(prefix: String, target: String): String {
        var nonce = 0L
        val md = java.security.MessageDigest.getInstance("SHA-256")
        while (true) {
            val attempt = "$prefix$nonce"
            val hash = md.digest(attempt.toByteArray()).joinToString("") { "%02x".format(it) }
            if (hash <= target) {
                return nonce.toString()
            }
            nonce++
            if (nonce % 10000 == 0L) {
                if (Thread.interrupted()) throw InterruptedException()
            }
        }
    }

    private fun post(url: String, json: JSONObject, token: String?): Result<Unit> {
        val response = postRaw(url, json, token) ?: return Result.failure(Exception("Network error or empty response"))
        return Result.success(Unit)
    }

    private fun postRaw(url: String, json: JSONObject, token: String?): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LRCGET-Android/0.1.0")
            if (token != null) {
                setRequestProperty("X-Publish-Token", token)
            }
        }

        return runCatching {
            connection.outputStream.use { it.write(json.toString().toByteArray()) }
            val responseCode = connection.responseCode
            if (responseCode in 200..201) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                android.util.Log.e("LrclibClient", "POST error $responseCode: $errorBody")
                null
            }
        }.getOrElse {
            android.util.Log.e("LrclibClient", "POST failed", it)
            null
        }.also {
            connection.disconnect()
        }
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
            duration = optInt("duration"),
            albumArtUrl = optString("albumArtUrl").takeIf { it.isNotBlank() && it != "null" }
        )
    }


    private fun Map<String, String>.toQuery(): String =
        entries.joinToString("&") { (key, value) -> "${key.urlEncoded()}=${value.urlEncoded()}" }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
}
