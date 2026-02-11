# Changelog

All notable changes to this project are documented in this file.

## [1.3.0] - 2026-02-11

### Added
- IPTV settings now include a dedicated `Delete M3U Playlist` action to remove configured M3U/EPG and IPTV favorites.
- Updated release screenshots for Catalogs and Live TV (`v1.3`).

### Changed
- Player controls overlay no longer adds a dark background scrim behind play/pause controls.
- Sidebar focus visibility and section handoff behavior improved for clearer TV remote navigation.
- Continue Watching cards show resume timestamp and a subtle progress track.

### Fixed
- Resume metadata flow to keep Continue Watching playback start position aligned with player start.
- Multiple focus/scroll consistency issues across Home/Settings/TV surfaces.

## [1.2.0] - 2026-02-10

### Added
- Live TV page in sidebar with IPTV support.
- M3U playlist configuration in Settings.
- Catalogs tab in Settings for custom Trakt and MDBList URLs.
- Catalog ordering controls (up/down) and deletion for custom catalogs.
- Live TV mini-player flow and expanded TV navigation support.
- New screenshots for Live TV and Catalogs in README.

### Changed
- Home and catalog loading behavior across profiles.
- Focus and scroll behavior improvements across Home, Details, Search, Watchlist, and TV surfaces.
- Player/stream handling refinements for smoother transitions.
- App release version updated to `1.2.0`.

### Fixed
- Continue Watching visibility and persistence regressions.
- Custom catalog rows not appearing on Home in some profile states.
- IPTV and mini-player stability issues including focus restore and state persistence.
- Multiple UI alignment and layout consistency issues in Settings and TV screens.
