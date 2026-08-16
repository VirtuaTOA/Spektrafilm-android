/*
 * Spektrafilm for Android — radix-2 FFT + real 2D convolution. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 * Implements kernels/fft.h.
 */
#include "kernels/fft.h"

#include <cmath>
#include <thread>
#include <algorithm>

#include "kernels/parallel.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace spk {

size_t next_pow2(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}

namespace {
// Twiddle table for a transform of size n: exp(-2*pi*i*j/n) for j < n/2. Every
// entry is computed DIRECTLY with std::polar, so accuracy matches evaluating
// std::polar per butterfly — but it is computed once per size instead of once per
// butterfly. Stage `len` reads it with stride n/len.
//
// This is not a micro-optimisation. Calling std::polar inside the butterfly loop
// costs a sin+cos per butterfly: a 4096^2 2D transform is ~8192 1D transforms of
// 24k trig calls each, and a full-frame diffusion export never finished a 10
// minute benchmark. Do NOT "simplify" this back to a per-butterfly std::polar.
//
// The other tempting form — advancing the twiddle multiplicatively (w *= wlen) —
// IS the one to avoid: it accumulates phase error across a long transform, and
// this has to survive a full convolution inside a 1e-4 parity tolerance.
const std::vector<std::complex<double>>& twiddles(size_t n) {
    thread_local size_t cached_n = 0;
    thread_local std::vector<std::complex<double>> cache;
    if (cached_n != n) {
        cache.resize(n / 2);
        for (size_t j = 0; j < n / 2; ++j)
            cache[j] = std::polar(1.0, -2.0 * M_PI * static_cast<double>(j) /
                                            static_cast<double>(n));
        cached_n = n;
    }
    return cache;
}
}  // namespace

void fft_1d(std::complex<double>* a, size_t n, bool inverse) {
    if (n < 2) return;
    // Bit-reversal permutation.
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    // Butterflies, reading the precomputed twiddle table with stride n/len.
    const std::vector<std::complex<double>>& tw = twiddles(n);
    for (size_t len = 2; len <= n; len <<= 1) {
        const size_t half = len >> 1;
        const size_t stride = n / len;
        for (size_t i = 0; i < n; i += len) {
            for (size_t k = 0; k < half; ++k) {
                const std::complex<double> t = tw[k * stride];
                const std::complex<double> w =
                    inverse ? std::conj(t) : t;
                const std::complex<double> u = a[i + k];
                const std::complex<double> v = a[i + k + half] * w;
                a[i + k] = u + v;
                a[i + k + half] = u - v;
            }
        }
    }
    if (inverse) {
        const double inv = 1.0 / static_cast<double>(n);
        for (size_t i = 0; i < n; ++i) a[i] *= inv;
    }
}

namespace {
// Split [0,n) into contiguous chunks across the engine's configured worker count
// and run f(begin,end) on each. Boundaries are a pure function of (n, threads) and
// every chunk touches a disjoint set of rows/columns, so the OUTPUT is identical
// for any thread count — the invariance the parity gate requires.
//
// spk::parallel_for is not used here on purpose: its kParallelMinChunk is tuned for
// per-PIXEL bodies, and one item here is an entire 1D transform. A 4096-row image
// is only 4096 items, which that heuristic would collapse to a single thread.
template <typename F>
void run_chunked(size_t n, const F& f) {
    const int nthreads = std::max(1, parallel_num_threads());
    if (nthreads <= 1 || n < 2) { f(0, n); return; }
    const size_t chunk = (n + nthreads - 1) / nthreads;
    std::vector<std::thread> workers;
    for (int t = 1; t < nthreads; ++t) {
        const size_t b = std::min(n, chunk * static_cast<size_t>(t));
        const size_t e = std::min(n, b + chunk);
        if (b < e) workers.emplace_back([&f, b, e] { f(b, e); });
    }
    f(0, std::min(n, chunk));                 // first chunk on the calling thread
    for (std::thread& t : workers) t.join();
}
}  // namespace

