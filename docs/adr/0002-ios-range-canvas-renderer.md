# ADR 0002: The iOS driving range renders with SwiftUI Canvas from shared Kotlin geometry

- **Status:** Accepted, 2026-09-26
- **Amends:** ADR 0001's consequence "The iOS driving range can use RealityKit (true 3D) again".
  RealityKit is replaced for the range. Nothing else in ADR 0001 changes.
- **Plan:** F-series §5 F8c (F8c1 shared geometry and style, F8c2 the iOS renderer)

## Context

Android draws the range on a Compose `Canvas` as flat 2.5D fills. Everything is projected
through the shared `RangeProjection` pinhole camera, and the shared `RangeCameraRig` supplies
the pose. iOS draws the same scene with RealityKit (`ARView(.nonAR)` plus a `PerspectiveCamera`,
`iosApp/iosApp/Range/RangeSceneController.swift`). The camera poses match, but the pictures
don't:

- **Sky.** iOS has a flat sky with no gradient down to the horizon.
- **Ground.** The sky shows below the horizon beyond the finite ground box. That's about 7% of
  the screen once the follow camera settles, and more in landscape. Android fills the band from
  the horizon to the bottom of the screen with the ground colour.
- **Colour.** PBR lighting and tone mapping shift the greens away from the palette.
- **Missing pieces.** iOS has no yardage labels and no target line.
- **Tracer.** It's built from per-segment entities. It thins to 1–2 px at distance and doubles
  its translucency where segments overlap. Android draws one filled ribbon with a pixel minimum.

Every further range feature would have to be built twice, in two very different technologies.
That includes F8a's replay overlay, F8a2's themes and F11's real course polygons. Parity would
drift further each time.

## Options considered

1. **Improve RealityKit.** Add a skybox gradient, extend the ground to the horizon, use unlit
   materials, add billboarded text labels, and batch the tracer into one mesh.
   - Rejected. Each fix fights the engine: unlit materials and text meshes are awkward, and
     mesh rebuilds are needed every frame for the tracer.
   - It still wouldn't match Android pixel for pixel, and every new feature (a 200-shot overlay,
     themes, course polygons with holes) would need a second, 3D implementation.
2. **Keep RealityKit and add a SwiftUI backdrop behind a transparent `ARView`.** This fixes the
   sky and horizon only. It leaves the colour shift, the labels and the tracer, and it adds a
   compositing layer whose horizon must track the 3D camera exactly.
3. **SwiftUI `Canvas` driven by the shared Kotlin geometry (chosen).** iOS paints exactly what
   Android paints. The scene, clipping, tracer ribbon, overlay and palette are computed once in
   `feature:range` `commonMain`, and each platform only fills paths with colours.
4. **SceneKit or Metal.** SceneKit is in maintenance mode and has the same lighting and
   parity issues as option 1. A custom Metal renderer would give full control, but it's far more
   code than 2D fills need and it would still duplicate the geometry.

## Decision

The iOS range renders with **SwiftUI `Canvas`** from the **shared Kotlin geometry**, replacing
RealityKit for the range.

- **Shared (`feature:range` `commonMain`, F8c1):**
  - `RangeScene`: ground, fairway, stripes, target line, markers, tee box, trees, labels,
    landing marker, and the horizon for the sky/ground split. It includes the near-plane
    clipping.
  - `FlightGeometry` (the shadow and ball sizes) and `TracerRibbon`.
  - `OverlayGeometry`.
  - `RangeFrame`, which keeps all of the above projected for a pose and documents the draw order.
  - `RangeVisualStyle` and `RangeColor`: the palette, shade stops and layout sizes, with
    `RangeTheme.DAY` holding Android's existing values.
  - One shared quality profile, `RangeFrame.QUALITY`.
  - Shapes write their outlines into a small `PathSink` interface (`rewind`/`moveTo`/`lineTo`/
    `close`). Each shape owns its sinks, which are rewritten in place, so no frame allocates.
- **Android** backs `PathSink` with a Compose `Path` and draws **pixel-identically** to before.
  F8c1 verified this by diffing device captures.
