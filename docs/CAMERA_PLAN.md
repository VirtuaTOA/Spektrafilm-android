# CAMERA_PLAN.md — in-app RAW camera with live film-stock preview

Status: **plan, nothing implemented.** Supersedes the JPEG-capture proposal (rejected — see §2).

## 1. Goal

Replace the current five-app workflow:

> Samsung Camera (Expert RAW) → export DNG → open Spektrafilm → import DNG → process → open
> Samsung Gallery to view

with a Fujifilm-style loop that never leaves the app:

> open Spektrafilm → pick a film stock → live preview of that stock → shutter → full-res render →
> appears in the app's own roll

### Non-negotiables

1. **Parity.** Nothing under `engine/spektra-core/src/main/cpp/**` changes. The camera is a new
   *source producer*; it feeds the existing `simulate()`. The 35-gate `engine-parity` suite is
   untouched by every phase below. If a phase ever seems to need an engine change, stop and
   re-scope — it almost certainly doesn't.
2. **RAW capture.** Scene-linear in. See §2.
3. **New code goes in new files.** `MainActivity.kt` is already 190 KB / ~4,400 lines. Camera and
   gallery screens must not be added to it.

## 2. Why RAW, not JPEG

The engine is a radiometric simulation, not a colour-grading LUT. `rgb_to_tc_b`
(`kernels/spectral_upsampling.h`) computes:

```
xyz = M @ rgb;  b = sum(xyz);  tc = tri2quad(xy)
spectrum = cubic_interp_lut_at_2d(spectra_lut, tc) * b
```

`b` is **irradiance** — the absolute magnitude of the linear pixel value *is* a physical light
quantity, multiplied straight into the reconstructed spectrum, which then meets the film's
log-sensitivity curves. An ISP tone curve is therefore a lie about how much light there was: a
highlight rolled off from 4.0 to 0.95 lands in the wrong place on the density curve, and the
shoulder the film is supposed to produce has already been produced by someone else in a different
shape.

`decodeToLinearProPhoto` (`app/.../ImagePipeline.kt:121`) inverts **only the sRGB gamma**. Tone
mapping, local contrast, saturation and sharpening are unrecoverable once baked.

Specific failure modes of a display-referred input:

- **Sharpening halos become fake light.** Halation/diffusion bloom the ISP's overshoot ring instead
  of the actual highlight.
- **Saturation boost becomes fake spectra.** The upsampler reconstructs a *more spectrally pure*
  light than existed; wrong colour goes in, not just "too saturated" coming out.
- **Noise reduction removes the substrate** the grain model is supposed to sit on.

The codebase already states this. On the lossy-DNG fallback (`ImagePipeline.kt:182`):

> the result is display-referred (NOT linear ACES scene data), so it bypasses the spectral
> scene-linear assumptions — preview/import quality only.

That path exists as a degraded fallback for broken files, not as a capture strategy.

**Also note:** a third-party JPEG is *not* the Samsung camera app's JPEG. Third-party apps go
through the standard Camera2/HAL path; Samsung's Scene Optimizer / multi-frame stack is private.
JPEG capture would have been worse than the stock app *and* carried processing the engine rejects.

## 3. What we reuse vs. what is new

### Already built (large head start)

| Capability | Where |
|---|---|
| Bake a film look into a 3D LUT | `SpektraEngine.bakeCubeLut(params, 33)`, gated by `test_bake_lut` |
| `.cube` parse + GLES 3.0 3D-LUT renderer, with driver-failure fallback | `LutGpuPreview.kt` |
| 28 named, grouped film-stock presets | `spektra/presets.json` + `BuiltInPresets` |
| RAW → linear via fd (no full-file byte[]) | `RawDecoder.decodeToLinear(fd)`, `decodeRawAtEdge` |
| Full-res render → `Pictures/Spektrafilm` | `simulate` → `simResultToBitmapGraded` → `saveToGallery` |
| Non-destructive per-source edit sidecars | `Recipes`, keyed by SHA-256 of the source Uri string |

### Greenfield

