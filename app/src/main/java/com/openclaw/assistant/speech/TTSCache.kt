package com.openclaw.assistant.speech

import android.content.Context
import android.util.Log
import com.openclaw.assistant.data.SettingsRepository
import java.io.File
import java.security.MessageDigest

/**
 * Persistent cache for TTS audio files.
 * Keyed by text + voice settings hash so cache invalidates when voice/model changes.
 * Automatically caches every TTS response for instant replay on repeat phrases.
 */
class TTSCache(private val context: Context) {

    companion object {
        private const val TAG = "TTSCache"
        private const val CACHE_DIR = "tts_cache"
        private const val MAX_CACHE_SIZE_MB = 200
        private const val SETTINGS_HASH_FILE = "voice_settings_hash.txt"

        @Volatile
        private var instance: TTSCache? = null

        fun getInstance(context: Context): TTSCache {
            return instance ?: synchronized(this) {
                instance ?: TTSCache(context.applicationContext).also { instance = it }
            }
        }
    }

    private val settings = SettingsRepository.getInstance(context)

    private val cacheDir: File
        get() = File(context.cacheDir, CACHE_DIR).also { it.mkdirs() }

    /** Hash of voice settings — cache invalidates when these change */
    private fun voiceSettingsHash(): String {
        val key = settings.elevenLabsVoiceId + "|" +
                  settings.elevenLabsModel + "|" +
                  settings.elevenLabsSpeed.toString()
        return md5(key)
    }

    /** Check if voice settings changed since last cache write */
    private fun isVoiceSettingsChanged(): Boolean {
        val hashFile = File(cacheDir, SETTINGS_HASH_FILE)
        if (!hashFile.exists()) return true
        return hashFile.readText().trim() != voiceSettingsHash()
    }

    /** Save current voice settings hash */
    private fun saveVoiceSettingsHash() {
        File(cacheDir, SETTINGS_HASH_FILE).writeText(voiceSettingsHash())
    }

    /**
     * Get cached audio for text. Returns the cached File or null if not cached.
     */
    fun get(text: String): File? {
        if (isVoiceSettingsChanged()) {
            Log.d(TAG, "Voice settings changed, invalidating cache")
            clearCache()
            saveVoiceSettingsHash()
            return null
        }
        val file = cacheFile(text)
        return if (file.exists() && file.length() > 0) {
            Log.d(TAG, "Cache HIT: '${text.take(50)}' -> ${file.name}")
            file
        } else {
            Log.d(TAG, "Cache MISS: '${text.take(50)}'")
            null
        }
    }

    /**
     * Store audio data in cache for the given text.
     */
    fun put(text: String, audioData: ByteArray): File {
        saveVoiceSettingsHash()
        val file = cacheFile(text)
        file.writeBytes(audioData)
        Log.d(TAG, "Cached: '${text.take(50)}' -> ${file.name} (${audioData.size} bytes)")
        evictIfNeeded()
        return file
    }

    /**
     * Check if text is cached.
     */
    fun isCached(text: String): Boolean {
        if (isVoiceSettingsChanged()) return false
        val file = cacheFile(text)
        return file.exists() && file.length() > 0
    }

    /**
     * Get the cache file path for a text string (normalized).
     */
    private fun cacheFile(text: String): File {
        val normalized = normalizeText(text)
        val hash = md5(normalized)
        return File(cacheDir, "tts_$hash.mp3")
    }

    /**
     * Normalize text for cache matching.
     * "Done. All the lights are on." and "Done. All lights are on." hit the same cache.
     */
    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\b(the|a|an)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Evict oldest files if cache exceeds size limit */
    private fun evictIfNeeded() {
        val files = cacheDir.listFiles { f -> f.name.startsWith("tts_") } ?: return
        val totalMB = files.sumOf { it.length() } / (1024 * 1024)
        if (totalMB <= MAX_CACHE_SIZE_MB) return

        Log.d(TAG, "Cache size ${totalMB}MB exceeds limit, evicting oldest files")
        files.sortBy { it.lastModified() }
        var freed = 0L
        val target = (totalMB - MAX_CACHE_SIZE_MB / 2) * 1024 * 1024
        for (file in files) {
            if (freed >= target) break
            freed += file.length()
            file.delete()
        }
    }

    /** Clear entire cache */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Cache cleared")
    }

    /** Get cache stats */
    fun getStats(): CacheStats {
        val files = cacheDir.listFiles { f -> f.name.startsWith("tts_") } ?: emptyArray()
        val totalBytes = files.sumOf { it.length() }
        return CacheStats(
            fileCount = files.size,
            totalSizeMB = totalBytes / (1024.0 * 1024.0),
        )
    }

    data class CacheStats(val fileCount: Int, val totalSizeMB: Double)

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