void fft_2d(std::complex<double>* a, size_t w, size_t h, bool inverse) {
    // Rows.
    run_chunked(h, [&](size_t y0, size_t y1) {
        for (size_t y = y0; y < y1; ++y) fft_1d(a + y * w, w, inverse);
    });
    // Columns, gathered into a contiguous scratch so fft_1d sees unit stride.
    // The scratch is per-worker; the twiddle cache in fft_1d is thread_local, so
    // each worker builds it once.
    run_chunked(w, [&](size_t x0, size_t x1) {
        std::vector<std::complex<double>> col(h);
        for (size_t x = x0; x < x1; ++x) {
            for (size_t y = 0; y < h; ++y) col[y] = a[y * w + x];
            fft_1d(col.data(), h, inverse);
            for (size_t y = 0; y < h; ++y) a[y * w + x] = col[y];
        }
    });
}

void convolve_same_2d(const double* img, size_t iw, size_t ih,
                      const double* kern, size_t kw, size_t kh,
                      double* out) {
    if (iw == 0 || ih == 0 || kw == 0 || kh == 0) return;
    const size_t fw = next_pow2(iw + kw - 1);
    const size_t fh = next_pow2(ih + kh - 1);

    std::vector<std::complex<double>> A(fw * fh, std::complex<double>(0.0, 0.0));
    std::vector<std::complex<double>> B(fw * fh, std::complex<double>(0.0, 0.0));
    for (size_t y = 0; y < ih; ++y)
        for (size_t x = 0; x < iw; ++x) A[y * fw + x] = img[y * iw + x];
    for (size_t y = 0; y < kh; ++y)
        for (size_t x = 0; x < kw; ++x) B[y * fw + x] = kern[y * kw + x];

    fft_2d(A.data(), fw, fh, false);
    fft_2d(B.data(), fw, fh, false);
    for (size_t i = 0; i < A.size(); ++i) A[i] *= B[i];
    fft_2d(A.data(), fw, fh, true);

    // 'same' centre of the full linear convolution: offset by the kernel's centre, matching
    // scipy's mode='same' for an odd, centred kernel.
    const size_t oy = (kh - 1) / 2;
    const size_t ox = (kw - 1) / 2;
    for (size_t y = 0; y < ih; ++y)
        for (size_t x = 0; x < iw; ++x)
            out[y * iw + x] = A[(y + oy) * fw + (x + ox)].real();
}

