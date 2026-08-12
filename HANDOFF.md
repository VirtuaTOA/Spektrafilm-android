# Spektrafilm Android — Session Handoff

## Current state (2026-08-12, fork `VirtuaTOA/Spektrafilm-android`, branch `main`)

**This is Dan's fork**, created 2026-08-08 from `thetechgeekko/Spektrafilm-android`. Everything
below the horizontal rule is **inherited upstream context** — still accurate about the engine, but it
describes a pre-camera world and a Linux container. This fork runs on **macOS**, and its work is the
**in-app camera**, not the gamut-compression roadmap.

### What this fork adds: a camera

27 commits (`8ccfbac`..`6759e1d`), pushed to `origin/main` 2026-08-12. Replaces a five-app workflow
(Samsung Expert RAW → export DNG → import → process → Gallery) with: launcher → viewfinder with live
film-stock preview → RAW capture → background processing → JPEG in `Pictures/Spektrafilm`.

- **Viewfinder** (`CameraScreen.kt`, `CameraGlPreview.kt`, `CameraSession.kt`): Camera2 +
  `setPhysicalCameraId` (the only route to the telephoto), GLES 3.0 `samplerExternalOES`, 3D LUT
  applied in-shader. 3:2 film frame, manual focus, three lenses, meter/AE-lock.
- **LUT bakery** (`LutBakery.kt`): 17³ lattice on an **sRGB-shaped** axis, LRU-cached per look.
  `ColorGrade` is folded in; spatial/stochastic effects cannot be and are forced off.
- **Capture** (`CaptureProcessing.kt`): RAW DNG via `DngCreator` → disk-backed queue → foreground
  service (Android freezes cached processes) → full engine render → JPEG.
- **Engine**: `spk_bake_cube_lut` (auto-exposure OFF by construction) + `spk_meter_exposure_ev`;
  grain parallelised (12MP grain 66.8s → 17.2s); scanner/enlarger spectral 3D LUTs memoized.
- **Presets**: one neutral preset per stock (20), saturation 0, scanner white/black correction on.
- **Version still 0.8.0 / versionCode 10** — bump before any release.

### Open issues — both were attempted, the attempt failed and was reverted

1. **Tungsten stocks preview blue in the viewfinder.** Cause confirmed by inspection:
   `FilmStockBalance` / `CreativeWhiteBalance` are **not `SpektraParams` fields** — they are Kotlin
   transforms applied to pixels *before* the engine. `LutBakery` bakes from params only, so the bake
   cannot see them (0 hits for `FilmStockBalance` in `LutBakery.kt`). **The camera capture path does
   not apply them either** — only the editor does (`MainActivity.kt:794`). So an in-app photo is
   consistently wrong in both viewfinder and export; opening that same file in the edit page
   corrects it.
2. **Lens focal lengths ignore the 3:2 crop.** `equivalentFocal` (`CameraSession.kt:153`) uses the
   full active-array diagonal, but the app crops to 3:2, so the shown equivalents read wider than
   what is actually delivered.

**A failed attempt at both is preserved on branch `wip/reverted-wb-focal`** (`9a46dc1`, `70e20dd`).
**Do not simply re-apply it.** It caused (a) apparent overexposure on *every* stock — cause never
identified, (b) an orange flash while dragging onto a tungsten stock, (c) completely blue exports.
The WB matrix was verified to be genuine identity for 18 of 20 stocks, so the overexposure was
**not** the WB matrix. Diagnose before retrying.

### Findings from 2026-08-12 (expensive to rediscover)

