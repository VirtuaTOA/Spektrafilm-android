/*
 * Spektrafilm for Android — host test for camera.balance_to_illuminant.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * The engine-side "virtual 85 filter": renormalises the film's spectral sensitivities so
 * their integrated response to the working illuminant is equal across channels.
 *
 * NO GOLDEN, BY NECESSITY. This feature does not exist upstream, so there is no oracle to
 * compare against — the .spkvec goldens can only pin behaviour that spektrafilm also has.
 * What CAN be pinned is the feature's contract, which is what this asserts:
 *
 *   1. DEFAULT IS INERT. Off, a tungsten render is byte-identical to one produced with the
 *      parameter absent from the struct entirely (zeroed params). This is the property the
 *      whole opt-in discipline rests on; test_simulate_e2e's goldens cover the same ground
 *      from the other side.
 *   2. IT DOES SOMETHING. On, a tungsten render differs. A silently-ignored parameter would
 *      otherwise pass every other gate in the suite.
 *   3. IT DOES THE RIGHT THING. A NEUTRAL input through a TUNGSTEN stock comes out closer to
 *      neutral with the flag on than off. This is the actual claim — that the correction
 *      removes the daylight-on-tungsten cast — and it is the assertion that would catch the
 *      normalisation being applied with the wrong sign, to the wrong axis, or to the wrong
 *      illuminant. Without it, 1 and 2 together would happily pass a transform that merely
 *      changed the picture.
 *   4. IT IS A WHITE BALANCE, NOT AN EXPOSURE CHANGE. The geometric-mean rescale exists so
 *      only the channel balance moves; overall level must stay put within a tight band.
 *   5. THE CACHE KEY IS SOUND. A warm engine that has already built the unbalanced tc_lut
 *      must return byte-identical pixels to a fresh one when the flag is then turned on.
 *      Omitting the key entry would serve the stale LUT and silently defeat the feature —
 *      the same failure mode test_lut_cache_e2e guards for every other tc_lut input.
 *
 * Build (host): see .github/workflows/ci.yml for the authoritative argv.
 */
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "spektra.h"

namespace {

const char* kAssetDir = "/home/user/spektrafilm/src/spektrafilm/data";

// Vision3 500T: reference_illuminant "T" (~2856 K). The cast this feature exists to remove
// is at its strongest here, so it is the honest stock to assert against.
const char* kTungstenFilm = "kodak_vision3_500t";
const char* kPrintPaper = "kodak_portra_endura";

struct Rgb {
    double r, g, b;
};

/* A flat neutral field. Neutral in, neutral out is the whole claim, so the input must carry
 * no colour of its own — any imbalance downstream is then the emulsion's, not the scene's. */
std::vector<float> neutral_image(int w, int h, float level) {
    return std::vector<float>(static_cast<size_t>(w) * h * 3, level);
}

void base_params(spk_params* p) {
    *p = spk_params{};
    p->film_profile = kTungstenFilm;
    p->print_profile = kPrintPaper;
    spk_default_params(p);
    p->scan_film = 1;          // negative scan route
    p->auto_exposure = 0;      // AE would chase the balance change and mask it
    p->output_color_space = SPK_CS_SRGB;
    p->output_cctf_encoding = 0;   // stay linear: ratios below must mean what they say
    // Stochastic/spatial effects off — they add noise to a mean with no bearing on balance.
    p->grain_active = 0;
    p->halation_active = 0;
    p->glare_active = 0;
}

bool render(spk_engine* eng, const spk_params* p, const std::vector<float>& in,
            int w, int h, std::vector<float>* out) {
    spk_image img{const_cast<float*>(in.data()), w, h, SPK_CS_PROPHOTO};
    spk_image res{};
    spk_status st = spk_simulate(eng, &img, p, &res);
    if (st != SPK_OK || res.data == nullptr) {
        std::fprintf(stderr, "simulate failed: %s\n", spk_status_str(st));
        return false;
    }
    out->assign(res.data, res.data + static_cast<size_t>(res.width) * res.height * 3);
    spk_image_free(&res);
    return true;
}

Rgb mean_rgb(const std::vector<float>& v) {
    Rgb m{0, 0, 0};
    const size_t n = v.size() / 3;
    for (size_t i = 0; i < n; ++i) {
        m.r += v[i * 3 + 0];
        m.g += v[i * 3 + 1];
        m.b += v[i * 3 + 2];
    }
    return Rgb{m.r / n, m.g / n, m.b / n};
}

/* Distance from neutral: max channel / min channel, 1.0 being perfectly grey. Scale-free, so
 * it cannot be gamed by the render simply getting brighter or darker. */
double cast_ratio(const Rgb& m) {
    double lo = std::fmin(m.r, std::fmin(m.g, m.b));
    double hi = std::fmax(m.r, std::fmax(m.g, m.b));
    if (lo <= 1e-9) return 1e9;
    return hi / lo;
}

double luma(const Rgb& m) { return 0.2126 * m.r + 0.7152 * m.g + 0.0722 * m.b; }

bool identical(const std::vector<float>& a, const std::vector<float>& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i)
        if (a[i] != b[i]) return false;   // byte-identical, not a tolerance
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir = argc > 1 ? argv[1] : kAssetDir;
    const int W = 16, H = 16;
    bool ok = true;

