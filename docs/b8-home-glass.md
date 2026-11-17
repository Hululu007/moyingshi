# b8-home-glass: liquid-glass look for the Flutter home (B11)

## Objective

Rebuild the Flutter home's glass look to match the native home: a
floating capsule bottom bar over a blurred wallpaper background. Wei
reported on build #51 that the liquid glass was gone ("把液态玻璃弄出来"):
the Dart `_GlassBottomBar` existed and the host sent glass parameters,
but on device it rendered as a flat full-width bar — top corners
rounded only, sitting in `bottomNavigationBar`, with no wallpaper
behind it, so the `BackdropFilter` blur had nothing to refract. The
native home shows a floating capsule over a blurred purple wallpaper.

## What native does (verified in code)

- **Capsule geometry** (`GlassNavBar.kt`, `activity_home.xml`): the
  glass bar lives in an 88dp slot aligned to the parent bottom; the
  bar itself is 64dp tall, with 24dp side insets and 16dp of float
  above the slot's bottom edge (top edge 8dp below the slot top).
  `FloatingNavBar` clips the bar with `Capsule()` — fully rounded.
- **Glass rendering** (`FloatingNavBar.kt`, Kyant backdrop library):
  a live capture of the content's bottom strip (including the root
  background) feeds a `CanvasBackdrop` each frame; the bar renders
  blur + lens distortion + dispersion over it. Parameters come from
  `Setting` (`liquid_glass` default on, blur 0–40 default 10,
  distortion 0–30 default 30, translucency 0–100 default 50,
  dispersion default on), synced by
  `HomeActivity.syncGlassState()`.
- **Wallpaper** (`BaseActivity.setContentView`): a `CustomWallView`
  is inserted as child 0 of `android.R.id.content`, full-screen,
  behind all content. `CustomWallView.load()` resolves, in order:
  built-in color wall (dead branch — `isBuiltInColorWall` always
  returns false), built-in design wall (drawable
  `wallpaper_design_*`, resolved by `getDesignResId`), legacy green
  wall (`R.drawable.wallpaper_1`, unreachable — `Setting.getWall()`
  remaps it), video/gif motion (file `FileUtil.getWall(wall)`), else
  the static frame-0 JPEG snapshot `FileUtil.getWallCache()` that
  `WallConfig` wrote, else the default dream-purple color
  (`0xFF7560CA`). The default wall is `WALL_DREAM_PURPLE` (22).
- **Wallpaper blur** (`ThemeController`): the home blurs the
  wallpaper layer itself 3dp (`applyWallpaperBlur(this, 3)`,
  `RenderEffect` on the `CustomWallView`, API 31+); settings pages
  use the full glass-blur radius. Glass off or radius 0 = no blur.
- **Glass off** (`HomeActivity.applyBottomBar`): the native home
  shows the plain full-width `BottomNavigationView` instead of the
  glass bar. (The Flutter home deliberately differs here; see
  Deviations D3.)

## What changed (4 files, one commit)

