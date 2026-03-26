package com.openclaw.assistant.speech

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Pre-generates and caches filler/wait phrases as audio files using ElevenLabs API.
 * Cached audio plays instantly without network delay.
 */
class FillerPhraseCache(private val context: Context) {

    companion object {
        private const val TAG = "FillerPhraseCache"
        private const val CACHE_DIR_NAME = "filler_phrases"
        private const val SETTINGS_HASH_FILE = "settings_hash.txt"

        @Volatile
        private var instance: FillerPhraseCache? = null

        fun getInstance(context: Context): FillerPhraseCache {
            return instance ?: synchronized(this) {
                instance ?: FillerPhraseCache(context.applicationContext).also { instance = it }
            }
        }
    }

    private val settings = SettingsRepository.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    private val cacheDir: File
        get() = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }

    /** All filler phrases (short acknowledgements, played after ~750ms) */
    val fillerPhrases: List<String>
        get() = listOf(
            context.getString(R.string.filler_got_it),
            context.getString(R.string.filler_sure),
            context.getString(R.string.filler_on_it)
        )

    /** Wait phrases (longer, played after ~5s of thinking) */
    val waitPhrases: List<String>
        get() = listOf(
            context.getString(R.string.wait_phrase_let_me_think),
            context.getString(R.string.wait_phrase_one_moment),
            context.getString(R.string.wait_phrase_checking),
            context.getString(R.string.wait_phrase_working_on_it)
        )

    /** All phrases that need to be cached */
    private val allPhrases: List<String>
        get() = fillerPhrases + waitPhrases

    /** Hash of current voice settings to detect when re-generation is needed */
    private fun currentSettingsHash(): String {
        return "${settings.elevenLabsVoiceId}|${settings.elevenLabsModel}|${settings.elevenLabsSpeed}"
    }

    /** Check if cached phrases match current settings */
    fun isCacheValid(): Boolean {
        val hashFile = File(cacheDir, SETTINGS_HASH_FILE)
        if (!hashFile.exists()) return false
        val storedHash = hashFile.readText().trim()
        if (storedHash != currentSettingsHash()) return false
        // Verify all phrase files exist
        return allPhrases.all { File(cacheDir, phraseFileName(it)).exists() }
    }

    /** Get cached audio file for a phrase, or null if not cached */
    fun getCachedFile(phrase: String): File? {
        val file = File(cacheDir, phraseFileName(phrase))
        return if (file.exists() && file.length() > 0) file else null
    }

    /** Play a random filler phrase from cache. Returns true if played from cache. */
    suspend fun playRandomFiller(): Boolean {
        val phrase = fillerPhrases.random()
        return playCached(phrase)
    }

    /** Play a random wait phrase from cache. Returns true if played from cache. */
    suspend fun playRandomWaitPhrase(): Boolean {
        val phrase = waitPhrases.random()
        return playCached(phrase)
    }

    /** Play cached audio for a specific phrase */
    suspend fun playCached(phrase: String): Boolean {
        val file = getCachedFile(phrase) ?: return false
        return playAudioFile(file)
    }

    /** Stop any currently playing cached audio */
    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping: ${e.message}")
        }
    }

    /**
     * Generate and cache all filler phrases using ElevenLabs API.
     * @param onProgress Called with (completed, total) counts
     * @return true if all phrases were cached successfully
     */
    suspend fun generateAll(onProgress: ((Int, Int) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        val apiKey = settings.elevenLabsApiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "Cannot generate: no API key")
            return@withContext false
        }

        val voiceId = settings.elevenLabsVoiceId
        if (voiceId.isBlank()) {
            Log.e(TAG, "Cannot generate: no voice ID")
            return@withContext false
        }

        val phrases = allPhrases
        var success = true

        phrases.forEachIndexed { index, phrase ->
            onProgress?.invoke(index, phrases.size)
            try {
                val audioData = synthesize(phrase, apiKey, voiceId)
                if (audioData != null) {
                    val file = File(cacheDir, phraseFileName(phrase))
                    FileOutputStream(file).use { it.write(audioData) }
                    Log.d(TAG, "Cached: '$phrase' -> ${file.name} (${audioData.size} bytes)")
                } else {
                    Log.e(TAG, "Failed to synthesize: '$phrase'")
                    success = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating '$phrase': ${e.message}", e)
                success = false
            }
        }

        // Save settings hash so we know when to regenerate
        if (success) {
            File(cacheDir, SETTINGS_HASH_FILE).writeText(currentSettingsHash())
        }

        onProgress?.invoke(phrases.size, phrases.size)
        success
    }

    /** Clear all cached files */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun phraseFileName(phrase: String): String {
        // Create a safe filename from the phrase
        val safe = phrase.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(" ", "_")
            .take(40)
        return "filler_${safe}.mp3"
    }

    private suspend fun synthesize(text: String, apiKey: String, voiceId: String): ByteArray? {
        val modelId = settings.elevenLabsModel
        val speed = String.format(Locale.US, "%.2f", settings.elevenLabsSpeed.coerceIn(0.7f, 1.2f)).toDouble()

        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"

        val requestBody = JSONObject().apply {
            put("text", text)
            put("model_id", modelId)
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
                put("style", 0.3)
                put("speed", speed)
            })
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.bytes()
                } else {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "API error: ${response.code}, $errorBody")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}", e)
            null
        }
    }

    private suspend fun playAudioFile(file: File): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            stop()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { start() }
                setOnCompletionListener {
                    if (continuation.isActive) continuation.resume(true)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: $what, $extra")
                    if (continuation.isActive) continuation.resume(false)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing: ${e.message}", e)
            if (continuation.isActive) continuation.resume(false)
        }

        continuation.invokeOnCancellation {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {
                mediaPlayer?.release()
            }
            mediaPlayer = null
        }
    }
}
