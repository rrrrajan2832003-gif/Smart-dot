package com.example

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Performs 100% free, offline-capable single and bulk screen text translation
 * using Google ML Kit On-Device Translation + Language Identification
 * (no paid APIs, no API keys, no screenshots).
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
    private val identifyLanguageFn: (suspend (text: String) -> String)? = null
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

    suspend fun translateSingleText(
        text: String,
        targetLanguageName: String,
        onModelDownloading: () -> Unit = {}
    ): String {
        return translateTextDetailed(
            text = text,
            targetLanguageName = targetLanguageName,
            autoFlipIfSameAsTarget = false,
            onModelDownloading = onModelDownloading
        ).translatedText
    }

    suspend fun translateNodesBulk(
        nodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        targetLanguageName: String,
        onModelDownloading: () -> Unit = {}
    ): List<TranslatedNode> = withContext(Dispatchers.Default) {
        if (nodes.isEmpty()) return@withContext emptyList()

        val cache = HashMap<String, TranslationResult>(nodes.size)
        val results = ArrayList<TranslatedNode>(nodes.size)
        var notifiedDownloading = false

        for (node in nodes) {
            val clean = node.text.trim()
            if (clean.isEmpty()) continue

            val cached = cache[clean]
            val result = if (cached != null) {
                cached
            } else {
                translateTextDetailed(
                    text = clean,
                    targetLanguageName = targetLanguageName,
                    autoFlipIfSameAsTarget = true,
                    onModelDownloading = {
                        if (!notifiedDownloading) {
                            notifiedDownloading = true
                            onModelDownloading()
                        }
                    }
                ).also { cache[clean] = it }
            }

            results.add(TranslatedNode(node = node, result = result))
        }

        results
    }

    suspend fun translateTextDetailed(
        text: String,
        targetLanguageName: String,
        autoFlipIfSameAsTarget: Boolean = true,
        onModelDownloading: () -> Unit = {}
    ): TranslationResult = withContext(Dispatchers.Default) {
        val clean = text.trim()
        val requestedTargetCode = resolveTargetLanguageCode(targetLanguageName)
        if (clean.isEmpty()) {
            return@withContext TranslationResult("", "", requestedTargetCode, requestedTargetCode)
        }

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

    private suspend fun detectSourceLanguageCode(text: String, fallbackTargetCode: String): String {
        identifyLanguageFn?.let { fn ->
            val custom = fn(text)
            TranslateLanguage.fromLanguageTag(custom)?.let { return it }
        }

        if (text.any { it in '\u0900'..'\u097F' }) return TranslateLanguage.HINDI
        if (text.any { it in '\u3040'..'\u30FF' }) return TranslateLanguage.JAPANESE
        if (text.any { it in '\u4E00'..'\u9FFF' }) return TranslateLanguage.CHINESE
        if (text.any { it in '\u0400'..'\u04FF' }) return TranslateLanguage.RUSSIAN

        val detectedTag = runCatching {
            languageIdentifier.identifyLanguage(text).await()
        }.getOrNull()

        if (!detectedTag.isNullOrBlank() && detectedTag != "und") {
            val mlKitCode = TranslateLanguage.fromLanguageTag(detectedTag)
            if (mlKitCode != null) {
                return mlKitCode
            }
        }

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
            "French",
            "German",
            "Japanese",
            "Chinese",
            "Italian",
            "Russian"
        )

        fun resolveTargetLanguageCode(languageName: String): String {
            return when (languageName.trim().lowercase()) {
                "english", "en" -> TranslateLanguage.ENGLISH
                "hindi", "hi" -> TranslateLanguage.HINDI
                "spanish", "es" -> TranslateLanguage.SPANISH
                "french", "fr" -> TranslateLanguage.FRENCH
                "german", "de" -> TranslateLanguage.GERMAN
                "japanese", "ja" -> TranslateLanguage.JAPANESE
                "chinese", "zh" -> TranslateLanguage.CHINESE
                "italian", "it" -> TranslateLanguage.ITALIAN
                "russian", "ru" -> TranslateLanguage.RUSSIAN
                else -> TranslateLanguage.fromLanguageTag(languageName.trim()) ?: TranslateLanguage.HINDI
            }
        }
    }
}