- **iOS (F8c2)** backs `PathSink` with a `CGMutablePath` through an `iosMain` sink, so the
  per-point loop stays in Kotlin. Swift fills the paths in a `Canvas` inside a
  `TimelineView(.animation)` that runs only while a flight or a camera settle is in progress.
  Labels are resolved SwiftUI `Text`. Playback and settle timing mirror `RangeCanvas.kt`.
- The RealityKit renderer stays behind a debug flag for one release, then it's deleted.

## Consequences

- **Parity by construction.** Sky gradient, ground band, palette, labels, target line and tracer
  come from the same numbers on both platforms. Screenshot comparison (ΔE < 3 on sampled pixels)
  becomes a meaningful exit check.
- **Flat trees.** Crowns are flat discs and trunks flat quads, as on Android. The RealityKit
  look (lit spheres, soft shading) is given up.
- **F8b shrinks.**
  - Dropped: its mesh batching, its skybox task and amendment A18 (the orthographic camera).
  - Kept: the replay transport, the session picker, gestures that set `ViewTransform`, and the
    iPad side pane.
  - The overlay and top-down views come from the shared geometry.
- **F11c becomes "fill the shared projected polygons".** Course features are filled even-odd, so
  holes (a bunker's island, a green's cut-out) need no triangulation on iOS.
- **F8a2 themes are data.** DUSK, NIGHT and LINKS are new `RangeVisualStyle` values, not new
  materials or skyboxes.
- **More public API in the shared framework.** The scene classes are now public in
  `feature:range`, which `Shared.framework` exports, so they appear in the Objective-C header.
- **Risk.** Kotlin→Swift calls per frame. The hot loop stays in Kotlin: the `CGPathSink` writes
  directly, and Swift makes one fill call per path. F8c2 must measure 60 fps live follow on an
  iPhone simulator and 60 fps for a 200-shot overlay on an iPad simulator.
- **Rollback.** Revert F8c2 and turn the RealityKit flag back on. F8c1 is behaviour-neutral on
  Android, so it doesn't need reverting.

## Note: F8a2a atmosphere pass and themes (2026-09-27)

F8a2a changed DAY on purpose, so F8c1's "pixel-identical to the old Android range" no longer
holds. Parity between the platforms still holds by construction, and the ΔE < 3 check was
re-baselined against the new look for every theme.

- **Still shared data.** The haze, the multi-band sky, the sun, the far ridges, the grass mottling,
  the three-tone lobed trees with contact shadows and the tracer glow are all in
  `RangeVisualStyle` and the shared scene. Each platform only fills paths.
- **Same geometry.** Crown lobes and ridges are written as polygons into `PathSink`s, not drawn
  as platform circles, so both platforms fill the same outlines.
- **Colours per pose.** Haze is measured from the camera. Each hazed shape's colour is recomputed
  once per re-projection and packed to 8-bit `0xAARRGGBB` in Kotlin (no allocation). Both
  platforms therefore fill the same rounded colour.
- **Platform gradients.** Only two gradients are the platform's own: the sky's multi-stop linear
  gradient and the sun glow's radial gradient.
- **Measured parity.** Android emulator vs iPhone 17 simulator, fixed pose and follow camera at
  p = 0.5, all four themes: worst sampled CIEDE2000 0.80.
- **Vector only.** There are still no bundled raster assets; every shape is procedural.

## Addendum: F8a2t shot trail styles

- **Trails are shared layers.** The eleven shot trail styles, the kept earlier trails and the
  landing effects are all written by the shared `ShotTrail` into a fixed list of `TrailLayer`s
  (a `PathSink` plus a packed `0xAARRGGBB`, or a club palette index). Colour and alpha changes
  along a trail (comet, smoke, speed heat, rainbow, the spin ribbon's two faces) are bands of
  separate fills, never platform gradients, so each platform only fills the visible layers in order.
- **No per-frame allocation.** The layers, run buffers and colours are preallocated; a frame only
  rewrites paths and ints.
- **Measured parity.** Android emulator vs iPhone 17 simulator, follow camera at p = 0.5, all
  eleven styles on DAY and NIGHT: worst sampled CIEDE2000 0.99.
