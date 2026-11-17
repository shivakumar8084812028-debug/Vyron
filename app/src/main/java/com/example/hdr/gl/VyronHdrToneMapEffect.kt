package com.example.hdr.gl

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.example.hdr.model.QualityTier
import com.example.hdr.model.ToneMappingConfig
import com.example.hdr.model.TransferCharacteristic
import com.example.hdr.model.VyronVisionMode

/**
 * Snapshot passed to the OpenGL ES 3.0 shader every frame without allocations or locks.
 */
data class HdrShaderRuntimeState(
    val config: ToneMappingConfig,
    val sourceTransfer: TransferCharacteristic,
    val isWideGamutBt2020: Boolean,
    val isLimitedRange: Boolean,
    val sourcePeakNits: Float
)

/**
 * Module C: Real-Time OpenGL ES 3.0 HDR-to-SDR Tone-Mapping Effect for Media3 ExoPlayer & Transformer.
 * Executes directly on GPU hardware (Mali-G57 MC2 / Adreno) via Media3 GlShaderProgram.
 */
@OptIn(UnstableApi::class)
class VyronHdrToneMapEffect(
    private val runtimeStateProvider: () -> HdrShaderRuntimeState,
    private val onFrameTimedNs: (Long) -> Unit
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return VyronHdrGlShaderProgram(
            useHdrWorkingSpace = useHdr,
            runtimeStateProvider = runtimeStateProvider,
            onFrameTimedNs = onFrameTimedNs
        )
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

@OptIn(UnstableApi::class)
private class VyronHdrGlShaderProgram(
    private val useHdrWorkingSpace: Boolean,
    private val runtimeStateProvider: () -> HdrShaderRuntimeState,
    private val onFrameTimedNs: (Long) -> Unit
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdrWorkingSpace, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram
    private var texWidth: Int = 1280
    private var texHeight: Int = 720

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texWidth = inputWidth.coerceAtLeast(1)
        texHeight = inputHeight.coerceAtLeast(1)
        return Size(texWidth, texHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val startNs = System.nanoTime()
        try {
            val state = runtimeStateProvider()
            val cfg = state.config

            val isBypassOriginal = cfg.mode == VyronVisionMode.ORIGINAL_PLAYBACK
            val isSmartMode = when (cfg.mode) {
                VyronVisionMode.HDR_TO_SDR_SMART -> true
                VyronVisionMode.AUTO_ADAPTIVE -> cfg.activeQualityTier != QualityTier.THERMAL_SAFE_720P
                else -> false
            }

            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1.0f / texWidth, 1.0f / texHeight))

            // Mode flags
            glProgram.setIntUniform("uBypass", if (isBypassOriginal) 1 else 0)
            glProgram.setIntUniform("uInputIsLinear", if (useHdrWorkingSpace) 1 else 0)
            glProgram.setIntUniform("uTransferMode", state.sourceTransfer.shaderModeInt)
            glProgram.setIntUniform("uIsBt2020", if (state.isWideGamutBt2020 && !useHdrWorkingSpace) 1 else 0)
            glProgram.setIntUniform("uLimitedRange", if (state.isLimitedRange && !useHdrWorkingSpace) 1 else 0)

            // Tone mapping parameters
            glProgram.setFloatUniform("uSourcePeakNits", state.sourcePeakNits.coerceIn(300f, 10000f))
            glProgram.setFloatUniform("uTargetSdrNits", cfg.targetSdrPeakNits.coerceIn(150f, 800f))
            glProgram.setFloatUniform("uHighlightRolloff", cfg.highlightRolloff.coerceIn(0.1f, 1.0f))
            glProgram.setFloatUniform("uShadowLift", cfg.shadowLift.coerceIn(0f, 0.4f))

            // Smart Enhancement parameters (gated off in Thermal Safe mode)
            val sharp = if (isSmartMode && cfg.activeQualityTier.enableSharpening) cfg.sharpnessAmount else 0f
            val contrast = if (isSmartMode) cfg.smartContrast else 0f
            glProgram.setFloatUniform("uSmartContrast", contrast)
            glProgram.setFloatUniform("uSaturationGuard", cfg.saturationGuard.coerceIn(0.6f, 1.15f))
            glProgram.setFloatUniform("uSharpness", sharp)

            // Split comparison slider
            glProgram.setIntUniform("uCompareEnabled", if (cfg.comparisonEnabled) 1 else 0)
            glProgram.setFloatUniform("uSplitX", cfg.comparisonSplitFraction.coerceIn(0.05f, 0.95f))

            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        } finally {
            onFrameTimedNs(System.nanoTime() - startNs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        /**
         * High-precision OpenGL ES Fragment Shader implementing:
         * - Video range normalization (Limited TV 16-235 vs Full)
         * - SMPTE ST.2084 PQ EOTF & HLG inverse OETF
         * - BT.2020 -> BT.709 Chromaticity Matrix + Out-of-gamut soft compression
         * - ITU-R BT.2390 Hermite Spline Highlight Roll-off & Shadow Detail Preservation
         * - Smart Enhancement (Adaptive S-Curve, Vibrance & 720p LCD Unsharp Clarity)
         * - IEC 61966-2-1 sRGB / BT.1886 Output Transfer Function
         * - Real-time Original vs Enhanced Split-Screen Wipe
         */
        private const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;

            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;

            uniform int uBypass;
            uniform int uInputIsLinear;
            uniform int uTransferMode;
            uniform int uIsBt2020;
            uniform int uLimitedRange;

            uniform float uSourcePeakNits;
            uniform float uTargetSdrNits;
            uniform float uHighlightRolloff;
            uniform float uShadowLift;
            uniform float uSmartContrast;
            uniform float uSaturationGuard;
            uniform float uSharpness;

            uniform int uCompareEnabled;
            uniform float uSplitX;

            varying vec2 vTexSamplingCoord;

            const float PQ_M1 = 0.1593017578125;
            const float PQ_M2 = 78.84375;
            const float PQ_C1 = 0.8359375;
            const float PQ_C2 = 18.8515625;
            const float PQ_C3 = 18.6875;

            vec3 pqEotfToNits(vec3 v) {
                vec3 cv = clamp(v, 0.0, 1.0);
                vec3 vp = pow(cv, vec3(1.0 / PQ_M2));
                vec3 num = max(vp - vec3(PQ_C1), vec3(0.0));
                vec3 den = max(vec3(PQ_C2) - vec3(PQ_C3) * vp, vec3(1e-5));
                return 10000.0 * pow(num / den, vec3(1.0 / PQ_M1));
            }

            vec3 hlgEotfToNits(vec3 v) {
                vec3 cv = clamp(v, 0.0, 1.0);
                const float a = 0.17883277;
                const float b = 0.28466892;
                const float c = 0.55991073;
                vec3 low = (cv * cv) / 3.0;
                vec3 high = (exp((cv - vec3(c)) / a) + vec3(b)) / 12.0;
                vec3 linearScene = mix(high, low, step(cv, vec3(0.5)));
                float ys = dot(linearScene, vec3(0.2627, 0.6780, 0.0593));
                float gamma = 1.2;
                return 1000.0 * linearScene * pow(max(ys, 1e-5), gamma - 1.0);
            }

            vec3 bt2020ToBt709(vec3 rgb2020) {
                mat3 m = mat3(
                     1.6604910, -0.1245505, -0.0181508,
                    -0.5876411,  1.1328999, -0.1005789,
                    -0.0728499, -0.0083494,  1.1187297
                );
                return m * rgb2020;
            }

            vec3 linearToSrgb(vec3 c) {
                vec3 clamped = clamp(c, 0.0, 1.0);
                vec3 low = clamped * 12.92;
                vec3 high = 1.055 * pow(clamped, vec3(1.0 / 2.4)) - 0.055;
                return mix(high, low, step(clamped, vec3(0.0031308)));
            }

            float toneMapLuminance(float linearNits) {
                float safeTarget = max(uTargetSdrNits, 120.0);
                float safePeak = max(uSourcePeakNits, safeTarget + 80.0);
                float norm = max(linearNits, 0.0) / safeTarget;
                float maxNorm = safePeak / safeTarget;

                // Shadow detail lift before highlight knee
                float lifted = norm + uShadowLift * 0.016 * (1.0 - min(norm, 1.0));
                float kneeStart = clamp(1.0 - 0.45 * uHighlightRolloff, 0.40, 0.92);
                if (lifted <= kneeStart) {
                    return clamp(lifted, 0.0, 1.0);
                }
                float t = clamp((lifted - kneeStart) / max(maxNorm - kneeStart, 0.05), 0.0, 1.0);
                float t2 = t * t;
                float t3 = t2 * t;
                float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
                float h10 = t3 - 2.0 * t2 + t;
                float h01 = -2.0 * t3 + 3.0 * t2;
                return clamp(h00 * kneeStart + h10 * (1.0 - kneeStart) + h01, 0.0, 1.0);
            }

            vec3 processHdrToSdr(vec3 sampleRgb, vec2 uv) {
                vec3 rgb = sampleRgb;

                if (uLimitedRange == 1) {
                    rgb = clamp((rgb - vec3(16.0 / 255.0)) / (219.0 / 255.0), 0.0, 1.0);
                }

                // Step 1: Obtain Linear Nits RGB
                vec3 linearNits;
                if (uInputIsLinear == 1) {
                    // Media3 RGBA16F working space normalizes SDR white ~ 1.0 (~203 nits)
                    linearNits = max(rgb, vec3(0.0)) * 203.0;
                } else if (uTransferMode == 1) {
                    linearNits = pqEotfToNits(rgb);
                } else if (uTransferMode == 2) {
                    linearNits = hlgEotfToNits(rgb);
                } else {
                    // SDR input in 0..1: linearize gamma 2.2 scaled to target SDR white
                    linearNits = pow(clamp(rgb, 0.0, 1.0), vec3(2.2)) * uTargetSdrNits;
                }

                // Step 2: Luminance-preserving tone mapping (avoids hue shift & clipped highlights)
                vec3 lumaWeights = (uIsBt2020 == 1)
                    ? vec3(0.2627, 0.6780, 0.0593)
                    : vec3(0.2126, 0.7152, 0.0722);
                float lumaIn = max(dot(linearNits, lumaWeights), 1e-4);
                float lumaMapped = toneMapLuminance(lumaIn);
                vec3 mappedLinear = (linearNits / lumaIn) * lumaMapped;

                // Step 3: Wide Gamut BT.2020 -> BT.709 Conversion + Gamut Compression
                if (uIsBt2020 == 1) {
                    mappedLinear = bt2020ToBt709(mappedLinear);
                    // Soft-desaturate out-of-gamut negative primaries toward luminance
                    float minC = min(min(mappedLinear.r, mappedLinear.g), mappedLinear.b);
                    if (minC < 0.0) {
                        float y = max(dot(mappedLinear, vec3(0.2126, 0.7152, 0.0722)), 0.0);
                        float scale = y / max(y - minC, 1e-4);
                        mappedLinear = mix(vec3(y), mappedLinear, clamp(scale, 0.0, 1.0));
                    }
                }

                // Step 4: Saturation Guard & Smart Enhancement (Contrast + LCD Micro-clarity)
                float sdrLuma = dot(mappedLinear, vec3(0.2126, 0.7152, 0.0722));
                // Desaturate bright highlights slightly to avoid neon clipping
                float highlightDesat = mix(uSaturationGuard, 0.72, smoothstep(0.65, 1.0, sdrLuma));
                mappedLinear = mix(vec3(sdrLuma), mappedLinear, highlightDesat);

                vec3 sdrOut = (uInputIsLinear == 1)
                    ? clamp(mappedLinear, 0.0, 1.0)
                    : linearToSrgb(mappedLinear);

                if (uSmartContrast > 0.001) {
                    // Subtle perceptual S-curve around midtone
                    vec3 sCurve = sdrOut * sdrOut * (3.0 - 2.0 * sdrOut);
                    sdrOut = mix(sdrOut, sCurve, uSmartContrast);
                }

                if (uSharpness > 0.001) {
                    vec3 n = texture2D(uTexSampler, uv + vec2(0.0, uTexelSize.y)).rgb;
                    vec3 s = texture2D(uTexSampler, uv - vec2(0.0, uTexelSize.y)).rgb;
                    vec3 e = texture2D(uTexSampler, uv + vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 w = texture2D(uTexSampler, uv - vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 avgNeighbors = (n + s + e + w) * 0.25;
                    vec3 detail = sampleRgb - avgNeighbors;
                    sdrOut = clamp(sdrOut + detail * uSharpness * 1.4, 0.0, 1.0);
                }

                return clamp(sdrOut, 0.0, 1.0);
            }

            void main() {
                vec4 rawSample = texture2D(uTexSampler, vTexSamplingCoord);

                if (uBypass == 1) {
                    gl_FragColor = rawSample;
                    return;
                }

                if (uCompareEnabled == 1) {
                    // Thin glowing divider line at uSplitX
                    if (abs(vTexSamplingCoord.x - uSplitX) < 0.0025) {
                        gl_FragColor = vec4(0.0, 0.90, 1.0, 1.0);
                        return;
                    }
                    if (vTexSamplingCoord.x < uSplitX) {
                        // Left side: Original un-graded reference
                        gl_FragColor = rawSample;
                        return;
                    }
                }

                vec3 enhanced = processHdrToSdr(rawSample.rgb, vTexSamplingCoord);
                gl_FragColor = vec4(enhanced, rawSample.a);
            }
        """
    }
}
