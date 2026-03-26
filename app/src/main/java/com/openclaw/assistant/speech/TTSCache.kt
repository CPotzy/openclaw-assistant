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
     * Canonicalize a response to a known fixed phrase for maximum cache hits.
     * Maps varied AI responses like "The lamp has been turned off. Haribol."
     * to a single canonical phrase "Done. Lamp is off."
     * Returns the original text if no pattern matches.
     */
    fun canonicalize(text: String): String {
        val t = text.lowercase().trim()
            .replace(Regex("[.!,]+$"), "")  // strip trailing punctuation
            .replace("haribol", "").replace("prabhu", "").replace("caleb", "")
            .replace(Regex("\\s+"), " ").trim()

        // Device state confirmations
        val onPatterns = listOf("is now on", "has been turned on", "turned on", "is on", "are now on", "have been turned on", "are on")
        val offPatterns = listOf("is now off", "has been turned off", "turned off", "is off", "are now off", "have been turned off", "are off")

        data class DeviceMapping(val keywords: List<String>, val name: String)
        val devices = listOf(
            DeviceMapping(listOf("lamp"), "Lamp"),
            DeviceMapping(listOf("living room"), "Living room lights"),
            DeviceMapping(listOf("kitchen"), "Kitchen lights"),
            DeviceMapping(listOf("all light", "all the light", "every light"), "All lights"),
            DeviceMapping(listOf("light"), "Lights"),
            DeviceMapping(listOf("air con", "ac ", "a.c."), "Air conditioning"),
            DeviceMapping(listOf("fan light", "fan lamp"), "Fan light"),
            DeviceMapping(listOf("fan"), "Fan"),
        )

        for (device in devices) {
            if (device.keywords.any { t.contains(it) }) {
                for (p in onPatterns) {
                    if (t.contains(p)) {
                        Log.d(TAG, "Canonicalized: '$text' -> 'Done. ${device.name} is on.'")
                        return "Done. ${device.name} is on."
                    }
                }
                for (p in offPatterns) {
                    if (t.contains(p)) {
                        Log.d(TAG, "Canonicalized: '$text' -> 'Done. ${device.name} is off.'")
                        return "Done. ${device.name} is off."
                    }
                }
                if (t.contains("brightness")) {
                    return "Done. Brightness set."
                }
            }
        }

        // Music confirmations
        if (t.contains("now playing") || t.contains("playing") && (t.contains("kirtan") || t.contains("music"))) {
            return "Now playing."
        }
        if (t.contains("paused")) return "Paused."
        if (t.contains("stopped") || t.contains("music stopped")) return "Stopped."
        if (t.contains("volume") && (t.contains("set") || t.contains("changed") || t.contains("adjusted"))) return "Done. Volume set."

        // Error messages
        if (t.contains("timed out")) return "Sorry, please try again."
        if (t.contains("trouble connecting") || t.contains("could not connect") || t.contains("unable to connect")) return "Sorry, that did not work."

        // No match — return original
        return text
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
