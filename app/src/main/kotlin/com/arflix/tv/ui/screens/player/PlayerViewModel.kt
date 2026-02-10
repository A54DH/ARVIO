package com.arflix.tv.ui.screens.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.data.model.Subtitle
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.WatchHistoryEntry
import com.arflix.tv.data.repository.WatchHistoryRepository
import com.arflix.tv.util.Constants
import com.arflix.tv.util.settingsDataStore
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val isLoading: Boolean = true,
    val isLoadingStreams: Boolean = false,
    val isLoadingSubtitles: Boolean = false,
    val title: String = "",
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val streams: List<StreamSource> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val selectedStream: StreamSource? = null,
    val selectedStreamUrl: String? = null,
    val selectedSubtitle: Subtitle? = null,
    val savedPosition: Long = 0,
    val preferredAudioLanguage: String = "en",
    val error: String? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val streamRepository: StreamRepository,
    private val traktRepository: TraktRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val tmdbApi: TmdbApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var currentMediaType: MediaType = MediaType.MOVIE
    private var currentMediaId: Int = 0
    private var currentSeason: Int? = null
    private var currentEpisode: Int? = null
    private var currentTitle: String = ""
    private var currentPoster: String? = null
    private var currentBackdrop: String? = null
    private var currentEpisodeTitle: String? = null
    private var currentOriginalLanguage: String? = null
    private var currentGenreIds: List<Int> = emptyList()
    private var currentItemTitle: String = ""
    private var currentTvdbId: Int? = null  // For anime Kitsu mapping
    private var currentStartPositionMs: Long? = null
    private var lastScrobbleTime: Long = 0
    private var lastWatchHistorySaveTime: Long = 0
    private var lastIsPlaying: Boolean = false
    private var hasMarkedWatched: Boolean = false

    private val SCROBBLE_UPDATE_INTERVAL_MS = 20_000L
    private val WATCH_HISTORY_UPDATE_INTERVAL_MS = 15_000L

    private val DEFAULT_SUBTITLE_KEY = stringPreferencesKey("default_subtitle")
    private val SUBTITLE_USAGE_KEY = stringPreferencesKey("subtitle_usage_v1")
    private val gson = Gson()
    private val knownLanguageCodes = setOf(
        "en", "es", "fr", "de", "it", "pt", "nl", "ru", "zh", "ja", "ko",
        "ar", "hi", "tr", "pl", "sv", "no", "da", "fi", "el", "cs", "hu",
        "ro", "th", "vi", "id", "he"
    )

    fun loadMedia(
        mediaType: MediaType,
        mediaId: Int,
        seasonNumber: Int?,
        episodeNumber: Int?,
        providedStreamUrl: String?,
        startPositionMs: Long?
    ) {
        currentMediaType = mediaType
        currentMediaId = mediaId
        currentSeason = seasonNumber
        currentEpisode = episodeNumber
        currentStartPositionMs = startPositionMs
        currentEpisodeTitle = null
        hasMarkedWatched = false
        lastIsPlaying = false
        lastScrobbleTime = 0
        lastWatchHistorySaveTime = 0
        val cachedItem = mediaRepository.getCachedItem(mediaType, mediaId)
        currentOriginalLanguage = cachedItem?.originalLanguage
        currentGenreIds = cachedItem?.genreIds ?: emptyList()
        currentItemTitle = cachedItem?.title ?: ""

        viewModelScope.launch {
            val preferredAudioLanguage = resolvePreferredAudioLanguage()
            _uiState.value = PlayerUiState(
                isLoading = true,
                isLoadingStreams = true,
                preferredAudioLanguage = preferredAudioLanguage
            )

            // If stream URL provided, use it directly - INSTANT
            if (providedStreamUrl != null) {
                val savedPosition = resolveResumePositionMs(
                    mediaType = mediaType,
                    mediaId = mediaId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    navigationStartPositionMs = startPositionMs
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingStreams = false,
                    selectedStreamUrl = providedStreamUrl,
                    savedPosition = savedPosition
                )
                // Fetch metadata in background
                launch { fetchMediaMetadata(mediaType, mediaId) }
                return@launch
            }

            try {
                // INSTANT MODE: Fetch streams in parallel with metadata
                // Start metadata fetch in background (non-blocking)
                launch { fetchMediaMetadata(mediaType, mediaId) }

                // Fetch saved position from watch history (for resume playback)
                val savedPositionDeferred = async {
                    resolveResumePositionMs(
                        mediaType = mediaType,
                        mediaId = mediaId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        navigationStartPositionMs = startPositionMs
                    )
                }

                // Get IMDB ID and TVDB ID as fast as possible
                // Need TVDB ID for anime Kitsu mapping, so always fetch external IDs
                val cachedImdbId = mediaRepository.getCachedImdbId(mediaType, mediaId)
                val externalIds = resolveExternalIds(mediaType, mediaId)
                currentTvdbId = externalIds.tvdbId

                val imdbId = cachedImdbId ?: externalIds.imdbId
                if (imdbId.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoadingStreams = false,
                        error = "Unable to resolve IMDB ID. Try again."
                    )
                    return@launch
                }
                if (cachedImdbId == null) {
                    mediaRepository.cacheImdbId(mediaType, mediaId, imdbId)
                }

                // Fetch streams IMMEDIATELY
                val result = if (mediaType == MediaType.MOVIE) {
                    streamRepository.resolveMovieStreams(imdbId)
                } else {
                    streamRepository.resolveEpisodeStreams(
                        imdbId = imdbId,
                        season = seasonNumber ?: 1,
                        episode = episodeNumber ?: 1,
                        tmdbId = mediaId,
                        tvdbId = currentTvdbId,  // Pass tvdbId for anime Kitsu mapping
                        genreIds = currentGenreIds,
                        originalLanguage = currentOriginalLanguage,
                        title = currentItemTitle
                    )
                }


                // Show all streams with valid URLs, sorted same as StreamSelector UI
                val allStreams = result.streams
                    .filter { stream -> stream.url != null }
                    .sortedWith(
                        compareByDescending<StreamSource> { playbackPriorityScore(it) }
                            .thenByDescending { qualityScore(it.quality) }
                            .thenByDescending { parseSize(it.size) }
                            .thenBy { it.source }
                    )

                // Get saved position for resume playback
                val savedPosition = savedPositionDeferred.await()

                // Determine appropriate error message
                val errorMessage = if (allStreams.isEmpty()) {
                    val streamingAddonsCount = streamRepository.installedAddons.first()
                        .count { it.isEnabled && it.type != com.arflix.tv.data.model.AddonType.SUBTITLE }

                    if (streamingAddonsCount == 0) {
                        "No streaming addons configured. Go to Settings > Addons to add Torrentio or another streaming addon."
                    } else {
                        "No streams found for this content. The addons may not have sources for this title."
                    }
                } else null

                // Update UI with sorted streams
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingStreams = false,
                    streams = allStreams,
                    subtitles = result.subtitles,
                    savedPosition = savedPosition,
                    error = errorMessage
                )

                // Auto-select first stream (same as top of source list)
                if (allStreams.isNotEmpty()) {
                    // Debug: log top 3 streams so we can verify sort order
                    allStreams.take(3).forEachIndexed { idx, s ->
                        Log.d("PlayerVM", "AutoPick sorted[$idx]: q=${s.quality} size=${s.size} parsed=${parseSize(s.size)} src=${s.source.take(60)}")
                    }
                    Log.d("PlayerVM", "AutoPick selecting: ${allStreams.first().source.take(80)}")
                    selectStream(allStreams.first())
                }

                // Apply subtitle preference in background (non-blocking)
                launch {
                    val preferredSub = getDefaultSubtitle()
                    applyPreferredSubtitle(preferredSub, result.subtitles, currentOriginalLanguage)
                    _uiState.value = _uiState.value.copy(isLoadingSubtitles = false)
                }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingStreams = false,
                    error = e.message
                )
            }
        }
    }

    /**
     * Fetch media metadata in background (non-blocking)
     */
    private suspend fun fetchMediaMetadata(mediaType: MediaType, mediaId: Int) {
        try {
            val details = if (mediaType == MediaType.TV) {
                tmdbApi.getTvDetails(mediaId, Constants.TMDB_API_KEY)
            } else {
                tmdbApi.getMovieDetails(mediaId, Constants.TMDB_API_KEY)
            }

            val logoUrl = try {
                mediaRepository.getLogoUrl(mediaType, mediaId)
            } catch (e: Exception) { null }

            val title: String
            val backdropUrl: String?
            val posterUrl: String?

            if (mediaType == MediaType.TV) {
                val tvDetails = details as com.arflix.tv.data.api.TmdbTvDetails
                title = tvDetails.name
                backdropUrl = tvDetails.backdropPath?.let { "${Constants.BACKDROP_BASE}$it" }
                posterUrl = tvDetails.posterPath?.let { "${Constants.IMAGE_BASE}$it" }
                currentOriginalLanguage = tvDetails.originalLanguage ?: currentOriginalLanguage

                // Keep episode title aligned with saved progress rows for TV playback sessions.
                val season = currentSeason
                val episode = currentEpisode
                if (season != null && episode != null) {
                    currentEpisodeTitle = runCatching {
                        val seasonDetails = tmdbApi.getTvSeason(mediaId, season, Constants.TMDB_API_KEY)
                        seasonDetails.episodes.firstOrNull { it.episodeNumber == episode }?.name
                    }.getOrNull()
                }
            } else {
                val movieDetails = details as com.arflix.tv.data.api.TmdbMovieDetails
                title = movieDetails.title
                backdropUrl = movieDetails.backdropPath?.let { "${Constants.BACKDROP_BASE}$it" }
                posterUrl = movieDetails.posterPath?.let { "${Constants.IMAGE_BASE}$it" }
                currentOriginalLanguage = movieDetails.originalLanguage ?: currentOriginalLanguage
            }

            // Store info for watch history
            currentTitle = title
            currentPoster = posterUrl
            currentBackdrop = backdropUrl

            // Update UI with metadata
            _uiState.value = _uiState.value.copy(
                title = title,
                backdropUrl = backdropUrl,
                logoUrl = logoUrl,
                preferredAudioLanguage = resolvePreferredAudioLanguage()
            )
        } catch (e: Exception) {
            // Failed to fetch metadata
        }
    }

    private data class ExternalIds(val imdbId: String?, val tvdbId: Int?)

    private suspend fun resolveExternalIds(mediaType: MediaType, mediaId: Int): ExternalIds {
        return try {
            val ids = when (mediaType) {
                MediaType.MOVIE -> tmdbApi.getMovieExternalIds(mediaId, Constants.TMDB_API_KEY)
                MediaType.TV -> tmdbApi.getTvExternalIds(mediaId, Constants.TMDB_API_KEY)
            }
            ExternalIds(imdbId = ids.imdbId, tvdbId = ids.tvdbId)
        } catch (_: Exception) {
            ExternalIds(null, null)
        }
    }

    private suspend fun getDefaultSubtitle(): String {
        return try {
            val prefs = context.settingsDataStore.data.first()
            // Default to Off - user must explicitly set preferred subtitle in settings
            prefs[DEFAULT_SUBTITLE_KEY] ?: "Off"
        } catch (_: Exception) {
            "Off"
        }
    }

    private fun applyPreferredSubtitle(preference: String, subtitles: List<Subtitle>, fallbackLanguage: String?) {
        if (preference.equals("Off", ignoreCase = true)) {
            return
        }

        // Normalize language codes for matching
        val normalizedPref = normalizeLanguage(preference)
        val normalizedFallback = fallbackLanguage
            ?.let { normalizeLanguage(it) }
            ?.takeIf { it.isNotBlank() && it != normalizedPref }

        fun findMatch(target: String): Subtitle? {
            return subtitles.firstOrNull { sub ->
                val normalizedSubLang = normalizeLanguage(sub.lang)
                normalizedSubLang == target
            } ?: subtitles.firstOrNull { sub ->
                val normalizedSubLabel = normalizeLanguage(sub.label)
                normalizedSubLabel == target
            }
        }

        // Prioritize: 1. Exact match, 2. Contains match, 3. English fallback if preference is English
        val match = findMatch(normalizedPref)
            ?: if (normalizedPref == "en") {
                // Fallback for English: Try "eng", "en", "English"
                subtitles.firstOrNull { sub ->
                    sub.lang.equals("en", ignoreCase = true) ||
                        sub.lang.equals("eng", ignoreCase = true) ||
                        sub.label.contains("English", ignoreCase = true)
                }
            } else null
            ?: normalizedFallback?.let { findMatch(it) }

        if (match != null) {
            _uiState.value = _uiState.value.copy(selectedSubtitle = match)
        }
    }

    private fun qualityScore(quality: String): Int {
        return when {
            quality.contains("4K", ignoreCase = true) || quality.contains("2160p", ignoreCase = true) -> 4
            quality.contains("1080p", ignoreCase = true) -> 3
            quality.contains("720p", ignoreCase = true) -> 2
            quality.contains("480p", ignoreCase = true) -> 1
            else -> 0
        }
    }

    /**
     * Prioritize streams for real-world TV playback stability and startup speed.
     * This reduces auto-picking very heavy remux/DV streams that often fail or buffer.
     */
    private fun playbackPriorityScore(stream: StreamSource): Int {
        val text = buildString {
            append(stream.source)
            append(' ')
            append(stream.addonName)
            stream.behaviorHints?.filename?.let {
                append(' ')
                append(it)
            }
        }.lowercase()

        var score = qualityScore(stream.quality) * 100

        val sizeBytes = parseSize(stream.size)
        score += when {
            sizeBytes <= 0L -> 30
            sizeBytes <= 8L * 1024 * 1024 * 1024 -> 80
            sizeBytes <= 15L * 1024 * 1024 * 1024 -> 55
            sizeBytes <= 25L * 1024 * 1024 * 1024 -> 20
            sizeBytes <= 40L * 1024 * 1024 * 1024 -> -10
            else -> -90
        }

        if (text.contains("remux")) score -= 120
        if (text.contains("dolby vision") || text.contains(" dovi") || text.contains(" dv ")) score -= 120
        if (text.contains("cam") || text.contains("hdcam") || text.contains("telesync")) score -= 300
        if (text.contains("web-dl") || text.contains("webrip")) score += 45
        if (text.contains("x264") || text.contains("h264")) score += 15

        return score
    }

    private fun resolvePreferredAudioLanguage(): String {
        val normalized = currentOriginalLanguage
            ?.let { normalizeLanguage(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "en"
        return if (normalized in knownLanguageCodes) normalized else "en"
    }

    private fun streamLanguageScore(stream: StreamSource, preferredLanguage: String): Int {
        val preferred = normalizeLanguage(preferredLanguage).ifBlank { "en" }
        val combined = buildString {
            append(stream.source)
            append(' ')
            append(stream.addonName)
            stream.behaviorHints?.filename?.let {
                append(' ')
                append(it)
            }
        }
        val codes = extractLanguageCodes(combined)
        val hasMulti = hasMultiLanguageHint(combined)
        return when {
            codes.contains(preferred) -> 2
            codes.isEmpty() || hasMulti -> 1
            else -> 0
        }
    }

    private fun pickPreferredStream(
        streams: List<StreamSource>,
        preferredLanguage: String
    ): StreamSource? {
        if (streams.isEmpty()) return null

        val maxSizeBytes = 20L * 1024 * 1024 * 1024 // 20GB - anything larger is likely a season pack

        // Step 1: Filter out season packs (>20GB) from all candidates
        val candidates = streams.filter {
            val size = parseSize(it.size)
            size == 0L || size < maxSizeBytes // 0 = unknown size, keep those
        }

        // Step 2: From reasonable candidates, find highest quality and pick first in addon order
        if (candidates.isNotEmpty()) {
            val bestQuality = candidates.maxOf { qualityScore(it.quality) }
            return candidates.first { qualityScore(it.quality) == bestQuality }
        }

        // Step 3: Fallback if ALL streams are >20GB — just pick first stream in addon order
        return streams.firstOrNull()
    }

    // Robust size string parser - identical to StreamSelector's parseSizeString()
    // Handles comma decimals ("5,2 GB"), GiB notation, extra spaces, etc.
    private fun parseSize(sizeStr: String): Long {
        if (sizeStr.isBlank()) return 0L

        // Normalize: uppercase, replace comma with dot, remove extra spaces
        val normalized = sizeStr.uppercase()
            .replace(",", ".")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Pattern 1: "15.2 GB", "6GB", "1.5 TB" etc.
        val pattern1 = Regex("""(\d+(?:\.\d+)?)\s*(TB|GB|MB|KB)""")
        pattern1.find(normalized)?.let { match ->
            val number = match.groupValues[1].toDoubleOrNull() ?: return@let
            val unit = match.groupValues[2]
            return calcBytes(number, unit)
        }

        // Pattern 2: Numbers with GiB/MiB notation
        val pattern2 = Regex("""(\d+(?:\.\d+)?)\s*(TIB|GIB|MIB|KIB)""")
        pattern2.find(normalized)?.let { match ->
            val number = match.groupValues[1].toDoubleOrNull() ?: return@let
            val unit = match.groupValues[2].replace("IB", "B")
            return calcBytes(number, unit)
        }

        // Pattern 3: Just a number (assume bytes)
        val pattern3 = Regex("""^(\d+(?:\.\d+)?)$""")
        pattern3.find(normalized)?.let { match ->
            return match.groupValues[1].toLongOrNull() ?: 0L
        }

        return 0L
    }

    private fun calcBytes(number: Double, unit: String): Long {
        return when (unit) {
            "TB" -> (number * 1024.0 * 1024.0 * 1024.0 * 1024.0).toLong()
            "GB" -> (number * 1024.0 * 1024.0 * 1024.0).toLong()
            "MB" -> (number * 1024.0 * 1024.0).toLong()
            "KB" -> (number * 1024.0).toLong()
            else -> number.toLong()
        }
    }

    private fun extractLanguageCodes(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val tokens = Regex("[A-Za-z]+").findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return emptySet()

        val codes = mutableSetOf<String>()
        for (token in tokens) {
            if (token.length < 2) continue
            val normalized = normalizeLanguage(token)
            if (normalized in knownLanguageCodes) {
                codes.add(normalized)
                continue
            }
            if (token.length >= 3) {
                val prefix3 = normalizeLanguage(token.take(3))
                if (prefix3 in knownLanguageCodes) {
                    codes.add(prefix3)
                    continue
                }
            }
            val prefix2 = normalizeLanguage(token.take(2))
            if (prefix2 in knownLanguageCodes) {
                codes.add(prefix2)
            }
        }
        return codes
    }

    private fun hasMultiLanguageHint(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("multi") ||
            lower.contains("dual audio") ||
            lower.contains("dual-audio") ||
            lower.contains("multi audio") ||
            lower.contains("multi-audio")
    }

    /**
     * Normalize language codes to a standard format for matching
     * Maps: "English" -> "en", "eng" -> "en", "Spanish" -> "es", etc.
     */
    private fun normalizeLanguage(lang: String): String {
        val lowerLang = lang.lowercase().trim()
        return when {
            // Full names
            lowerLang == "english" || lowerLang.startsWith("english") -> "en"
            lowerLang == "spanish" || lowerLang.startsWith("spanish") || lowerLang == "espanol" -> "es"
            lowerLang == "french" || lowerLang.startsWith("french") || lowerLang == "francais" -> "fr"
            lowerLang == "german" || lowerLang.startsWith("german") || lowerLang == "deutsch" -> "de"
            lowerLang == "italian" || lowerLang.startsWith("italian") -> "it"
            lowerLang == "portuguese" || lowerLang.startsWith("portuguese") -> "pt"
            lowerLang == "dutch" || lowerLang.startsWith("dutch") -> "nl"
            lowerLang == "russian" || lowerLang.startsWith("russian") -> "ru"
            lowerLang == "chinese" || lowerLang.startsWith("chinese") -> "zh"
            lowerLang == "japanese" || lowerLang.startsWith("japanese") || lowerLang == "jp" || lowerLang == "jap" -> "ja"
            lowerLang == "korean" || lowerLang.startsWith("korean") -> "ko"
            lowerLang == "arabic" || lowerLang.startsWith("arabic") -> "ar"
            lowerLang == "hindi" || lowerLang.startsWith("hindi") -> "hi"
            lowerLang == "turkish" || lowerLang.startsWith("turkish") -> "tr"
            lowerLang == "polish" || lowerLang.startsWith("polish") -> "pl"
            lowerLang == "swedish" || lowerLang.startsWith("swedish") -> "sv"
            lowerLang == "norwegian" || lowerLang.startsWith("norwegian") -> "no"
            lowerLang == "danish" || lowerLang.startsWith("danish") -> "da"
            lowerLang == "finnish" || lowerLang.startsWith("finnish") -> "fi"
            lowerLang == "greek" || lowerLang.startsWith("greek") -> "el"
            lowerLang == "czech" || lowerLang.startsWith("czech") -> "cs"
            lowerLang == "hungarian" || lowerLang.startsWith("hungarian") -> "hu"
            lowerLang == "romanian" || lowerLang.startsWith("romanian") -> "ro"
            lowerLang == "thai" || lowerLang.startsWith("thai") -> "th"
            lowerLang == "vietnamese" || lowerLang.startsWith("vietnamese") -> "vi"
            lowerLang == "indonesian" || lowerLang.startsWith("indonesian") -> "id"
            lowerLang == "hebrew" || lowerLang.startsWith("hebrew") -> "he"
            // ISO 639-1 codes (2 letter)
            lowerLang.length == 2 -> lowerLang
            // ISO 639-2 codes (3 letter)
            lowerLang == "eng" -> "en"
            lowerLang == "spa" -> "es"
            lowerLang == "fra" || lowerLang == "fre" -> "fr"
            lowerLang == "deu" || lowerLang == "ger" -> "de"
            lowerLang == "ita" -> "it"
            lowerLang == "por" -> "pt"
            lowerLang == "nld" || lowerLang == "dut" -> "nl"
            lowerLang == "rus" -> "ru"
            lowerLang == "zho" || lowerLang == "chi" -> "zh"
            lowerLang == "jpn" -> "ja"
            lowerLang == "kor" -> "ko"
            lowerLang == "ara" -> "ar"
            lowerLang == "hin" -> "hi"
            lowerLang == "tur" -> "tr"
            lowerLang == "pol" -> "pl"
            lowerLang == "swe" -> "sv"
            lowerLang == "nor" -> "no"
            lowerLang == "dan" -> "da"
            lowerLang == "fin" -> "fi"
            lowerLang == "ell" || lowerLang == "gre" -> "el"
            lowerLang == "ces" || lowerLang == "cze" -> "cs"
            lowerLang == "hun" -> "hu"
            lowerLang == "ron" || lowerLang == "rum" -> "ro"
            lowerLang == "tha" -> "th"
            lowerLang == "vie" -> "vi"
            lowerLang == "ind" -> "id"
            lowerLang == "heb" -> "he"
            else -> lowerLang
        }
    }

    // Track current stream index for auto-retry
    private var currentStreamIndex = 0

    /**
     * Select a stream for playback
     */
    fun selectStream(stream: StreamSource, autoRetryOnFail: Boolean = true) {
        viewModelScope.launch {
            val resolvedStream = runCatching {
                streamRepository.resolveStreamForPlayback(stream)
            }.getOrNull() ?: stream
            val url = resolvedStream.url ?: return@launch

            // Find the index of this stream
            val streams = _uiState.value.streams
            val streamIndex = streams.indexOf(stream)
            if (streamIndex >= 0) {
                currentStreamIndex = streamIndex
            }

            // Merge stream's embedded subtitles with existing subtitles
            val streamSubs = stream.subtitles
            if (streamSubs.isNotEmpty()) {
                val existingSubs = _uiState.value.subtitles
                val newSubs = streamSubs.filter { newSub ->
                    existingSubs.none { it.id == newSub.id || (it.url == newSub.url && newSub.url.isNotBlank()) }
                }
                if (newSubs.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(subtitles = existingSubs + newSubs)
                }
            }

            // Direct URL - use immediately (ExoPlayer handles redirects)
            _uiState.value = _uiState.value.copy(
                selectedStream = resolvedStream,
                selectedStreamUrl = url
            )
        }
    }

    /**
     * Try the next available stream when current one fails
     */
    private fun tryNextStream(streams: List<StreamSource>, reason: String) {
        val nextIndex = currentStreamIndex + 1

        if (nextIndex < streams.size && nextIndex < 10) { // Limit to 10 retries
            currentStreamIndex = nextIndex
            val nextStream = streams[nextIndex]
            selectStream(nextStream, autoRetryOnFail = true)
        } else {
            _uiState.value = _uiState.value.copy(
                error = "Failed to play stream. Try another source."
            )
        }
    }

    fun updateEmbeddedSubtitles(embedded: List<Subtitle>) {
        val current = _uiState.value.subtitles.filter { !it.isEmbedded }
        // Avoid duplicates based on ID
        val newEmbedded = embedded.filter { new ->
            current.none { it.id == new.id }
        }

        if (newEmbedded.isEmpty()) return

        val merged = current + newEmbedded

        _uiState.value = _uiState.value.copy(subtitles = merged)

        // Re-apply preference if currently selected is null or we are in initial state
        // This ensures embedded English subs get picked up if external ones failed
        if (_uiState.value.selectedSubtitle == null) {
             viewModelScope.launch {
                 val preferred = getDefaultSubtitle()
                 applyPreferredSubtitle(preferred, merged, currentOriginalLanguage)
             }
        }
    }

    fun selectSubtitle(subtitle: Subtitle) {
        _uiState.value = _uiState.value.copy(selectedSubtitle = subtitle)
        recordSubtitleUsage(subtitle)
    }

    fun disableSubtitles() {
        _uiState.value = _uiState.value.copy(selectedSubtitle = null)
    }

    private fun recordSubtitleUsage(subtitle: Subtitle) {
        viewModelScope.launch {
            val raw = subtitle.lang.ifBlank { subtitle.label }
            if (raw.isBlank()) return@launch
            val key = normalizeLanguage(raw)
            if (key.isBlank()) return@launch

            val prefs = context.settingsDataStore.data.first()
            val json = prefs[SUBTITLE_USAGE_KEY]
            val type = TypeToken.getParameterized(MutableMap::class.java, String::class.java, Int::class.javaObjectType).type
            val map: MutableMap<String, Int> = if (!json.isNullOrBlank()) {
                gson.fromJson(json, type)
            } else {
                mutableMapOf()
            }

            map[key] = (map[key] ?: 0) + 1
            context.settingsDataStore.edit { it[SUBTITLE_USAGE_KEY] = gson.toJson(map) }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun retry() {
        loadMedia(
            currentMediaType,
            currentMediaId,
            currentSeason,
            currentEpisode,
            null,
            currentStartPositionMs
        )
    }

    private suspend fun resolveResumePositionMs(
        mediaType: MediaType,
        mediaId: Int,
        seasonNumber: Int?,
        episodeNumber: Int?,
        navigationStartPositionMs: Long?
    ): Long {
        val navStart = navigationStartPositionMs?.coerceAtLeast(0L) ?: 0L
        if (navStart > 0L) return navStart

        val entry = watchHistoryRepository.getProgress(mediaType, mediaId, seasonNumber, episodeNumber) ?: return 0L
        return computeResumePositionMs(entry, mediaType, mediaId, seasonNumber, episodeNumber)
    }

    private suspend fun computeResumePositionMs(
        entry: WatchHistoryEntry,
        mediaType: MediaType,
        mediaId: Int,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Long {
        val normalizedPositionSeconds = normalizeStoredSeconds(entry.position_seconds)
        if (normalizedPositionSeconds > 0L) {
            return normalizedPositionSeconds * 1000L
        }

        val normalizedDurationSeconds = normalizeStoredSeconds(entry.duration_seconds)
        if (normalizedDurationSeconds > 0L && entry.progress > 0f) {
            return (normalizedDurationSeconds * entry.progress).toLong().coerceAtLeast(0L) * 1000L
        }

        if (entry.progress <= 0f) return 0L

        val runtimeSeconds = resolveRuntimeSeconds(mediaType, mediaId, seasonNumber, episodeNumber)
        if (runtimeSeconds > 0L) {
            return (runtimeSeconds * entry.progress).toLong().coerceAtLeast(0L) * 1000L
        }
        return 0L
    }

    private fun normalizeStoredSeconds(value: Long): Long {
        // Defensive conversion for rows that may have been stored as milliseconds.
        return if (value > 86_400L) value / 1000L else value
    }

    private suspend fun resolveRuntimeSeconds(
        mediaType: MediaType,
        mediaId: Int,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Long {
        return try {
            if (mediaType == MediaType.MOVIE) {
                val details = tmdbApi.getMovieDetails(mediaId, Constants.TMDB_API_KEY)
                (details.runtime ?: 0) * 60L
            } else {
                val details = tmdbApi.getTvDetails(mediaId, Constants.TMDB_API_KEY)
                val avgRuntime = details.episodeRunTime.firstOrNull() ?: 0
                if (avgRuntime > 0) {
                    avgRuntime * 60L
                } else {
                    val season = seasonNumber ?: return 0L
                    val episode = episodeNumber ?: return 0L
                    val seasonDetails = tmdbApi.getTvSeason(mediaId, season, Constants.TMDB_API_KEY)
                    val runtime = seasonDetails.episodes.firstOrNull { it.episodeNumber == episode }?.runtime
                        ?: seasonDetails.episodes.firstOrNull { it.runtime != null }?.runtime
                        ?: 0
                    runtime * 60L
                }
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun saveProgress(position: Long, duration: Long, progressPercent: Int, isPlaying: Boolean, playbackState: Int) {
        if (duration <= 0) return

        if (progressSaveJob?.isActive == true) return
        progressSaveJob = viewModelScope.launch {
            val currentTime = System.currentTimeMillis()
            val progressFraction = (progressPercent / 100f).coerceIn(0f, 1f)

            // Scrobble start/pause/updates with debounce
            if (isPlaying && !lastIsPlaying) {
                try {
                    traktRepository.scrobbleStart(
                        mediaType = currentMediaType,
                        tmdbId = currentMediaId,
                        progress = progressPercent.toFloat(),
                        season = currentSeason,
                        episode = currentEpisode
                    )
                } catch (e: Exception) {
                    // Scrobble start failed
                }
                lastScrobbleTime = currentTime
            } else if (!isPlaying && lastIsPlaying) {
                try {
                    traktRepository.scrobblePauseImmediate(
                        mediaType = currentMediaType,
                        tmdbId = currentMediaId,
                        progress = progressPercent.toFloat(),
                        season = currentSeason,
                        episode = currentEpisode
                    )
                } catch (e: Exception) {
                    // Scrobble pause immediate failed
                }
                lastScrobbleTime = currentTime
            } else if (isPlaying && currentTime - lastScrobbleTime >= SCROBBLE_UPDATE_INTERVAL_MS) {
                try {
                    traktRepository.scrobblePause(
                        mediaType = currentMediaType,
                        tmdbId = currentMediaId,
                        progress = progressPercent.toFloat(),
                        season = currentSeason,
                        episode = currentEpisode
                    )
                } catch (e: Exception) {
                    // Scrobble pause failed
                }
                lastScrobbleTime = currentTime
            }

            // Save to Supabase watch history (debounced + on pause/stop)
            if (!isPlaying || currentTime - lastWatchHistorySaveTime >= WATCH_HISTORY_UPDATE_INTERVAL_MS || progressPercent >= Constants.WATCHED_THRESHOLD) {
                lastWatchHistorySaveTime = currentTime
                val durationSeconds = (duration / 1000L).coerceAtLeast(1L)
                val positionSeconds = (position / 1000L).coerceAtLeast(0L)
                watchHistoryRepository.saveProgress(
                    mediaType = currentMediaType,
                    tmdbId = currentMediaId,
                    title = currentTitle,
                    poster = currentPoster,
                    backdrop = currentBackdrop,
                    season = currentSeason,
                    episode = currentEpisode,
                    episodeTitle = currentEpisodeTitle,
                    progress = progressFraction,
                    duration = durationSeconds,
                    position = positionSeconds
                )

                // Also save to local Continue Watching (profile-scoped, for profiles without Trakt)
                traktRepository.saveLocalContinueWatching(
                    mediaType = currentMediaType,
                    tmdbId = currentMediaId,
                    title = currentItemTitle.ifEmpty { currentTitle },
                    posterPath = currentPoster,
                    backdropPath = currentBackdrop,
                    season = currentSeason,
                    episode = currentEpisode,
                    episodeTitle = currentEpisodeTitle,
                    progress = progressPercent
                )
            }

            // Mark as watched when playback ends or crosses threshold
            if (!hasMarkedWatched && (playbackState == Player.STATE_ENDED || progressPercent >= Constants.WATCHED_THRESHOLD)) {
                hasMarkedWatched = true
                try {
                    traktRepository.scrobbleStop(
                        mediaType = currentMediaType,
                        tmdbId = currentMediaId,
                        progress = progressPercent.toFloat(),
                        season = currentSeason,
                        episode = currentEpisode
                    )
                } catch (e: Exception) {
                    // Scrobble stop failed
                }
                try {
                    if (currentMediaType == MediaType.TV && currentSeason != null && currentEpisode != null) {
                        traktRepository.deletePlaybackForEpisode(currentMediaId, currentSeason!!, currentEpisode!!)
                    } else if (currentMediaType == MediaType.MOVIE) {
                        traktRepository.deletePlaybackForContent(currentMediaId, currentMediaType)
                    }
                } catch (e: Exception) {
                    // Delete playback failed
                }
            }

            lastIsPlaying = isPlaying
        }.also { job ->
            job.invokeOnCompletion { progressSaveJob = null }
        }
    }

    private var progressSaveJob: Job? = null
}