- **FIXED: the still capture was leaving the vendor tone curve on the preview.** After every shot
  the viewfinder went flat, lifted and washed out until the user switched lens or toggled AE-L. Cause:
  the still request used `TEMPLATE_STILL_CAPTURE` **without `applyIspDisables`**, so the identity
  tone curve was not set on it, and the vendor's own curve stayed applied to the preview afterwards.
  Fix: call `applyIspDisables` on the still request too (`CameraSession.capture`). Harmless to the
  file — RAW is read before the ISP.
  - **`TONEMAP_MODE` IS NOT A USEFUL SIGNAL HERE.** It read `CONTRAST_CURVE` before *and* after,
    which is what made this take four wrong attempts: the mode says only that *a* curve is in use,
    never *which*. `TONEMAP_CURVE` is what changed and it is not in the result the same way.
  - **The measurement that actually worked** was logging the pixels handed to the meter
    (mean/max/clipped) across one shutter press, phone still, same scene. Before the fix:
    mean 0.1239→0.2302 (1.86x) while max 0.8863→0.9569 (1.08x). **Midtones lifting far more than
    highlights is a CURVE; an exposure change scales both alike.** After: 0.1337→0.1327 (0.7%).
    Reach for this first next time a "brightness" bug appears — `SENSOR_EXPOSURE_TIME`/
    `SENSOR_SENSITIVITY` were identical throughout and proved nothing.
  - Dead ends, so they are not retried: re-submitting the repeating request after a capture (does
    not restore the curve, and restarts AE convergence — visibly changes contrast);
    `CONTROL_POST_RAW_SENSITIVITY_BOOST` (pinned at 100 throughout); sensor readout-mode switching.
  - Also fixed alongside: the startup auto-meter fired at 1200ms, measured on device to land while
    `CONTROL_AE_STATE` was still SEARCHING, and pinned a gain from a frame 0.67 EV darker than the
    one AE settled on. Now 1800ms. And the meter now re-runs after each capture (`meterUnlocked`),
    which is what the user had been doing by hand with AE-L.
- **R8/minify produces BLACK exports.** A release build installs, launches, and shows a working
  viewfinder — then writes an all-black image. Shrinking removes something the processing path
  reaches indirectly; it fails silently, not loudly. `proguard-rules.pro` keeps
  `com.spectrafilm.engine.**`, the libraw/tiff/png writers, native methods and enums — not enough.
  **Ship debug APKs until this is diagnosed.**
- **Distribution needs no release keystore.** The repo commits a stable `debug.keystore`
  (`app/build.gradle.kts:47`, `.gitignore` `!/debug.keystore`), so debug APKs are signed with a
  consistent key and update cleanly forever from any machine with the repo. A release key is only
  needed for the Play Store — and switching keys later forces every tester to uninstall, losing
  their photos.
- **Tested on exactly one device** (SM-S948W / Android 16). The camera needs API 28+ and a Camera2
  `RAW_SENSOR` output. Without RAW the viewfinder still works but `canCapture` is false and the
  shutter is disabled — graceful, no crash.

### Next

- Fix the two open issues above.
- Not built: in-app gallery (Phase 3); vintage EV-comp meter (designed in `docs/CAMERA_PLAN.md`
  §8c); focusing-screen overlay.
- Deferred: diagnose the R8 failure; per-stock grain calibration; LibRaw lossy-DNG support (Samsung
  Expert RAW writes lossy-JPEG DNG 1.6 that LibRaw rejects, so the platform decoder returns
  display-referred pixels — the "deep-fried" import, a banner warns); export memory tiling.
- **README does not mention the camera at all.**

---

## Inherited upstream state (2026-07-02, branch `claude/exciting-hamilton-hya62`) — engine roadmap

