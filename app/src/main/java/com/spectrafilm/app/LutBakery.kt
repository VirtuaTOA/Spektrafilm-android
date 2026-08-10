/*
 * Spektrafilm for Android — film-stock LUT bakery for the camera viewfinder. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Bakes a film look into a 3D LUT for the GPU viewfinder, and caches it per look so
 * swiping stocks does not re-bake. See docs/CAMERA_PLAN.md §5 step 1d.
 *
 * WHY A CACHE: baking runs the whole pointwise pipeline over size^3 lattice points
 * (35937 at 33). That is far too slow for a frame, but perfectly fine once per stock
 * selection — and once cached, swiping back to a previously-seen stock is instant.
 *
 * WHY THE GRADE IS FOLDED IN HERE: spk_bake_cube_lut takes SpektraParams, but
 * saturation / vibrance / gamutCompress are NOT SpektraParams fields — they are the
 * Kotlin post-engine grade in ColorGrade, applied to the render's output buffer. A
 * LUT baked from params alone would therefore MISS them, and the viewfinder would
 * disagree with the capture for any preset that uses them. So the baked lattice is
 * pushed through the identical ColorGrade.applyInPlace the render path uses.
 *
 * WHAT A LUT CANNOT CARRY: grain, halation, diffusion glare, DIR-coupler diffusion
 * and scanner unsharp are spatial or stochastic and are forced off by the bake. The
 * viewfinder shows colour and tone; texture appears on capture. Auto-exposure is also
 * absent by construction (see SpektraEngine.bakeCubeLut) — the gain is supplied
 * separately by the meter/lock button.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.SpektraEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LutBakery {

    /** Viewfinder lattice size. 17^3 bakes ~7x faster than 33^3 and is smooth enough
     *  for a preview; the capture never uses a LUT at all, so nothing rides on it. */
    const val PREVIEW_LUT_SIZE = 17

    /**
     * Identity of a baked look. Two requests with equal keys produce the same LUT, so
     * this is what the cache is keyed on. The grade fields are part of the key because
     * they are folded into the lattice below — omitting them would serve a stale LUT
     * after a saturation change.
     */
    data class Key(
        val presetId: String,
        val size: Int,
        val saturation: Float,
        val vibrance: Float,
        val gamutCompress: Float,
        val cctfEncoded: Boolean,
        val outputColorSpace: ColorSpace,
    )

    // Small LRU. Bounded because a user can swipe through every stock; each 17^3 LUT
    // is ~59 KB of floats, so a dozen is trivial next to the camera's own buffers.
    private const val MAX_ENTRIES = 12
    private val cache = object : LinkedHashMap<Key, CubeLut>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CubeLut>?) =
            size > MAX_ENTRIES
    }

    @Synchronized
    private fun cached(key: Key): CubeLut? = cache[key]

    @Synchronized
    private fun store(key: Key, lut: CubeLut) { cache[key] = lut }

    /**
     * Bake (or fetch) the LUT for [state]'s current look. HEAVY on a miss — call from a
     * background dispatcher. Returns null if the bake or parse fails, so the caller can
     * fall back to a plain passthrough rather than showing a wrong look.
     */
    fun bake(
        engine: SpektraEngine,
        state: ParamsState,
        presetId: String,
        size: Int = PREVIEW_LUT_SIZE,
    ): CubeLut? {
        val key = Key(
            presetId = presetId,
            size = size,
            saturation = state.saturation,
            vibrance = state.vibrance,
            gamutCompress = state.gamutCompress,
            cctfEncoded = state.savingCctfEncoding,
            outputColorSpace = state.outputColorSpace,
        )
        cached(key)?.let { return it }

        // SHAPED lattice: the viewfinder is the one consumer where both ends are ours, so
        // it can spend the LUT's resolution where the film curve actually bends rather than
        // on highlights. The shader applies the matching transfer before its lookup.
        val raw = runCatching {
            engine.bakeCubeLut(state.toParams(), size, SpektraEngine.SHAPER_SRGB)
        }.getOrNull() ?: return null
        val parsed = CubeLut.parse(raw) ?: return null
        val graded = applyGrade(parsed, key) ?: parsed
        store(key, graded)
        return graded
    }

    /**
     * Push the baked lattice through the SAME post-engine grade the render applies, so
     * the viewfinder and the capture agree. No-op (returns null, caller keeps the
     * original) when the grade is neutral — ColorGrade itself early-returns then, and
     * running the CCTF round-trip for nothing would only add float error.
     */
    private fun applyGrade(lut: CubeLut, key: Key): CubeLut? {
        val gradeActive = ColorGrade.isActive(key.saturation, key.vibrance) ||
            GamutCompress.isActive(key.gamutCompress)
        if (!gradeActive) return null

        // ColorGrade works in place on an interleaved float32 RGB buffer, exactly the
        // layout of CubeLut.rgb — so the lattice can be graded as if it were an image
        // of n^3 x 1 pixels. Identical code path, therefore identical result.
        val n = lut.size
        val count = n * n * n
        val buf = ByteBuffer.allocateDirect(count * 3 * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(lut.rgb)
        ColorGrade.applyInPlace(
            buf, count, 1, key.outputColorSpace, key.cctfEncoded,
            key.saturation, key.vibrance, key.gamutCompress,
        )
        val out = FloatArray(lut.rgb.size)
        buf.asFloatBuffer().get(out)
        return runCatching { CubeLut(n, out) }.getOrNull()
    }

    @Synchronized
    fun clear() = cache.clear()
}
