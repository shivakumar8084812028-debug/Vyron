package com.example.hdr.gl

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Reference mathematical implementation of ITU-R BT.2100 PQ (SMPTE ST.2084),
 * ITU-R BT.2390 EETF Luminance Mapping, Shadow Toe Preservation,
 * BT.2020 -> BT.709 Gamut Matrix, and BT.1886/sRGB OETF.
 * Used both for real-time transfer curve visualization and unit verification.
 */
object ToneMappingMath {

    private const val PQ_M1 = 0.1593017578125      // 2610 / 16384
    private const val PQ_M2 = 78.84375             // 2523 / 4096 * 128
    private const val PQ_C1 = 0.8359375            // 3424 / 4096
    private const val PQ_C2 = 18.8515625           // 2413 / 4096 * 32
    private const val PQ_C3 = 18.6875              // 2392 / 4096 * 32

    /**
     * SMPTE ST.2084 PQ EOTF: electrical [0..1] -> optical linear luminance [0..10000] nits.
     */
    fun pqEotfToNits(v: Double): Double {
        val clamped = v.coerceIn(0.0, 1.0)
        val vp = clamped.pow(1.0 / PQ_M2)
        val num = max(vp - PQ_C1, 0.0)
        val den = max(PQ_C2 - PQ_C3 * vp, 1e-6)
        return 10000.0 * (num / den).pow(1.0 / PQ_M1)
    }

    /**
     * Inverse PQ OETF: optical linear luminance [0..10000] nits -> electrical [0..1].
     */
    fun nitsToPqOetf(nits: Double): Double {
        val y = (nits / 10000.0).coerceIn(0.0, 1.0)
        val ym1 = y.pow(PQ_M1)
        val num = PQ_C1 + PQ_C2 * ym1
        val den = 1.0 + PQ_C3 * ym1
        return (num / den).pow(PQ_M2).coerceIn(0.0, 1.0)
    }

    /**
     * Smooth BT.2390-inspired Hermite knee tone mapper mapping [0..sourcePeakNits] -> [0..1] SDR.
     */
    fun mapLuminanceNitsToSdrLinear(
        linearNits: Double,
        sourcePeakNits: Double,
        targetSdrPeakNits: Double,
        highlightRolloff: Double,
        shadowLift: Double
    ): Double {
        val safePeak = max(sourcePeakNits, targetSdrPeakNits + 50.0)
        val safeTarget = targetSdrPeakNits.coerceIn(100.0, 1000.0)
        // Normalize so 1.0 corresponds to target SDR peak white
        val norm = max(linearNits, 0.0) / safeTarget
        val maxNorm = safePeak / safeTarget

        // Shadow toe preservation in linear domain before highlight compression
        val lifted = norm + shadowLift * 0.015 * (1.0 - min(norm, 1.0))

        // Knee start depends on highlightRolloff (0.55..0.90 of target white)
        val kneeStart = (1.0 - 0.45 * highlightRolloff).coerceIn(0.40, 0.92)
        if (lifted <= kneeStart) {
            return lifted.coerceIn(0.0, 1.0)
        }

        // Hermite spline roll-off from kneeStart..maxNorm -> kneeStart..1.0
        val t = ((lifted - kneeStart) / max(maxNorm - kneeStart, 0.05)).coerceIn(0.0, 1.0)
        val h00 = 2.0 * t * t * t - 3.0 * t * t + 1.0
        val h10 = t * t * t - 2.0 * t * t + t
        val h01 = -2.0 * t * t * t + 3.0 * t * t
        val mapped = h00 * kneeStart + h10 * (1.0 - kneeStart) + h01 * 1.0
        return mapped.coerceIn(0.0, 1.0)
    }

    /**
     * Exact sRGB / IEC 61966-2-1 OETF for SDR LCD output.
     */
    fun linearToSrgbOetf(linear: Double): Double {
        val c = linear.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) {
            12.92 * c
        } else {
            1.055 * c.pow(1.0 / 2.4) - 0.055
        }
    }

    /**
     * Exact ITU-R BT.2020 linear RGB -> ITU-R BT.709 linear RGB matrix conversion.
     */
    fun convertBt2020ToBt709(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val outR = 1.6604910 * r - 0.5876411 * g - 0.0728499 * b
        val outG = -0.1245505 * r + 1.1328999 * g - 0.0083494 * b
        val outB = -0.0181508 * r - 0.1005789 * g + 1.1187297 * b
        return Triple(outR, outG, outB)
    }
}
