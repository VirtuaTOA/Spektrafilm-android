/*
 * Spektrafilm for Android — native engine: radix-2 FFT + real 2D convolution. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * WHY THIS EXISTS: the oracle convolves the diffusion PSF with scipy.signal.fftconvolve, but this
 * port implemented the same convolution directly (see model/diffusion.h). Direct and FFT agree to
 * ~1e-16, so it was a fair trade at PREVIEW resolution — and fatal at capture resolution, where the
 * PSF radius reaches ~886px and the dense kernel is 3.1M taps per pixel (~3.6 HOURS per frame).
 * See docs/CAMERA_PLAN.md §9c/§9d.
 *
 * NOT YET WIRED INTO diffusion.cpp. Deliberately additive so it cannot disturb the parity suite
 * until it is verified against it.
 *
 * MEMORY IS THE DESIGN CONSTRAINT, not speed. Padding a 4080x2720 frame for a 1773x1773 kernel
 * needs 5852x4492; rounded to powers of two that is 8192x8192 = 1.07 GB per complex plane, which
 * is NOT viable on top of the pipeline's ~1.5 GB peak. Hence [convolve_same_2d] is written to be
 * driven TILE-WISE by the caller (overlap-add): a 4096x4096 tile costs ~268 MB per plane and still
 * yields 4096-1773 = 2323 useful output pixels, so a full frame is ~2x2 tiles.
 */
#ifndef SPK_KERNELS_FFT_H
#define SPK_KERNELS_FFT_H

#include <complex>
#include <cstddef>
#include <vector>

namespace spk {

/** Smallest power of two >= n. The transform below is radix-2 only. */
size_t next_pow2(size_t n);

/**
 * In-place iterative radix-2 Cooley-Tukey FFT over `n` = a power of two.
 * `inverse` applies the conjugate twiddles AND the 1/n scaling, so
 * fft(fft(x, false), true) == x to float error.
 */
void fft_1d(std::complex<double>* a, size_t n, bool inverse);

/** In-place 2D FFT of a `h x w` row-major plane; both dimensions must be powers of two. */
void fft_2d(std::complex<double>* a, size_t w, size_t h, bool inverse);

/**
 * Linear convolution of a real image with a real kernel, returning the 'same'-sized centre —
 * the equivalent of scipy.signal.fftconvolve(img, kern, mode='same').
 *
 * `img` is `iw x ih`, `kern` is `kw x kh` (both row-major, single plane). `out` is `iw x ih` and
 * must not alias `img`. The caller is responsible for any edge padding it wants (the diffusion
 * path reflect-pads before calling, matching numpy mode='reflect').
 *
 * HEAVY IN MEMORY: allocates two complex planes of next_pow2(iw+kw-1) x next_pow2(ih+kh-1).
 * Check the sizing note above before calling this on a whole frame rather than a tile.
 */
void convolve_same_2d(const double* img, size_t iw, size_t ih,
                      const double* kern, size_t kw, size_t kh,
                      double* out);

/**
 * Overlap-add convolution returning the 'valid' region: `out` is
 * (iw-kw+1) x (ih-kh+1). This is the shape the diffusion stage wants — it
 * reflect-pads the image itself (numpy mode='reflect') and then takes the part of
 * the convolution that used no zero padding.
 *
 * MEMORY IS WHY THIS EXISTS. [convolve_same_2d] transforms the whole frame at
 * once, and at capture resolution that is not survivable: measured on the real
 * export path (4080x3060, 35 mm format => 8.578 um/px),
 *
 *     family          radius    ks     pow2      per plane
 *     Glimmerglass       607  1215     8192        1.07 GB
 *     BlackProMist       886  1773     8192        1.07 GB
 *     ProMist           1516  3033    16384        4.29 GB
 *     Cinebloom         1529  3059    16384        4.29 GB   (radius capped)
 *
 * with at least two planes live. Overlap-add instead works in power-of-two tiles,
 * so the footprint is O(tile^2) REGARDLESS of frame size: a 4096 tile is 268 MB
 * per plane, 537 MB for the two live planes, and needs 4 tiles (Glimmerglass) to
 * 42 tiles (Cinebloom) for a full frame.
 *
 * `tile` must be a power of two and STRICTLY GREATER than both kw and kh — the
 * per-tile useful output is tile-kw+1, so a tile at or below the kernel size makes
 * no progress. Returns without writing anything if that does not hold.
 */
void convolve_valid_2d_ola(const double* img, size_t iw, size_t ih,
                           const double* kern, size_t kw, size_t kh,
                           double* out, size_t tile);

/** Smallest m >= n whose only prime factors are 2, 3, 5, 7 (what scipy calls a
 *  "fast length"). Radix-2 alone rounds 7112 up to 8192; this returns 7168. */
size_t next_fast_len(size_t n);

/** Mixed-radix (2/3/5/7) 1D FFT, in place. n must be a next_fast_len value. */
void fft_1d_mixed(std::complex<double>* a, size_t n, bool inverse);

/** Mixed-radix 2D FFT over a `h x w` row-major plane, in place, threaded. */
void fft_2d_mixed(std::complex<double>* a, size_t w, size_t h, bool inverse);

/**
 * SINGLE-PASS 'valid' convolution — the fast path, and the one that matches what
 * the desktop client does.
 *
 * `out` is (iw-kw+1) x (ih-kh+1), same contract as [convolve_valid_2d_ola].
 *
 * Two things make this ~12x cheaper than the overlap-add form at diffusion's
 * kernel sizes:
 *
 *  1. NO TILING. Overlap-add keeps only tile-kw+1 useful pixels per transform, and
 *     at ks~3000 with a 4096 tile that is 6.7% — 42 tiles for one frame. One pass
 *     wastes nothing.
 *  2. NO DOUBLED PADDING. The caller has already reflect-padded by the kernel
 *     radius, so a CIRCULAR convolution of size >= iw is exact over the valid
 *     region: wraparound only corrupts indices below kw-1, and the valid region
 *     starts exactly at kw-1. So the transform is sized to iw, not iw+kw-1 —
 *     a 4x area saving over treating it as a general linear convolution.
 *
 * Mixed-radix sizing then avoids the power-of-two jump on top of that (7112 ->
 * 7168 rather than 8192).
 *
 * Cost: two complex planes of next_fast_len(iw) x next_fast_len(ih). Caller must
 * check that against its memory budget and fall back to [convolve_valid_2d_ola]
 * when it does not fit.
 */
void convolve_valid_2d_circular(const double* img, size_t iw, size_t ih,
                                const double* kern, size_t kw, size_t kh,
                                double* out);

}  // namespace spk

#endif  // SPK_KERNELS_FFT_H