void convolve_valid_2d_ola(const double* img, size_t iw, size_t ih,
                           const double* kern, size_t kw, size_t kh,
                           double* out, size_t tile) {
    if (iw < kw || ih < kh || kw == 0 || kh == 0) return;
    if (tile <= kw || tile <= kh || tile != next_pow2(tile)) return;
    const size_t ow = iw - kw + 1, oh = ih - kh + 1;
    const size_t lx = tile - kw + 1, ly = tile - kh + 1;  // useful step per tile
    std::fill(out, out + ow * oh, 0.0);

    // Kernel spectrum: computed once and reused for every tile.
    std::vector<std::complex<double>> K(tile * tile, std::complex<double>(0.0, 0.0));
    for (size_t y = 0; y < kh; ++y)
        for (size_t x = 0; x < kw; ++x) K[y * tile + x] = kern[y * kw + x];
    fft_2d(K.data(), tile, tile, false);

    std::vector<std::complex<double>> B(tile * tile);
    for (size_t by = 0; by < ih; by += ly) {
        for (size_t bx = 0; bx < iw; bx += lx) {
            std::fill(B.begin(), B.end(), std::complex<double>(0.0, 0.0));
            const size_t bh = std::min(ly, ih - by), bw = std::min(lx, iw - bx);
            for (size_t y = 0; y < bh; ++y)
                for (size_t x = 0; x < bw; ++x)
                    B[y * tile + x] = img[(by + y) * iw + bx + x];
            fft_2d(B.data(), tile, tile, false);
            for (size_t i = 0; i < B.size(); ++i) B[i] *= K[i];
            fft_2d(B.data(), tile, tile, true);
            // This tile's result sits at offset (by, bx) in the full linear
            // convolution; the valid window starts at (kh-1, kw-1) of that.
            for (size_t y = 0; y < tile; ++y) {
                const size_t fy = by + y;
                if (fy < kh - 1) continue;
                const size_t oy = fy - (kh - 1);
                if (oy >= oh) break;
                for (size_t x = 0; x < tile; ++x) {
                    const size_t fx = bx + x;
                    if (fx < kw - 1) continue;
                    const size_t ox = fx - (kw - 1);
                    if (ox >= ow) break;
                    out[oy * ow + ox] += B[y * tile + x].real();
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mixed-radix (2/3/5/7) transform + single-pass circular convolution.
// ---------------------------------------------------------------------------

size_t next_fast_len(size_t n) {
    if (n <= 1) return 1;
    for (size_t m = n;; ++m) {
        size_t t = m;
        for (size_t r : {2u, 3u, 5u, 7u})
            while (t % r == 0) t /= r;
        if (t == 1) return m;
    }
}

namespace {

// Full twiddle ring for size N: exp(-2*pi*i*j/N), j < N. One table serves every
// recursion level: a sub-transform of size n reads it with stride N/n, and the
// r-point butterfly reads it with stride N/r. Entries are direct std::polar calls,
// so accuracy matches evaluating each twiddle individually.
const std::vector<std::complex<double>>& ring(size_t N) {
    thread_local size_t cached = 0;
    thread_local std::vector<std::complex<double>> w;
    if (cached != N) {
        w.resize(N);
        for (size_t j = 0; j < N; ++j)
            w[j] = std::polar(1.0, -2.0 * M_PI * static_cast<double>(j) /
                                       static_cast<double>(N));
        cached = N;
    }
    return w;
}

size_t smallest_factor(size_t n) {
    for (size_t r : {2u, 3u, 5u, 7u})
        if (n % r == 0) return r;
    return n;  // prime > 7: handled by a direct O(n^2) DFT below
}

// Recursive decimation-in-time. `a` is the strided input, `out` the contiguous
// destination of length n. `step` = N/n is the twiddle stride for this level.
void fft_rec(const std::complex<double>* a, size_t stride, size_t n,
             std::complex<double>* out, const std::vector<std::complex<double>>& W,
             size_t N, bool inv) {
    if (n == 1) { out[0] = a[0]; return; }
    const size_t r = smallest_factor(n);
    if (r == n) {                       // prime factor > 7 — direct DFT
        const size_t stepd = N / n;
        for (size_t k = 0; k < n; ++k) {
            std::complex<double> s(0.0, 0.0);
            for (size_t j = 0; j < n; ++j) {
                std::complex<double> t = W[(j * k * stepd) % N];
                s += a[j * stride] * (inv ? std::conj(t) : t);
            }
            out[k] = s;
        }
        return;
    }
    const size_t m = n / r;
    for (size_t j = 0; j < r; ++j)
        fft_rec(a + j * stride, stride * r, m, out + j * m, W, N, inv);

    const size_t stepj = N / n;
    // Radix-2 fast path. Sizes here are overwhelmingly 2^a * (one small odd
    // factor), so this is the hot case: it drops the generic r-point DFT, the
    // r-wide temporary, and both modulo operations (the j=1 twiddle index
    // k*stepj is always < N).
    if (r == 2) {
        for (size_t k = 0; k < m; ++k) {
            std::complex<double> t = W[k * stepj];
            if (inv) t = std::conj(t);
            const std::complex<double> u0 = out[k];
            const std::complex<double> u1 = out[m + k] * t;
            out[k]     = u0 + u1;
            out[m + k] = u0 - u1;
        }
        return;
    }

    // Combine in place: position k of each of the r sub-results maps onto the r
    // outputs {k, k+m, ..., k+(r-1)m}, and those are exactly the slots being read,
    // so only an r-wide temporary is needed.
    const size_t step = N / n;
    const size_t rstep = N / r;
    std::complex<double> u[8];
    for (size_t k = 0; k < m; ++k) {
        for (size_t j = 0; j < r; ++j) {
            // j*k*step < N strictly ((r-1)(m-1) < rm), so no modulo is needed.
            std::complex<double> t = W[j * k * step];
            u[j] = out[j * m + k] * (inv ? std::conj(t) : t);
        }
        for (size_t q = 0; q < r; ++q) {
            std::complex<double> s(0.0, 0.0);
            for (size_t j = 0; j < r; ++j) {
                std::complex<double> t = W[(j * q * rstep) % N];
                s += u[j] * (inv ? std::conj(t) : t);
            }
            out[q * m + k] = s;
        }
    }
}

}  // namespace

void fft_1d_mixed(std::complex<double>* a, size_t n, bool inverse) {
    if (n < 2) return;
    const std::vector<std::complex<double>>& W = ring(n);
    thread_local std::vector<std::complex<double>> buf;
    buf.assign(a, a + n);
    fft_rec(buf.data(), 1, n, a, W, n, inverse);
    if (inverse) {
        const double s = 1.0 / static_cast<double>(n);
        for (size_t i = 0; i < n; ++i) a[i] *= s;
    }
}

void fft_2d_mixed(std::complex<double>* a, size_t w, size_t h, bool inverse) {
    run_chunked(h, [&](size_t y0, size_t y1) {
        for (size_t y = y0; y < y1; ++y) fft_1d_mixed(a + y * w, w, inverse);
    });
    run_chunked(w, [&](size_t x0, size_t x1) {
        // One column at a time on purpose. A blocked variant (8 columns together,
        // to make each row read contiguous) was MEASURED SLOWER: the per-thread
        // buffer grows 8x to ~780 KB and stops fitting L2, which costs more than
        // the strided gather saves. Do not "optimise" this without benchmarking.
        std::vector<std::complex<double>> col(h);
        for (size_t x = x0; x < x1; ++x) {
            for (size_t y = 0; y < h; ++y) col[y] = a[y * w + x];
            fft_1d_mixed(col.data(), h, inverse);
            for (size_t y = 0; y < h; ++y) a[y * w + x] = col[y];
        }
    });
}

void convolve_valid_2d_circular(const double* img, size_t iw, size_t ih,
                                const double* kern, size_t kw, size_t kh,
                                double* out) {
    if (iw < kw || ih < kh || kw == 0 || kh == 0) return;
    const size_t ow = iw - kw + 1, oh = ih - kh + 1;
    // Q >= iw suffices: circular aliasing only reaches indices below kw-1, and the
    // valid window starts at exactly kw-1. Same argument per axis.
    const size_t fw = next_fast_len(iw), fh = next_fast_len(ih);

    std::vector<std::complex<double>> A(fw * fh, std::complex<double>(0.0, 0.0));
    std::vector<std::complex<double>> B(fw * fh, std::complex<double>(0.0, 0.0));
    for (size_t y = 0; y < ih; ++y)
        for (size_t x = 0; x < iw; ++x) A[y * fw + x] = img[y * iw + x];
    for (size_t y = 0; y < kh; ++y)
        for (size_t x = 0; x < kw; ++x) B[y * fw + x] = kern[y * kw + x];

    fft_2d_mixed(A.data(), fw, fh, false);
    fft_2d_mixed(B.data(), fw, fh, false);
    for (size_t i = 0; i < A.size(); ++i) A[i] *= B[i];
    fft_2d_mixed(A.data(), fw, fh, true);

    for (size_t y = 0; y < oh; ++y)
        for (size_t x = 0; x < ow; ++x)
            out[y * ow + x] = A[(y + kh - 1) * fw + (x + kw - 1)].real();
}

}  // namespace spk
