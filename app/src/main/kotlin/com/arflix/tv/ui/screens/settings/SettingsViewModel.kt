package com.arflix.tv.ui.screens.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.api.TraktDeviceCode
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.repository.AuthRepository
import com.arflix.tv.data.repository.AuthState
import com.arflix.tv.data.repository.CatalogRepository
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.TraktSyncService
import com.arflix.tv.data.repository.SyncProgress
import com.arflix.tv.data.repository.SyncStatus
import com.arflix.tv.data.repository.SyncResult
import com.arflix.tv.util.settingsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ToastType {
    SUCCESS, ERROR, INFO
}

data class SettingsUiState(
    val defaultSubtitle: String = "Off",
    val subtitleOptions: List<String> = emptyList(),
    val autoPlayNext: Boolean = true,
    val includeSpecials: Boolean = false,
    val isLoggedIn: Boolean = false,
    val accountEmail: String? = null,
    // Trakt
    val isTraktAuthenticated: Boolean = false,
    val traktCode: TraktDeviceCode? = null,
    val isTraktPolling: Boolean = false,
    val traktExpiration: String? = null,
    // Trakt Sync
    val isSyncing: Boolean = false,
    val syncProgress: SyncProgress = SyncProgress(),
    val lastSyncTime: String? = null,
    val syncedMovies: Int = 0,
    val syncedEpisodes: Int = 0,
    // IPTV
    val iptvM3uUrl: String = "",
    val iptvEpgUrl: String = "",
    val iptvChannelCount: Int = 0,
    val isIptvLoading: Boolean = false,
    val iptvError: String? = null,
    val iptvStatusMessage: String? = null,
    val iptvStatusType: ToastType = ToastType.INFO,
    val iptvProgressText: String? = null,
    val iptvProgressPercent: Int = 0,
    // Catalogs
    val catalogs: List<CatalogConfig> = emptyList(),
    // Addons
    val addons: List<Addon> = emptyList(),
    // Toast
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.INFO
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktRepository: TraktRepository,
    private val streamRepository: StreamRepository,
    private val mediaRepository: MediaRepository,
    private val catalogRepository: CatalogRepository,
    private val iptvRepository: IptvRepository,
    private val authRepository: AuthRepository,
    private val traktSyncService: TraktSyncService
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val DEFAULT_SUBTITLE_KEY = stringPreferencesKey("default_subtitle")
    private val SUBTITLE_USAGE_KEY = stringPreferencesKey("subtitle_usage_v1")
    private val AUTO_PLAY_NEXT_KEY = booleanPreferencesKey("auto_play_next")
    private val INCLUDE_SPECIALS_KEY = booleanPreferencesKey("include_specials")
    private val gson = Gson()
    private var lastObservedIptvM3u: String = ""

    private var traktPollingJob: Job? = null
    private var iptvLoadJob: Job? = null

    init {
        loadSettings()
        observeAddons()
        observeSyncState()
        observeAuthState()
        observeIptvConfig()
        initializeCatalogs()
        observeCatalogs()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            // Load local preferences first
            val prefs = context.settingsDataStore.data.first()
            var defaultSub = prefs[DEFAULT_SUBTITLE_KEY] ?: "Off"
            var autoPlay = prefs[AUTO_PLAY_NEXT_KEY] ?: true
            val includeSpecials = prefs[INCLUDE_SPECIALS_KEY] ?: false

            // Try to load from cloud profile (takes priority if user is logged in)
            val cloudSubtitle = authRepository.getDefaultSubtitleFromProfile()
            val cloudAutoPlay = authRepository.getAutoPlayNextFromProfile()

            // Use cloud values if available, sync to local
            if (cloudSubtitle != null && cloudSubtitle != defaultSub) {
                defaultSub = cloudSubtitle
                context.settingsDataStore.edit { it[DEFAULT_SUBTITLE_KEY] = defaultSub }
            }
            if (cloudAutoPlay != null && cloudAutoPlay != autoPlay) {
                autoPlay = cloudAutoPlay
                context.settingsDataStore.edit { it[AUTO_PLAY_NEXT_KEY] = autoPlay }
            }

            // Check auth statuses
            val authState = authRepository.authState.first()
            val isLoggedIn = authState is AuthState.Authenticated
            val accountEmail = (authState as? AuthState.Authenticated)?.email
            val isTrakt = traktRepository.isAuthenticated.first()

            // Get Trakt expiration if authenticated
            var traktExpiration: String? = null
            if (isTrakt) {
                traktExpiration = traktRepository.getTokenExpirationDate()
            }

            // Load addons immediately to avoid showing 0
            val addons = streamRepository.installedAddons.first()
            val subtitleOptions = loadSubtitleOptions(defaultSub)
            val iptvConfig = iptvRepository.observeConfig().first()
            val existingCatalogs = _uiState.value.catalogs.ifEmpty {
                mediaRepository.getDefaultCatalogConfigs()
            }

            _uiState.value = SettingsUiState(
                defaultSubtitle = defaultSub,
                subtitleOptions = subtitleOptions,
                autoPlayNext = autoPlay,
                includeSpecials = includeSpecials,
                isLoggedIn = isLoggedIn,
                accountEmail = accountEmail,
                isTraktAuthenticated = isTrakt,
                traktExpiration = traktExpiration,
                iptvM3uUrl = iptvConfig.m3uUrl,
                iptvEpgUrl = iptvConfig.epgUrl,
                catalogs = existingCatalogs,
                addons = addons
            )
        }
    }

    fun refreshSubtitleOptions() {
        viewModelScope.launch {
            val options = loadSubtitleOptions(_uiState.value.defaultSubtitle)
            _uiState.value = _uiState.value.copy(subtitleOptions = options)
        }
    }
    
    private fun observeAddons() {
        viewModelScope.launch {
            streamRepository.installedAddons.collect { addons ->
                _uiState.value = _uiState.value.copy(addons = addons)
            }
        }
    }

    private fun observeSyncState() {
        // Observe sync progress
        viewModelScope.launch {
            traktSyncService.syncProgress.collect { progress ->
                _uiState.value = _uiState.value.copy(syncProgress = progress)
            }
        }

        // Observe sync status
        viewModelScope.launch {
            traktSyncService.isSyncing.collect { isSyncing ->
                _uiState.value = _uiState.value.copy(isSyncing = isSyncing)
            }
        }

        // Load last sync time
        viewModelScope.launch {
            val lastSync = traktSyncService.getLastSyncTime()
            _uiState.value = _uiState.value.copy(lastSyncTime = formatSyncTime(lastSync))
        }
    }

    private fun formatSyncTime(isoTime: String?): String? {
        if (isoTime == null) return null
        return try {
            val instant = java.time.Instant.parse(isoTime)
            val formatter = java.time.format.DateTimeFormatter
                .ofPattern("MMM dd, yyyy 'at' h:mm a")
                .withZone(java.time.ZoneId.systemDefault())
            formatter.format(instant)
        } catch (e: Exception) {
            null
        }
    }

    // ========== Trakt Sync ==========

    fun performFullSync(silent: Boolean = false) {
        viewModelScope.launch {
            if (_uiState.value.isSyncing) return@launch
            val result = traktSyncService.performFullSync()
            when (result) {
                is SyncResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        syncedMovies = result.moviesSynced,
                        syncedEpisodes = result.episodesSynced,
                        lastSyncTime = formatSyncTime(java.time.Instant.now().toString()),
                        toastMessage = "Synced ${result.moviesSynced} movies and ${result.episodesSynced} episodes",
                        toastType = ToastType.SUCCESS
                    )
                    // Invalidate repository cache to pick up new data
                    traktRepository.invalidateWatchedCache()
                    traktRepository.initializeWatchedCache()
                }
                is SyncResult.Error -> {
                    if (!silent) {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Sync failed: ${result.message}",
                            toastType = ToastType.ERROR
                        )
                    }
                }
            }
        }
    }

    fun performIncrementalSync() {
        viewModelScope.launch {
            val result = traktSyncService.performIncrementalSync()
            when (result) {
                is SyncResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        syncedMovies = _uiState.value.syncedMovies + result.moviesSynced,
                        syncedEpisodes = _uiState.value.syncedEpisodes + result.episodesSynced,
                        lastSyncTime = formatSyncTime(java.time.Instant.now().toString()),
                        toastMessage = if (result.moviesSynced == 0 && result.episodesSynced == 0)
                            "Already up to date"
                        else
                            "Synced ${result.moviesSynced} movies and ${result.episodesSynced} episodes",
                        toastType = ToastType.SUCCESS
                    )
                    // Invalidate repository cache to pick up new data
                    traktRepository.invalidateWatchedCache()
                    traktRepository.initializeWatchedCache()
                }
                is SyncResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        toastMessage = "Sync failed: ${result.message}",
                        toastType = ToastType.ERROR
                    )
                }
            }
        }
    }
    
    fun setDefaultSubtitle(language: String) {
        viewModelScope.launch {
            // Save locally
            context.settingsDataStore.edit { prefs ->
                prefs[DEFAULT_SUBTITLE_KEY] = language
            }
            _uiState.value = _uiState.value.copy(
                defaultSubtitle = language,
                subtitleOptions = loadSubtitleOptions(language)
            )

            // Sync to cloud
            authRepository.saveDefaultSubtitleToProfile(language)
        }
    }

    private suspend fun loadSubtitleOptions(current: String): List<String> {
        val prefs = context.settingsDataStore.data.first()
        val json = prefs[SUBTITLE_USAGE_KEY]
        val type = TypeToken.getParameterized(Map::class.java, String::class.java, Int::class.javaObjectType).type
        val usage: Map<String, Int> = if (!json.isNullOrBlank()) {
            gson.fromJson(json, type)
        } else {
            emptyMap()
        }

        val topUsed = usage.entries
            .sortedByDescending { it.value }
            .map { entry -> displayLanguage(entry.key) }
            .filter { it.isNotBlank() }
            .take(20)

        val fallback = listOf("English", "Dutch", "Spanish", "French", "German", "Italian")
        val base = buildList {
            add("Off")
            if (current.isNotBlank()) add(current)
            addAll(topUsed)
            if (topUsed.isEmpty()) addAll(fallback)
        }

        return base.distinct().take(21)
    }

    private fun displayLanguage(code: String): String {
        val normalized = code.trim()
        if (normalized.isBlank()) return ""
        val isCode = normalized.length <= 3 && normalized.all { it.isLetter() }
        if (!isCode) return normalized.replaceFirstChar { it.uppercase() }
        val locale = java.util.Locale(normalized)
        val name = locale.getDisplayLanguage(java.util.Locale.ENGLISH)
        return if (name.isNullOrBlank()) normalized else name
    }

    fun setAutoPlayNext(enabled: Boolean) {
        viewModelScope.launch {
            // Save locally
            context.settingsDataStore.edit { prefs ->
                prefs[AUTO_PLAY_NEXT_KEY] = enabled
            }
            _uiState.value = _uiState.value.copy(autoPlayNext = enabled)

            // Sync to cloud
            authRepository.saveAutoPlayNextToProfile(enabled)
        }
    }

    fun setIncludeSpecials(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[INCLUDE_SPECIALS_KEY] = enabled
            }
            _uiState.value = _uiState.value.copy(includeSpecials = enabled)
        }
    }
    
    // ========== Addon Management ==========
    
    fun toggleAddon(addonId: String) {
        viewModelScope.launch {
            streamRepository.toggleAddon(addonId)
        }
    }
    
    fun addCustomAddon(url: String) {
        viewModelScope.launch {
            val result = streamRepository.addCustomAddon(url)
            result.onSuccess { addon ->
                val currentAddons = _uiState.value.addons.toMutableList()
                currentAddons.removeAll { it.id == addon.id }
                currentAddons.add(addon)
                _uiState.value = _uiState.value.copy(
                    addons = currentAddons,
                    toastMessage = "Added ${addon.name}",
                    toastType = ToastType.SUCCESS
                )
            }.onFailure { _ ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to add addon",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authRepository.authState.collect { state ->
                val isLoggedIn = state is AuthState.Authenticated
                val email = (state as? AuthState.Authenticated)?.email
                _uiState.value = _uiState.value.copy(
                    isLoggedIn = isLoggedIn,
                    accountEmail = email
                )
            }
        }
    }

    private fun observeIptvConfig() {
        viewModelScope.launch {
            iptvRepository.observeConfig().collect { config ->
                _uiState.value = _uiState.value.copy(
                    iptvM3uUrl = config.m3uUrl,
                    iptvEpgUrl = config.epgUrl
                )
                if (config.m3uUrl.isNotBlank() && config.m3uUrl != lastObservedIptvM3u) {
                    lastObservedIptvM3u = config.m3uUrl
                    if (iptvLoadJob?.isActive != true) {
                        refreshIptv(showToast = false)
                    }
                } else if (config.m3uUrl.isBlank()) {
                    lastObservedIptvM3u = ""
                    _uiState.value = _uiState.value.copy(
                        iptvChannelCount = 0,
                        iptvError = null,
                        iptvProgressText = null,
                        iptvProgressPercent = 0
                    )
                }
            }
        }
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            catalogRepository.observeCatalogs().collect { catalogs ->
                val effectiveCatalogs = if (catalogs.isEmpty()) {
                    catalogRepository.ensurePreinstalledDefaults(mediaRepository.getDefaultCatalogConfigs())
                } else {
                    catalogs
                }
                _uiState.value = _uiState.value.copy(catalogs = effectiveCatalogs)
            }
        }
    }

    private fun initializeCatalogs() {
        viewModelScope.launch {
            runCatching {
                catalogRepository.ensurePreinstalledDefaults(mediaRepository.getDefaultCatalogConfigs())
            }
        }
    }

    fun addCatalog(url: String) {
        viewModelScope.launch {
            val result = catalogRepository.addCustomCatalog(url)
            result.onSuccess { catalog ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Added ${catalog.title}",
                    toastType = ToastType.SUCCESS
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message ?: "Failed to add catalog",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun updateCatalog(catalogId: String, url: String) {
        viewModelScope.launch {
            val result = catalogRepository.updateCustomCatalog(catalogId, url)
            result.onSuccess { catalog ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Updated ${catalog.title}",
                    toastType = ToastType.SUCCESS
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message ?: "Failed to update catalog",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun removeCatalog(catalogId: String) {
        viewModelScope.launch {
            val result = catalogRepository.removeCustomCatalog(catalogId)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Catalog removed",
                    toastType = ToastType.SUCCESS
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    toastMessage = error.message ?: "Failed to remove catalog",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun moveCatalogUp(catalogId: String) {
        viewModelScope.launch {
            catalogRepository.moveCatalogUp(catalogId)
        }
    }

    fun moveCatalogDown(catalogId: String) {
        viewModelScope.launch {
            catalogRepository.moveCatalogDown(catalogId)
        }
    }

    fun saveIptvConfig(m3uUrl: String, epgUrl: String) {
        viewModelScope.launch {
            val trimmedM3u = m3uUrl.trim()
            val trimmedEpg = epgUrl.trim()
            if (trimmedM3u.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "M3U URL is required",
                    toastType = ToastType.ERROR
                )
                return@launch
            }

            // Prevent duplicate auto-refresh from observer right after save.
            lastObservedIptvM3u = trimmedM3u
            iptvRepository.saveConfig(trimmedM3u, trimmedEpg)
            refreshIptv(showToast = true, configured = true)
        }
    }

    fun refreshIptv(showToast: Boolean = true, configured: Boolean = false) {
        if (_uiState.value.iptvM3uUrl.isBlank()) return
        if (iptvLoadJob?.isActive == true) return

        iptvLoadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isIptvLoading = true, iptvError = null)
            runCatching {
                val snapshot = iptvRepository.loadSnapshot(
                    forcePlaylistReload = true,
                    forceEpgReload = true
                ) { progress ->
                    _uiState.value = _uiState.value.copy(
                        isIptvLoading = true,
                        iptvProgressText = progress.message,
                        iptvProgressPercent = progress.percent ?: _uiState.value.iptvProgressPercent
                    )
                }
                val doneMsg = if (configured) {
                    snapshot.epgWarning ?: "Connected. Loaded ${snapshot.channels.size} channels."
                } else {
                    snapshot.epgWarning ?: "Refreshed ${snapshot.channels.size} channels."
                }
                _uiState.value = _uiState.value.copy(
                    isIptvLoading = false,
                    iptvChannelCount = snapshot.channels.size,
                    iptvError = null,
                    iptvStatusMessage = doneMsg,
                    iptvStatusType = if (snapshot.epgWarning != null) ToastType.INFO else ToastType.SUCCESS,
                    iptvProgressText = "Done",
                    iptvProgressPercent = 100,
                    toastMessage = if (showToast) {
                        if (configured) "IPTV configured (${snapshot.channels.size} channels)" else "IPTV refreshed (${snapshot.channels.size} channels)"
                    } else _uiState.value.toastMessage,
                    toastType = if (showToast) ToastType.SUCCESS else _uiState.value.toastType
                )
            }.onFailure { error ->
                val failMessage = if (configured) "Failed to load IPTV playlist" else "Failed to refresh IPTV"
                _uiState.value = _uiState.value.copy(
                    isIptvLoading = false,
                    iptvError = error.message ?: failMessage,
                    iptvStatusMessage = error.message ?: failMessage,
                    iptvStatusType = ToastType.ERROR,
                    iptvProgressText = null,
                    iptvProgressPercent = 0,
                    toastMessage = if (showToast) failMessage else _uiState.value.toastMessage,
                    toastType = if (showToast) ToastType.ERROR else _uiState.value.toastType
                )
            }
        }.also { job ->
            job.invokeOnCompletion { iptvLoadJob = null }
        }
    }
    
    fun removeAddon(addonId: String) {
        viewModelScope.launch {
            streamRepository.removeAddon(addonId)
        }
    }
    
    // ========== Trakt Authentication ==========
    
    fun startTraktAuth() {
        viewModelScope.launch {
            try {
                val deviceCode = traktRepository.getDeviceCode()
                _uiState.value = _uiState.value.copy(
                    traktCode = deviceCode,
                    isTraktPolling = true
                )
                
                // Start polling for token
                startTraktPolling(deviceCode)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    traktCode = null,
                    isTraktPolling = false
                )
            }
        }
    }
    
    private fun startTraktPolling(deviceCode: TraktDeviceCode) {
        traktPollingJob?.cancel()
        traktPollingJob = viewModelScope.launch {
            val expiresAt = System.currentTimeMillis() + (deviceCode.expiresIn * 1000)
            
            while (System.currentTimeMillis() < expiresAt) {
                delay(deviceCode.interval * 1000L)
                
                try {
                    traktRepository.pollForToken(deviceCode.deviceCode)

                    // Get the expiration date
                    val expirationDate = traktRepository.getTokenExpirationDate()

                    // Success!
                    _uiState.value = _uiState.value.copy(
                        isTraktAuthenticated = true,
                        traktCode = null,
                        isTraktPolling = false,
                        traktExpiration = expirationDate,
                        toastMessage = "Trakt connected successfully",
                        toastType = ToastType.SUCCESS
                    )
                    performFullSync(silent = true)
                    return@launch
                } catch (e: Exception) {
                    // Keep polling on 400 (pending) - user hasn't entered code yet
                    // Check both HttpException code and message for 400
                    val is400 = when (e) {
                        is retrofit2.HttpException -> e.code() == 400
                        else -> e.message?.contains("400") == true ||
                                e.message?.contains("pending") == true
                    }
                    if (!is400) {
                        // Stop on actual error (401, 500, etc.)
                        break
                    }
                    // 400 = pending, continue polling
                }
            }
            
            // Expired or failed
            _uiState.value = _uiState.value.copy(
                traktCode = null,
                isTraktPolling = false
            )
        }
    }
    
    fun cancelTraktAuth() {
        traktPollingJob?.cancel()
        _uiState.value = _uiState.value.copy(
            traktCode = null,
            isTraktPolling = false
        )
    }
    
    fun disconnectTrakt() {
        viewModelScope.launch {
            traktRepository.logout()
            _uiState.value = _uiState.value.copy(
                isTraktAuthenticated = false,
                toastMessage = "Trakt disconnected",
                toastType = ToastType.SUCCESS
            )
        }
    }
    
    fun dismissToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.signOut()
            _uiState.value = _uiState.value.copy(
                toastMessage = "Signed out",
                toastType = ToastType.SUCCESS
            )
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        traktPollingJob?.cancel()
    }
}


