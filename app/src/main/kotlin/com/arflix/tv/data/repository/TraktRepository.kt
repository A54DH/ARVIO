package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arflix.tv.data.api.*
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.NextEpisode
import com.arflix.tv.util.AppException
import com.arflix.tv.util.ContinueWatchingSelector
import com.arflix.tv.util.EpisodePointer
import com.arflix.tv.util.EpisodeProgressSnapshot
import com.arflix.tv.util.Result
import com.arflix.tv.util.WatchedEpisodeSnapshot
import com.arflix.tv.util.Constants
import com.arflix.tv.util.runCatching
import com.arflix.tv.util.settingsDataStore
import com.arflix.tv.util.traktDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Repository for Trakt.tv API interactions
 *
 * This repository now uses TraktSyncService for watched state management,
 * which ensures Supabase is the source of truth for all watched data.
 *
 * Key changes:
 * - Watched state queries Supabase, not local cache
 * - Mark watched/unwatched writes to Supabase first, then syncs to Trakt
 * - Continue Watching uses Supabase data augmented with Trakt progress API
 */
@Singleton
class TraktRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktApi: TraktApi,
    private val tmdbApi: TmdbApi,
    private val syncServiceProvider: Provider<TraktSyncService>
) {
    private val TAG = "TraktRepository"
    private val gson = Gson()

    // Lazy sync service to avoid circular dependency
    private val syncService: TraktSyncService by lazy { syncServiceProvider.get() }

    // Supabase client for profile sync (lazy to avoid startup overhead)
    private val supabase: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = Constants.SUPABASE_URL,
            supabaseKey = Constants.SUPABASE_ANON_KEY
        ) {
            install(Postgrest)
        }
    }

    // User ID key for Supabase sync
    private val USER_ID_KEY = stringPreferencesKey("user_id")
    private val clientId = Constants.TRAKT_CLIENT_ID
    private val clientSecret = Constants.TRAKT_CLIENT_SECRET

    // Preference keys
    private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
    private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
    private val EXPIRES_AT_KEY = longPreferencesKey("expires_at")
    private val INCLUDE_SPECIALS_KEY = booleanPreferencesKey("include_specials")
    private val DISMISSED_CONTINUE_WATCHING_KEY = stringPreferencesKey("dismissed_continue_watching_v1")
    private val CONTINUE_WATCHING_CACHE_KEY = stringPreferencesKey("continue_watching_cache_v1")
    private var attemptedProfileTokenLoad = false
    @Volatile private var cachedContinueWatching: List<ContinueWatchingItem> = emptyList()

    @Serializable
    private data class TraktTokenUpdate(
        val trakt_token: JsonObject,
        val updated_at: String
    )

    private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

    companion object {
        // Pagination constants to prevent OOM
        private const val MAX_ITEMS = 1000
        private const val PAGE_SIZE = 100

        // Cache duration constants
        private const val SHOW_CACHE_DURATION_MS = 5 * 60 * 1000L // 5 minutes
        private const val SHOW_COMPLETION_CACHE_MS = 10 * 60 * 1000L // 10 minutes

        // Scrobble constants
        private const val SCROBBLE_DEBOUNCE_MS = 5000L // 5 seconds

        // Token refresh constants
        private const val TOKEN_REFRESH_BUFFER_SECONDS = 3600L // 1 hour

        // Retry constants
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 10000L

        // API rate limiting
        private const val API_SEMAPHORE_PERMITS = 10

        // Watched shows fetch limit
        private const val WATCHED_SHOWS_LIMIT = 200
    }

    // ========== Authentication ==========

    val isAuthenticated: Flow<Boolean> = context.traktDataStore.data.map { prefs ->
        prefs[ACCESS_TOKEN_KEY] != null
    }

    /**
     * Get token expiration timestamp (seconds since epoch)
     */
    suspend fun getTokenExpiration(): Result<Long> {
        return runCatching {
            val prefs = context.traktDataStore.data.first()
            prefs[EXPIRES_AT_KEY] ?: throw AppException.Auth("No token expiration found")
        }
    }

    /**
     * Get formatted token expiration date
     */
    suspend fun getTokenExpirationDate(): Result<String> {
        return runCatching {
            val expiresAtResult = getTokenExpiration()
            val expiresAt = when (expiresAtResult) {
                is Result.Success -> expiresAtResult.data
                is Result.Error -> throw expiresAtResult.exception
            }
            val expirationDate = java.time.Instant.ofEpochSecond(expiresAt)
            val formatter = java.time.format.DateTimeFormatter
                .ofPattern("MMM dd, yyyy")
                .withZone(java.time.ZoneId.systemDefault())
            formatter.format(expirationDate)
        }
    }

    suspend fun getDeviceCode(): Result<TraktDeviceCode> {
        return runCatching {
            traktApi.getDeviceCode(DeviceCodeRequest(clientId))
        }
    }

    suspend fun pollForToken(deviceCode: String): Result<TraktToken> {
        return runCatching {
            val token = traktApi.pollToken(
                TokenPollRequest(
                    code = deviceCode,
                    clientId = clientId,
                    clientSecret = clientSecret
                )
            )
            saveToken(token)
            token
        }
    }

    suspend fun refreshTokenIfNeeded(): Result<String> {
        return runCatching {
            val prefs = context.traktDataStore.data.first()
            val accessToken = prefs[ACCESS_TOKEN_KEY]
                ?: throw AppException.Auth("No access token found")
            val refreshToken = prefs[REFRESH_TOKEN_KEY]
            val expiresAt = prefs[EXPIRES_AT_KEY]

            // If we don't have refresh metadata (older tokens), use the existing access token
            if (refreshToken == null || expiresAt == null) {
                return@runCatching accessToken
            }

            // Check if token is expired (with buffer)
            val now = System.currentTimeMillis() / 1000
            if (now < expiresAt - TOKEN_REFRESH_BUFFER_SECONDS) {
                return@runCatching accessToken
            }

            // Refresh token
            val newToken = traktApi.refreshToken(
                RefreshTokenRequest(
                    refreshToken = refreshToken,
                    clientId = clientId,
                    clientSecret = clientSecret
                )
            )
            saveToken(newToken)
            newToken.accessToken
        }
    }

    private suspend fun saveToken(token: TraktToken) {
        context.traktDataStore.edit { prefs ->
            prefs[ACCESS_TOKEN_KEY] = token.accessToken
            prefs[REFRESH_TOKEN_KEY] = token.refreshToken
            prefs[EXPIRES_AT_KEY] = token.createdAt + token.expiresIn
        }

        // Sync to Supabase profile
        syncTokenToSupabase(token)
    }

    /**
     * Sync Trakt token to Supabase profile
     */
    private suspend fun syncTokenToSupabase(token: TraktToken) {
        runCatching {
            val prefs = context.traktDataStore.data.first()
            val userId = prefs[USER_ID_KEY]
                ?: throw AppException.Auth("No user ID for token sync")

            // Build token object matching webapp format
            val tokenJson = buildJsonObject {
                put("access_token", token.accessToken)
                put("refresh_token", token.refreshToken)
                put("expires_in", token.expiresIn)
                put("created_at", token.createdAt)
            }

            supabase.postgrest
                .from("profiles")
                .update(TraktTokenUpdate(tokenJson, java.time.Instant.now().toString())) {
                    filter { eq("id", userId) }
                }
        }
    }

    /**
     * Set the user ID for Supabase sync (called after login)
     */
    suspend fun setUserId(userId: String) {
        context.traktDataStore.edit { prefs ->
            prefs[USER_ID_KEY] = userId
        }
    }

    /**
     * Load tokens from Supabase profile
     */
    suspend fun loadTokensFromProfile(traktToken: JsonObject?): Result<Unit> {
        if (traktToken == null) {
            return Result.error(AppException.Auth("No Trakt token in profile"))
        }

        return runCatching {
            val accessToken = traktToken["access_token"]?.toString()?.trim('"')
                ?: throw AppException.Auth("Missing access_token in profile")
            val refreshToken = traktToken["refresh_token"]?.toString()?.trim('"')
                ?: throw AppException.Auth("Missing refresh_token in profile")
            val expiresIn = traktToken["expires_in"]?.toString()?.toLongOrNull() ?: 7776000L
            val createdAt = traktToken["created_at"]?.toString()?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)

            context.traktDataStore.edit { prefs ->
                prefs[ACCESS_TOKEN_KEY] = accessToken
                prefs[REFRESH_TOKEN_KEY] = refreshToken
                prefs[EXPIRES_AT_KEY] = createdAt + expiresIn
            }
        }
    }

    suspend fun logout() {
        context.traktDataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN_KEY)
            prefs.remove(REFRESH_TOKEN_KEY)
            prefs.remove(EXPIRES_AT_KEY)
        }
    }

    private suspend fun getAuthHeader(): String? {
        val tokenResult = refreshTokenIfNeeded()
        if (tokenResult.isSuccess) {
            return "Bearer ${tokenResult.getOrNull()}"
        }

        // Fallback: load Trakt tokens from Supabase profile if available
        if (!attemptedProfileTokenLoad) {
            attemptedProfileTokenLoad = true
            runCatching {
                val userId = context.traktDataStore.data.first()[USER_ID_KEY]
                    ?: context.authDataStore.data.first()[USER_ID_KEY]
                if (!userId.isNullOrBlank()) {
                    val profile = supabase.postgrest
                        .from("profiles")
                        .select { filter { eq("id", userId) } }
                        .decodeSingleOrNull<JsonObject>()
                    val traktTokenElement = profile?.get("trakt_token")
                    when {
                        traktTokenElement is JsonObject -> {
                            loadTokensFromProfile(traktTokenElement)
                        }
                        traktTokenElement != null -> {
                            val accessToken = traktTokenElement.jsonPrimitive.content
                            if (accessToken.isNotBlank() && accessToken != "null") {
                                context.traktDataStore.edit { prefs ->
                                    prefs[ACCESS_TOKEN_KEY] = accessToken
                                }
                            }
                        }
                    }
                }
            }
        }

        val refreshedResult = refreshTokenIfNeeded()
        return refreshedResult.getOrNull()?.let { "Bearer $it" }
    }

    // ========== Watched History ==========

    suspend fun getWatchedMovies(): Result<Set<Int>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val watched = traktApi.getWatchedMovies(auth, clientId)
            watched.mapNotNull { it.movie.ids.tmdb }.toSet()
        }
    }

    suspend fun getWatchedEpisodes(): Result<Set<String>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val watched = traktApi.getWatchedShows(auth, clientId)
            val episodes = mutableSetOf<String>()
            watched.forEach { show ->
                val tmdbId = show.show.ids.tmdb ?: return@forEach
                show.seasons?.forEach { season ->
                    season.episodes.forEach { ep ->
                        buildEpisodeKey(
                            traktEpisodeId = null,
                            showTraktId = null,
                            showTmdbId = tmdbId,
                            season = season.number,
                            episode = ep.number
                        )?.let { episodes.add(it) }
                    }
                }
            }
            episodes
        }
    }

    /**
     * Mark movie as watched - writes to Supabase first (source of truth), then syncs to Trakt
     */
    suspend fun markMovieWatched(tmdbId: Int): Result<Unit> {
        return runCatching {
            // Use sync service which writes to Supabase first, then Trakt
            val success = syncService.markMovieWatched(tmdbId)
            if (success) {
                // Update local cache for immediate UI update
                updateWatchedCache(tmdbId, null, null, true)
            } else {
                throw AppException.Unknown("Failed to mark movie $tmdbId as watched")
            }
        }
    }

    /**
     * Mark movie as unwatched - removes from Supabase first (source of truth), then syncs to Trakt
     */
    suspend fun markMovieUnwatched(tmdbId: Int): Result<Unit> {
        return runCatching {
            // Use sync service which removes from Supabase first, then Trakt
            val success = syncService.markMovieUnwatched(tmdbId)
            if (success) {
                // Update local cache for immediate UI update
                updateWatchedCache(tmdbId, null, null, false)
            } else {
                throw AppException.Unknown("Failed to mark movie $tmdbId as unwatched")
            }
        }
    }

    /**
     * Mark episode as watched - writes to Supabase first (source of truth), then syncs to Trakt
     */
    suspend fun markEpisodeWatched(showTmdbId: Int, season: Int, episode: Int): Result<Unit> {
        return runCatching {
            // Get Trakt show ID if available
            val traktShowId = tmdbToTraktIdCache[showTmdbId]

            // Use sync service which writes to Supabase first, then Trakt
            val success = syncService.markEpisodeWatched(showTmdbId, season, episode, traktShowId)
            if (success) {
                // Update caches so the UI updates immediately
                clearShowWatchedCache()
                updateWatchedCache(showTmdbId, season, episode, true)
            } else {
                throw AppException.Unknown("Failed to mark episode S${season}E${episode} as watched")
            }
        }
    }

    /**
     * Mark episode as unwatched - removes from Supabase first (source of truth), then syncs to Trakt
     */
    suspend fun markEpisodeUnwatched(showTmdbId: Int, season: Int, episode: Int): Result<Unit> {
        return runCatching {
            // Use sync service which removes from Supabase first, then Trakt
            val success = syncService.markEpisodeUnwatched(showTmdbId, season, episode)
            if (success) {
                // Update caches so the UI updates immediately
                clearShowWatchedCache()
                updateWatchedCache(showTmdbId, season, episode, false)
            } else {
                throw AppException.Unknown("Failed to mark episode S${season}E${episode} as unwatched")
            }
        }
    }

    // ========== Scrobbling (Like NuvioStreaming) ==========

    // Queue-based scrobbling to prevent duplicate API calls
    private var lastScrobbleKey: String? = null
    private var lastScrobbleTime: Long = 0

    private suspend fun <T> executeWithRetry(
        operation: String,
        maxAttempts: Int = MAX_RETRY_ATTEMPTS,
        initialDelayMs: Long = INITIAL_RETRY_DELAY_MS,
        block: suspend () -> T
    ): Result<T> {
        var attempt = 1
        var delayMs = initialDelayMs
        while (attempt <= maxAttempts) {
            try {
                return Result.success(block())
            } catch (e: HttpException) {
                val code = e.code()
                val shouldRetry = code == 429 || code >= 500 || code == 401
                if (code == 401) {
                    refreshTokenIfNeeded()
                }
                if (!shouldRetry || attempt == maxAttempts) {
                    return Result.error(AppException.Server("$operation failed", code, e))
                }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                attempt++
            } catch (e: Exception) {
                if (attempt == maxAttempts) {
                    return Result.error(AppException.Unknown("$operation failed after $attempt attempts", e))
                }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                attempt++
            }
        }
        return Result.error(AppException.Unknown("$operation failed - max attempts exceeded"))
    }

    /**
     * Scrobble Start - Called when playback begins
     */
    suspend fun scrobbleStart(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    ): Result<TraktScrobbleResponse> {
        val body = buildScrobbleBody(mediaType, tmdbId, progress, season, episode)
        return executeWithRetry("Scrobble start") {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.scrobbleStart(auth, clientId, "2", body)
        }
    }

    /**
     * Scrobble Pause - Called when playback is paused (saves progress)
     * Uses queue-based deduplication like NuvioStreaming
     */
    suspend fun scrobblePause(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    ): Result<TraktScrobbleResponse?> {
        val key = "$tmdbId-$season-$episode"
        val now = System.currentTimeMillis()

        // Debounce duplicate calls
        if (key == lastScrobbleKey && now - lastScrobbleTime < SCROBBLE_DEBOUNCE_MS) {
            return Result.success(null)
        }

        lastScrobbleKey = key
        lastScrobbleTime = now

        val body = buildScrobbleBody(mediaType, tmdbId, progress, season, episode)
        return executeWithRetry("Scrobble pause") {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.scrobblePause(auth, clientId, "2", body)
        }
    }

    /**
     * Scrobble Pause Immediate - Bypass queue for instant pause
     */
    suspend fun scrobblePauseImmediate(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    ): Result<TraktScrobbleResponse> {
        val body = buildScrobbleBody(mediaType, tmdbId, progress, season, episode)
        return executeWithRetry("Scrobble pause immediate") {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.scrobblePause(auth, clientId, "2", body)
        }
    }

    /**
     * Scrobble Stop - Called when playback ends
     * Auto-marks as watched if progress >= threshold
     */
    suspend fun scrobbleStop(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    ): Result<TraktScrobbleResponse> {
        val body = buildScrobbleBody(mediaType, tmdbId, progress, season, episode)
        val response = executeWithRetry("Scrobble stop") {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.scrobbleStop(auth, clientId, "2", body)
        }

        // Auto-mark as watched if progress >= threshold (like NuvioStreaming)
        if (progress >= Constants.WATCHED_THRESHOLD) {
            if (mediaType == MediaType.MOVIE) {
                markMovieWatched(tmdbId)
                updateWatchedCache(tmdbId, null, null, true)
            } else if (season != null && episode != null) {
                markEpisodeWatched(tmdbId, season, episode)
                updateWatchedCache(tmdbId, season, episode, true)
            }
        }

        return response
    }

    /**
     * Scrobble Stop Immediate - Bypass queue for instant stop
     */
    suspend fun scrobbleStopImmediate(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    ): Result<TraktScrobbleResponse> {
        val body = buildScrobbleBody(mediaType, tmdbId, progress, season, episode)
        return executeWithRetry("Scrobble stop immediate") {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.scrobbleStop(auth, clientId, "2", body)
        }
    }

    private fun buildScrobbleBody(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int?,
        episode: Int?
    ): TraktScrobbleBody {
        return if (mediaType == MediaType.MOVIE) {
            TraktScrobbleBody(
                movie = TraktMovieId(TraktIds(tmdb = tmdbId)),
                progress = progress
            )
        } else {
            TraktScrobbleBody(
                episode = TraktEpisodeId(season = season, number = episode),
                show = TraktShowId(TraktIds(tmdb = tmdbId)),
                progress = progress
            )
        }
    }

    /**
     * Legacy method - delegates to scrobblePause for backwards compatibility
     */
    suspend fun savePlaybackProgress(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    ) {
        scrobblePause(mediaType, tmdbId, progress, season, episode)
    }

    /**
     * Delete playback progress item by ID
     */
    suspend fun deletePlaybackItem(playbackId: Long): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.removePlaybackItem(auth, clientId, "2", playbackId)
        }
    }

    /**
     * Delete playback progress for specific content (like NuvioStreaming's deletePlaybackForContent)
     */
    suspend fun deletePlaybackForContent(tmdbId: Int, mediaType: MediaType): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val playback = getAllPlaybackProgress(auth)
            val item = playback.find {
                when (mediaType) {
                    MediaType.MOVIE -> it.movie?.ids?.tmdb == tmdbId
                    MediaType.TV -> it.show?.ids?.tmdb == tmdbId
                }
            }
            if (item != null) {
                traktApi.removePlaybackItem(auth, clientId, "2", item.id)
            } else {
                throw AppException.Server.notFound("Playback item")
            }
        }
    }

    // ========== Watched Episodes ==========

    // Cache for TMDB to Trakt ID mapping (populated from watched shows)
    private val tmdbToTraktIdCache = mutableMapOf<Int, Int>()

    // Cache for watched episodes per show (to avoid repeated API calls)
    private val showWatchedEpisodesCache = mutableMapOf<Int, Set<String>>()
    private var showWatchedCacheTime = 0L
    private val showCompletionCache = mutableMapOf<Int, Pair<Boolean, Long>>()

    /**
     * Get watched episodes for a specific show (by TMDB ID)
     * Returns a Set of episode keys in format "tmdbId-season-episode"
     * Uses caching to avoid repeated API calls
     */
    suspend fun getWatchedEpisodesForShow(tmdbId: Int): Result<Set<String>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")

            // Check cache first (within cache duration)
            val now = System.currentTimeMillis()
            if (now - showWatchedCacheTime < SHOW_CACHE_DURATION_MS) {
                showWatchedEpisodesCache[tmdbId]?.let { cachedSet ->
                    return@runCatching cachedSet
                }
            }

            val watchedSet = mutableSetOf<String>()

            // First try to get Trakt ID from cache
            var traktId = tmdbToTraktIdCache[tmdbId]

            // If not in cache, populate cache from watched shows
            if (traktId == null) {
                populateTmdbToTraktCache()
                traktId = tmdbToTraktIdCache[tmdbId]
            }

            // If still not found, try search API as fallback
            if (traktId == null) {
                traktId = getTraktIdForTmdb(tmdbId, "show")
                if (traktId != null) {
                    tmdbToTraktIdCache[tmdbId] = traktId
                }
            }

            if (traktId == null) {
                // Cache empty result to avoid repeated lookups
                showWatchedEpisodesCache[tmdbId] = emptySet()
                return@runCatching emptySet()
            }

            // Get show progress which includes per-episode completion status
            val progress = traktApi.getShowProgress(auth, clientId, "2", traktId.toString())

            // Iterate through all seasons and episodes
            progress.seasons?.forEach { season ->
                season.episodes?.forEach { episode ->
                    if (episode.completed) {
                        buildEpisodeKey(
                            traktEpisodeId = null,
                            showTraktId = null,
                            showTmdbId = tmdbId,
                            season = season.number,
                            episode = episode.number
                        )?.let { watchedSet.add(it) }
                    }
                }
            }

            // Cache the result
            showWatchedEpisodesCache[tmdbId] = watchedSet
            showWatchedCacheTime = now

            watchedSet
        }
    }

    /**
     * Clear the watched episodes cache (call when user marks episode as watched/unwatched)
     */
    fun clearShowWatchedCache() {
        showWatchedEpisodesCache.clear()
        showWatchedCacheTime = 0L
        showCompletionCache.clear()
    }

    suspend fun isShowFullyWatched(tmdbId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val now = System.currentTimeMillis()
            showCompletionCache[tmdbId]?.let { (cached, timestamp) ->
                if (now - timestamp < SHOW_COMPLETION_CACHE_MS) {
                    return@runCatching cached
                }
            }

            var traktId = tmdbToTraktIdCache[tmdbId]
            if (traktId == null) {
                populateTmdbToTraktCache()
                traktId = tmdbToTraktIdCache[tmdbId]
            }

            if (traktId == null) {
                traktId = getTraktIdForTmdb(tmdbId, "show")
                if (traktId != null) {
                    tmdbToTraktIdCache[tmdbId] = traktId
                }
            }

            if (traktId == null) {
                showCompletionCache[tmdbId] = false to now
                return@runCatching false
            }

            val includeSpecials = context.settingsDataStore.data.first()[INCLUDE_SPECIALS_KEY] ?: false
            val progress = traktApi.getShowProgress(
                auth,
                clientId,
                "2",
                traktId.toString(),
                specials = includeSpecials.toString(),
                countSpecials = includeSpecials.toString()
            )
            val complete = progress.aired > 0 && progress.completed >= progress.aired
            showCompletionCache[tmdbId] = complete to now
            complete
        }
    }

    /**
     * Sync locally stored Trakt tokens to Supabase if profile is empty.
     */
    suspend fun syncLocalTokensToProfileIfNeeded(): Result<Unit> {
        return runCatching {
            val prefs = context.traktDataStore.data.first()
            val accessToken = prefs[ACCESS_TOKEN_KEY]
                ?: throw AppException.Auth("No local access token to sync")
            val refreshToken = prefs[REFRESH_TOKEN_KEY]
            val expiresAt = prefs[EXPIRES_AT_KEY]
            val now = System.currentTimeMillis() / 1000
            val computedExpiresIn = expiresAt?.let { (it - now).toInt() } ?: 0
            val expiresIn = if (computedExpiresIn > 0) computedExpiresIn else 7776000
            val createdAt = if (computedExpiresIn > 0 && expiresAt != null) {
                (expiresAt - expiresIn).coerceAtMost(now)
            } else {
                now
            }

            val userId = prefs[USER_ID_KEY]
                ?: context.authDataStore.data.first()[USER_ID_KEY]
                ?: throw AppException.Auth("No user ID for sync")

            val tokenJson = buildJsonObject {
                put("access_token", accessToken)
                refreshToken?.let { put("refresh_token", it) }
                put("expires_in", expiresIn)
                put("created_at", createdAt)
            }

            supabase.postgrest
                .from("profiles")
                .update(TraktTokenUpdate(tokenJson, java.time.Instant.now().toString())) {
                    filter { eq("id", userId) }
                }
        }
    }

    /**
     * Delete playback progress for a specific episode
     */
    suspend fun deletePlaybackForEpisode(showTmdbId: Int, season: Int, episode: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val playback = getAllPlaybackProgress(auth)
            val item = playback.find { playbackItem ->
                playbackItem.type == "episode" &&
                    playbackItem.show?.ids?.tmdb == showTmdbId &&
                    playbackItem.episode?.season == season &&
                    playbackItem.episode?.number == episode
            }
            if (item != null) {
                traktApi.removePlaybackItem(auth, clientId, "2", item.id)
            } else {
                throw AppException.Server.notFound("Playback item for episode")
            }
        }
    }

    /**
     * Populate the TMDB to Trakt ID cache from watched shows
     */
    private suspend fun populateTmdbToTraktCache() {
        val auth = getAuthHeader() ?: return
        runCatching {
            val watchedShows = traktApi.getWatchedShows(auth, clientId)
            watchedShows.forEach { item ->
                val tmdbId = item.show.ids.tmdb
                val traktId = item.show.ids.trakt
                if (tmdbId != null && traktId != null) {
                    tmdbToTraktIdCache[tmdbId] = traktId
                }
            }
        }
    }

    /**
     * Get Trakt ID from TMDB ID using search API (fallback)
     */
    private suspend fun getTraktIdForTmdb(tmdbId: Int, type: String): Int? {
        val result = runCatching {
            val results = traktApi.searchByTmdb(clientId, tmdbId, type)
            when (type) {
                "show" -> results.firstOrNull()?.show?.ids?.trakt
                "movie" -> results.firstOrNull()?.movie?.ids?.trakt
                else -> null
            }
        }
        return result.getOrNull()
    }

    /**
     * Fetch all playback progress with pagination guard to prevent OOM.
     * Limits total items to MAX_ITEMS.
     */
    private suspend fun getAllPlaybackProgress(auth: String): List<TraktPlaybackItem> {
        val all = mutableListOf<TraktPlaybackItem>()
        var page = 1

        while (all.size < MAX_ITEMS) {
            val pageItems = traktApi.getPlaybackProgress(auth, clientId, "2", null, page, PAGE_SIZE)
            if (pageItems.isEmpty()) break

            val remainingCapacity = MAX_ITEMS - all.size
            if (pageItems.size <= remainingCapacity) {
                all.addAll(pageItems)
            } else {
                all.addAll(pageItems.take(remainingCapacity))
                break
            }

            if (pageItems.size < PAGE_SIZE) break
            page++
        }

        return all
    }

    /**
     * Get items to continue watching - Uses Trakt API directly for accuracy and speed.
     * Refactored to fetch more shows and process in parallel.
     */
    suspend fun getContinueWatching(): Result<List<ContinueWatchingItem>> = coroutineScope {
        runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val candidates = mutableListOf<ContinueWatchingCandidate>()
            val processedIds = mutableSetOf<Int>() // TMDB IDs
            val includeSpecials = context.settingsDataStore.data.first()[INCLUDE_SPECIALS_KEY] ?: false
            val showProgressCache = mutableMapOf<Int, TraktShowProgress>()

            initializeWatchedCache()

            // 1. In-Progress (Paused) items
            val playbackDeferred = async {
                runCatching {
                    getAllPlaybackProgress(auth)
                }.getOrDefault(emptyList())
            }

            // 2. Up Next (from Watched Shows summary)
            val watchedShowsDeferred = async {
                runCatching {
                    traktApi.getWatchedShows(auth, clientId)
                        .sortedByDescending { it.lastWatchedAt }
                        .take(WATCHED_SHOWS_LIMIT)
                }.getOrDefault(emptyList())
            }

            // Process Playback Items (paused mid-episode/movie)
            // NO time filter - include all paused items regardless of when they were paused
            val playbackItems = playbackDeferred.await()
                .sortedByDescending { parseIso8601(it.pausedAt ?: "") }
                .take(Constants.MAX_PROGRESS_ENTRIES)

            for (item in playbackItems) {
                val tmdbId = item.movie?.ids?.tmdb ?: item.show?.ids?.tmdb ?: continue
                if (tmdbId in processedIds) continue

                // Skip if progress is above watched threshold
                if (item.progress > Constants.WATCHED_THRESHOLD) {
                    continue
                }

                if (item.type == "movie") {
                    val movie = item.movie ?: continue
                    // Skip movies already marked as watched
                    if (isMovieWatched(tmdbId)) {
                        continue
                    }
                    candidates.add(
                        ContinueWatchingCandidate(
                            item = ContinueWatchingItem(
                                id = tmdbId,
                                title = movie.title,
                                mediaType = MediaType.MOVIE,
                                progress = item.progress.toInt().coerceIn(0, 100),
                                year = movie.year?.toString() ?: ""
                            ),
                            lastActivityAt = item.pausedAt ?: ""
                        )
                    )
                    processedIds.add(tmdbId)
                } else if (item.type == "episode") {
                    val show = item.show ?: continue
                    val episode = item.episode ?: continue
                    val showTmdbId = show.ids.tmdb ?: continue
                    val traktId = show.ids.trakt

                    if (isEpisodeWatched(showTmdbId, episode.season, episode.number)) {
                        runCatching {
                            traktApi.removePlaybackItem(auth, clientId, "2", item.id)
                        }
                        continue
                    }

                    // Check if the show is fully watched before adding
                    if (traktId != null) {
                        val progressResult = runCatching {
                            showProgressCache.getOrPut(traktId) {
                                traktApi.getShowProgress(
                                    auth, clientId, "2", traktId.toString(),
                                    specials = includeSpecials.toString(),
                                    countSpecials = includeSpecials.toString()
                                )
                            }
                        }

                        if (progressResult.isSuccess) {
                            val showProgress = progressResult.getOrNull()!!
                            val episodeCompleted = showProgress.seasons
                                ?.firstOrNull { it.number == episode.season }
                                ?.episodes
                                ?.firstOrNull { it.number == episode.number }
                                ?.completed == true

                            if (episodeCompleted) {
                                runCatching {
                                    traktApi.removePlaybackItem(auth, clientId, "2", item.id)
                                }
                                continue
                            }
                            // Skip if show is fully watched (no unwatched aired episodes)
                            if (showProgress.completed >= showProgress.aired) {
                                // Delete the stale playback item from Trakt
                                runCatching {
                                    traktApi.removePlaybackItem(auth, clientId, "2", item.id)
                                }
                                continue
                            }
                        }
                    }

                    candidates.add(
                        ContinueWatchingCandidate(
                            item = ContinueWatchingItem(
                                id = tmdbId,
                                title = show.title,
                                mediaType = MediaType.TV,
                                progress = item.progress.toInt().coerceIn(0, 100),
                                season = episode.season,
                                episode = episode.number,
                                episodeTitle = episode.title,
                                year = show.year?.toString() ?: ""
                            ),
                            lastActivityAt = item.pausedAt ?: ""
                        )
                    )
                    processedIds.add(tmdbId)
                }
            }

            // Process Watched Shows in Parallel
            val watchedShows = watchedShowsDeferred.await()

            // Semaphore to limit concurrent Trakt API calls (Rate limiting protection)
            val semaphore = Semaphore(API_SEMAPHORE_PERMITS)

            val showTasks = watchedShows.map { show ->
                async {
                    semaphore.withPermit {
                        val tmdbId = show.show.ids.tmdb ?: return@withPermit null
                        if (tmdbId in processedIds) return@withPermit null
                        val traktId = show.show.ids.trakt ?: return@withPermit null

                        val progressResult = runCatching {
                            traktApi.getShowProgress(
                                auth, clientId, "2", traktId.toString(),
                                specials = includeSpecials.toString(),
                                countSpecials = includeSpecials.toString()
                            )
                        }

                        if (progressResult.isError) return@withPermit null

                        val progress = progressResult.getOrNull()!!

                        // Only include if show is actually incomplete (has unwatched aired episodes)
                        // AND has a next episode to watch
                        val nextEp = progress.nextEpisode
                        val isIncomplete = progress.completed < progress.aired

                        // Filter out shows with minimal progress (likely accidental scrobbles)
                        // Require at least 1 episode watched to appear in Continue Watching
                        val hasMinimumProgress = progress.completed >= 1

                        if (nextEp != null && isIncomplete) {
                            if (!hasMinimumProgress) {
                                null
                            } else {
                                ContinueWatchingCandidate(
                                    item = ContinueWatchingItem(
                                        id = tmdbId,
                                        title = show.show.title,
                                        mediaType = MediaType.TV,
                                        progress = 0,
                                        season = nextEp.season,
                                        episode = nextEp.number,
                                        episodeTitle = nextEp.title,
                                        year = show.show.year?.toString() ?: ""
                                    ),
                                    lastActivityAt = show.lastWatchedAt ?: ""
                                )
                            }
                        } else {
                            null
                        }
                    }
                }
            }

            val nextEpisodes = showTasks.awaitAll().filterNotNull()

            // Add unique items
            for (candidate in nextEpisodes) {
                if (candidate.item.id !in processedIds) {
                    candidates.add(candidate)
                    processedIds.add(candidate.item.id)
                }
            }

            // 3. Hydrate with TMDB Details (Parallel)
            // Only hydrate the top items we will actually display
            val dismissed = loadDismissedContinueWatching()
            val filteredCandidates = if (dismissed.isNotEmpty()) {
                val updatedDismissed = dismissed.toMutableMap()
                val kept = candidates.filter { candidate ->
                    val key = buildContinueWatchingKey(candidate.item)
                    val dismissedAt = key?.let { dismissed[it] }
                    if (dismissedAt == null) {
                        true
                    } else {
                        val activityAt = parseIso8601(candidate.lastActivityAt)
                        if (activityAt > dismissedAt) {
                            updatedDismissed.remove(key)
                            true
                        } else {
                            false
                        }
                    }
                }
                if (updatedDismissed.size != dismissed.size) {
                    persistDismissedContinueWatching(updatedDismissed)
                }
                kept
            } else {
                candidates
            }

            val topCandidates = filteredCandidates.sortedByDescending { it.lastActivityAt }.take(Constants.MAX_CONTINUE_WATCHING)

            val hydrationTasks = topCandidates.map { candidate ->
                async {
                    val detailsResult = runCatching {
                        val item = candidate.item
                        if (item.mediaType == MediaType.MOVIE) {
                            val details = tmdbApi.getMovieDetails(item.id, Constants.TMDB_API_KEY)
                            item.copy(
                                backdropPath = details.backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" },
                                posterPath = details.posterPath?.let { "${Constants.IMAGE_BASE}$it" },
                                overview = details.overview ?: "",
                                imdbRating = String.format("%.1f", details.voteAverage),
                                duration = details.runtime?.let { formatRuntime(it) } ?: ""
                            )
                        } else {
                            val details = tmdbApi.getTvDetails(item.id, Constants.TMDB_API_KEY)
                            item.copy(
                                backdropPath = details.backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" },
                                posterPath = details.posterPath?.let { "${Constants.IMAGE_BASE}$it" },
                                overview = details.overview ?: "",
                                imdbRating = String.format("%.1f", details.voteAverage),
                                duration = details.episodeRunTime?.firstOrNull()?.let { "${it}m" } ?: ""
                            )
                        }
                    }
                    detailsResult.getOrDefault(candidate.item)
                }
            }

            val hydratedItems = hydrationTasks.awaitAll()

            val resolvedItems = if (hydratedItems.isNotEmpty()) {
                cachedContinueWatching = hydratedItems
                persistContinueWatchingCache(hydratedItems)
                hydratedItems
            } else {
                val cached = if (cachedContinueWatching.isNotEmpty()) {
                    cachedContinueWatching
                } else {
                    loadContinueWatchingCache().also { cachedContinueWatching = it }
                }
                cached
            }
            resolvedItems
        }
    }

    fun getCachedContinueWatching(): List<ContinueWatchingItem> = cachedContinueWatching

    suspend fun preloadContinueWatchingCache(): List<ContinueWatchingItem> {
        if (cachedContinueWatching.isNotEmpty()) return cachedContinueWatching
        val cached = loadContinueWatchingCache()
        cachedContinueWatching = cached
        return cachedContinueWatching
    }

    private fun formatRuntime(runtime: Int): String {
        val hours = runtime / 60
        val mins = runtime % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    private fun parseIso8601(dateString: String): Long {
        return try {
            java.time.Instant.parse(dateString).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    private suspend fun loadDismissedContinueWatching(): Map<String, Long> {
        val raw = context.settingsDataStore.data.first()[DISMISSED_CONTINUE_WATCHING_KEY]
        return parseDismissedMap(raw)
    }

    private suspend fun persistDismissedContinueWatching(map: Map<String, Long>) {
        context.settingsDataStore.edit { prefs ->
            if (map.isEmpty()) {
                prefs.remove(DISMISSED_CONTINUE_WATCHING_KEY)
            } else {
                prefs[DISMISSED_CONTINUE_WATCHING_KEY] = encodeDismissedMap(map)
            }
        }
    }

    suspend fun dismissContinueWatching(item: MediaItem) {
        val key = buildContinueWatchingKey(item) ?: return
        val now = System.currentTimeMillis()
        context.settingsDataStore.edit { prefs ->
            val map = parseDismissedMap(prefs[DISMISSED_CONTINUE_WATCHING_KEY])
            map[key] = now
            prefs[DISMISSED_CONTINUE_WATCHING_KEY] = encodeDismissedMap(map)
        }
    }

    private fun buildContinueWatchingKey(item: ContinueWatchingItem): String? {
        return buildContinueWatchingKey(item.mediaType, item.id, item.season, item.episode)
    }

    private fun buildContinueWatchingKey(item: MediaItem): String? {
        val season = item.nextEpisode?.seasonNumber
        val episode = item.nextEpisode?.episodeNumber
        return buildContinueWatchingKey(item.mediaType, item.id, season, episode)
    }

    private fun buildContinueWatchingKey(
        mediaType: MediaType,
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ): String {
        return if (mediaType == MediaType.MOVIE) {
            "movie:$tmdbId"
        } else {
            if (season != null && episode != null) {
                "tv:$tmdbId:$season:$episode"
            } else {
                "tv:$tmdbId"
            }
        }
    }

    private fun parseDismissedMap(raw: String?): MutableMap<String, Long> {
        val map = mutableMapOf<String, Long>()
        if (raw.isNullOrBlank()) return map
        raw.split("|").forEach { entry ->
            val idx = entry.lastIndexOf(',')
            if (idx <= 0 || idx >= entry.length - 1) return@forEach
            val key = entry.substring(0, idx)
            val value = entry.substring(idx + 1).toLongOrNull() ?: return@forEach
            map[key] = value
        }
        return map
    }

    private fun encodeDismissedMap(map: Map<String, Long>): String {
        return map.entries.joinToString("|") { (key, value) -> "$key,$value" }
    }

    private suspend fun persistContinueWatchingCache(items: List<ContinueWatchingItem>) {
        val trimmed = items.take(Constants.MAX_CONTINUE_WATCHING)
        val json = gson.toJson(trimmed)
        context.traktDataStore.edit { prefs ->
            prefs[CONTINUE_WATCHING_CACHE_KEY] = json
        }
    }

    private suspend fun loadContinueWatchingCache(): List<ContinueWatchingItem> {
        val prefs = context.traktDataStore.data.first()
        val json = prefs[CONTINUE_WATCHING_CACHE_KEY] ?: return emptyList()
        return try {
            val type = com.google.gson.reflect.TypeToken
                .getParameterized(MutableList::class.java, ContinueWatchingItem::class.java)
                .type
            val parsed: List<ContinueWatchingItem> = gson.fromJson(json, type)
            parsed
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ========== Watchlist ==========

    suspend fun getWatchlist(): Result<List<MediaItem>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val items = mutableListOf<MediaItem>()

            val watchlist = traktApi.getWatchlist(auth, clientId)

            for (item in watchlist) {
                when (item.type) {
                    "movie" -> {
                        val tmdbId = item.movie?.ids?.tmdb ?: continue
                        val detailsResult = runCatching {
                            tmdbApi.getMovieDetails(tmdbId, Constants.TMDB_API_KEY)
                        }
                        if (detailsResult.isSuccess) {
                            val details = detailsResult.getOrNull()!!
                            items.add(
                                MediaItem(
                                    id = tmdbId,
                                    title = details.title,
                                    subtitle = "Movie",
                                    overview = details.overview ?: "",
                                    year = details.releaseDate?.take(4) ?: "",
                                    imdbRating = String.format("%.1f", details.voteAverage),
                                    mediaType = MediaType.MOVIE,
                                    image = details.backdropPath?.let { "${Constants.BACKDROP_BASE}$it" }
                                        ?: details.posterPath?.let { "${Constants.IMAGE_BASE}$it" } ?: "",
                                    backdrop = details.backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" }
                                )
                            )
                        }
                    }
                    "show" -> {
                        val tmdbId = item.show?.ids?.tmdb ?: continue
                        val detailsResult = runCatching {
                            tmdbApi.getTvDetails(tmdbId, Constants.TMDB_API_KEY)
                        }
                        if (detailsResult.isSuccess) {
                            val details = detailsResult.getOrNull()!!
                            items.add(
                                MediaItem(
                                    id = tmdbId,
                                    title = details.name,
                                    subtitle = "TV Series",
                                    overview = details.overview ?: "",
                                    year = details.firstAirDate?.take(4) ?: "",
                                    imdbRating = String.format("%.1f", details.voteAverage),
                                    mediaType = MediaType.TV,
                                    image = details.backdropPath?.let { "${Constants.BACKDROP_BASE}$it" }
                                        ?: details.posterPath?.let { "${Constants.IMAGE_BASE}$it" } ?: "",
                                    backdrop = details.backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" }
                                )
                            )
                        }
                    }
                }
            }

            items
        }
    }

    suspend fun addToWatchlist(mediaType: MediaType, tmdbId: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val body = if (mediaType == MediaType.MOVIE) {
                TraktWatchlistBody(movies = listOf(TraktMovieId(TraktIds(tmdb = tmdbId))))
            } else {
                TraktWatchlistBody(shows = listOf(TraktShowId(TraktIds(tmdb = tmdbId))))
            }
            traktApi.addToWatchlist(auth, clientId, "2", body)
        }
    }

    suspend fun removeFromWatchlist(mediaType: MediaType, tmdbId: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val body = if (mediaType == MediaType.MOVIE) {
                TraktWatchlistBody(movies = listOf(TraktMovieId(TraktIds(tmdb = tmdbId))))
            } else {
                TraktWatchlistBody(shows = listOf(TraktShowId(TraktIds(tmdb = tmdbId))))
            }
            traktApi.removeFromWatchlist(auth, clientId, "2", body)
        }
    }

    suspend fun checkInWatchlist(mediaType: MediaType, tmdbId: Int): Result<Boolean> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val watchlist = traktApi.getWatchlist(auth, clientId)
            watchlist.any { item ->
                when (item.type) {
                    "movie" -> item.movie?.ids?.tmdb == tmdbId
                    "show" -> item.show?.ids?.tmdb == tmdbId
                    else -> false
                }
            }
        }
    }

    // ========== Collection Management (Like NuvioStreaming) ==========

    /**
     * Get user's movie collection
     */
    suspend fun getCollectionMovies(): Result<List<TraktCollectionMovie>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.getCollectionMovies(auth, clientId)
        }
    }

    /**
     * Get user's show collection
     */
    suspend fun getCollectionShows(): Result<List<TraktCollectionShow>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.getCollectionShows(auth, clientId)
        }
    }

    /**
     * Add movie to collection
     */
    suspend fun addMovieToCollection(tmdbId: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.addToCollection(
                auth, clientId, "2",
                TraktCollectionBody(movies = listOf(TraktMovieId(TraktIds(tmdb = tmdbId))))
            )
        }
    }

    /**
     * Add show to collection
     */
    suspend fun addShowToCollection(tmdbId: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.addToCollection(
                auth, clientId, "2",
                TraktCollectionBody(shows = listOf(TraktShowId(TraktIds(tmdb = tmdbId))))
            )
        }
    }

    /**
     * Remove movie from collection
     */
    suspend fun removeMovieFromCollection(tmdbId: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.removeFromCollection(
                auth, clientId, "2",
                TraktCollectionBody(movies = listOf(TraktMovieId(TraktIds(tmdb = tmdbId))))
            )
        }
    }

    /**
     * Remove show from collection
     */
    suspend fun removeShowFromCollection(tmdbId: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.removeFromCollection(
                auth, clientId, "2",
                TraktCollectionBody(shows = listOf(TraktShowId(TraktIds(tmdb = tmdbId))))
            )
        }
    }

    /**
     * Check if movie is in collection
     */
    suspend fun isMovieInCollection(tmdbId: Int): Result<Boolean> {
        return runCatching {
            val collectionResult = getCollectionMovies()
            val collection = when (collectionResult) {
                is Result.Success -> collectionResult.data
                is Result.Error -> throw collectionResult.exception
            }
            collection.any { it.movie.ids.tmdb == tmdbId }
        }
    }

    /**
     * Check if show is in collection
     */
    suspend fun isShowInCollection(tmdbId: Int): Result<Boolean> {
        return runCatching {
            val collectionResult = getCollectionShows()
            val collection = when (collectionResult) {
                is Result.Success -> collectionResult.data
                is Result.Error -> throw collectionResult.exception
            }
            collection.any { it.show.ids.tmdb == tmdbId }
        }
    }

    // ========== Ratings (Like NuvioStreaming) ==========

    /**
     * Get user's movie ratings
     */
    suspend fun getRatingsMovies(): Result<List<TraktRatingItem>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.getRatingsMovies(auth, clientId)
        }
    }

    /**
     * Get user's show ratings
     */
    suspend fun getRatingsShows(): Result<List<TraktRatingItem>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.getRatingsShows(auth, clientId)
        }
    }

    /**
     * Get user's episode ratings
     */
    suspend fun getRatingsEpisodes(): Result<List<TraktRatingItem>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.getRatingsEpisodes(auth, clientId)
        }
    }

    /**
     * Rate a movie (1-10)
     */
    suspend fun rateMovie(tmdbId: Int, rating: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.addRating(
                auth, clientId, "2",
                TraktRatingBody(
                    movies = listOf(TraktRatingMovieItem(rating = rating, ids = TraktIds(tmdb = tmdbId)))
                )
            )
        }
    }

    /**
     * Rate a show (1-10)
     */
    suspend fun rateShow(tmdbId: Int, rating: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.addRating(
                auth, clientId, "2",
                TraktRatingBody(
                    shows = listOf(TraktRatingShowItem(rating = rating, ids = TraktIds(tmdb = tmdbId)))
                )
            )
        }
    }

    /**
     * Rate an episode (1-10)
     */
    suspend fun rateEpisode(showTmdbId: Int, season: Int, episode: Int, rating: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.addRating(
                auth, clientId, "2",
                TraktRatingBody(
                    episodes = listOf(
                        TraktRatingEpisodeItem(
                            rating = rating,
                            ids = TraktIds(tmdb = showTmdbId),
                            season = season,
                            number = episode
                        )
                    )
                )
            )
        }
    }

    /**
     * Remove movie rating
     */
    suspend fun removeMovieRating(tmdbId: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.removeRating(
                auth, clientId, "2",
                TraktRatingBody(
                    movies = listOf(TraktRatingMovieItem(rating = 0, ids = TraktIds(tmdb = tmdbId)))
                )
            )
        }
    }

    /**
     * Get movie rating (null if not rated)
     */
    suspend fun getMovieRating(tmdbId: Int): Result<Int?> {
        return runCatching {
            val ratingsResult = getRatingsMovies()
            val ratings = when (ratingsResult) {
                is Result.Success -> ratingsResult.data
                is Result.Error -> throw ratingsResult.exception
            }
            ratings.find { it.movie?.ids?.tmdb == tmdbId }?.rating
        }
    }

    /**
     * Get show rating (null if not rated)
     */
    suspend fun getShowRating(tmdbId: Int): Result<Int?> {
        return runCatching {
            val ratingsResult = getRatingsShows()
            val ratings = when (ratingsResult) {
                is Result.Success -> ratingsResult.data
                is Result.Error -> throw ratingsResult.exception
            }
            ratings.find { it.show?.ids?.tmdb == tmdbId }?.rating
        }
    }

    // ========== Comments (Like NuvioStreaming) ==========

    /**
     * Get movie comments
     */
    suspend fun getMovieComments(tmdbId: Int, page: Int = 1, limit: Int = 10): Result<List<TraktComment>> {
        return runCatching {
            traktApi.getMovieComments(clientId, "2", tmdbId.toString(), page, limit)
        }
    }

    /**
     * Get show comments
     */
    suspend fun getShowComments(tmdbId: Int, page: Int = 1, limit: Int = 10): Result<List<TraktComment>> {
        return runCatching {
            traktApi.getShowComments(clientId, "2", tmdbId.toString(), page, limit)
        }
    }

    /**
     * Get season comments
     */
    suspend fun getSeasonComments(showTmdbId: Int, season: Int, page: Int = 1, limit: Int = 10): Result<List<TraktComment>> {
        return runCatching {
            traktApi.getSeasonComments(clientId, "2", showTmdbId.toString(), season, page, limit)
        }
    }

    /**
     * Get episode comments
     */
    suspend fun getEpisodeComments(showTmdbId: Int, season: Int, episode: Int, page: Int = 1, limit: Int = 10): Result<List<TraktComment>> {
        return runCatching {
            traktApi.getEpisodeComments(clientId, "2", showTmdbId.toString(), season, episode, page, limit)
        }
    }

    // ========== Bulk Watch Operations (Like NuvioStreaming) ==========

    /**
     * Mark entire season as watched
     */
    suspend fun markSeasonWatched(showTmdbId: Int, seasonNumber: Int, episodes: List<Int>): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val episodeIds = episodes.map {
                TraktEpisodeId(
                    ids = TraktIds(tmdb = showTmdbId),
                    season = seasonNumber,
                    number = it
                )
            }
            traktApi.addToHistory(
                auth, clientId, "2",
                TraktHistoryBody(episodes = episodeIds)
            )
            // Update cache for all episodes
            episodes.forEach { ep ->
                updateWatchedCache(showTmdbId, seasonNumber, ep, true)
            }
        }
    }

    /**
     * Mark entire show as watched
     */
    suspend fun markShowWatched(tmdbId: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.addToHistory(
                auth, clientId, "2",
                TraktHistoryBody(shows = listOf(TraktShowId(TraktIds(tmdb = tmdbId))))
            )
        }
    }

    suspend fun markShowUnwatched(tmdbId: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.removeFromHistory(
                auth, clientId, "2",
                TraktHistoryBody(shows = listOf(TraktShowId(TraktIds(tmdb = tmdbId))))
            )
        }
    }

    /**
     * Mark multiple episodes as watched (batch)
     */
    suspend fun markEpisodesWatched(showTmdbId: Int, episodes: List<Pair<Int, Int>>): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val episodeIds = episodes.map { (season, episode) ->
                TraktEpisodeId(
                    ids = TraktIds(tmdb = showTmdbId),
                    season = season,
                    number = episode
                )
            }
            traktApi.addToHistory(
                auth, clientId, "2",
                TraktHistoryBody(episodes = episodeIds)
            )
            // Update cache
            episodes.forEach { (season, ep) ->
                updateWatchedCache(showTmdbId, season, ep, true)
            }
        }
    }

    /**
     * Remove season from history
     */
    suspend fun removeSeasonFromHistory(showTmdbId: Int, seasonNumber: Int, episodes: List<Int>): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            val episodeIds = episodes.map {
                TraktEpisodeId(
                    ids = TraktIds(tmdb = showTmdbId),
                    season = seasonNumber,
                    number = it
                )
            }
            traktApi.removeFromHistory(
                auth, clientId, "2",
                TraktHistoryBody(episodes = episodeIds)
            )
            // Update cache
            episodes.forEach { ep ->
                updateWatchedCache(showTmdbId, seasonNumber, ep, false)
            }
        }
    }

    /**
     * Remove show from history
     */
    suspend fun removeShowFromHistory(tmdbId: Int): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.removeFromHistory(
                auth, clientId, "2",
                TraktHistoryBody(shows = listOf(TraktShowId(TraktIds(tmdb = tmdbId))))
            )
        }
    }

    /**
     * Remove items from history by history IDs
     */
    suspend fun removeFromHistoryByIds(ids: List<Long>): Result<Unit> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.removeFromHistoryByIds(
                auth, clientId, "2",
                TraktHistoryRemoveBody(ids = ids)
            )
        }
    }

    // ========== History (Paginated) ==========

    /**
     * Get paginated movie history
     */
    suspend fun getHistoryMovies(page: Int = 1, limit: Int = 20): Result<List<TraktHistoryItem>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.getHistoryMovies(auth, clientId, "2", page, limit)
        }
    }

    /**
     * Get paginated episode history
     */
    suspend fun getHistoryEpisodes(page: Int = 1, limit: Int = 20): Result<List<TraktHistoryItem>> {
        return runCatching {
            val auth = getAuthHeader()
                ?: throw AppException.Auth("Not authenticated")
            traktApi.getHistoryEpisodes(auth, clientId, "2", page, limit)
        }
    }

    // ========== Local Watched Status Cache (Like NuvioStreaming) ==========

    // In-memory cache for watched status (mirrors Supabase data)
    private val watchedMoviesCache = mutableSetOf<Int>()
    private val watchedEpisodesCache = mutableSetOf<String>()
    private var cacheInitialized = false

    /**
     * Invalidate watched cache - forces reload on next access
     * Call this after sync operations to pick up new data
     */
    fun invalidateWatchedCache() {
        cacheInitialized = false
        watchedMoviesCache.clear()
        watchedEpisodesCache.clear()
    }

    /**
     * Initialize watched cache from Supabase (source of truth)
     * Falls back to Trakt if Supabase data is not available
     */
    suspend fun initializeWatchedCache() {
        if (cacheInitialized) return
        runCatching {
            // Try to load from Supabase first (source of truth)
            val supabaseMovies = syncService.getWatchedMovies()
            val supabaseEpisodes = syncService.getWatchedEpisodes()

            val traktMoviesResult = if (supabaseMovies.isEmpty()) getWatchedMovies() else Result.success(emptySet())
            val traktEpisodesResult = if (supabaseEpisodes.isEmpty()) getWatchedEpisodes() else Result.success(emptySet())

            val traktMovies = traktMoviesResult.getOrDefault(emptySet())
            val traktEpisodes = traktEpisodesResult.getOrDefault(emptySet())

            watchedMoviesCache.clear()
            watchedMoviesCache.addAll(if (supabaseMovies.isNotEmpty()) supabaseMovies else traktMovies)

            watchedEpisodesCache.clear()
            watchedEpisodesCache.addAll(if (supabaseEpisodes.isNotEmpty()) supabaseEpisodes else traktEpisodes)

            cacheInitialized = true
        }.onError {
            // If sync service fails, try direct Trakt load
            runCatching {
                watchedMoviesCache.clear()
                val moviesResult = getWatchedMovies()
                watchedMoviesCache.addAll(moviesResult.getOrDefault(emptySet()))
                watchedEpisodesCache.clear()
                val episodesResult = getWatchedEpisodes()
                watchedEpisodesCache.addAll(episodesResult.getOrDefault(emptySet()))
                cacheInitialized = true
            }
        }
    }

    /**
     * Update watched cache entry
     */
    private fun updateWatchedCache(tmdbId: Int, season: Int?, episode: Int?, watched: Boolean) {
        if (season == null || episode == null) {
            // Movie
            if (watched) {
                watchedMoviesCache.add(tmdbId)
            } else {
                watchedMoviesCache.remove(tmdbId)
            }
        } else {
            // Episode
            val key = buildEpisodeKey(
                traktEpisodeId = null,
                showTraktId = null,
                showTmdbId = tmdbId,
                season = season,
                episode = episode
            ) ?: return
            if (watched) {
                watchedEpisodesCache.add(key)
            } else {
                watchedEpisodesCache.remove(key)
            }
        }
    }

    /**
     * Check if movie is watched (uses cache)
     */
    fun isMovieWatched(tmdbId: Int): Boolean {
        return watchedMoviesCache.contains(tmdbId)
    }

    /**
     * Check if episode is watched (uses cache)
     */
    fun isEpisodeWatched(tmdbId: Int, season: Int, episode: Int): Boolean {
        val key = buildEpisodeKey(
            traktEpisodeId = null,
            showTraktId = null,
            showTmdbId = tmdbId,
            season = season,
            episode = episode
        ) ?: return false
        return watchedEpisodesCache.contains(key)
    }

    /**
     * Get all watched movie IDs from cache
     */
    fun getWatchedMoviesFromCache(): Set<Int> = watchedMoviesCache.toSet()

    /**
     * Get all watched episode keys from cache
     */
    fun getWatchedEpisodesFromCache(): Set<String> {
        return watchedEpisodesCache.toSet()
    }

    // ========== Background Sync ==========

    /**
     * Sync watched history from Trakt - used by background worker
     * Pre-fetches and caches watched movies and episodes using the local cache
     */
    suspend fun syncWatchedHistory(): Result<Unit> {
        return runCatching {
            if (getAuthHeader() == null) {
                throw AppException.Auth("Not authenticated")
            }
            // Invalidate cache and re-initialize to get fresh data
            invalidateWatchedCache()
            initializeWatchedCache()
        }
    }
}

