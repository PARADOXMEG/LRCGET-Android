package app.lrcget.android.data

import app.lrcget.android.model.TrackItem
import androidx.compose.runtime.Immutable
import android.util.Log
import kotlinx.coroutines.yield
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
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val duration: Int,
    val albumArtUrl: String? = null
) {
    val lineCount: Int get() = lyrics.lines().count { it.isNotBlank() }
}

data class Challenge(val prefix: String, val target: String)

class LrclibClient {
    fun findLyrics(track: TrackItem, syncedOnly: Boolean): LyricsLookupResult? {
        return findAllLyrics(track, syncedOnly).firstOrNull()
    }

    fun findAllLyrics(track: TrackItem, syncedOnly: Boolean): List<LyricsLookupResult> {
        val query = mutableMapOf(
            "track_name" to track.title,
            "artist_name" to track.artist,
            "album_name" to track.album,
            "duration" to track.durationSeconds.toString()
        )
        val url = "https://lrclib.net/api/get?${query.toQuery()}"
        val response = requestJsonObject(url)
        
        if (response != null) {
            val result = response.lyrics(syncedOnly)
            if (result != null) return listOf(result)
        }

        // If not found, try search
        val searchUrl = "https://lrclib.net/api/search?${query.toQuery()}"
        val search = requestJsonArray(searchUrl) ?: return emptyList()
        val results = mutableListOf<LyricsLookupResult>()
        for (i in 0 until search.length()) {
            search.optJSONObject(i)?.lyrics(syncedOnly)?.let { results.add(it) }
        }
        return results
    }

    fun searchLyricsManual(trackName: String, artistName: String, albumName: String): List<LyricsLookupResult> {
        val query = mutableMapOf(
            "track_name" to trackName,
            "artist_name" to artistName,
            "album_name" to albumName
        )
        val searchUrl = "https://lrclib.net/api/search?${query.toQuery()}"
        val search = requestJsonArray(searchUrl) ?: return emptyList()
        val results = mutableListOf<LyricsLookupResult>()
        for (i in 0 until search.length()) {
            search.optJSONObject(i)?.lyrics(syncedOnly = false)?.let { results.add(it) }
        }
        return results
    }

    suspend fun publishLyrics(
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

    private suspend fun solveChallenge(prefix: String, target: String): String {
        var nonce = 0L
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val targetBytes = ByteArray(target.length / 2)
        for (i in 0 until target.length step 2) {
            targetBytes[i / 2] = target.substring(i, i + 2).toInt(16).toByte()
        }
        val prefixBytes = prefix.toByteArray()

        while (true) {
            md.reset()
            md.update(prefixBytes)
            md.update(nonce.toString().toByteArray())
            val hash = md.digest()

            var isLowerOrEqual = true
            for (i in 0 until minOf(hash.size, targetBytes.size)) {
                val h = hash[i].toInt() and 0xFF
                val t = targetBytes[i].toInt() and 0xFF
                if (h < t) {
                    isLowerOrEqual = true
                    break
                }
                if (h > t) {
                    isLowerOrEqual = false
                    break
                }
            }

            if (isLowerOrEqual) {
                return nonce.toString()
            }
            nonce++
            if (nonce % 10000L == 0L) {
                kotlinx.coroutines.yield()
            }
        }
    }

    private fun post(url: String, json: JSONObject, token: String?): Result<Unit> {
        return try {
            val response = postRaw(url, json, token)
            if (response != null) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Empty response from server"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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

        return try {
            connection.outputStream.use { it.write(json.toString().toByteArray()) }
            val responseCode = connection.responseCode
            if (responseCode in 200..201) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e("LrclibClient", "POST error $responseCode: $errorBody")
                if (url.contains("publish")) {
                    throw Exception("HTTP $responseCode: $errorBody")
                }
                null
            }
        } catch (e: Exception) {
            Log.e("LrclibClient", "POST failed for $url", e)
            if (url.contains("publish")) throw e
            null
        } finally {
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

        return try {
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                Log.e("LrclibClient", "HTTP error $responseCode for $url")
                null
            }
        } catch (e: Exception) {
            Log.e("LrclibClient", "Request failed for $url", e)
            null
        } finally {
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
