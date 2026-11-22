package com.example

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performs 100% free screen & phrase translation:
 * - Primary Fast Mode (`preferOnline = true`): Instant parallel HTTP translation via Google Translate free endpoint
 *   (`translate.googleapis.com/translate_a/single?client=gtx`), zero model download wait (< 1 second).
 * - Automatic Fallback / Offline Mode: Free on-device Google ML Kit Translation + Language Identification.
 */
class ScreenTranslateHelper(
    private val languageIdClientFactory: () -> LanguageIdentifier = {
        LanguageIdentification.getClient()
    },
    private val translatorFactory: (sourceCode: String, targetCode: String) -> Translator = { src, dst ->
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(dst)
            .build()
        Translation.getClient(options)
    },
    private val translateBlockFn: (suspend (sourceCode: String, targetCode: String, text: String, onDownloadStarted: () -> Unit) -> String)? = null,
    private val identifyLanguageFn: (suspend (text: String) -> String)? = null,
    private val onlineTranslateFn: (suspend (targetCode: String, text: String) -> Pair<String, String>?)? = null
) {

    data class TranslationResult(
        val originalText: String,
        val translatedText: String,
        val sourceLanguageTag: String,
        val targetLanguageTag: String
    )

    data class TranslatedNode(
        val node: SmartDotAccessibilityService.ScreenTextNode,
        val result: TranslationResult
    )

    private val languageIdentifier: LanguageIdentifier by lazy(languageIdClientFactory)
    private val translators = ConcurrentHashMap<String, Translator>()
    private val downloadedPairs = ConcurrentHashMap.newKeySet<String>()
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    suspend fun translateSingleText(
        text: String,
        targetLanguageName: String,
        preferOnline: Boolean = true,
        onModelDownloading: () -> Unit = {}
    ): String {
        return translateTextDetailed(
            text = text,
            targetLanguageName = targetLanguageName,
            autoFlipIfSameAsTarget = false,
            preferOnline = preferOnline,
            onModelDownloading = onModelDownloading
        ).translatedText
    }

    suspend fun translateNodesBulk(
        nodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        targetLanguageName: String,
        preferOnline: Boolean = true,
        onModelDownloading: () -> Unit = {}
    ): List<TranslatedNode> = coroutineScope {
        if (nodes.isEmpty()) return@coroutineScope emptyList()

        val validNodes = nodes.mapNotNull { node ->
            val clean = node.text.trim()
            if (clean.isEmpty()) null else node to clean
        }
        if (validNodes.isEmpty()) return@coroutineScope emptyList()

        val uniqueTexts = validNodes.map { it.second }.distinct()
        val cache = ConcurrentHashMap<String, TranslationResult>(uniqueTexts.size)
        val notifiedDownloading = AtomicBoolean(false)
        val notifyOnce: () -> Unit = {
            if (notifiedDownloading.compareAndSet(false, true)) {
                onModelDownloading()
            }
        }

        if (preferOnline) {
            // Translate all unique screen items concurrently for <1s total turnaround
            val concurrencyGate = Semaphore(6)
            uniqueTexts.map { unique ->
                async(Dispatchers.Default) {
                    concurrencyGate.withPermit {
                        val res = translateTextDetailed(
                            text = unique,
                            targetLanguageName = targetLanguageName,
                            autoFlipIfSameAsTarget = true,
                            preferOnline = true,
                            onModelDownloading = notifyOnce
                        )
                        cache[unique] = res
                    }
                }
            }.awaitAll()
        } else {
            for (unique in uniqueTexts) {
                cache[unique] = translateTextDetailed(
                    text = unique,
                    targetLanguageName = targetLanguageName,
                    autoFlipIfSameAsTarget = true,
                    preferOnline = false,
                    onModelDownloading = notifyOnce
                )
            }
        }

        validNodes.mapNotNull { (node, clean) ->
            val res = cache[clean] ?: return@mapNotNull null
            TranslatedNode(node = node, result = res)
        }
    }

    suspend fun translateTextDetailed(
        text: String,
        targetLanguageName: String,
        autoFlipIfSameAsTarget: Boolean = true,
        preferOnline: Boolean = true,
        onModelDownloading: () -> Unit = {}
    ): TranslationResult = withContext(Dispatchers.Default) {
        val clean = text.trim()
        val requestedTargetCode = resolveTargetLanguageCode(targetLanguageName)
        if (clean.isEmpty()) {
            return@withContext TranslationResult("", "", requestedTargetCode, requestedTargetCode)
        }

        // Fast primary path: instant online translation with zero model download wait
        if (preferOnline) {
            val onlineAttempt = runCatching {
                translateOnlineWithAutoFlip(clean, requestedTargetCode, autoFlipIfSameAsTarget)
            }.getOrNull()

            if (onlineAttempt != null && onlineAttempt.translatedText.isNotBlank()) {
                return@withContext onlineAttempt
            }
        }

        // Fallback (or explicit Offline mode): on-device ML Kit Translation
        val sourceCode = detectSourceLanguageCode(clean, fallbackTargetCode = requestedTargetCode)
        val effectiveTargetCode = if (autoFlipIfSameAsTarget && sourceCode == requestedTargetCode) {
            if (requestedTargetCode == TranslateLanguage.ENGLISH) {
                TranslateLanguage.HINDI
            } else {
                TranslateLanguage.ENGLISH
            }
        } else {
            requestedTargetCode
        }

        if (sourceCode == effectiveTargetCode) {
            return@withContext TranslationResult(clean, clean, sourceCode, effectiveTargetCode)
        }

        val translated = translateWithClient(sourceCode, effectiveTargetCode, clean, onModelDownloading)
        TranslationResult(
            originalText = clean,
            translatedText = translated.ifBlank { clean },
            sourceLanguageTag = sourceCode,
            targetLanguageTag = effectiveTargetCode
        )
    }

    private suspend fun translateOnlineWithAutoFlip(
        clean: String,
        requestedTargetCode: String,
        autoFlipIfSameAsTarget: Boolean
    ): TranslationResult? {
        val firstPass = performOnlineRequest(requestedTargetCode, clean) ?: return null
        val (firstTranslated, detectedSrc) = firstPass

        if (autoFlipIfSameAsTarget && detectedSrc.equals(requestedTargetCode, ignoreCase = true)) {
            val flippedTarget = if (requestedTargetCode == TranslateLanguage.ENGLISH) {
                TranslateLanguage.HINDI
            } else {
                TranslateLanguage.ENGLISH
            }
            val secondPass = performOnlineRequest(flippedTarget, clean)
            if (secondPass != null) {
                return TranslationResult(
                    originalText = clean,
                    translatedText = secondPass.first.ifBlank { clean },
                    sourceLanguageTag = detectedSrc.ifBlank { requestedTargetCode },
                    targetLanguageTag = flippedTarget
                )
            }
        }

        return TranslationResult(
            originalText = clean,
            translatedText = firstTranslated.ifBlank { clean },
            sourceLanguageTag = detectedSrc.ifBlank { "auto" },
            targetLanguageTag = requestedTargetCode
        )
    }

    private suspend fun performOnlineRequest(targetCode: String, text: String): Pair<String, String>? {
        onlineTranslateFn?.let { fn ->
            return fn(targetCode, text)
        }

        return withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetCode&dt=t&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                parseGoogleFreeTranslateJson(body)
            }
        }
    }

    internal suspend fun detectSourceLanguageCode(text: String, fallbackTargetCode: String): String {
        identifyLanguageFn?.let { fn ->
            val custom = fn(text)
            TranslateLanguage.fromLanguageTag(custom)?.let { return it }
        }

        val detectedTag = runCatching {
            languageIdentifier.identifyLanguage(text).await()
        }.getOrNull()

        if (!detectedTag.isNullOrBlank() && detectedTag != "und") {
            val mlKitCode = TranslateLanguage.fromLanguageTag(detectedTag)
            if (mlKitCode != null) {
                return mlKitCode
            }
        }

        // Script fallbacks if short text returns "und" on device
        if (text.any { it in '\u0900'..'\u097F' }) return TranslateLanguage.HINDI
        if (text.any { it in '\u0980'..'\u09FF' }) return TranslateLanguage.BENGALI
        if (text.any { it in '\u0600'..'\u06FF' }) return TranslateLanguage.URDU
        if (text.any { it in '\u0B80'..'\u0BFF' }) return TranslateLanguage.TAMIL
        if (text.any { it in '\u0C00'..'\u0C7F' }) return TranslateLanguage.TELUGU
        if (text.any { it in '\u0A80'..'\u0AFF' }) return TranslateLanguage.GUJARATI
        if (text.any { it in '\uAC00'..'\uD7AF' }) return TranslateLanguage.KOREAN
        if (text.any { it in '\u3040'..'\u30FF' }) return TranslateLanguage.JAPANESE
        if (text.any { it in '\u4E00'..'\u9FFF' }) return TranslateLanguage.CHINESE
        if (text.any { it in '\u0400'..'\u04FF' }) return TranslateLanguage.RUSSIAN

        return if (fallbackTargetCode == TranslateLanguage.ENGLISH) {
            TranslateLanguage.HINDI
        } else {
            TranslateLanguage.ENGLISH
        }
    }

    private suspend fun translateWithClient(
        sourceCode: String,
        targetCode: String,
        text: String,
        onModelDownloading: () -> Unit
    ): String {
        translateBlockFn?.let { fn ->
            return fn(sourceCode, targetCode, text, onModelDownloading)
        }

        val pairKey = "$sourceCode->$targetCode"
        val translator = translators.getOrPut(pairKey) {
            translatorFactory(sourceCode, targetCode)
        }

        if (!downloadedPairs.contains(pairKey)) {
            onModelDownloading()
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()
            downloadedPairs.add(pairKey)
        }

        return translator.translate(text).await()
    }

    fun close() {
        runCatching { languageIdentifier.close() }
        for (translator in translators.values) {
            runCatching { translator.close() }
        }
        translators.clear()
        downloadedPairs.clear()
    }

    companion object {
        val SUPPORTED_LANGUAGES = listOf(
            "Hindi",
            "English",
            "Spanish",
            "Urdu",
            "Bengali",
            "Marathi",
            "Tamil",
            "Telugu",
            "Gujarati",
            "Arabic",
            "French",
            "German",
            "Portuguese",
            "Japanese",
            "Chinese",
            "Korean",
            "Italian",
            "Russian"
        )

        fun parseGoogleFreeTranslateJson(jsonBody: String): Pair<String, String>? {
            val root = JSONArray(jsonBody)
            val sentences = root.optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val seg = sentences.optJSONArray(i) ?: continue
                val piece = seg.optString(0, "")
                sb.append(piece)
            }
            val translated = sb.toString().trim()
            if (translated.isEmpty()) return null
            val detectedLang = root.optString(2, "auto")
            return translated to detectedLang
        }

        fun resolveTargetLanguageCode(languageName: String): String {
            return when (languageName.trim().lowercase()) {
                "english", "en" -> TranslateLanguage.ENGLISH
                "hindi", "hi" -> TranslateLanguage.HINDI
                "spanish", "es" -> TranslateLanguage.SPANISH
                "urdu", "ur" -> TranslateLanguage.URDU
                "bengali", "bn" -> TranslateLanguage.BENGALI
                "marathi", "mr" -> TranslateLanguage.MARATHI
                "tamil", "ta" -> TranslateLanguage.TAMIL
                "telugu", "te" -> TranslateLanguage.TELUGU
                "gujarati", "gu" -> TranslateLanguage.GUJARATI
                "arabic", "ar" -> TranslateLanguage.ARABIC
                "french", "fr" -> TranslateLanguage.FRENCH
                "german", "de" -> TranslateLanguage.GERMAN
                "portuguese", "pt" -> TranslateLanguage.PORTUGUESE
                "japanese", "ja" -> TranslateLanguage.JAPANESE
                "chinese", "zh" -> TranslateLanguage.CHINESE
                "korean", "ko" -> TranslateLanguage.KOREAN
                "italian", "it" -> TranslateLanguage.ITALIAN
                "russian", "ru" -> TranslateLanguage.RUSSIAN
                else -> TranslateLanguage.fromLanguageTag(languageName.trim()) ?: TranslateLanguage.HINDI
            }
        }
    }
}
