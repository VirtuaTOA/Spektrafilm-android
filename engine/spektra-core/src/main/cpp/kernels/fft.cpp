/*
 * Spektrafilm for Android — radix-2 FFT + real 2D convolution. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 * Implements kernels/fft.h.
 */
#include "kernels/fft.h"

#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace spk {

size_t next_pow2(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}

void fft_1d(std::complex<double>* a, size_t n, bool inverse) {
    if (n < 2) return;
    // Bit-reversal permutation.
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    // Butterflies. Twiddles are recomputed per stage from std::polar rather than advanced
    // multiplicatively: the incremental form accumulates phase error over long transforms, and
    // this has to land inside a 1e-4 parity tolerance after a full convolution.
    for (size_t len = 2; len <= n; len <<= 1) {
        const double ang = 2.0 * M_PI / static_cast<double>(len) * (inverse ? 1.0 : -1.0);
        const size_t half = len >> 1;
        for (size_t i = 0; i < n; i += len) {
            for (size_t k = 0; k < half; ++k) {
                const std::complex<double> w =
                    std::polar(1.0, ang * static_cast<double>(k));
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

void fft_2d(std::complex<double>* a, size_t w, size_t h, bool inverse) {
    // Rows.
    for (size_t y = 0; y < h; ++y) fft_1d(a + y * w, w, inverse);
    // Columns, gathered into a contiguous scratch so fft_1d sees unit stride.
    std::vector<std::complex<double>> col(h);
    for (size_t x = 0; x < w; ++x) {
        for (size_t y = 0; y < h; ++y) col[y] = a[y * w + x];
        fft_1d(col.data(), h, inverse);
        for (size_t y = 0; y < h; ++y) a[y * w + x] = col[y];
    }
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

}  // namespace spk
