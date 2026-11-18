# b8-home-glass-v2: liquid-glass sliding effect for the Flutter home bar

## Objective

Wei reported on build #75 (2026-11-18 10:32, screenshot + "底栏没有滑
动特效"): the Flutter home bottom bar looks like a static translucent
capsule — content sliding underneath shows no liquid refraction. The
v1 bar (`docs/b8-home-glass.md`) only applied a `BackdropFilter` blur
plus a translucent fill. On a flat-color wallpaper a pure blur is
nearly invisible, so the bar reads as a static frosted pill however
the content moves.

This task closes the gap to the native bar as far as pure
Dart/Flutter layered filters allow: blur + refraction + dispersion
feel + native color controls, with no new dependency, no Pigeon
change, no Java change, and no change to the bar's shape, size,
position, or tap behavior.

## What native does (verified in code)

`FloatingNavBar.kt` (Kyant backdrop library) renders the bar's
backdrop in this order, over a live capture of the content's bottom
strip:

1. `colorControls(brightness, contrast, saturation)` — formulas in
   `LiquidGlassConfig.kt`, translucency `t` in 0..1:
   - `containerAlphaScale = 2 - 2t` (surface tint alpha = `0.4 * scale`)
   - `contentBrightness = max(0, 0.5 - t) * 0.12`
   - `contentContrast = 1 - max(0, 0.5 - t) * 0.25`
   - `contentSaturation = 1.5 - max(0, t - 0.5)`
2. `blur(blurDp)`
3. `lens(distortionDp, distortionDp, chromaticAberration = dispersion)`
   — radial refraction + channel-split dispersion.
4. `Highlight.Default` (top-edge highlight) + `Shadow.Default` (drop
   shadow, black 0.1 light / 0.2 dark).
5. Surface tint `drawRect(containerColor)`.

At the defaults (`t = 0.5`, blur 10, distortion 30, dispersion on)
brightness/contrast are neutral, saturation is **1.5**, and the lens
refracts up to 30dp at the rim. The Dart v1 bar implemented only
step 2 (blur) plus a translucency fill — the lens, the dispersion,
and the saturation pop were all missing. That is the whole gap.

The host already sends all five parameters
(`B11HostApiImpl.buildScreen`: `glassEnabled` / `glassBlurDp` /
`glassDistortionDp` / `glassTranslucency` / `glassDispersion`), and
the Pigeon contract already carries them — no plumbing work was
needed.

## What changed (1 code file + this doc)

| File | Change |
|---|---|
| `flutter_module/lib/b11_home_page.dart` | `_GlassBottomBar` rebuilt as a layered glass stack (below); file-header + widget doc comments updated. No other widget touched. |
| `docs/b8-home-glass-v2.md` | This file. |

No Pigeon contract change, no Java change, no new Dart dependency
(`dart:ui` `Matrix4` only, framework). Glass-off path, dark mode,
rotation, wallpaper switching, and the FAB layer are untouched.
This supersedes deviation D1 of `docs/b8-home-glass.md` (which said
"no lens distortion / dispersion") — both are now approximated;
that doc is left as the v1 record.

## Implementation: layered filters

`_GlassBottomBar.build` keeps the opaque path byte-for-byte
(glass off or blur <= 0 → opaque capsule). The glass path is now:

```
Container (drop shadow, outside the clip)
└─ ClipRRect (capsule)
   └─ LayoutBuilder (bar width → lens center)
      └─ BackdropFilter (compose: matrix lens ∘ blur)
         └─ SizedBox 64dp
            └─ Stack
               ├─ tint (native containerAlphaScale formula)
               ├─ saturation lift (nested BackdropFilter, soft-light)
               ├─ brightness wash (white, native formula)
               ├─ contrast wash (mid-gray, native formula)
               ├─ highlight + dispersion fringe (_buildHighlight)
               └─ bar content (icons/labels/pills, unchanged)
```

Native → Dart mapping:

| Native step | Dart approximation |
|---|---|
| `colorControls` saturation (1.5 at default) | Second blurred backdrop composited with `BlendMode.softLight` at opacity `(saturation - 1) * 0.3` (0.15 at default), skipped when saturation is 1. A soft-light self-blend lifts contrast and perceived saturation. |
| `colorControls` brightness | White overlay at alpha = native brightness term (0 at default, max 0.06). |
| `colorControls` contrast | Mid-gray overlay at alpha = `(1 - contrast) * 0.5` (0 at default, max 0.0625). |
| `blur` | Unchanged: `ImageFilter.blur(sigma = glassBlurDp)`. |
| `lens` distortion | Uniform magnification about the bar center via `ImageFilter.matrix` (scale `1 + distortionDp/600`, capped at 1.08; 1.05 at default 30), composed under the blur. Skipped when distortion is 0. |
| `lens` dispersion | Static rim fringe in the highlight gradient: faint cyan top edge + faint magenta bottom edge, only when `glassDispersion` is on. |
| `Highlight.Default` | Top white gradient (0.22 light / 0.14 dark) + 1px white 0.10 rim border. |
| `Shadow.Default` | `BoxShadow` outside the clip: black 0.10 light / 0.20 dark, blur 12, offset (0, 4). |
| Surface tint | Native formula `0.4 * (2 - 2t)`, clamped 0.05–0.8 (0.4 at default; v1 used `1 - t` clamped 0.15–0.9, i.e. 0.5 at default). |