| File | Change |
|---|---|
| `flutter_module/pigeons/b11_home_messages.dart` | Two additive fields at the end of `B11HomeScreen`: `wallpaperPath` (String) + `wallpaperColor` (int). No existing method or field changed. |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B11HostApiImpl.java` | `resolveWallpaper()` + `snapshotDrawable()` mirroring `CustomWallView.load()`; two builder calls in `buildScreen`. |
| `flutter_module/lib/b11_home_page.dart` | Stack layout (wallpaper + content + floating capsule), `_WallpaperBackground`, capsule `_GlassBottomBar`, grid bottom padding. Top bar, search pill, tab bar, B12/B13 widgets, routing untouched. |
| `docs/b8-home-glass.md` | This file. |

No other batch's contract touched. No new Dart dependencies (only
`dart:io` `File` + `ImageFiltered`, both framework). No schema
changes. Mobile only. Generated Pigeon bindings are produced in CI;
no generated file is committed. The prospective scope path
`flutter_module/lib/b11_glass_bar.dart` was not needed — the capsule
and wallpaper widgets stay in `b11_home_page.dart` next to the bar
they replace.

## New Pigeon fields

```dart
// B11HomeScreen, appended at the end (additive-only):
String wallpaperPath;
int wallpaperColor;
```

- `wallpaperPath`: absolute path of a renderable image file — a
  files-dir JPEG snapshot of the built-in design drawable, or the
  `WallConfig` frame-0 cache file for a custom / video / gif wall.
  Empty when the wallpaper is a flat color or no image exists.
- `wallpaperColor`: ARGB fallback painted behind the image and used
  on its own when the path is empty — the built-in design's
  representative color (`Setting.getBuiltInWallColor`), or dream
  purple for custom walls / no image.

Java builder names follow the existing pattern exactly:
`.setWallpaperPath(String)` / `.setWallpaperColor((long) int)`.

## Host plumbing

`resolveWallpaper()` mirrors `CustomWallView.load()` order-for-order
(including the two dead branches, so a future `Setting` change cannot
silently reorder resolution):

1. Built-in color wall → color only (dead upstream, kept for order).
2. Built-in design wall → `CustomWallView.getDesignResId(wall)` +
   `snapshotDrawable()`; failure degrades to the design color.
3. Legacy green wall → `R.drawable.wallpaper_1` snapshot; failure
   degrades to `0xFF40C090`.
4. Anything else (custom file / video / gif) → `FileUtil.getWallCache()`
   when it exists (the frame-0 snapshot), else dream purple.

`snapshotDrawable()` decodes the drawable with the same downsampling
as `CustomWallView.decodeWallBitmap` (max side 1920, ARGB_8888 —
blurred gradient walls band visibly in RGB_565) and compresses it to
`wallpaper_flutter` in the files dir (never `WallConfig`'s cache
file, which belongs to custom walls). The render is cached by wall
index (`lastWallpaperWall`): repeat snapshots reuse the file, so the
decode/compress cost is paid once per wall change. Any failure
returns null and the shell still completes with the fallback color —
the wallpaper must never fail the snapshot. The impl lives in the
main source set: `R.drawable.wallpaper_design_*` and
`R.drawable.wallpaper_1` exist in both the mobile and leanback res
sets, so both flavors compile.

No new snapshot field was needed for the wallpaper blur: the home
blur is fixed 3dp when glass is on and 0 when off, so Dart derives
it from the existing `glassEnabled` field.

## Dart behavior

- **Layers** (`_buildHomeStack`): `Stack` of full-screen
  `_WallpaperBackground` (edge-to-edge, behind everything), the
  existing content `Column` inside `SafeArea`, and the capsule
  `Positioned` at 24dp sides / 16dp float above the bottom safe
  inset. The `Scaffold` background is transparent once the shell
  has loaded; the loading/error state keeps the default surface.
  The capsule overlay only hit-tests on the bar itself, so content
  beneath it stays tappable.
- **Wallpaper** (`_WallpaperBackground`): `Image.file` with
  `BoxFit.cover`; a missing/unreadable file degrades to the
  fallback color via `errorBuilder`. Glass on wraps the image in
  `ImageFiltered` with a 3dp blur (sigma 3, the native home
  radius); glass off shows it sharp. Only the wallpaper is
  blurred — foreground content is never touched.
- **Capsule** (`_GlassBottomBar`): 64dp tall, fully rounded
  (`BorderRadius.circular(32)`), same items/order/selection pill/
  badge/icons/long-press routing as before. Glass mode keeps the
  V4 translucency mapping (`alpha = 1 - translucency/100`, clamped
  0.15–0.9) and `BackdropFilter` blur with sigma = `glassBlurDp`,
  now refracting the wallpaper and scrolled content behind it.
  The inner `SafeArea` is gone — the `Positioned` placement owns
  the bottom inset now. This supersedes the V4 height note in
  `docs/b8-b11-home.md` (76dp was a guess; native is 64dp in an
  88dp slot).
- **Grid clearance**: `_TabVideoGrid` takes a `bottomPadding` (the
  88dp slot height) applied inside all three scrollables (list /
  oval / rect), so the last row scrolls clear of the floating
  capsule. Empty/error states are centered and need no padding.

## Deviations

- **D1** No lens distortion / dispersion: Dart has no equivalent of
  the Kyant backdrop lens. The bar is blur + translucency only
  (same V4 approximation as before, now over a real backdrop).
- **D2** Video/gif wallpapers are static: Dart shows the frame-0
  snapshot, without motion. Native plays video/gif walls in
  `CustomWallView`. No video player is wired into the Flutter
  home; a motion wallpaper on the native home still animates
  underneath when the Flutter home is not on top.
- **D3** Glass-off keeps the capsule: when `Setting.isLiquidGlass()`
  is false, the capsule keeps its shape but renders opaque. Native
  shows a full-width plain bar instead. The capsule shape is the
  Mooyingshi look and stays regardless (task decision).
- **D4** Wallpaper blur sigma: native blurs with a pixel radius of
  `3dp * density` via `RenderEffect`; Dart uses sigma 3 logical
  pixels. Visually equivalent at the home's light 3dp radius;
  not pixel-matched.
- **D5** First-load render cost: the first snapshot after a wall
  change decodes + JPEG-compresses the design drawable on the
  calling thread (same class of main-thread work `CustomWallView`
  already does on refresh). Cached afterwards; no per-frame cost.
- **D6** Search pill / list rows keep their opaque surfaces
  (`surfaceContainerHighest` / `surfaceContainer`, B8 pattern).
  Only the page background and the bar changed; card/pill surfaces
  are untouched.

## Acceptance checklist (device)

1. Home shows the wallpaper full-screen behind the content
   (default: dream-purple design), slightly blurred with glass on,
   sharp with glass off.
2. Bottom bar is a floating capsule (rounded all corners, 24dp
   side margins, floating above the bottom edge), refracting the
   wallpaper/content behind it when glass is on; opaque capsule
   with glass off.
3. Bar items, order, selection pill, badge, taps and live
   long-press behave exactly as build #51.
4. Grid last row scrolls fully clear of the capsule (rect, list
   and oval styles); banner/tabs/top bar unaffected.
5. Switching wallpaper in settings (design / custom image) shows
   the new wallpaper on the Flutter home after returning to it.
6. Rotation and dark mode: capsule and wallpaper stay correct, no
   overflow, no blank background.
7. Video/gif wallpaper: home shows a static frame (no crash, no
   blank background).

## Verification (no SDKs on this VM)

- Grep parity for the new contract fields across all three sides:
  `wallpaperPath` / `wallpaperColor` in the Pigeon contract, the
  Java Builder calls in `B11HostApiImpl`, and the Dart reads in
  `b11_home_page.dart` — names match character-by-character
  (Pigeon preserves Dart names verbatim in Java).
- Diff scoped to the 4 task files; no other contract, no native
  activity/diversion, no master-switch change.
- Imports audited: new Java imports are all main-source-set safe
  (`android.graphics`, `java.io`, `CustomWallView`,
  `FileUtil`); new Dart import is `dart:io` only.
- Full verification is the CI build + device acceptance (parent
  pushes after run #55 resolves; this commit is local-only).

## Recovery anchor

- Objective: floating capsule glass bar + wallpaper background on
  the Flutter home, matching native; see Acceptance checklist.
- Status: implemented, local commit only (no push — CI run #55
  was building head `ae99879a` when this work started).
- Files: `flutter_module/pigeons/b11_home_messages.dart`,
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B11HostApiImpl.java`,
  `flutter_module/lib/b11_home_page.dart`, `docs/b8-home-glass.md`.
- Next action: parent pushes after run #55 resolves, then device
  acceptance against the checklist above.
