# Roadmap: ARVIO Quality & Stability

**Created:** 2026-01-29
**Milestone:** v1.0 Production Release
**Core Value:** Users can browse, discover, and stream content reliably on their TV with a smooth, crash-free experience.

## Phase Overview

| Phase | Name | Requirements | Status |
|-------|------|--------------|--------|
| 1 | Critical Security | SEC-01, SEC-02, SEC-04, SEC-05 | Pending |
| 2 | Error Handling Foundation | SEC-03, ERR-02, ERR-04 | Pending |
| 3 | Repository Migration | ERR-01, ERR-03, ERR-05, ERR-06, DAT-01, DAT-02, QUA-02, QUA-03, QUA-04 | Pending |
| 4 | Observability | OBS-01, OBS-02, OBS-03, OBS-04 | Pending |
| 5 | Static Analysis | QUA-01 | Pending |
| 6 | Testing Infrastructure | TST-01, TST-02, TST-03 | Pending |
| 7 | Build Hardening | BLD-01, BLD-02, BLD-03 | Pending |

## Phases

### Phase 1: Critical Security

**Goal:** Remove hardcoded API keys from source code and fix SSL configuration.

**Requirements:**
- SEC-01: API keys stored in BuildConfig via Secrets Gradle Plugin
- SEC-02: API keys protected with provider restrictions
- SEC-04: SSL validation uses NetworkSecurityConfig
- SEC-05: Comprehensive .gitignore prevents secrets from version control

**Delivers:**
- `secrets.properties` file (gitignored) with all API keys
- Secrets Gradle Plugin 2.0.1 integration
- BuildConfig injection for TMDB, Trakt, Supabase, Google OAuth keys
- NetworkSecurityConfig.xml with debug-only certificate overrides
- Comprehensive .gitignore at project root

**Success Criteria:**
- [ ] `grep -r "API_KEY\|SECRET\|KEY=" app/src/` returns zero results in source files
- [ ] App builds and runs successfully with secrets from secrets.properties
- [ ] SSL errors only in debug when intercepting with proxy (release is strict)
- [ ] `git status` shows no secrets.properties tracked

**Research:** Standard patterns (skip research-phase)

---

### Phase 2: Error Handling Foundation

**Goal:** Establish infrastructure for type-safe error handling across the app.

**Requirements:**
- SEC-03: JWT validation rejects tokens with missing exp claim
- ERR-02: AppException hierarchy distinguishes error types
- ERR-04: NetworkMonitor provides connectivity state

**Delivers:**
- `Result<T>` sealed class with Success/Error variants
- `AppException` hierarchy: NetworkException, AuthException, ServerException, UnknownException
- `UiState<T>` sealed class with Loading/Success/Error/Idle variants
- `NetworkMonitor` class wrapping ConnectivityManager
- `NetworkStateInterceptor` for OkHttp to detect offline state
- JWT validation fix in AuthRepository

**Success Criteria:**
- [ ] `Result<T>` class compiles and can wrap any return type
- [ ] `AppException` types distinguish network/auth/server/unknown errors
- [ ] `NetworkMonitor.isConnected()` returns accurate state
- [ ] JWT tokens without `exp` claim are rejected (test case passes)

**Research:** Standard patterns (skip research-phase)

---

### Phase 3: Repository Migration

**Goal:** Migrate all repositories to Result<T> pattern with proper error handling.

**Requirements:**
- ERR-01: All repository methods return Result<T>
- ERR-03: ViewModels expose StateFlow<UiState<T>>
- ERR-05: Error messages include error codes
- ERR-06: Retry mechanisms use exponential backoff
- DAT-01: Pagination guard on getAllPlaybackProgress
- DAT-02: Stream API uses typed models
- QUA-02: Magic numbers extracted to Constants
- QUA-03: Stream resolution logic deduplicated
- QUA-04: dp/sp values extracted to theme

**Delivers:**
- AuthRepository migrated to Result<T>
- TmdbRepository migrated to Result<T>
- TraktRepository migrated to Result<T>
- StreamRepository migrated to Result<T> with deduplicated logic
- PlaybackProgressRepository with pagination guard (max 1000 items per page)
- ViewModels using StateFlow<UiState<T>> pattern
- Constants.kt with extracted magic numbers
- Theme dimensions extracted

**Success Criteria:**
- [ ] No nullable return types in public repository methods
- [ ] All ViewModels emit UiState sealed class states
- [ ] `getAllPlaybackProgress()` uses pagination with configurable page size
- [ ] Stream resolution for movie and episode uses shared method
- [ ] No hardcoded dp/sp values in UI code

**Research:** Needs phase-specific research (ARVIO repository structure)

---

### Phase 4: Observability

**Goal:** Clean up production logging and add crash reporting.