Why the lens scale reads as refraction: content inside the bar is
magnified ~5%, so a card edge sliding under the bar no longer lines
up with the same edge outside it — the misalignment while scrolling
is the perceptual cue the v1 bar lacked.

Why no custom shader: a true radial lens + channel-split dispersion
needs a fragment shader over the backdrop (`ImageFilter` shader
support / runtime-effect image filter). With no Flutter SDK on this
VM the shader route cannot be compile-checked before CI, and a
shader bug would blank the bar rather than degrade gracefully. The
layered-filter route uses only long-stable framework APIs
(`ImageFilter.compose` / `matrix`, `BackdropFilter.blendMode`,
`Opacity`), each of which degrades to the v1 look if a layer is
removed. The shader route stays open as future work if device
acceptance finds the approximation insufficient (see D1/D2).

## Deviations

- **D1** Uniform magnification, not a true radial lens: the Kyant
  lens refracts most at the rim and least at the center;
  `ImageFilter.matrix` can only apply one affine transform. Edge
  weighting is lost; the sliding misalignment cue is kept. The
  scale factor (`distortionDp / 600`, max 1.08) is a tuned
  constant, not derived from the Kyant lens math — device
  acceptance decides whether to strengthen or weaken it.
- **D2** Dispersion is a static rim fringe, not channel-split
  refraction: true chromatic aberration offsets the R/B channels
  per pixel. The fringe does not move with content; it only tints
  the rim. Only visible when dispersion is on (default).
- **D3** Saturation is a soft-light lift, not a color matrix:
  `dart:ui` exposes no color-matrix image filter for backdrops, so
  the native saturation term cannot be applied directly. The lift
  relies on nested-`BackdropFilter` compositing (the inner filter
  samples the original backdrop, not the outer blur's output);
  either nesting behavior yields a usable result (a double-blurred
  soft-light lift if the inner filter sees the outer output), but
  the exact on-device strength is unverified until acceptance.
  If it looks wrong, removing the lift layer is a one-line change
  and the bar falls back to blur + washes.
- **D4** Highlight/shadow parameters are approximations: the exact
  Kyant `Highlight.Default` / `Shadow.Default` curves were not
  ported; alphas follow the native formulas where known (shadow
  0.1/0.2) and are otherwise chosen to be subtle.
- **D5** Two backdrop blurs per frame (main + lift layer) instead
  of one. The bar is small (full width × 64dp) and native already
  re-captures + re-renders every frame; the lift layer is skipped
  when saturation is 1 (translucency 100). Scroll smoothness is
  an acceptance item.
- **D6** Glass-off still keeps the capsule (b8-home-glass D3,
  unchanged): native shows a full-width plain bar instead. The
  capsule shape is the Mooyingshi look and stays regardless.
- **D7** The `Row` extraction leaves the item-builder block at its
  old indentation (cosmetic only; CI runs `dart analyze`, not
  `dart format`).

## Acceptance checklist (device)

1. Scroll the home grid: cards sliding under the bottom bar show a
   visible liquid effect — blurred, slightly magnified/misaligned
   against the content outside the bar, with a vivid (saturated)
   tint. The bar no longer looks like a static frosted pill.
2. On a flat-color wallpaper the bar still reads as glass (edge
   highlight + fringe + shadow), not a flat overlay.
3. Glass off (or blur 0): opaque capsule, exactly as before.
4. Dispersion off in settings: no cyan/magenta rim tint; highlight
   stays plain white.
5. Distortion 0 in settings: no magnification (plain blur); raising
   distortion strengthens the refraction feel.
6. Dark mode, rotation, wallpaper switch: capsule, highlight and
   shadow stay correct, no overflow, no blank bar.
7. Taps / live long-press / badges / selection pill unchanged;
   FABs unaffected; scrolling stays smooth (D5).

## Verification (no SDKs on this VM)

- Re-read the full modified widget: opaque path returns the same
  `Container` + content as before; glass path is additive layers
  only; no other widget in the file changed.
- Grep: no leftover references to the removed `bar`/`opacity`
  locals; new symbols (`Matrix4`, `ImageFilter.compose`,
  `ImageFilter.matrix`, `BlendMode.softLight`, `_buildHighlight`)
  all referenced; gradient color/stop counts match (4/4
  dispersion, 2/2 plain); `git diff` limited to the 2 scope files.
- Bracket balance checked over the modified file.
- Full verification is the CI build + device acceptance (parent
  pushes; this commit is local-only).

## Recovery anchor

- Objective: liquid-glass sliding effect on the Flutter home bar
  (blur + refraction + dispersion feel), glass-off opaque capsule
  unchanged; see Acceptance checklist.
- Status: implemented, local commit only (no push).
- Files: `flutter_module/lib/b11_home_page.dart`,
  `docs/b8-home-glass-v2.md`.
- Next action: parent pushes, CI builds, then device acceptance
  against the checklist above.
