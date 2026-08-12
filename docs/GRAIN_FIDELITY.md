# Per-stock grain fidelity — findings and plan

Status: **investigated, not implemented** (2026-08-12). Parked deliberately; nothing here has been
built. Written down because the analysis cost real effort and the conclusion is counter-intuitive.

## What is true today

`digest_grain_params` (`runtime/params.cpp`) takes **no profile argument** and does exactly one
thing: sets `active = true`. Its own comment says the values are *"the schema GrainParams defaults
for kodak_portra_400 (no `_apply_film_specifics` grain override)"*.

**So every one of the 20 stocks gets Portra 400's grain physics.** Presets do not help: across all
20 they differ in only four fields — `filmProfile`, `printProfile`, `io.scanFilm` and
`creativeWb.balanceToFilmStock`. Grain, halation and DIR couplers are `active: true` and nothing
else.

Two qualifications, both important:

- **Grain amplitude IS modulated per stock**, indirectly. `density_max_curves[c] = nanmax(that
  film's normalized density curves)`, and the sublayer path (the default) uses `density_max_layers`
  from each film's `density_curves_layers` — present in all 20 profiles. So output grain is not
  literally identical between stocks; what is identical is the *particle statistics*.
- **Halation, by contrast, IS profile-driven** — the only stage that is. `digest_halation_params`
  keys on `(info.use, info.antihalation)`: sigma 65µm still / 50µm cine, strength strong
  `(0.015, 0.005, 0)` / weak `(0.08, 0.02, 0)` / no `(0.30, 0.10, 0.015)`. Coarse (20 stocks collapse
  onto ~4 settings) but real.

**Film speed is not modelled anywhere.** The profiles' `info` block has no ISO field at all, so
Portra 160 and Portra 800 share one particle area (0.2 µm²) despite hugely different real
granularity. That is the largest single fidelity gap.

## The model's statistics (derived from `model/grain.h`)

`layer_particle_model` compounds `Binomial(Poisson(n_ppp/sat), p)` → `Poisson(n_ppp·p/sat)`, scaled
by `od_particle × sat` with `od_particle = density_max / n_ppp`. That gives

```
σ = density_max · √( sat · p / n_ppp )        and      n_ppp = pixel_area · density_max_fraction / particle_area

  =>   σ  ∝  density_max · √particle_area
```

**TWO independent per-stock factors.** `density_max` (from the film's own curves) scales noise
LINEARLY; `particle_area` scales it as a SQUARE ROOT.

### The trap this creates

The obvious approach — scale particle area by the ratio of published RMS granularities,
`area = 0.2 × (RMS/4)²` — **double-counts**. On a stock with both a higher datasheet RMS and a
higher Dmax the two factors multiply and the grain comes out overdone. The naive formula is wrong
and must not be used.

### The correct formulation

Make RMS granularity the **target** and treat `density_max` as an **input**, solving for the area:

```
particle_area = (RMS_target / density_max)² · pixel_area · density_max_fraction / (sat · p)
```

evaluated at the datasheet condition (net density 1.0, 48 µm aperture). This absorbs each stock's
density variation instead of stacking on it. It is not a constant you can hand-write into a profile;
it needs a calibration harness that renders, measures and solves.

### Useful property: one lever moves both axes correctly

`particle_area` raises amplitude as √area AND coarsens texture, because the sublayer path's
dye-cloud blur is `sigma = blur_particle · √od_particle` and `od_particle ∝ particle_area`. Bigger
silver grains give noise that is both stronger and chunkier — which is how a faster film actually
reads. So the single parameter is a legitimate "grainier stock" control, not a blunt gain.

## Plan, in order

1. **Verify the anchor before anything else.** Measure whether the current Portra 400 render lands
   near its published RMS of ~4. If it does not, every per-stock number derived from it inherits the
   error, and this work should stop until that is resolved. Method below.
2. **Add `info.rms_granularity` to the film profiles**, deriving particle area in the engine — the
   same shape as `digest_halation_params`, which already proves the pattern. Absent field falls back
   to 0.2 (today's value), so existing renders stay byte-identical and the opt-in discipline holds.
   Keeping it in the profile matters: the white-balance work showed what happens when film data
   lives in app code instead.
3. **Gate it** with a host test asserting: absent field ⇒ unchanged; higher RMS ⇒ higher measured
   variance; and the √area relationship holds.

Do NOT touch `particleScale[3]` / `particleScaleLayers[3]`. Per-layer and per-channel granularity is
not published; setting it would be estimation dressed as measurement.

## How to measure the anchor

Everything needed already exists — no engine change.

- **Measure DENSITY, not RGB.** `spk_simulate_tap(..., "film_density_cmy", ...)` is what a
  densitometer reads, and grain is applied to density. The final RGB would fold in the print, the
  scan, the tone curve and the output transfer — none of which are in the datasheet definition.
- **Get the aperture right; it decides the number.** σ ∝ 1/√(aperture area), and
  `pixel_size_um = film_format_mm × 1000 / longest_image_dimension`. Set `film_format_mm = 36` with
  **750 px** on the long side ⇒ exactly 48.0 µm per pixel, one pixel per aperture sample. Kodak's
  aperture is CIRCULAR, so a 48 µm square covers ~27% more area and biases σ **low by ~13%**; for an
  exact figure render at ~6 µm and average over a circular mask.
- **Isolate grain**: `scan_film = 1`, `auto_exposure = 0`, halation/glare/camera-diffusion OFF (they
  correlate neighbours). Leave grain's own `blur` / `blurDyeCloudsUm` / `microStructure` ON — those
  are the emulsion's grain, not external effects.
- **Find net density 1.0**: grain OFF, sweep a uniform input, tap density, locate D-min, then the
  exposure giving D-min + 1.0.
- **Measure**: grain ON at that exposure, 750×750 uniform patch, standard deviation of the GREEN
  record (magenta dye — what Status-M granularity is quoted on). Average over several `seed_offset`
  values; a single realisation of σ has its own sampling error.
- **MIND THE CONVENTION**: Kodak quotes RMS granularity as **σ × 1000**. Portra 400's "4" means
  **σ_D ≈ 0.004**. Getting this wrong by 1000× is the easiest route to a confident wrong answer.
- **Validate the harness itself**: double `agx_particle_area_um2` and confirm measured σ rises by
  √2 ≈ 1.414. If it does not, the harness is wrong and no number from it can be trusted.

Expect ballpark agreement, not two decimal places: `film_density_cmy` is CMY dye density while Kodak
measures diffuse density through a Status-M response. Within ~±30% means the calibration is sound; a
factor of several means it is not.
