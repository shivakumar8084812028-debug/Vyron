package com.example

import com.example.hdr.analyzer.VideoStreamAnalyzer
import com.example.hdr.gl.ToneMappingMath
import com.example.hdr.model.ColorPrimaries
import com.example.hdr.model.ColorRangeType
import com.example.hdr.model.TransferCharacteristic
import com.example.hdr.model.VideoAnalysisReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VyronEngineUnitTest {

    @Test
    fun `pq st2084 eotf and inverse oetf roundtrip accuracy`() {
        val testNits = listOf(0.05, 10.0, 100.0, 400.0, 1000.0, 4000.0, 10000.0)
        for (nits in testNits) {
            val pqCode = ToneMappingMath.nitsToPqOetf(nits)
            val recovered = ToneMappingMath.pqEotfToNits(pqCode)
            assertEquals("PQ roundtrip failed for $nits nits", nits, recovered, nits * 0.01 + 0.01)
        }
        // 100 nits is ~0.5081 in SMPTE ST.2084
        assertEquals(0.5081, ToneMappingMath.nitsToPqOetf(100.0), 0.005)
    }

    @Test
    fun `bt2020 to bt709 matrix preserves D65 white point`() {
        val (r, g, b) = ToneMappingMath.convertBt2020ToBt709(1.0, 1.0, 1.0)
        assertEquals(1.0, r, 0.001)
        assertEquals(1.0, g, 0.001)
        assertEquals(1.0, b, 0.001)
    }

    @Test
    fun `bt2390 tone mapper is monotonic and rolls off highlights smoothly without clipping`() {
        val samples = listOf(10.0, 50.0, 100.0, 380.0, 1000.0, 2500.0, 4000.0)
        val mapped = samples.map { nits ->
            ToneMappingMath.mapLuminanceNitsToSdrLinear(
                linearNits = nits,
                sourcePeakNits = 4000.0,
                targetSdrPeakNits = 380.0,
                highlightRolloff = 0.85,
                shadowLift = 0.14
            )
        }
        for (i in 1 until mapped.size) {
            assertTrue("Tone curve must be monotonically non-decreasing", mapped[i] >= mapped[i - 1])
        }
        assertTrue("1000 nits should retain highlight headroom below 1.0 when peak is 4000", mapped[4] < 0.99)
        assertEquals(1.0, mapped.last(), 0.02)
    }

    @Test
    fun `parseHdrStaticInfo extracts mastering luminance, maxCll, and maxFall`() {
        val buf = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0.toByte()) // Type 1
        repeat(8) { buf.putShort(1000.toShort()) } // Primaries & white point
        buf.putShort(1000.toShort()) // Max mastering 1000 nits
        buf.putShort(50.toShort())   // Min mastering 0.0050 nits
        buf.putShort(1250.toShort()) // MaxCLL 1250 nits
        buf.putShort(310.toShort())  // MaxFALL 310 nits
        buf.flip()

        val parsed = VideoStreamAnalyzer.parseHdrStaticInfo(buf)
        assertEquals(1000f, parsed.masteringMaxLuminanceNits ?: 0f, 0.1f)
        assertEquals(0.005f, parsed.masteringMinLuminanceNits ?: 0f, 0.0005f)
        assertEquals(1250, parsed.maxContentLightLevelNits)
        assertEquals(310, parsed.maxFrameAverageLightLevelNits)
        assertEquals(1250f, parsed.effectivePeakNits, 0.1f)
    }

    @Test
    fun `hdr10 classification identifies pq bt2020 10bit accurately`() {
        val hdr10Report = VideoAnalysisReport(
            sourceUri = "content://test/4k.mkv",
            displayName = "4k_hdr10.mkv",
            width = 3840,
            height = 2160,
            frameRateFps = 24f,
            codecMime = "video/hevc",
            codecProfileName = "HEVC / H.265 (Main10 HDR10)",
            bitDepth = 10,
            transferCharacteristic = TransferCharacteristic.PQ_ST2084,
            colorPrimaries = ColorPrimaries.BT_2020,
            colorRange = ColorRangeType.LIMITED
        )
        assertTrue(hdr10Report.isHdr10)
        assertTrue(hdr10Report.isHdr)
        assertTrue(hdr10Report.resolutionLabel.contains("4K UHD"))

        val sdrReport = hdr10Report.copy(
            bitDepth = 8,
            transferCharacteristic = TransferCharacteristic.SDR_GAMMA,
            colorPrimaries = ColorPrimaries.BT_709
        )
        assertFalse(sdrReport.isHdr10)
        assertFalse(sdrReport.isHdr)
    }
}
