# Orpheon desktop: bringing the Immersive UI to Windows and Linux

Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0. Origin: Myspoti. Keep the license
headers and the BitChord attributions already at the top of some Immersive files.

**This file is the handoff.** Any agent (or person) should be able to continue from it alone.
Read "Status" and "Next action" first, do the next action, then update both before stopping.

---

## Goal and decision

The desktop app (`:desktop`, Compose Multiplatform Desktop, libVLC playback) must look and behave
like the Android **Immersive** look. Decision (taken with the owner): **reuse the Android Immersive
code, do not recreate it.** It is already responsive (`WindowSize`: two-pane players, side rail,
one-row chrome, grid columns, page-width cap, mouse right-click), so the layouts carry over. What
does not carry over is where the code lives and what it calls.

Why it cannot simply be used in place: it is in `:app` (an Android *application* module). `:desktop`
is a plain JVM module and cannot depend on it. So the code moves into a shared Compose
Multiplatform module, **`:immersive`**, that both `:app` and `:desktop` use. One source of truth.

## Hard rules for whoever works on this

1. **Android must keep building after every step.** Each step is small and self-contained.
2. **Keep package names unchanged** (`com.music.spotui.ui.immersive.*`, etc.) when moving files, so
   importers in `:app` do not change. Rename packages only as a separate, final cleanup.
3. Move with `git mv` so history follows. Do not commit unless the owner asks.
4. Moving a file across a module boundary breaks `internal`: anything `internal` that `:app` (or a
   test) still uses must become public in the same step.
5. `commonMain` has no `java.*`, `android.*`, `org.intellij.*` (`@Language`), `androidx.core`,
   `androidx.activity`, Glide, or `R`. Put platform code behind `expect`/`actual` (androidMain /
   desktopMain) or behind an interface passed in.