- **"Exact + fast" pass MERGED (PR #109 + #110).** The PM directive (*"spektrafilm-exact result at
  ultra-fast speed"*) is fully landed: F1–F7 Kotlin robustness fixes, **E1** per-effect spatial
  decouple, **E2** print-route spatial + grain enable, **S1** scan-route film-density memo,
  **S2** print-density memo, **S3** Kotlin retained-result grade cache, **S4** deterministic loop
  parallelization. Every default engine path stays byte-identical; the two intentional look
  changes (RAW-input colorspace correction, print-route now carries film character) are onto the
  oracle. See the changelog entry below for commits + the perf table.
- **Oklch perceptual output-gamut compression MERGED (PR #111) — P2 #6 slice 1.** Opt-in /
  default-OFF, byte-identical off. C++ `OutputGamutCompress::kOklch=3` / facade `OKLCH` / UI
  "Oklch (perceptual, keep hue)": perceptual-hue-preserving chroma compression at constant
  Oklch(L, h) — Reinhard knee on `C / C_max`, `C_max` regenerated in-engine by a 64×720 bisection,
  float64 matrices from colour-science. Bit-exact to the oracle (gate `max_abs 1.077e-14`), gated
  by **`test_gamut_out_oklch`** (golden generated at oracle `27bd085`).
- **Oklrab perceptual output-gamut compression LANDED on this branch (P2 #6 slice 2) — NOT yet
  merged.** Opt-in / default-OFF, byte-identical off. C++ `OutputGamutCompress::kOklrab=4` / facade
  `OKLRAB` / UI "Oklrab (perceptual, even lightness)": the oklch chroma reduction with the per-pixel
  `C_max` lookup indexed by Ottosson 2023's rebased lightness `Lr = f(L)` instead of raw `L` (and the
  `C_max(Lr,h)` table built over an Lr grid, OkLab `L` recovered per row by the inverse remap before
  `Oklab→XYZ`); reconstruction still preserves `L`. Bit-exact to the oracle (gate `max_abs 1.055e-14`
  / probes `1.221e-15`), gated by **`test_gamut_out_oklrab`** (golden at oracle `27bd085`). Commits
  `9cb0a0b` golden / `94d2274` engine+ci / `e1b75d8` app on `claude/exciting-hamilton-hya62`.
- **Host parity suite = 35 gates**, all green (argv authoritative in `.github/workflows/ci.yml`);
  `SPK_NUM_THREADS` 1≡8 byte-identical (oklrab compress is serial+stateless); NDK r27 3-ABI build
  path unchanged. App **0.8.0 / versionCode 10**.
- **This branch now carries the unmerged oklrab commits (slice 2) on top of `origin/main` + the
  `1174fd8` docs commit.** Open a NEW draft PR for them; the remote branch auto-deletes on merge and
  recreates with a plain push. Never stack new work on already-merged history.

## Next — P2 #6 slice 3: `jzazbz` (then slice 4 `cam16ucs`)

Slice 2 `oklrab` is DONE (see the state block above). Clone the same pattern; the templates are now
`tools/parity/gen_gamut_oklrab_golden.py`, `model/gamut_compression.{h,cpp}` (oklch + oklrab
sections), and `tests/test_gamut_out_oklrab.cpp` + its ci.yml argv. **`jzazbz` is harder than
oklrab** — it is NOT a simple L-remap: it needs a JzAzBz forward/inverse (PQ encoding + matrices,
absolute-luminance scaled by `_JZAZBZ_Y_W_CDM2 = 100` cd/m²) and its OWN C_max table geometry
(`L_grid=linspace(0.002, 0.18, 64)`, `chroma_initial_upper=0.3`) plus a per-space Jz-white
normalizer for the lightness knee (`_jzazbz_white_Jz`). See oracle `compress_rgb_jzazbz_chroma` +
the `"jzazbz"` branch of `_get_output_c_max_table` in `utils/gamut_compression.py`.
- **Golden:** new `gen_gamut_oklrab_golden.py`-clone → `gen_gamut_jzazbz_golden.py`; call
  `gc.compress_rgb_jzazbz_chroma`; generate at oracle `27bd085` (already checked out) and pin the SHA.
- **C++:** port JzAzBz forward/inverse into `model/gamut_compression.cpp` (capture the colour-science
  matrices/constants as bit-exact hex, as oklch did for OkLab); enum slot `kJzazbz=5` is reserved.
- **Gate:** `test_gamut_out_jzazbz` + its `ci.yml` `build_run … tests/gamut_jzazbz_cases.bin` line
  (bumps the suite to 36). Add `gamut_out_jzazbz` to the enumerated lists in CLAUDE.md + the skills.
- **Facade/UI:** add `JZAZBZ` to `enum class OutputGamutCompress` (+ the exhaustive `when` in
  MainActivity — Kotlin will error if you forget) and the Output-gamut dropdown.
- Then **`cam16ucs`** (`kCam16ucs=6`, the heaviest — full CIECAM16 forward/inverse). Default upstream.

Per increment: default path byte-identical, opt-in/default-OFF, feature-on within tol
(`max_abs ≤ 1e-4`, `rms ≤ 1e-5`), `SPK_NUM_THREADS` 1≡8, NDK r27 3-ABI build, commit+push on green.
**Ship ONE algo per PR** — subagents died on token limits when given more, so keep each unit small.

### Also open (unchanged)
- **Strategy-B rebaseline cluster** (`PRIORITY_ROADMAP` #20-27; incl. CAT02→CAT16 + xy-clip removal)
  — one coordinated baseline bump; trigger NOT fired (upstream WB-norm `e301791`/`526e200` still
  churning on `reflectance-upsampling-methods`, checked 2026-07-01). Keep the `c1d0e44` pin.
- **Device-gated queue** (user tests on his SM-S948W/Android 16): R8 0.8.0 release smoke; GPU-LUT
  re-arm feel; the E2 print-route look change (film character now in prints — intentional, eyeball
  it); AUDIT §A param-wiring UX decisions.
- **MALLETT2019** — disclosed as a GatedBlock; implement-vs-remove decision still open (`#18`).

---

## Evergreen operating notes (read once per session)

> **Fork caveat (2026-08-12):** the notes below were written for the upstream Linux **container**
> environment. On this fork's macOS checkout, ignore the container-reset / proxy-desync / PR-lifecycle
> / oracle-setup notes and the `/opt/android-sdk` + `/home/user/spektrafilm` paths — none apply. The
> engineering rules (parity gate, `-fno-finite-math-only`, GPU-preview-only, engine-param honesty,
> APK build flags, user directives) **do** still apply.

- **Container-reset recovery** (drilled 5+ times): the env re-clones to a stale commit mid-session.
  Recover via `git fetch origin main` (and the branch) → `git remote prune origin` → verify pushed
  work is on origin → `git reset --hard <ref>`. Untracked new files SURVIVE `reset --hard`; tracked
  edits do NOT. Rule: `git add && git commit -c commit.gpgsign=false && git push` the instant a unit
  builds green. `/tmp` and pip envs do not persist.
- **Proxy-desync recovery:** the local git proxy can return a stale snapshot and refuse
  `git fetch origin <branch>` by name — `git fetch origin <full-sha>` or `refs/pull/<N>/head` still
  works → `git reset --hard FETCH_HEAD`. Once a PR is merged the work is safe on real GitHub.
- **PR/branch lifecycle:** the remote branch auto-deletes on merge — recreate with a plain
  `git push` (`--force-with-lease` fails 'stale info'; `git fetch --prune` first). After a merge,
  restart from origin/main and open a NEW PR (never stack on merged history). The user may merge
  mid-session and webhooks don't deliver merges — re-check PR state before pushing. Merging is
  policy-gated (explicit user go-ahead); tag-push releases allowed when asked.
- **Oracle setup:** local clone at `/home/user/spektrafilm`; env = system python3.11 with
  `PYTHONPATH=/home/user/spektrafilm/src:/tmp/spkstubs` (stubs mock heavy IO deps). **e2e /
  param-wiring goldens pinned at `c1d0e44`** (upstream drift began at `a9bccd6` — never regenerate
  from tip); **gamut primitive goldens generated at `27bd085`**. Checkout the pin SHA before
  generating, restore the branch after; new gen scripts must pin the SHA they generate at.
- **Parity gate: 34 host tests**; per-test argv is authoritative in `.github/workflows/ci.yml`
  (copy, never guess) — any doc citing 15/26/31/33 gates is stale. Every engine change: default
  path byte-identical, feature-on within tol, `SPK_NUM_THREADS` 1≡8, NDK r27 3-ABI build green. All
  new engine features ship opt-in / default-OFF.
- **Land engine fixes ONE AT A TIME**, one small item per subagent — parallel agents collide on the
  shared engine files and the PR, and larger tasks blew the subagents' token limits mid-run.
- **`-fno-finite-math-only` is required** (scanning relies on NaN propagation through
  `density_to_light`); **GPU is preview-only, NEVER export** (vendor-varying float, float64 expose
  integrals, implementation-defined NaN handling).
- **Build distributable debug APKs with plain `./gradlew :app:assembleDebug`** — NEVER
  `-Pandroid.injected.build.abi` (stamps `android:testOnly`, blocks tap-install, moves output to
  `intermediates/`). **R8/minified release is NOT exercised by CI** — smoke-test on-device before
  tagging (last validated 2026-06-04 on SM-S948W/Android 16). **As of 2026-08-12 it is BROKEN on the
  fork**: minified builds render a working viewfinder but export an all-black image. See the
  fork's findings section at the top.
- **User directives on record:** do NOT modify `.github/workflows/` ('everything works there'); do
  NOT convert `.lut`→`.bin` (measured net-negative); **GPLv3 attribution "Film modeling powered by
  spektrafilm" must stay**; never put the model identifier in committed artifacts.
- **Toolchain** at `/opt/android-sdk` (NDK 27.0.12077973, CMake 3.22.1, build-tools 35.0.0) may not
  persist across containers — reinstall via `sdkmanager` if gone; `local.properties` is gitignored.
- **Kotlin/UI-only changes never touch the parity suite.** Post-engine grades and masks composite
  once, in-place on `res.data` via `simResultToBitmapGraded` right after simulate — never inside
  `simResultToBitmap` (the export site feeds `res` to both the bitmap and the 16-bit writers, so
  consumer-side mutation double-applies).
- **Engine param honesty:** presets/UI must set only engine-honored fields (halation via
  `halationAmount`/`scatterAmount`/`boostEv` — `halationStrength`/`halationFirstSigmaUm` are baked
  per-profile and ignored); params threaded only inside conditional blocks (e.g. `if(spatial)`) get
  silently dropped on the default path — thread unconditionally and fold into the relevant cache keys.
- **Perf medians are container-specific** — never compare benchmark numbers across boxes (the older
  2-core numbers are not comparable with the current 4-core ones).
- **CI flake:** the android job intermittently fails setup-android with 'Error on ZipFile unknown
  archive' (corrupt SDK download) — not a code failure; re-run the job.
- **Orphaned commit:** §6g ProfileValidator was committed as `660d33a` and pushed but never merged
  (slipped #102, force-dropped from #103) — re-land it if profile import is prioritized.
- **The user is Akshay Sharma**, the app's author (pixls.us megathread), testing on a Galaxy S26
  Ultra (SM-S948W, Android 16, arm64) — device-gated items queue until he tests. His laptop env
  (adb device testing): working copy `C:\Filmcam123\Spectrafilmandroid` (`C:\Spectrafilm` is
  docs-only — a trap); oracle = Python 3.13 venv `C:\Filmcam123\spkenv` + `spkstubs`; arm64 test
  binaries at `C:\Filmcam123\spk_arm64`; `JAVA_HOME` = Android Studio jbr JDK 21.
- `docs/PRIORITY_ROADMAP_2026-06-24.md` defines the P0–P3 item numbering (#1–#27) used throughout
  (P2 #6 = perceptual gamut algos, #18 = MALLETT2019, #20-27 = Strategy-B rebaseline cluster).

---

## Session history (compressed; full prose in this file's git history)

- **2026-07-02 — P2 #6 slice 2: `oklrab` output-gamut compression (new draft PR, unmerged).** Cloned
  the merged `oklch` pattern: `compress_rgb_oklrab_chroma` = the oklch chroma reduction with the
  `C_max` lookup indexed by Ottosson 2023's rebased lightness `Lr = f(L)` (constants k1=0.206,
  k2=0.03, k3=(1+k1)/(1+k2)); the `C_max(Lr,h)` bisection table is built over an Lr grid with each
  row's OkLab `L` recovered by the inverse remap before `Oklab→XYZ`, and reconstruction preserves the
  original (lightness-compressed) `L`. Reuses oklch's OkLab/RGB↔XYZ hex constants, `cmax_lookup`,
  `reinhard_knee`; table built locally per call (thread-invariant, warm==cold). Golden
  `gen_gamut_oklrab_golden.py` @ oracle `27bd085` (24 cases / 1152 px); gate `test_gamut_out_oklrab`
  `max_abs 1.055e-14` / probes `1.221e-15`. Suite 34→35, defaults byte-identical, oklch/aces/
  output_spaces/simulate_e2e/test_parallel unchanged. Facade `OKLRAB`=4 + Output-gamut dropdown
  ("Oklrab (perceptual, even lightness)"); `:app:compileDebugKotlin` green. Commits `9cb0a0b` /
  `94d2274` / `e1b75d8`.
- **2026-07-02 — "exact + fast" pass (PR #109 + #110).** F1–F7 Kotlin fixes; **E1** per-effect
  spatial gates (`test_spatial_decouple_e2e`, golden `scan_portra_lensblur_nohalation`); **E2**
  print-route filming spatial + grain (`test_print_spatial_e2e`, golden `print_portra_spatial`);
  **S1** scan-route film-density memo + per-param key-completeness tests; **S2** print-density memo
  (keyed on film_density_cmy CONTENT ⊕ printing inputs ⊕ the tc_lut-shaping film params); **S3**
  Kotlin retained-result grade cache (grade-only edits = zero native work); **S4** DIR-develop +
  exposure-interp + expose-tail loops → deterministic `parallel_for`. Both gamut e9e70f8 goldens
  ACCEPTED. Perf (4-core, 8 threads, 512²): cold scan **211 ms** (−13% from S4); warm scan / output-
  only edit 144–159; cold print ~400; warm print y-shift / output-only 153–162 (film + print memo →
  `scan()` alone).
- **2026-06-24 — P2 #5/#7 gamut + #8/#9 + P3 quick-wins (PR #109).** Output ACES-RGC v1.3
  (`test_gamut_out_aces`) + input radial-to-locus xy tc_lut bake (`test_gamut_in_xy`), both
  default-OFF, goldens @ `27bd085`; gamut flags → JNI → facade → two Simulation→Output dropdowns;
  `input_gamut_compress` folded into the tc_lut + film-memo keys only when active. Preset/diagnostics
  IO off-main; undo restoring-flag window fix; P3 quick-wins #10-16. SCOPE finding: CAT02→CAT16 +
  xy-clip removal are UNCONDITIONAL default-path changes (Strategy-B), NOT the opt-in locus bake.
- **2026-06-09 — WB wave + v0.8.0 release prep (PR #103).** Gray-point eyedropper, "Balance to film
  stock" (virtual-85, Bradford-adapt of the profile `reference_illuminant` CCT), auto-exposure
  default ON (matches upstream). versionCode 9→10, versionName 0.7.0→0.8.0. Scanner white/black
  correction gated to the strict-no-op case in UI.
- **2026-06-08 — masking + color/tone foundation (PRs #90–#103).** The keystone arc, all device-
  confirmed: §2 P0 color management (display tag + wide-gamut + ICC embed), §3.1 Contrast, §3.2
  Sat/Vibrance (Oklab post-engine grade), §3.3 couplers relabel, §2 P1 ACES gamut slider (post-
  engine v1), the full masking system (radial/linear/luminance/color-range masks → per-mask Tier-A
  Temp/Tint/Exp/Sat/Hue/Contrast/Whites/Blacks → draw-on-preview overlay → Class-S spatial ops,
  13 of LR's ~14 local ops), CLF/`.cube` LUT export (17/33/65), Lightroom-style export sheet
  (JPEG/UltraHDR/PNG16/TIFF16/TIFF32F/scene-linear), onboarding + slide-mode, the
  spectrafilm-solutions skill + `docs/USER_DRIVEN_SOLUTIONS.md`, and the full Lightroom RE
  (`docs/lightroom-re/`). Zero engine C++ changes in this arc. Design rule established: post-engine
  grades composite once in-place on `res.data`, mask ordinals pinned to crs:MaskBlendMode for XMP
  interop, gray-neutrality from Oklab/Rec-709 rows summing to 1.
- **2026-06-05 — editor + preview-speed wave (PRs #82–#88).** Point tone-curve editor (#88, faithful
  Fritsch–Carlson monotone-cubic Kotlin port); Lightroom UX + draft/final render worker + zoom ROI
  (#85/#86); highlight-boost ported (#82, `diffusion.cpp::apply_highlight_boost`, golden
  `scan_portra_boost` @ `c1d0e44`, `test_highlight_boost_e2e`); half→float LUT-load speedup (#83).
  GPU fit-preview promoted then reverted (broke the editor on SM-S948W). brutalist-re skill added.
- **2026-06-05 — param-wiring audit + print EV-comp (PRs #77–#80).** Downscale AA-prefilter parity
  fix (#77, real ~0.18–0.4 bug); print EV-compensation midgray fix (#80, `runtime/print_digest.cpp`,
  goldens `print_portra_evcomp{,_nonorm}`); R8 on-device validation recorded (#79). Opened the
  5-finding audit ledger (all since closed: boost→#82, MALLETT disclosed, spatial→E1, print→E2,
  dead sliders disclosed).
- **2026-06-04 — oracle pin + inert params + positive-film coupler (PRs #67–#76).** Oracle PINNED at
  **`c1d0e44`** (#67; drift = `a9bccd6`, changed filming raw-scaling). Wired all inert marshalled
  params: spectral blur (#68), hanatos window/surface (#69, surface = per-cell degree-4 2D poly),
  camera UV/IR (#72), enlarger preflash (#73, print-route only, NOT in the film-memo key), scanner
  white/black (#74, new `runtime/color_reference`). Positive-film DIR coupler fix (#75: per-stock
  provia/velvia gamma overrides; ~0.32 divergence on scan_film with couplers ON).
- **2026-06-03 — audit + lifecycle + zoom (PR #60).** Removed stale committed `dist/` APKs + closed
  the ICC license gap; process-scoped `EngineHolder` singleton (immutable engine never closed mid-
  life); profile+tc_lut memo keyed on immutable profile id (byte-exact); Lightroom ROI zoom. GPU
  standing verdict recorded: preview-only accelerator, never export.
- **2026-06-02 — v0.7.0 released.** `release.yml` published the signed APK + `.sha256`; apksigner
  verify passed. Workflow: feature branch → PR → merge (policy-gated); tag-push releases on request.
- **v0.7.0 session — engine completion (PR #59, Windows laptop + Galaxy S25 over adb).** AAssetManager
  direct-load (`SpektraEngine.fromAssets`, `#ifdef __ANDROID__`, skips the ~17 MB first-run extract)
  and `use_enlarger_lut` wired (opt-in PCHIP LUT mirroring the scanner LUT, `test_enlarger_lut_e2e`)
  — last reserved engine LUT flag gone. On-device parity runner: NDK clang `--target=aarch64-linux-
  android24`, push test + `libc++_shared.so` + assets + goldens, run under adb (`max_abs 5.96e-08`).
- **RAW export OOM (PR #56, device-confirmed v0.6.3).** Full-res RAW input + engine output moved off
  the ART managed heap via `malloc` + `NewDirectByteBuffer`; `LinearImage`/`SimResult` made
  `AutoCloseable`. Root cause: `ByteBuffer.allocateDirect` is a non-movable `byte[]` on the ~256 MB
  managed heap, not native memory — two ~140 MB full-res buffers cannot coexist there.
- **Since v0.4.0 (merged).** LR-RE feature wave (#35–#42 preset amount / copy-paste / resets /
  tone-curve stage), MotionCam `.mcraw` parser (#37/#38), perf scaffolding all opt-in/off (#46–#52:
  Vulkan compute + SPIR-V scan port, fp16 NEON, oneTBB, LiteRT stub), big-file RAW fixes
  (#43/#44/#56), Neutral (Adobe-like) preset (#55). GPU speedup remains UNPROVEN/hardware-blocked.

## Doc map (what to read for what)

`CLAUDE.md` build/parity/arch · `docs/AUDIT.md` open items + severity · `CHANGELOG.md` release notes ·
`docs/PRIORITY_ROADMAP_2026-06-24.md` the #1–#27 priority numbering ·
`docs/UPSTREAM_SYNC_2026-06-24.md` Strategy-A/B port plan · `docs/IMPROVEMENT_BACKLOG.md` LR-RE'd
feature list · `docs/PERF_ROADMAP.md` perf plan+policy · `docs/USER_DRIVEN_SOLUTIONS.md` +
`.claude/skills/spectrafilm-solutions/` the user-need catalog · `docs/RESEARCH_*` / `docs/lightroom-re/`
RE studies · `docs/PRESETS.md` / `docs/FILM_STOCKS.md` content · `docs/maps/` source-project maps.