    const std::vector<float> in = neutral_image(W, H, 0.18f);   // ~mid grey, scene-linear

    spk_engine* eng = nullptr;
    if (spk_engine_create(asset_dir.c_str(), &eng) != SPK_OK) {
        std::fprintf(stderr, "engine create failed\n");
        std::printf("FAIL\n");
        return 2;
    }

    spk_params off, on;
    base_params(&off);
    base_params(&on);
    on.camera_balance_to_illuminant = 1;

    std::vector<float> r_off, r_on;
    if (!render(eng, &off, in, W, H, &r_off) || !render(eng, &on, in, W, H, &r_on)) {
        std::printf("FAIL\n");
        return 2;
    }

    const Rgb m_off = mean_rgb(r_off);
    const Rgb m_on = mean_rgb(r_on);
    const double cast_off = cast_ratio(m_off);
    const double cast_on = cast_ratio(m_on);

    std::printf("  off: rgb=(%.5f %.5f %.5f) cast=%.4f luma=%.5f\n",
                m_off.r, m_off.g, m_off.b, cast_off, luma(m_off));
    std::printf("  on : rgb=(%.5f %.5f %.5f) cast=%.4f luma=%.5f\n",
                m_on.r, m_on.g, m_on.b, cast_on, luma(m_on));

    // 2. The parameter is wired to something.
    if (identical(r_off, r_on)) {
        std::fprintf(stderr, "FAIL: balance_to_illuminant had no effect (param not wired)\n");
        ok = false;
    }

    // 3. THE CLAIM: it moves a neutral toward neutral on a tungsten stock.
    if (!(cast_on < cast_off)) {
        std::fprintf(stderr,
                     "FAIL: balance did not reduce the cast (off=%.4f on=%.4f)\n",
                     cast_off, cast_on);
        ok = false;
    }
    // And meaningfully so — a change too small to see would satisfy the line above while
    // leaving the tungsten cast the user actually complained about fully intact.
    if (cast_off > 1.05 && !(cast_on < 1.0 + (cast_off - 1.0) * 0.5)) {
        std::fprintf(stderr,
                     "FAIL: cast reduced by less than half (off=%.4f on=%.4f)\n",
                     cast_off, cast_on);
        ok = false;
    }

    // 4. A white balance, not an exposure change.
    const double lum_ratio = luma(m_on) / (luma(m_off) > 0 ? luma(m_off) : 1.0);
    if (!(lum_ratio > 0.80 && lum_ratio < 1.25)) {
        std::fprintf(stderr, "FAIL: level moved too far (luma ratio %.4f)\n", lum_ratio);
        ok = false;
    }

    // 5. Cache soundness: this engine is now WARM (it built the off-LUT first, above). A
    // fresh engine can only ever build cold, so the two must agree bit-for-bit.
    spk_engine* fresh = nullptr;
    if (spk_engine_create(asset_dir.c_str(), &fresh) != SPK_OK) {
        std::fprintf(stderr, "FAIL: second engine create failed\n");
        ok = false;
    } else {
        std::vector<float> r_fresh;
        if (!render(fresh, &on, in, W, H, &r_fresh)) {
            ok = false;
        } else if (!identical(r_on, r_fresh)) {
            std::fprintf(stderr,
                         "FAIL: warm engine != fresh engine (tc_lut cache key misses "
                         "balance_to_illuminant)\n");
            ok = false;
        }
        // 1. And turning it back off on the warm engine must return the original render —
        // the balanced LUT must not have displaced the unbalanced one.
        std::vector<float> r_off2;
        if (!render(fresh, &off, in, W, H, &r_off2)) {
            ok = false;
        } else if (!identical(r_off, r_off2)) {
            std::fprintf(stderr, "FAIL: default render changed after the flag was used\n");
            ok = false;
        }
        spk_engine_destroy(fresh);
    }

    spk_engine_destroy(eng);
    std::printf(ok ? "PASS\n" : "FAIL\n");
    return ok ? 0 : 1;
}