/**
 * Continue watching item model
 */
data class ContinueWatchingItem(
    val id: Int,
    val title: String,
    val mediaType: MediaType,
    val progress: Int, // 0-100
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val backdropPath: String? = null,
    val posterPath: String? = null,
    val year: String = "",
    val releaseDate: String = "",  // Full formatted date
    val isUpNext: Boolean = false,
    val overview: String = "",
    val imdbRating: String = "",
    val duration: String = "",
    val budget: Long? = null
) {
    fun toMediaItem(): MediaItem {
        val subtitle = if (mediaType == MediaType.TV && season != null && episode != null) {
            "S${season}:E${episode}" + (episodeTitle?.let { " - $it" } ?: "")
        } else {
            if (mediaType == MediaType.MOVIE) "Movie" else "TV Series"
        }

        // Create NextEpisode for TV shows with season/episode info
        val nextEp = if (mediaType == MediaType.TV && season != null && episode != null) {
            NextEpisode(
                id = 0, // Not needed for navigation
                seasonNumber = season,
                episodeNumber = episode,
                name = episodeTitle ?: "Episode $episode"
            )
        } else null

        return MediaItem(
            id = id,
            title = title,
            subtitle = subtitle,
            overview = overview,
            year = year,
            releaseDate = releaseDate,
            imdbRating = imdbRating,
            duration = duration,
            mediaType = mediaType,
            progress = progress,
            image = backdropPath ?: posterPath ?: "",
            backdrop = backdropPath,
            badge = null, // No badge needed
            budget = budget,
            nextEpisode = nextEp
        )
    }
}

private data class ContinueWatchingCandidate(
    val item: ContinueWatchingItem,
    val lastActivityAt: String
)

/**
 * Format date from "yyyy-MM-dd" to "MMMM d, yyyy" (e.g., "December 16, 2025")
 */
private fun formatDateString(dateStr: String?): String {
    if (dateStr.isNullOrEmpty()) return ""
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outputFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
        val date = inputFormat.parse(dateStr)
        date?.let { outputFormat.format(it) } ?: ""
    } catch (e: Exception) {
        ""
    }
}

private fun buildEpisodeKey(
    traktEpisodeId: Int?,
    showTraktId: Int?,
    showTmdbId: Int?,
    season: Int?,
    episode: Int?
): String? {
    return when {
        traktEpisodeId != null -> "trakt:$traktEpisodeId"
        showTraktId != null && season != null && episode != null -> "show_trakt:$showTraktId:$season:$episode"
        showTmdbId != null && season != null && episode != null -> "show_tmdb:$showTmdbId:$season:$episode"
        else -> null
    }
}
