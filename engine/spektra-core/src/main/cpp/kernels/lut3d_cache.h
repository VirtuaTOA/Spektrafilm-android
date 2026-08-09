/*
 * Spektrafilm for Android — native engine: memo for built 3D LUTs.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 *
 * PERF, not parity. The opt-in scanner (scanning.cpp) and enlarger
 * (printing.cpp) 3D LUTs are pure functions of the profile spectra, the
 * enlarger/scan constants, the domain bounds and the step count — yet both were
 * rebuilt from scratch on EVERY simulate call, including every interactive
 * preview frame, where spk_simulate_preview forces both LUTs on. Building one
 * 17^3 LUT costs steps^3 spectral integrals over 81 bands (plus an O(steps^3)
 * PCHIP prepare), so an interactive render paid that twice per frame for a
 * result that had not changed.
 *
 * This memo removes that. It is a MEMO, NOT AN APPROXIMATION: a hit returns a
 * LUT built by the identical code from identical inputs, so the render is
 * byte-identical to rebuilding (test_lut_accel / test_scanner_lut_e2e /
 * test_enlarger_lut_e2e gate exactly that).
 *
 * KEYING DISCIPLINE (the correctness argument — read before adding a caller):
 * the key is an OPAQUE BYTE STRING the caller assembles from every value its
 * sample function and its grid construction consume, in the caller's own units
 * (raw IEEE-754 object representations, not rounded/derived summaries), plus a
 * per-kind tag. Keys are compared EXACTLY (byte-for-byte), never hashed, so
 * there is no collision risk — a stale reuse would require two genuinely
 * identical input sets, which by definition produce the same LUT. Anything the
 * sample function reads that is NOT folded into the key is a latent
 * wrong-render bug, so fold conservatively: a superset of what is read is safe,
 * a subset is not. Compile-time constants (the CIE CMFs, the D50 scan
 * illuminant) cannot vary between calls and need no fold.
 */
#ifndef SPEKTRA_KERNELS_LUT3D_CACHE_H_
#define SPEKTRA_KERNELS_LUT3D_CACHE_H_

#include <cstddef>
#include <cstdint>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <string>

#include "kernels/lut3d.h"

namespace spk {

// Append the raw object representation of `n` values of T to `key`. The exact
// IEEE-754 bytes are folded (not a formatted/rounded rendering), so float
// jitter can never alias two distinct inputs onto one entry.
template <typename T>
inline void lut_key_append(std::string* key, const T* values, size_t n) {
    const char* b = reinterpret_cast<const char*>(values);
    key->append(b, n * sizeof(T));
}

template <typename T>
inline void lut_key_append(std::string* key, const T& value) {
    lut_key_append(key, &value, 1);
}

// Append a NUL-terminated kind tag (e.g. "scan3d"). Every caller must start its
// key with a tag unique to its LUT kind so two kinds can share one cache with
// no chance of aliasing.
inline void lut_key_append_tag(std::string* key, const char* tag) {
    key->append(tag);
    key->push_back('\0');
}

// LRU memo for prepared 3D LUTs, bounded by a heap-byte budget.
//
// Bounded, not unbounded, because several of the keyed inputs are USER PARAMS
// that vary continuously during a slider drag (the enlarger LUT depends on the
// dichroic-filtered illuminant and the midgray exposure factor; both LUTs
// depend on grain.density_min). An unbounded map keyed on those would grow
// without limit; the budget caps residency while still serving the common case,
// where a drag moves a param NO LUT depends on and every frame hits.
//
// Thread-safe. Misses build OUTSIDE the lock, so a slow build never blocks
// other renders; two threads racing the same key may both build, and since the
// build is a pure function of the key inputs, either result is correct.
// Entries are handed out as shared_ptr, so eviction never invalidates a LUT a
// render is still using.
class Lut3DCache {
 public:
    // ~8 MB holds a dozen 17^3 LUTs (the preview resolution) — enough for both
    // kinds plus A/B toggling between looks — while staying small next to the
    // multi-megapixel float buffers a render already carries. A single entry
    // larger than the budget is still cached, as the sole resident.
    static constexpr size_t kDefaultByteBudget = 8u * 1024u * 1024u;

    explicit Lut3DCache(size_t byte_budget = kDefaultByteBudget)
        : budget_(byte_budget) {}

    // Return the prepared LUT for `key`, building (and preparing) it via
    // fn/ctx over the [xmin, xmax] grid at `steps` on a miss. `key` must fold
    // every input fn and the grid consume — see the file comment.
    std::shared_ptr<const PreparedLut3D> get_or_build(
        const std::string& key, const double xmin[3], const double xmax[3],
        int steps, void (*fn)(const double in[3], double out[3], void* ctx),
        void* ctx);

    // Observability for the host parity/bench tests. Not part of any ABI.
    uint64_t hits() const;
    uint64_t misses() const;
    size_t bytes() const;

 private:
    struct Entry {
        std::shared_ptr<const PreparedLut3D> lut;
        size_t bytes = 0;
        std::list<std::string>::iterator lru;  // position in lru_ (front = newest)
    };

    // Caller must hold mu_.
    void evict_to_fit_locked(size_t incoming_bytes);
    void touch_locked(std::map<std::string, Entry>::iterator it);

    mutable std::mutex mu_;
    std::map<std::string, Entry> entries_;
    std::list<std::string> lru_;  // most-recently-used first
    size_t budget_;
    size_t bytes_ = 0;
    uint64_t hits_ = 0;
    uint64_t misses_ = 0;
};

}  // namespace spk

#endif  // SPEKTRA_KERNELS_LUT3D_CACHE_H_