**Requirements:**
- OBS-01: R8 rules strip debug/verbose/info logs
- OBS-02: No PII in remaining logs
- OBS-03: Firebase Crashlytics integrated
- OBS-04: Non-fatal errors reported

**Delivers:**
- R8 ProGuard rules to strip `Log.d`, `Log.v`, `Log.i` in release
- Audit and sanitize remaining `Log.w` and `Log.e` calls
- Firebase Crashlytics SDK integration
- Custom keys: user_id (hashed), content_id, playback_state
- Non-fatal error reporting for API failures

**Success Criteria:**
- [ ] Release APK contains zero debug/verbose/info log calls (verify with APK analyzer)
- [ ] Remaining log statements contain no emails, tokens, or API keys
- [ ] Test crash appears in Firebase Console
- [ ] API error triggers non-fatal report with context

**Research:** Standard patterns (skip research-phase)

---

### Phase 5: Static Analysis

**Goal:** Establish code quality baseline with Detekt.

**Requirements:**
- QUA-01: Detekt configured with baseline

**Delivers:**
- Detekt 1.23.8 Gradle plugin configuration
- detekt-formatting plugin for ktlint integration
- Baseline file for existing violations
- Security rules enabled, style rules in warning mode
- Gradle task `./gradlew detektDebug`

**Success Criteria:**
- [ ] `./gradlew detektDebug` runs without failing build
- [ ] Baseline file generated with existing violations
- [ ] New code violations fail the build
- [ ] CI can run detekt check

**Research:** Standard patterns (skip research-phase)

---

### Phase 6: Testing Infrastructure

**Goal:** Add unit testing capability and critical test coverage.

**Requirements:**
- TST-01: Unit test dependencies added
- TST-02: ViewModel unit tests
- TST-03: Repository unit tests

**Delivers:**
- Test dependencies: JUnit 4.13.2, MockK 1.13.8, Turbine 1.2.1, kotlinx-coroutines-test
- HomeViewModel unit tests (loading, error, success states)
- AuthRepository unit tests (login success, failure, JWT validation)
- StreamRepository unit tests (error handling paths)

**Success Criteria:**
- [ ] `./gradlew test` runs and reports results
- [ ] HomeViewModel has tests for all UiState transitions
- [ ] AuthRepository has tests for JWT validation edge cases
- [ ] StreamRepository has tests for error mapping

**Research:** Standard patterns (skip research-phase)

---

### Phase 7: Build Hardening

**Goal:** Optimize release build for production distribution.

**Requirements:**
- BLD-01: R8 optimization enabled
- BLD-02: ProGuard rules preserve required classes
- BLD-03: Release signing configuration

**Delivers:**
- R8 with `proguard-android-optimize.txt` base
- Custom rules for Supabase SDK reflection
- Custom rules for ExoPlayer decoder classes
- Custom rules for Compose stability
- Custom rules for Result<T> sealed class
- Release signing configuration (keystore path in secrets.properties)
- Mapping file output for crash symbolication

**Success Criteria:**
- [ ] Release APK builds without R8 errors
- [ ] App launches and plays content in release build
- [ ] APK size reduced compared to debug build
- [ ] Mapping file generated for crash reporting upload

**Research:** Needs validation during implementation (ProGuard rules for sealed classes)

---

## Phase Dependencies

```
Phase 1 (Security) ─────────────────────────────────────────────┐
                                                                 │
Phase 2 (Error Foundation) ─── depends on Phase 1 ──────────────┤
                                                                 │
Phase 3 (Migration) ─── depends on Phase 2 ─────────────────────┤
                                                                 │
Phase 4 (Observability) ─── depends on Phase 1 ─────────────────┤
                                                                 │
Phase 5 (Static Analysis) ─── independent ──────────────────────┤
                                                                 │
Phase 6 (Testing) ─── depends on Phase 3 ───────────────────────┤
                                                                 │
Phase 7 (Build) ─── depends on Phase 4, 6 ──────────────────────┘
```

**Parallelization opportunities:**
- Phase 4 + Phase 5 can run in parallel after Phase 1
- Phase 6 must wait for Phase 3 (testable architecture)
- Phase 7 must wait for Phase 4 + 6 (log stripping + tests)

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| BuildConfig secrets are extractable | Add API restrictions on provider consoles (Phase 1) |
| 500+ Detekt violations | Use baseline strategy, enable incrementally (Phase 5) |
| Tests for untestable code | Result<T> migration creates testable boundaries (Phase 3 before 6) |
| R8 breaks reflection | Custom rules for Supabase/ExoPlayer, test release build (Phase 7) |
| Scope creep to v2 features | Out of scope documented in REQUIREMENTS.md |

---
*Roadmap created: 2026-01-29*
*Last updated: 2026-01-29 after requirements definition*