6. Screens must end up taking **plain state plus callbacks**, not `Graph`, `SongPlayer`, ViewModels
   or `Context`. `ui/immersive/player/PlayerBridge.kt` + `ImmersivePlayerState` already work this
   way (shape adapted from BitChord's shared UI): copy that pattern.
7. **Agent sandboxes usually cannot build this project** (no Maven/Google repositories, no Android
   SDK). Everything below marked *unverified* was written without a compiler. The first thing a
   person with a toolchain should run is in "Verify".
8. Never apply `git stash`/reset over `base/desktop/` or `base/settings.gradle.kts`: they were
   uncommitted work when this started.

## Verify (run these; they are the definition of "step done")

```
cd base
./gradlew :immersive:compileKotlinDesktop      # commonMain + desktopMain compile
./gradlew :app:compileDebugKotlin              # Android still compiles with the moved files
./gradlew :app:testDebugUnitTest               # includes the Roborazzi immersive screenshot tests
./gradlew :desktop:compileKotlin               # once :desktop depends on :immersive
./gradlew :desktop:run                         # needs VLC installed
```

If `:immersive` fails at configuration with *plugin not found / not applicable*: see "Build wiring".

## Where things are (measured, not guessed)

- Immersive code: `base/app/src/main/java/com/music/spotui/ui/immersive/` (95 files, 24.7k lines)
  plus `radio/ui/ImmersiveLiveRadioScreen.kt`. Strings: `res/values*/immersive_*_strings.xml`.
- It reaches back into the app: 836 imports of `com.music.spotui.*` (ui.immersive itself 389,
  ui.screens 96, data 95, ui.components 88, ui.navigation 50, di 50, ui.look 45, ui.viewmodel 12,
  extensions 11). Direct `android.*` use is light (21 files); the real coupling is the singletons.
- Desktop today (`base/desktop`): a plain Material shell (search / queue / extensions) over the real
  `DesktopPlayer`, catalog and extensions. Its `Main.kt` says the Immersive screens replace it.
- The desktop speaks `:api` models (`Track`, `Album`, `NowPlaying`); the Immersive screens speak
  `data.entity.*` (`SongsModel`, `AlbumsModel`, ...: 12 pure-Kotlin files, ~420 lines).

Re-measure any time: `python3 tools/desktop-port/portability.py` (add `--movable`).
**Pessimistic** (identifier matching over-links). A "blocked" file may be fine; check by hand.

### Blockers, with how many Immersive files each affects (direct imports)

| Blocker | Files | Plan (seam) |
|---|---|---|
| `app.myspoti.engine.R` (Android string resources) | 46 | Compose Multiplatform resources (`composeResources/values*/strings.xml`), `stringResource(Res.string.x)`. French files `values-fr/immersive_*_strings.xml` exist: carry them over. Strings shared with Classic need to stay available to `:app` too. |
| `com.bumptech.glide` (`GlideImage`) | 23 | One `expect/actual` cover-image composable. Android actual keeps Glide (same behaviour); desktop actual uses Coil 3 (+ OkHttp) or a small own loader. |
| `androidx.navigation.NavController` | 30 | JetBrains `navigation-compose` (same API on desktop). `ui/navigation/Routes.kt` + route helpers (955 lines) move too. |
| `androidx.lifecycle.viewmodel` | 12 | KMP lifecycle-viewmodel-compose. ViewModels (1088 lines) read `Graph.repository`: give them an interface. `PlayerViewModel` uses Android APIs. |
| `di.SongPlayer` | 21 | `PlayerHost` interface (state flows + actions). Android implements it with `SongPlayer`, desktop with `DesktopPlayer`. Start from `ImmersivePlayerState`. |
| `di.Graph` | 11 | An `ImmersiveServices` CompositionLocal (repository, profile, features). |
| `di.Haptics` | 11 | `LocalHaptics` (desktop: no-op). |
| `android.os.Build` | 8 | `PlatformCaps` (`supportsRuntimeShader`, ...), expect/actual. |
| `app.myspoti.host.android.MyspotiRuntime` | 8 | Inspect per use; mostly app context for strings/prefs: becomes part of `ImmersiveServices`. |
| `android.widget.Toast` | 5 | `LocalToaster` (desktop: snackbar). |
| `android.content.Intent` / `net.Uri` / share / open-link | ~8 | `PlatformActions` (openUrl, share, ...). |
| `androidx.activity.compose.BackHandler` | 4 | expect/actual (CMP also ships a common BackHandler: verify availability for the version in use). |
| `ui.components.*`, `ui.look.*` helpers (`WindowSize`, `rememberReduceMotion`, `secondaryClick`, `FastScrollbar`, `pressHint`, `imageUrlOf`, `titleMarquee`, fonts `ImmersiveSans`/`ImmersiveDisplay`, ...) | ~130 imports | Move the ones Immersive uses into `:immersive`. `WindowSize` reads `LocalConfiguration` (Android): switch to `LocalWindowInfo.containerSize` (common) or expect/actual. |
| `data.entity.*` models | ~35 | Move the 12 files into `:immersive`; on desktop map `:api` `Track/Album/...` to them (adapter next to `DesktopApi`). |
| `java.*`, `android.icu`, `@Language` | ~6 files | `kotlinx-datetime` / expect-actual formatting; drop `@Language` annotations. |
| Liquid-glass shaders: `shell/backdrop/Platform.kt`, `RuntimeShader.kt`, `internal/RenderEffect.kt`, `internal/Paint.kt` (`android.graphics.RuntimeShader`, `RenderEffect`, `BlurMaskFilter`, `SystemClock`) | 5 | expect/actual. Desktop first runs the **Haze** fallback (`ImmersiveHaze.kt`, and the `LiquidGlassPref` switch) with `supportsRuntimeShader = false`; a Skia `RuntimeEffect` (SkSL, close to AGSL) version can come later. |

## Target architecture

```
:immersive   (Kotlin Multiplatform: commonMain + androidMain + desktopMain)
   theme, glass shell/backdrop, components, sheets, pages, screens (plain state + callbacks),
   ports: PlayerHost, ImmersiveServices, LocalHaptics, LocalToaster, PlatformActions, PlatformCaps,
   models (data.entity), navigation routes
:app         depends on :immersive; implements the ports with SongPlayer/Graph/Toast/Intent/...
:desktop     depends on :immersive; implements the ports with DesktopPlayer/DesktopApi/...
```

## Milestones (do in order; each leaves Android building)

- **M0 Scaffold the module and move the files that are already self-contained.**
- **M1 Foundation.** `ImmersiveTheme` (+ fonts from `ui.look`), `ImmersiveMotion` (`rememberReduceMotion`),
  `WindowSize`, `secondaryClick`, `ImmersiveClickable`. Small, few blockers each.
- **M2 Glass cluster** (`shell/backdrop/**`, `ImmersiveGlass`, `GlassSelectionPill`, `FloatingTabBar`,
  `ImmersiveHaze`: about 28 files in one dependency cycle, so they move together). Only ~5 files have
  real Android calls (see table). `ImmersiveGlass` must take the liquid-glass setting as a parameter
  instead of reading `LiquidGlassPref`.
- **M3 Resources and images.** Strings to CMP resources; the cover-image seam (Glide / Coil).
- **M4 Ports and models.** Define the ports; move `data.entity`; adapters on both sides; convert
  the player (`ImmersivePlayer`, `PlayerControls`, `ImmersiveMiniPlayer`, lyrics/queue panels).
- **M5 Shell and first screens:** `ImmersiveChrome`/tab bar/top bar + navigation, Home, Search, Queue.
- **M6 Desktop wiring.** `:desktop` depends on `:immersive`, `Main.kt` swaps its Material shell for the
  Immersive one, keyboard/mouse polish (media keys, window resize, right-click already exists).
- **M7 Remaining screens** (library, artist, album/context, podcast, settings, extensions, equalizer, ...).

## Build wiring (M0, unverified)

- `settings.gradle.kts`: `include(":immersive")`.
- `libs.versions.toml`: plugin `android-kotlin-multiplatform-library` = `com.android.kotlin.multiplatform.library`
  (version = AGP). This is the AGP 9 way to give a KMP module an Android target; the older
  `kotlin-multiplatform` + `com.android.library` + `androidTarget()` combination is not supported by
  AGP 9. **If your AGP says otherwise, change this one plugin line and the `kotlin { android { } }`
  block in `immersive/build.gradle.kts`; nothing else depends on it.**
- Targets: Android (`minSdk 29`, `compileSdk 37`, JVM 17) and `jvm("desktop")` (JVM 17).
- `:app` gets `implementation(project(":immersive"))`.
- `:desktop` is a plain `kotlin("jvm")` module and can consume the `desktop` target as-is.
- Compose: `kotlin.compose` + `compose.multiplatform` plugins, same versions as `:desktop`.
- Possible friction: `:app` uses androidx Compose BOM 2026.06.01 (material3 1.5.0-beta02) while
  CMP 1.12.2 brings its own androidx artifacts; Gradle picks the higher. If a duplicate-class or
  API mismatch appears, align them in `libs.versions.toml`.

## Status

| Milestone | State |
|---|---|
| Analysis, `tools/desktop-port/portability.py`, this file | done |
| M0 scaffold + first files | **`:immersive:compileKotlinDesktop` GREEN (2026-11-21, patch applied from orpheon-immersive-m0.patch). Android side `:app:compileDebugKotlin` still UNVERIFIED: no Android SDK on this machine.** Originally written without a compiler. `:immersive` module created and wired (`settings`, `libs.versions.toml`, root `build.gradle.kts`, `:app` dependency). Moved to `immersive/src/commonMain/...` (same packages): `shell/backdrop/internal/{InverseLayerScope,LayerRecorder,Outline,Shaders,ShapeProvider}.kt`, `internal` made public (the other glass files still in `:app` use them), `@Language` dropped from `Shaders.kt`. Not yet done: `:desktop` does not depend on `:immersive` yet. |
| M1 Foundation | **partly done, desktop-verified only.** `ImmersiveTheme.kt` moved; its host-specific inputs (page/raised/accent colours, fonts, Liquid Glass switch) now come from `ImmersiveAppearance` (`ImmersiveAppearanceHost.current`, set in `MyspotiApplication`; `:app` impl = `ui/look/AppImmersiveAppearance.kt`). `rememberReduceMotion`/`LocalReduceMotion` moved (expect/actual). `ImmersiveMotion` moved; its nav `Pages` split out to `:app` as `ImmersivePages`. Still in :app: `WindowSize`, `secondaryClick`, `ImmersiveClickable`. |
| M2 Glass cluster | **done for the desktop target** (`:immersive:compileKotlinDesktop` green): all of `shell/backdrop/**`, `ImmersiveGlass`, `ImmersiveHaze`, `FloatingTabBar`, `GlassSelectionPill` are in `:immersive` (Haze 1.3.1 added). Android-only calls sit in `androidMain` actuals (`Platform`, `RuntimeShader`, `internal/RenderEffect`, `internal/Paint`, `uptimeMillis`); desktop actuals report no RenderEffect/RuntimeShader so desktop draws the Haze frost. Logging goes through `ImmersiveLog.sink` (set in `MyspotiApplication`). **Android verified 2026-11-21:** `:app:compileDebugKotlin` green (needed the top-level `internal` of the four moved shell files made public); `:app:testDebugUnitTest` 814 tests, 2 failed (`RadioFavoritesStoreTest`, file-store tests, not touched by this work; likely Windows-specific, not checked against a clean tree). |
| M3 ... M7 | not started |

## Next action

1. **Someone with a toolchain: run "Verify" above and fix M0 until it is green** (most likely the AGP
   plugin id / `kotlin { android { } }` block, or a Compose version mismatch; see "Build wiring"). Do not
   start M1 on a red M0. Record the outcome in the Log, and if the plugin line had to change, update
   "Build wiring" too.
2. **Then M1, first file `ui/immersive/ImmersiveTheme.kt` (86 lines).** It is not pure yet:
   - `Immersive.Page` reads `app.myspoti.power.EnergySettings.oledBlack` and `Immersive.Raised` reads
     `com.music.spotui.extensions.ExtensionLook.color(...)`: replace both with a small
     `ImmersiveAppearance` (an interface or fun values, provided through a CompositionLocal or set once at
     startup) that `:app` fills from those two sources and `:desktop` fills with defaults.
   - Fonts `ImmersiveSans` / `ImmersiveDisplay` live in `ui/look/Look.kt` and read `AppFontPref.system` and
     Android `res/font` (`inter.ttf`, `app_sans.ttf`): move the two font vals out, backed by Compose
     Multiplatform resources (`Font(Res.font.inter)`), with "use system font" passed in via the same
     appearance object.
   - `rememberReduceMotion()` (`ui/look/Look.kt`, used by 15 Immersive files) reads Android
     `Settings.Global` through `LocalContext`: make it `expect/actual` (desktop: `false`, or an OS hint).
   - Then `ImmersiveMotion.kt` (needs `rememberReduceMotion`, `Routes`, `NavBackStackEntry`),
     `WindowSize.kt` (`LocalConfiguration` -> `LocalWindowInfo.containerSize`), `secondaryClick`,
     `ImmersiveClickable.kt`.
3. After each file or small group: `python3 tools/desktop-port/portability.py` to see what became movable,
   move it (`git mv`, same package, `internal` -> public where `:app` still uses it), run Verify, and update
   Status and Log.

## Log (newest first; add one line per session)

- 2026-11-21 (later): M1 partly + M2 done on the desktop target; `portability.py` fixed for Windows paths. Android compile verified afterwards (see M2). In the shell that runs Gradle, ANDROID_HOME must be set (a new terminal picks it up). 64 Immersive files / 20.0k lines remain in :app. Next: Android compile, then rest of M1 (`WindowSize`, `secondaryClick`, `ImmersiveClickable`), then M3 (strings, cover images).
- 2026-11-21: applied orpheon-immersive-m0.patch (it was missing from the repo); `:immersive:compileKotlinDesktop` passes (Java 25, Gradle 9.8.2). `:app:compileDebugKotlin` blocked: no Android SDK (set ANDROID_HOME or base/local.properties sdk.dir). Next: verify Android, then M1.
- 2026-11-21: analysis, plan, M0 written (no compiler available in that session). See STATUS above.

## Things learned the hard way (add yours)

- "Portable by imports" is not portable: Kotlin needs no import for same-package references, so a file
  with clean imports can still depend on an Android-only neighbour. Check with the script, which
  follows same-package references. First pass said 24 files were portable; the real number was 5.
- Even the theme is impure: `object Immersive` (`ImmersiveTheme.kt`) reads app singletons (`EnergySettings`,
  `ExtensionLook`), and the fonts come from Android `res/font` via `AppFontPref`. Expect this in other
  "obviously UI" files: check the file, not its name.
- `Shaders.kt` (AGSL strings) uses `@Language`, which only exists on the JVM; it is an editor hint
  and is dropped when the file moves.