- No CameraX/Camera2 dependency, no `CAMERA` permission, **no runtime-permission UI anywhere in the
  app** (MediaStore on API 29+ needs none, so this pattern doesn't exist yet).
- No gallery screen. `Screen` is a 6-value enum at `MainActivity.kt:93`.
- No background job infrastructure.

### One free win

Captures are written to app-private storage, giving a **stable `file://` Uri**. `Recipes` keys off
the Uri string and its docs note photo-picker grants are ephemeral and may fail to re-bind — a
camera capture re-binds reliably. Re-developing a shot months later works better than the current
import path does.

## 4. Phase 0 — kill the two unknowns first

Small, throwaway, and it decides the shape of Phases 1–2. **Do not skip.**

### 0a. Does the GPU LUT path actually work on this device?

`LutGpuPreview.kt` carries: *"NOT yet verified on a real GPU in this environment — must be
device-tested before it is enabled by default."* The entire live viewfinder rests on it.

Enable the existing experimental GPU preview toggle in Settings, in the **editor**, on the S25.
Compare against the CPU render of the same image. Fix any problems here, where there is a known-good
reference to diff against — not later inside a live camera feed where everything is moving.

### 0b. How much of the ISP can we switch off?

This decides whether the viewfinder is representative or merely indicative. Throwaway probe screen:
open Camera2, apply these to the preview `CaptureRequest`, then **read the `CaptureResult` back** to
see which were actually honoured (Samsung's support is partial):

| Key | Value | Removes |
|---|---|---|
| `EDGE_MODE` | `OFF` | sharpening halos |
| `NOISE_REDUCTION_MODE` | `OFF` | smearing |
| `TONEMAP_MODE` | `CONTRAST_CURVE` + linear curve | highlight rolloff |
| `COLOR_CORRECTION_MODE` | `TRANSFORM_MATRIX` | saturation boost |

While in there, also record:

- `REQUEST_AVAILABLE_CAPABILITIES` contains `RAW`?
- `INFO_SUPPORTED_HARDWARE_LEVEL` (`FULL` / `LEVEL_3` expected)
- `SCALER_STREAM_CONFIGURATION_MAP` → available `RAW_SENSOR` sizes. **Third-party apps on Galaxy
  often get binned (~12 MP) rather than full sensor resolution, and lens access varies.** Find out
  now.
- Which physical cameras (ultrawide / tele) expose RAW at all.

### 0c. One timing measurement

Time a single `simulatePreview` at `DRAFT_RENDER_MAX_PX` (384). Full-res 12 MP is measured at
1–2 s on this device, which extrapolates to roughly 20–40 ms at 384 px. If it lands there, running
the **real engine** on the live viewfinder becomes possible, not just a baked LUT — a materially
more accurate preview (it would include auto-exposure). The GPU LUT stays the default for thermal
reasons, but this determines whether a hybrid "accurate check render on a static scene" is worth
building in Phase 4.

**Done when:** you can state, in one line each, (a) whether the GPU LUT renders correctly, (b) which
of the four ISP keys stick, (c) what RAW resolutions and lenses are available, (d) the 384 px render
time.

### RESULTS — SM-S931B (Galaxy S25), Android 16 / API 36, 2026-08-09

**A. RAW inventory — better than assumed.** Four camera ids visible to third parties:

| id | lens | level | RAW | size |
|---|---|---|---|---|
| 0 | back main, 5.4 mm | **LEVEL_3** (best) | yes | 4080×3060 (12.5 MP) |
| 1 | front, 3.3 mm | FULL | yes | 4000×3000 |
| 2 | back ultrawide, 2.2 mm | LIMITED | yes | 4000×3000 |
| 3 | front, 3.3 mm | FULL | yes | 3392×2544 |

The main camera is `LEVEL_3` — the highest Camera2 tier, full manual control. RAW is the sensor's
**binned 12.5 MP mode**, and `SENSOR_INFO_ACTIVE_ARRAY_SIZE` matches it exactly, so there is no crop
penalty — but the full 50 MP mode is not offered to third parties.

**All three rear lenses do RAW.** The first probe's "no telephoto" reading was wrong — it only
called `CameraManager.getCameraIdList()`, which **by design omits the physical sub-cameras behind a
logical multi-camera** (documented Android behaviour, not an OEM restriction). Logical camera 0
exposes three physical ids:

| physical id | focal | lens | level | RAW |
|---|---|---|---|---|
| 5 | 5.4 mm | main wide | **LEVEL_3** | 4080×3060 (12.5 MP) |
| 2 | 2.2 mm | ultrawide | LIMITED | 4000×3000 (12 MP) |
| 6 | 7.0 mm | **3× telephoto** | LIMITED | 3648×2736 (10 MP) |

Physical 6 is the 3× tele: 7.0 mm actual focal length and a 10 MP sensor both match the S25's
telephoto module. It appears **only** as a physical sub-camera, never in the public id list, which
is exactly why the first probe missed it. Independently confirmed in practice — MotionCam Pro
records linear RAW DNG from this lens on this device.

Access path: open the **logical** camera (id 0) and select the lens per output with
`OutputConfiguration.setPhysicalCameraId()` (API 28+). Do not open the physical id directly.

**Caveat for Phase 1:** the main lens is `LEVEL_3` but the ultrawide and tele are `LIMITED`. Section
B's ISP-disable test only ran against logical camera 0 (which routes to the main lens), so it is
**not established that the five controls hold when streaming from physical 2 or 6**. Ship main-lens
first and re-probe per physical id before enabling the other two in the viewfinder.

**B. ISP disable — all five controls stick.** `EDGE_MODE=OFF`, `NOISE_REDUCTION_MODE=OFF`,
`TONEMAP_MODE=CONTRAST_CURVE` (512 curve points available), `CONTROL_AWB_MODE=OFF`,
`COLOR_CORRECTION_MODE=TRANSFORM_MATRIX` all read back as requested. This is the good outcome: the
viewfinder can be fed a near-linear stream rather than a tone-mapped one.

**Re-confirmed at 1920×1080** (the first run used 176×144, which would not have proved anything
about a real viewfinder stream — some OEM pipelines route small legacy streams differently). All
five still apply at preview resolution.

Remaining caveats: a mode reading back as applied is strong evidence, not proof, that the processing
is fully bypassed; and this was tested on the main lens only (see the LIMITED-lens note in A).

**C. Draft render = 219 ms median** (384 px, grain/halation off, 5 runs after warm-up). Far above a
33 ms frame budget, so **running the real engine on the live viewfinder is ruled out**. The baked
LUT is the only viable viewfinder. Question closed.

**CONCLUSION IS FINAL — the LUT viewfinder stands.** This was briefly reopened by a hypothesis that
~200 ms of the 219 ms was uncached spectral-LUT construction. **That hypothesis was wrong by ~100×.**
Measured on host at `lut_resolution = 17`: `build_lut_3d` costs 1.3–1.7 ms per LUT and the PCHIP
prepare 0.23 ms — a total fixed cost of ~1.9 ms, not 200 ms. The structural claim (no cache existed)
was correct; the magnitude was not. It came from comparing a device `simulate` measurement at 12 MP
against a device `simulate_preview` measurement at 384 px — different entry points, not a valid
per-pixel comparison.

**The device's 219 ms remains unexplained.** Host fits the whole 384 px preview call at
≈1.9 ms + 0.076 µs/px ≈ 13 ms — a ~17× gap to the phone. Untested lead: `spektra_jni.cpp` resolves
every param getter with a fresh `GetObjectClass` + string-based `GetMethodID` on each call, ~200 of
them per render. That is per-call and unamortized, so it would dominate a small render and vanish at
12 MP — which matches the observed signature — and it is invisible to host benchmarks, which call
the C++ directly.

None of this changes the decision, and the reason does not depend on any timing number: **the real
engine saturates every CPU core, and a viewfinder runs for minutes.** Even if it hit 30 fps cold it
would thermally throttle out of it mid-session, while the GPU LUT is nearly free. Real-engine
viewfinder is closed; the optional static-scene check render (§8) remains the escape hatch. `spk_simulate_preview` force-enables both spectral
LUTs (`use_scanner_lut`, `use_enlarger_lut`, 17³) and the engine caches only `profile_cache` /
`tc_lut_cache` — the scanner and enlarger LUTs appear to be rebuilt on every call. Not on the camera
critical path (the viewfinder is LUT-based regardless), but it likely costs the **editor** preview
~200 ms per render. Tracked separately.

**0a. GPU LUT preview is BROKEN — root cause found.** A DNG rendered through the GPU LUT path is
visibly underexposed with raised, flat blacks; the CPU path on the same file is correct.

`spk_bake_cube_lut` (`spektra.cpp:1904`) copies the params and zeroes every spatial/stochastic
field — grain, halation, glare, both diffusions, lens blur, DIR diffusion, scanner unsharp, crop,
upscale — but **does not zero `auto_exposure`**, which defaults ON. So `run_print` →
`preprocess_geometry` runs `apply_auto_exposure` on the *identity lattice*: a synthetic n³×1 row of
every RGB combination, nothing like a photograph. The meaningless gain it meters from that ramp is
baked into the LUT, then applied to real images whose correct gain is completely different.

A wrong gain is not merely a brightness offset — it lands the scene in the wrong region of the
film's density curve. Sitting in the toe is exactly where base fog lifts the shadows and contrast
collapses, which is the reported signature.

(Secondary, compounding: the shader clamps the proxy to `[0,1]` and indexes the LUT linearly in
linear light, so a scene sitting around 0.1–0.2 uses only the bottom few lattice points.)

**Consequence for Phase 1.** The LUT must be baked with `auto_exposure = 0` and the exposure gain
applied separately, outside the LUT — see §5. This is a pre-existing bug in the shipped
(experimental, default-off) editor feature too, not something the camera introduces.

## 5. Phase 1 — viewfinder with live film-stock preview

**Goal:** point the phone at a scene, swipe through stocks, see the look change live. No capture.

### Steps 1–2 — DONE and device-verified (2026-08-09)

The exposure problem is solved. `spk_bake_cube_lut` now bakes with `auto_exposure = 0` (ec2ca59),
and `spk_meter_exposure_ev` exposes the gain the render would apply so callers can supply it
(cae7fa2). It does not re-derive the metering — it runs `spk::apply_auto_exposure`, the identical
function `preprocess_geometry` calls, and returns the EV that function already reports, so the
preview's gain **cannot** drift from the render's. Surfaced as
`SpektraEngine.meterExposureEv` / `.exposureGain`; the GPU preview meters the proxy it bakes from
and applies the gain in-shader as `uExposureGain` before the `[0,1]` LUT-domain clamp.

**Verified on SM-S931B:** with GPU preview on, an Expert RAW DNG now matches the CPU render in
colour and exposure. Previously it was visibly darker with lifted, flat blacks. 36/36 parity green,
NDK build + unit tests + lint green.

Useful property that fell out of this: AE always meters a max-256 internal downscale, so a small
proxy meters near-identically to the full-resolution original of the same scene. That is what will
let a viewfinder-metered gain carry over to a full-res capture in Phase 2.

Note the fit preview is soft on BOTH paths — that is `previewMaxSize` (default 640 px), the
two-resolution proxy rule, not a LUT artifact. It does not affect the camera, whose viewfinder is
fed a ~1080p camera stream rather than a decoded proxy. On the GPU path a larger proxy costs only
decode + upload rather than a per-frame CPU render, so raising `previewMaxSize` is far cheaper
there — a possible later refinement, not Phase 1 work.

### Steps 3–8 — camera plumbing

**Camera2 directly, NOT CameraX.** Decision #3 (a `compileSdk` bump) is therefore moot. Phase 0
already proved a working Camera2 path on this device, and all three things this feature needs are
Camera2-native while being awkward or unsupported through CameraX:
`OutputConfiguration.setPhysicalCameraId` for the telephoto, the four ISP-disable
`CaptureRequest` keys, and `RAW_SENSOR` + `DngCreator`. CameraX's value is in simplifying the
common case; none of these are it, and its `Preview` use case is not needed either since the
viewfinder renders through our own GL surface.

#### Metering: an explicit meter/lock button, NOT continuous AE

Continuous per-frame metering was the original sketch. It is the wrong design here, and an
explicit **meter button** (user's proposal) is better on four counts:

1. **It removes a whole problem class.** No per-frame JNI metering, no smoothing filter, no gain
   jitter, no flicker while panning.
2. **It matches the workflow being emulated.** Meter the scene, set exposure, shoot. This is a
   deliberate stills app, not a point-and-shoot; AE-Lock is a standard control on every serious
   camera, so this is a feature rather than a simplification.
3. **A locked gain is stable while composing.** Continuous AE re-judges the scene as you pan, so
   the preview's brightness shifts under you while you are trying to frame.
4. **It is what makes preview and capture agree — the decisive reason.** Two exposures are in
   play: the *sensor's* (shutter/ISO, Camera2's `CONTROL_AE_*`) and the *engine's* digital gain.
   If the sensor's AE keeps moving, the RAW's linear values move with it and a pinned engine gain
   becomes wrong. So the button must lock **both together**: set `CONTROL_AE_LOCK = true` and
   capture the engine gain in the same action. Pin both and the viewfinder's exposure is the
   capture's exposure by construction, instead of by approximation.

Behaviour: meter once automatically when the viewfinder starts (so it is never wildly wrong),
display the metered EV, and re-meter only on the button. The gain is **stock-independent** —
`spk_meter_exposure_ev` reads only `auto_exposure`, `auto_exposure_method` and
`input_cctf_decoding`, never the film profile — so swiping stocks must NOT re-meter. Spot/tap
metering is a natural later addition.

Work, in **verifiable increments** — the GL external-texture path is the only piece with real
unknowns left (orientation, aspect and the transform matrix across device rotation), so it is
proved with a plain passthrough BEFORE the LUT is layered on. A sideways viewfinder and a wrong
LUT look identical from the couch otherwise.

- **1a.** Manifest `CAMERA` permission (landed in Phase 0) + runtime permission flow — a new
  pattern for this app, needs a denial/rationale path. `Screen.CAMERA` in the enum.
- **1b.** `CameraSession.kt` — Camera2 open/session/repeating request, the four ISP-disable keys,
  `CONTROL_AE_LOCK`, physical-lens selection via `OutputConfiguration.setPhysicalCameraId`.
- **1c.** `CameraLutRenderer.kt` — GL `samplerExternalOES` + `SurfaceTexture` transform matrix.
  **Checkpoint: plain passthrough, no LUT.** Verify a live, correctly-oriented, correctly-shaped
  image first.
- **1d.** `LutBakery.kt` — bake per stock on a background thread, cached, with `ColorGrade`
  folded into the lattice (`bakeCubeLut` takes `SpektraParams`, and saturation/vibrance/
  gamutCompress are not `SpektraParams` fields — without this the viewfinder misses them).
- **1e.** LUT + `uExposureGain` into the camera shader; meter/lock button.
- **1f.** Stock picker strip; lens picker (main lens only until the LIMITED lenses are re-probed).
- **Fork `LutRenderer`** into a camera variant. Reusable as-is: program build, 3D-LUT upload with
  its (B,G,R) axis mapping, full-screen quad, letterbox, driver-failure fallback. Changes needed:
  `samplerExternalOES` + `#extension GL_OES_EGL_image_external_essl3 : require`, the
  `SurfaceTexture` transform matrix, and an input transform in front of the LUT lookup (linear if
  Phase 0b succeeded; sRGB-decode → ProPhoto matrix otherwise).
- `LutBakery`: bake on stock selection, on a background thread, cached per stock. **Never per
  frame.** Consider 17³ on the swipe and 33³ on settle.
- **Bake with `auto_exposure = 0`, and apply the exposure gain outside the LUT.** Phase 0a proved
  that baking with AE on meters the identity lattice and poisons the LUT. A pointwise LUT
  fundamentally cannot carry AE — AE is a function of the whole image, and the lattice is not an
  image. So the viewfinder needs a scalar gain applied to the frame *before* the LUT lookup (a
  cheap shader multiply), derived from the preview frame's own statistics to mirror what the
  engine's `apply_auto_exposure` would compute on the capture. Getting that approximation close is
  the main calibration work of Phase 1, and it is what makes preview and capture agree on exposure.
  Fixing the bake also repairs the existing editor GPU-preview feature.
- **Fold the post-engine grade into the baked LUT.** `bakeCubeLut` takes `SpektraParams`, and
  `saturation` / `vibrance` / `gamutCompress` are *not* `SpektraParams` fields — they're the Kotlin
  post-grade in `ColorGrade`. Run the 33³ lattice through `ColorGrade.applyInPlace` after baking or
  the preview will not match the capture. (`contrast` *is* included — it composes into the tone
  curve inside `toParams`.)
- Bottom stock-picker strip driven by `BuiltInPresets.grouped`.

**Done when:** live viewfinder, stock swiping changes the look on screen, no crash on permission
denial, graceful fallback if GL fails.

## 6. Phase 2 — shutter → RAW → process → save

**Goal:** the full loop.

The cheapest *correct* implementation: **the camera writes a DNG, then hands its `file://` Uri to
the existing pipeline as a `SourceKind.RAW` source.** No new processing code at all — `loadSource`,
`simulate`, `simResultToBitmapGraded` and `saveToGallery` run unchanged.

Work:

- RAW capture (CameraX RAW output if the version supports it, else Camera2 interop) →
  `DngCreator` → `filesDir/captures/<timestamp>.dng`.
- Gate on the Phase 0b capability check; clear message if the device/lens can't do RAW.
- Optionally request the ISP JPEG on a second surface *purely as an instant roll thumbnail* — never
  as engine input.
- White balance: `WhiteBalance.AS_SHOT` (the existing default) so the DNG's own WB is honoured.
- Processing job: async so bursts work, but at 1–2 s full-res this is ergonomics, not architecture.
- Write the recipe sidecar at capture time so the shot is re-editable.
- Save the render through the existing export path (format from Settings).

**Done when:** shutter → ~1–2 s → processed full-res image saved, and you can keep shooting
meanwhile.

## 7. Phase 3 — in-app camera roll

- `Screen.GALLERY`, backed by a MediaStore query on `RELATIVE_PATH = Pictures/Spektrafilm`. Because
  it's MediaStore, shots also appear in Samsung Gallery for free and `saveToGallery` is reused
  verbatim.
- Grid → tap → full view → share / open in editor / **re-develop with a different stock** (loads the
  retained DNG + its recipe). This is the thing a Fuji cannot do, and it's the strongest argument
  for keeping sources.

## 8. Phase 4 — robustness and feel

- Foreground service or WorkManager so processing survives backgrounding.
- Queue-depth cap and thermal backoff.
- Tap-to-focus, exposure compensation, grid lines, shutter haptics/sound, front/rear switch.
- Settings: default stock, keep-sources toggle + storage budget, capture resolution.
- Optional (decided by Phase 0c): hybrid "accurate check render" — run the real engine at draft
  resolution on a static scene to correct the LUT preview.

## 8b. Viewfinder banding — 10-bit attempted and reverted (2026-08-09)

The 8-bit preview stream carries a SCENE-LINEAR signal (the ISP tone curve is disabled so
the engine sees real radiometry), and linear 8-bit spends almost no code values in the
shadows — mid-grey sits near code 46. Gradients therefore arrive already quantised, and
`uExposureGain` multiplies those steps. Two fixes were attempted and both reverted:

1. **sRGB transport curve** (encode in `TONEMAP_CURVE`, decode in the shader). **Silently
   not applied**: with the 32-point curve requested, mean raw luma stayed at ~0.19
   (scene-linear); an applied sRGB encode would have read ~0.45. The 4-point identity
   curve demonstrably IS honoured, so `CONTRAST_CURVE` works — just not that curve.
2. **HLG10 10-bit** (`DYNAMIC_RANGE_TEN_BIT` is advertised, and the profile WAS granted —
   the status line read 10-bit). Reverted because **HLG10 changes the exposure semantics
   of the whole session, not just its bit depth**: mean luma on the untouched 8-bit
   metering stream fell from ~0.19 to ~0.09, i.e. the camera re-exposed about a stop down
   to reserve specular headroom, as HDR capture should. Rendering that correctly means
   modelling HDR reference white and specular headroom — an HDR pipeline, not a flag.

Current state: 8-bit linear plus a +/- half-LSB shader dither, which converts banding into
fine noise. It cannot restore lost information. **This affects the VIEWFINDER ONLY** —
captures are RAW at 10-14 bits and never touch this path.

If revisited: the honest scope is HDR reference levels (diffuse white at ~0.5 signal,
headroom above), plus establishing empirically whether the external texture delivers raw
HLG or driver-converted display colour, which the API does not report. Do that with a
measurement first, not by reasoning forward — every failure above came from assuming a
Camera2 request equals a Camera2 result.

## 9. Preview fidelity — what will and won't match

Be explicit with expectations; this is the main UX risk.

**Will match reasonably:** overall colour and tone of the stock, film/print profile character,
contrast, the creative grade (once §5's `ColorGrade` fold-in is done).

**Will not match:**

- **Grain, halation, diffusion glare, DIR-coupler diffusion, scanner unsharp.** A 3D LUT cannot
  represent spatial or stochastic effects; `bakeCubeLut` forces them off. The preview shows colour
  and tone, never texture.
- **Exposure placement**, to a degree. Camera AE meters the scene, then the engine's `autoExposure`
  (default on) meters again and applies its own gain. Consider a histogram / clipping indicator from
  sensor metadata rather than trusting the preview for exposure.
- **Whatever ISP processing Phase 0b failed to switch off.**

## 8c. Proposed: vintage EV-compensation meter (not built)

A match-needle exposure meter along the viewfinder edge, in the idiom of an SLR finder:
a scale with a centre notch and a needle showing the current reading. FUNCTIONAL, not
decoration — dragging the needle applies exposure compensation, and the reading reflects
what the engine will actually do.

It has a real advantage over a numeric EV readout: the app already meters through
`spk_meter_exposure_ev`, so the needle can show the ENGINE's exposure decision rather
than the sensor's, which is the number that determines where the scene lands on the film
curve. Compensation would feed `camera.exposureCompensationEv`, so it flows through the
same path a capture uses.

Pairs naturally with the focusing-screen overlay (split-image centre, microprism collar,
vignette) and 35 mm frame lines — one overlay layer above the viewfinder.

## 10. Open decisions

| # | Decision | Recommendation |
|---|---|---|
| 1 | Where the roll lives | MediaStore `Pictures/Spektrafilm` — zero new storage code, visible in Samsung Gallery too |
| 2 | Keep source DNGs? | Yes — enables re-develop (§7). Budget ~25–50 MB per shot; needs a retention setting |
| 3 | `compileSdk` 34 → 35 | Only if the chosen CameraX version requires it; re-run the 16 KB-page gates if so |
| 4 | Capture resolution | Decided by Phase 0b's finding on binned vs full sensor RAW |

## 11. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| GPU LUT path unverified on real hardware | High | Phase 0a, before anything else |
| Samsung honours few/no ISP-off keys → preview diverges | Medium | Phase 0b; fall back to a calibrated sRGB→linear approximation |
| Third-party RAW is single-frame — **noisier than Samsung Expert RAW**, which uses multi-frame stacking unavailable to us | Medium | Evaluate real captures in Phase 2. May be acceptable, or even moot, under film grain |
| Third-party RAW may be binned resolution / main lens only | Medium | Phase 0b establishes the ceiling |
| Thermals: live camera + continuous GLES + repeated full-res renders | Medium | Queue cap, thermal backoff (Phase 4) |
| Memory: camera buffers coexisting with full-res engine renders — new pressure the existing OOM ladder has never faced | Medium | Reuse the fd decode path; cap queue depth |
| Storage growth from retained DNGs | Low | Retention setting + budget |
| ~~Full-res render too slow to feel like a camera~~ | **Resolved** | Measured 1–2 s full-res on S25. The stale 4560 ms in `DEVICE_TEST_REPORT.md` was an older build, at preview scale, on an older device, and predates the full-res export fix |

## 12. Code touchpoints

| Purpose | File |
|---|---|
| Screen enum, navigation | `app/.../MainActivity.kt:93`, `:175` |
| Source kinds, decode funnel | `app/.../MainActivity.kt:685` (`loadSource`) |
| RAW decode from fd | `app/.../EngineHelpers.kt:166` (`decodeRawAtEdge`) |
| LUT parse + GL renderer to fork | `app/.../LutGpuPreview.kt` |
| LUT bake (native) | `engine/.../SpektraEngine.kt:158` |
| Post-engine grade to fold into the LUT | `app/.../ColorGrade.kt:41` |
| Export writers | `app/.../ImagePipeline.kt:574` (`saveToGallery`), `:674`, `:818` |
| Recipe sidecars | `app/.../Recipes.kt` |
| Built-in stocks | `app/.../BuiltInPresets.kt` + `spektra/presets.json` |
