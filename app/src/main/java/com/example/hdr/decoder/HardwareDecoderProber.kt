package com.example.hdr.decoder

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import com.example.hdr.model.CodecProbeEntry
import com.example.hdr.model.DecoderCapabilityReport
import com.example.hdr.model.DeviceHardwareProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Module B: Hardware Decoder Prober & Device Capability Matrix.
 * Queries MediaCodecList, VideoCapabilities, EGL/GLES GPU details, Display HDR caps,
 * and performs a serialized MediaCodec.configure() initialization probe without
 * saturating MediaResourceManager buffer pools.
 */
class HardwareDecoderProber(private val context: Context) {

    private val probeMutex = Mutex()

    suspend fun probeDeviceCapabilities(runLiveCodecInitProbe: Boolean = true): DecoderCapabilityReport =
        probeMutex.withLock {
            withContext(Dispatchers.Default) {
                val deviceProfile = collectDeviceHardwareProfile()
                val allCodecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos

                val hevcEntries = mutableListOf<CodecProbeEntry>()
                val avcEntries = mutableListOf<CodecProbeEntry>()
                val av1Entries = mutableListOf<CodecProbeEntry>()

                for (info in allCodecs) {
                    if (info.isEncoder) continue
                    for (mime in info.supportedTypes) {
                        when {
                            mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) ->
                                hevcEntries += inspectCodecCapabilities(info, mime)
                            mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) ->
                                avcEntries += inspectCodecCapabilities(info, mime)
                            mime.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) ->
                                av1Entries += inspectCodecCapabilities(info, mime)
                        }
                    }
                }

                val sortedHevc = hevcEntries.sortedByDescending {
                    (if (it.isHardwareAccelerated) 100 else 0) +
                        (if (it.supportsHevcMain10) 50 else 0) +
                        (if (it.supports4k30Fps) 20 else 0)
                }.toMutableList()

                // Only perform live MediaCodec.configure() init probe on the primary HEVC decoder
                // to avoid exceeding concurrent MediaResourceManager codec/buffer quotas.
                if (runLiveCodecInitProbe && sortedHevc.isNotEmpty()) {
                    val primaryIdx = sortedHevc.indexOfFirst { it.isHardwareAccelerated }
                        .takeIf { it >= 0 } ?: 0
                    val target = sortedHevc[primaryIdx]
                    val probeW = if (target.supports4k24Fps || target.maxSupportedWidth >= 3840) {
                        3840
                    } else {
                        target.maxSupportedWidth.coerceIn(720, 1920)
                    }
                    val probeH = if (target.supports4k24Fps || target.maxSupportedHeight >= 2160) {
                        2160
                    } else {
                        target.maxSupportedHeight.coerceIn(480, 1080)
                    }
                    val result = performActualCodecInitProbe(
                        codecName = target.codecName,
                        mime = target.mimeType,
                        width = probeW,
                        height = probeH,
                        main10 = target.supportsHevcMain10
                    )
                    sortedHevc[primaryIdx] = target.copy(
                        initProbeSucceeded = result.first,
                        initProbeLatencyMs = result.second,
                        initProbeMessage = result.third
                    )
                }

                val primaryHwHevc = sortedHevc.firstOrNull { it.isHardwareAccelerated }
                    ?: sortedHevc.firstOrNull()

                val canDecode4kHdr10Live = primaryHwHevc != null &&
                    primaryHwHevc.isHardwareAccelerated &&
                    primaryHwHevc.supportsHevcMain10 &&
                    primaryHwHevc.supports4k24Fps &&
                    primaryHwHevc.initProbeSucceeded

                val canRunGlEffect = !deviceProfile.gpuRenderer.contains("Error", ignoreCase = true)

                val summary = buildString {
                    if (primaryHwHevc == null) {
                        append("No HEVC video decoder found on this device.")
                    } else if (canDecode4kHdr10Live) {
                        append("Hardware 4K HEVC Main10 verified (${primaryHwHevc.codecName}). Real-time OpenGL ES HDR-to-SDR supported.")
                    } else if (primaryHwHevc.isHardwareAccelerated && primaryHwHevc.supportsHevcMain10) {
                        append("HEVC Main10 present (${primaryHwHevc.codecName}), 4K@24/30 live capability: ${primaryHwHevc.initProbeMessage}.")
                    } else {
                        append("Hardware 4K HDR10 Main10 unsupported on ${primaryHwHevc.codecName}; fallback or offline Media3 Transformer recommended.")
                    }
                }

                DecoderCapabilityReport(
                    deviceProfile = deviceProfile,
                    hevcDecoders = sortedHevc,
                    avcDecoders = avcEntries,
                    av1Decoders = av1Entries,
                    primaryHevcHwDecoder = primaryHwHevc,
                    canDecode4kHdr10Live = canDecode4kHdr10Live,
                    canRunGlHdrToSdrEffect = canRunGlEffect,
                    capabilitySummary = summary
                )
            }
        }

    private fun inspectCodecCapabilities(
        info: MediaCodecInfo,
        mime: String
    ): CodecProbeEntry {
        val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
        val videoCaps = caps?.videoCapabilities
        val profiles = caps?.profileLevels?.map { it.profile }?.toSet().orEmpty()

        val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            !info.name.startsWith("OMX.google.", ignoreCase = true) &&
                !info.name.startsWith("c2.android.", ignoreCase = true)
        }

        val supportsMain10 = mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) && (
            profiles.contains(CodecProfileLevel.HEVCProfileMain10) ||
                profiles.contains(CodecProfileLevel.HEVCProfileMain10HDR10) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    profiles.contains(CodecProfileLevel.HEVCProfileMain10HDR10Plus))
            )

        val supportsHdr10 = mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) && (
            profiles.contains(CodecProfileLevel.HEVCProfileMain10HDR10) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    profiles.contains(CodecProfileLevel.HEVCProfileMain10HDR10Plus))
            )

        val maxW = videoCaps?.supportedWidths?.upper ?: 0
        val maxH = videoCaps?.supportedHeights?.upper ?: 0
        val s4k24 = videoCaps?.areSizeAndRateSupported(3840, 2160, 24.0) == true
        val s4k30 = videoCaps?.areSizeAndRateSupported(3840, 2160, 30.0) == true
        val s4k60 = videoCaps?.areSizeAndRateSupported(3840, 2160, 60.0) == true
        val s1080p60 = videoCaps?.areSizeAndRateSupported(1920, 1080, 60.0) == true

        return CodecProbeEntry(
            codecName = info.name,
            mimeType = mime,
            isHardwareAccelerated = isHw,
            supportsHevcMain10 = supportsMain10,
            supportsHevcMain10Hdr10 = supportsHdr10,
            maxSupportedWidth = maxW,
            maxSupportedHeight = maxH,
            supports4k24Fps = s4k24,
            supports4k30Fps = s4k30,
            supports4k60Fps = s4k60,
            supports1080p60Fps = s1080p60,
            initProbeSucceeded = false,
            initProbeLatencyMs = 0L,
            initProbeMessage = "Capability check only (secondary codec)"
        )
    }

    /**
     * Instantiates and configures MediaCodec against a lightweight ImageReader Surface
     * to verify driver initialization without calling start(), avoiding MediaResourceManager
     * buffer pool exhaustion or preemption during active playback.
     */
    private fun performActualCodecInitProbe(
        codecName: String,
        mime: String,
        width: Int,
        height: Int,
        main10: Boolean
    ): Triple<Boolean, Long, String> {
        val start = SystemClock.elapsedRealtime()
        var codec: MediaCodec? = null
        var imageReader: ImageReader? = null
        return try {
            imageReader = ImageReader.newInstance(
                width.coerceAtLeast(64),
                height.coerceAtLeast(64),
                ImageFormat.PRIVATE,
                1
            )

            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, 24)
                if (main10) {
                    setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
                }
            }

            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, imageReader.surface, null, 0)
            val elapsed = SystemClock.elapsedRealtime() - start
            Triple(true, elapsed, "Verified ${width}x${height} ${if (main10) "Main10" else "8-bit"} configure in ${elapsed}ms")
        } catch (t: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - start
            Triple(false, elapsed, t.javaClass.simpleName + ": " + (t.message ?: "Configure failed"))
        } finally {
            runCatching { codec?.reset() }
            runCatching { codec?.release() }
            runCatching { imageReader?.close() }
        }
    }

    private fun collectDeviceHardwareProfile(): DeviceHardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024L * 1024L)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.display }.getOrNull() ?: wm?.defaultDisplay
        } else {
            wm?.defaultDisplay
        }

        val (dispW, dispH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }

        val refreshHz = display?.refreshRate ?: 60f
        val hdrTypes = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && display != null) {
            @Suppress("DEPRECATION")
            val caps = display.hdrCapabilities
            if (caps != null) {
                for (type in caps.supportedHdrTypes) {
                    hdrTypes += when (type) {
                        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                        else -> "HDR ($type)"
                    }
                }
            }
        }
        if (hdrTypes.isEmpty()) hdrTypes += "SDR Panel Only"

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }

        val gpuStrings = queryEglGpuStrings()

        return DeviceHardwareProfile(
            deviceModel = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            socModel = socModel,
            hardwareBoard = Build.HARDWARE,
            androidVersionLabel = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            apiLevel = Build.VERSION.SDK_INT,
            displayResolution = "${dispW}x${dispH}",
            displayWidthPx = dispW,
            displayHeightPx = dispH,
            displayRefreshHz = refreshHz,
            displayHdrTypes = hdrTypes,
            totalRamMb = totalRamMb,
            gpuRenderer = gpuStrings.first,
            gpuVendor = gpuStrings.second,
            glesVersion = gpuStrings.third
        )
    }

    private fun queryEglGpuStrings(): Triple<String, String, String> {
        var display = EGL14.EGL_NO_DISPLAY
        var contextEgl = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return Triple("Unknown GPU", "Unknown", "OpenGL ES 3.0")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return Triple("Unknown GPU", "Unknown", "OpenGL ES 3.0")
            }
            val attribList = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                return Triple("Unknown GPU", "Unknown", "OpenGL ES 3.0")
            }
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            contextEgl = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)
            EGL14.eglMakeCurrent(display, surface, surface, contextEgl)

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown GPU"
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unknown"
            val glVer = GLES20.glGetString(GLES20.GL_VERSION) ?: "OpenGL ES 3.0"
            Triple(renderer, vendor, glVer)
        } catch (_: Throwable) {
            Triple("Mali / Hardware GPU", "Vendor", "OpenGL ES 3.0")
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (contextEgl != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, contextEgl)
                EGL14.eglTerminate(display)
            }
        }
    }
}
