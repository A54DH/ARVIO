## ARVIO v1.6.0

### Added
- Extended Live TV EPG timeline model to support multiple upcoming programs per channel (beyond now/next).
- Per-profile cloud snapshot payload maps for settings, addons, catalogs, IPTV config/favorites, and watchlist.
- Repository helpers for profile-specific export/import of addons, catalogs, IPTV config, and watchlist state.
- Expanded HTTP/HTTPS playback compatibility path for stream sources and header handling.
- IPTV VOD support for both movies and TV shows integrated into source resolution flows.
- Card layout mode toggle for switching between landscape and poster styles.
- Default audio language option in Settings with profile-scoped persistence.

### Changed
- App version updated to 1.6.0 (versionCode 160) and Settings label updated to ARVIO V1.6.
- Live TV EPG lane now uses real upcoming program blocks and only shows filler when timeline data is genuinely unavailable.
- IPTV loading/retry strategy tuned to reduce multi-minute startup delays and improve responsiveness.
- Playback startup buffering strategy rebalanced for movie/TV streams (larger startup gate + safer initial buffer thresholds).
- External subtitle injection timing adjusted to avoid immediate post-start media-item rebuilds.
- Profile boot flow now starts IPTV warm/load earlier after profile selection for faster Live TV readiness.
- Live TV and Settings surfaces received additional UI polish and focus/navigation refinements for Android TV remote use.

### Fixed
- IPTV Refresh action could fail with cancellation errors (StandaloneCoroutine was canceled) and not reload channels.
- Live TV timeline third/fourth blocks incorrectly showing No EPG data despite available EPG entries.
- Cross-profile leakage risk where addon sets could appear across profiles due to account-wide startup sync behavior.
- Profile isolation gaps by moving remaining global settings storage (card layout mode) to profile scope.
- Multiple IPTV EPG parsing paths now keep consistent upcoming-program selection across pull-parser and SAX fallbacks.
- Improved Dolby Vision startup compatibility with automatic codec fallback path (DV -> HEVC -> AVC) before source failover.
