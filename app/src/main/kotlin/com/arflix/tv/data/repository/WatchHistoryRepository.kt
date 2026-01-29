package com.arflix.tv.data.repository

import com.arflix.tv.data.api.SupabaseApi
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.AppException
import com.arflix.tv.util.Constants
import com.arflix.tv.util.Result
import com.arflix.tv.util.runCatching
import kotlinx.serialization.Serializable
import retrofit2.HttpException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Watch history entry for Supabase
 */
@Serializable
data class WatchHistoryEntry(
    val id: String? = null,
    val user_id: String,
    val media_type: String, // "movie" or "tv"
    val show_tmdb_id: Int,
    val show_trakt_id: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val trakt_episode_id: Int? = null,
    val tmdb_episode_id: Int? = null,
    val title: String? = null,
    val episode_title: String? = null,
    val progress: Float = 0f, // 0.0-1.0
    val duration_seconds: Long = 0,
    val position_seconds: Long = 0,
    val paused_at: String? = null,
    val updated_at: String? = null,
    val source: String? = null,
    val backdrop_path: String? = null,
    val poster_path: String? = null
)

/**
 * Repository for syncing watch history with Supabase
 */
@Singleton
class WatchHistoryRepository @Inject constructor(
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val supabaseApi: SupabaseApi
) {
    /**
     * Save watch progress to Supabase
     */
    suspend fun saveProgress(
        mediaType: MediaType,
        tmdbId: Int,
        title: String,
        poster: String?,
        backdrop: String?,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
        progress: Float,
        duration: Long,
        position: Long
    ): Result<Unit> {
        val userId = authRepositoryProvider.get().getCurrentUserId()
            ?: return Result.error(AppException.Auth.SESSION_EXPIRED)

        return runCatching {
            val entry = WatchHistoryEntry(
                user_id = userId,
                media_type = if (mediaType == MediaType.MOVIE) "movie" else "tv",
                show_tmdb_id = tmdbId,
                title = title,
                poster_path = poster,
                backdrop_path = backdrop,
                season = season,
                episode = episode,
                episode_title = episodeTitle,
                progress = progress,
                duration_seconds = duration,
                position_seconds = position,
                source = "arvio"
            )

            // Upsert - update if exists, insert if not
            executeSupabaseCall("save watch progress") { auth ->
                supabaseApi.upsertWatchHistory(auth = auth, item = entry.toRecord())
            }
        }
    }

    /**
     * Get watch history for current user
     */
    suspend fun getWatchHistory(): Result<List<WatchHistoryEntry>> {
        val userId = authRepositoryProvider.get().getCurrentUserId()
            ?: return Result.error(AppException.Auth.SESSION_EXPIRED)

        return runCatching {
            executeSupabaseCall("get watch history") { auth ->
                supabaseApi.getWatchHistory(
                    auth = auth,
                    userId = "eq.$userId",
                    order = "updated_at.desc",
                    limit = 500
                )
            }.map { it.toEntry() }
        }
    }

    /**
     * Get continue watching items (progress < 90%)
     */
    suspend fun getContinueWatching(): Result<List<WatchHistoryEntry>> {
        val userId = authRepositoryProvider.get().getCurrentUserId()
            ?: return Result.error(AppException.Auth.SESSION_EXPIRED)

        return runCatching {
            val records = executeSupabaseCall("get continue watching history") { auth ->
                supabaseApi.getWatchHistory(
                    auth = auth,
                    userId = "eq.$userId",
                    order = "updated_at.desc",
                    limit = 500
                )
            }
            val threshold = Constants.WATCHED_THRESHOLD / 100f
            records.filter { it.progress > 0f && it.progress < threshold }.map { it.toEntry() }
        }
    }

    /**
     * Get progress for a specific item
     */
    suspend fun getProgress(
        mediaType: MediaType,
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ): Result<WatchHistoryEntry?> {
        val userId = authRepositoryProvider.get().getCurrentUserId()
            ?: return Result.error(AppException.Auth.SESSION_EXPIRED)

        return runCatching {
            val records = executeSupabaseCall("get watch history item") { auth ->
                supabaseApi.getWatchHistoryItem(
                    auth = auth,
                    userId = "eq.$userId",
                    showTmdbId = "eq.$tmdbId",
                    mediaType = "eq.${if (mediaType == MediaType.MOVIE) "movie" else "tv"}",
                    season = season?.let { "eq.$it" },
                    episode = episode?.let { "eq.$it" }
                )
            }
            records.firstOrNull()?.toEntry()
        }
    }

    /**
     * Get the latest in-progress entry for a show/movie.
     */
    suspend fun getLatestProgress(
        mediaType: MediaType,
        tmdbId: Int
    ): Result<WatchHistoryEntry?> {
        val userId = authRepositoryProvider.get().getCurrentUserId()
            ?: return Result.error(AppException.Auth.SESSION_EXPIRED)
        val mediaTypeKey = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        val threshold = Constants.WATCHED_THRESHOLD / 100f

        return runCatching {
            val records = executeSupabaseCall("get watch history by show") { auth ->
                supabaseApi.getWatchHistoryItem(
                    auth = auth,
                    userId = "eq.$userId",
                    showTmdbId = "eq.$tmdbId",
                    mediaType = "eq.$mediaTypeKey",
                    order = "updated_at.desc",
                    limit = 1
                )
            }
            records
                .map { it.toEntry() }
                .filter { it.progress > 0f && it.progress < threshold }
                .maxByOrNull { entry ->
                    parseEpoch(entry.updated_at).coerceAtLeast(parseEpoch(entry.paused_at))
                }
        }
    }

    /**
     * Remove item from watch history
     */
    suspend fun removeFromHistory(
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ): Result<Unit> {
        val userId = authRepositoryProvider.get().getCurrentUserId()
            ?: return Result.error(AppException.Auth.SESSION_EXPIRED)

        return runCatching {
            executeSupabaseCall("remove watch history item") { auth ->
                supabaseApi.deleteWatchHistory(
                    auth = auth,
                    userId = "eq.$userId",
                    showTmdbId = "eq.$tmdbId",
                    season = season?.let { "eq.$it" },
                    episode = episode?.let { "eq.$it" }
                )
            }
        }
    }

    /**
     * Clear all watch history
     */
    suspend fun clearHistory(): Result<Unit> {
        val userId = authRepositoryProvider.get().getCurrentUserId()
            ?: return Result.error(AppException.Auth.SESSION_EXPIRED)

        return runCatching {
            executeSupabaseCall("clear watch history") { auth ->
                supabaseApi.deleteWatchHistory(
                    auth = auth,
                    userId = "eq.$userId"
                )
            }
        }
    }

    private suspend fun <T> executeSupabaseCall(
        operation: String,
        block: suspend (String) -> T
    ): T {
        val auth = getSupabaseAuth()
            ?: throw AppException.Auth("Supabase auth failed", errorCode = "ERR_SUPABASE_AUTH")
        return try {
            block(auth)
        } catch (e: HttpException) {
            if (e.code() == 401) {
                val refreshed = authRepositoryProvider.get().refreshAccessToken()
                if (!refreshed.isNullOrBlank()) {
                    return block("Bearer $refreshed")
                }
            }
            throw e
        }
    }

    private suspend fun getSupabaseAuth(): String? {
        val authRepository = authRepositoryProvider.get()
        val token = authRepository.getAccessToken()
        if (!token.isNullOrBlank()) return "Bearer $token"
        val refreshed = authRepository.refreshAccessToken()
        return refreshed?.let { "Bearer $it" }
    }

    private fun parseEpoch(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

private fun WatchHistoryEntry.toRecord(): com.arflix.tv.data.api.WatchHistoryRecord {
    return com.arflix.tv.data.api.WatchHistoryRecord(
        userId = user_id,
        mediaType = media_type,
        showTmdbId = show_tmdb_id,
        showTraktId = show_trakt_id,
        season = season,
        episode = episode,
        traktEpisodeId = trakt_episode_id,
        tmdbEpisodeId = tmdb_episode_id,
        progress = progress,
        positionSeconds = position_seconds,
        durationSeconds = duration_seconds,
        pausedAt = paused_at,
        updatedAt = updated_at,
        source = source,
        title = title,
        episodeTitle = episode_title,
        backdropPath = backdrop_path,
        posterPath = poster_path
    )
}

private fun com.arflix.tv.data.api.WatchHistoryRecord.toEntry(): WatchHistoryEntry {
    return WatchHistoryEntry(
        id = id,
        user_id = userId,
        media_type = mediaType,
        show_tmdb_id = showTmdbId ?: 0,
        show_trakt_id = showTraktId,
        season = season,
        episode = episode,
        trakt_episode_id = traktEpisodeId,
        tmdb_episode_id = tmdbEpisodeId,
        title = title,
        episode_title = episodeTitle,
        progress = progress,
        duration_seconds = durationSeconds,
        position_seconds = positionSeconds,
        paused_at = pausedAt,
        updated_at = updatedAt,
        source = source,
        backdrop_path = backdropPath,
        poster_path = posterPath
    )
}
