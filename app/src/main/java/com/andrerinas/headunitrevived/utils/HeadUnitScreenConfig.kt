package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import kotlin.math.roundToInt

object HeadUnitScreenConfig {

    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0
    private var density: Float = 1.0f
    private var densityDpi: Int = 240
    private var scaleFactor: Float = 1.0f
    private var isInitialized: Boolean = false
    private var lastSettingsHash: Int = 0
    
    // Flag to determine if the projection should stretch and ignore aspect ratio
    private var stretchToFill: Boolean = false 
    
    // Forced scale for older devices (Legacy fix)
    var forcedScale: Boolean = false
        private set

    var negotiatedResolutionType: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
    var isResolutionLocked: Boolean = false
        private set

    private lateinit var currentSettings: Settings // Store settings instance

    // System Insets (Bars/Cutouts)
    var systemInsetLeft: Int = 0
        private set
    var systemInsetTop: Int = 0
        private set
    var systemInsetRight: Int = 0
        private set
    var systemInsetBottom: Int = 0
        private set

    // Raw Screen Dimensions (Full Display)
    private var realScreenWidthPx: Int = 0
    private var realScreenHeightPx: Int = 0


    fun init(context: Context, displayMetrics: DisplayMetrics, settings: Settings) {
        stretchToFill = settings.stretchToFill
        forcedScale = settings.forcedScale && settings.viewMode == Settings.ViewMode.SURFACE

        val realW: Int
        val realH: Int
        val usableW: Int
        val usableH: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
            val windowManager = context.getSystemService(android.view.WindowManager::class.java)
            val bounds = windowManager.currentWindowMetrics.bounds
            // On API 30+, bounds on an Activity context often return the usable area.
            // We use the displayMetrics as a fallback for the physical area.
            realW = displayMetrics.widthPixels
            realH = displayMetrics.heightPixels
            usableW = bounds.width()
            usableH = bounds.height()
        } else { // Older APIs
            @Suppress("DEPRECATION")
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = windowManager.defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            realW = size.x
            realH = size.y
            
            @Suppress("DEPRECATION")
            display.getSize(size)
            usableW = size.x
            usableH = size.y
        }

        val finalRealW: Int
        val finalRealH: Int
        val finalUsableW: Int
        val finalUsableH: Int

        val screenOrientation = settings.screenOrientation
        if (screenOrientation == Settings.ScreenOrientation.LANDSCAPE || 
            screenOrientation == Settings.ScreenOrientation.LANDSCAPE_REVERSE) {
            finalRealW = Math.max(realW, realH)
            finalRealH = Math.min(realW, realH)
            finalUsableW = Math.max(usableW, usableH)
            finalUsableH = Math.min(usableW, usableH)
        } else if (screenOrientation == Settings.ScreenOrientation.PORTRAIT || 
                   screenOrientation == Settings.ScreenOrientation.PORTRAIT_REVERSE) {
            finalRealW = Math.min(realW, realH)
            finalRealH = Math.max(realW, realH)
            finalUsableW = Math.min(usableW, usableH)
            finalUsableH = Math.max(usableW, usableH)
        } else {
            finalRealW = realW
            finalRealH = realH
            finalUsableW = usableW
            finalUsableH = usableH
        }

        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Raw size: ${realW}x${realH}, usable: ${usableW}x${usableH}, orientation setting: $screenOrientation")
        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Final normalized size: ${finalRealW}x${finalRealH}, usable: ${finalUsableW}x${finalUsableH}")

        // Only update if dimensions or settings changed
        val currentHash = computeSettingsHash(settings)
        if (isInitialized && realScreenWidthPx == finalRealW && realScreenHeightPx == finalRealH && lastSettingsHash == currentHash) {
            return
        }

        // If settings changed (e.g. orientation swap), unlock resolution before recalculating
        if (isInitialized && lastSettingsHash != 0 && lastSettingsHash != currentHash) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Settings changed ($lastSettingsHash -> $currentHash). Unlocking resolution.")
            unlockResolution()
        }

        isInitialized = true
        lastSettingsHash = currentHash
        currentSettings = settings

        // Determine if we are planning to hide the bars (Immersive)
        val immersive = settings.fullscreenMode == Settings.FullscreenMode.IMMERSIVE || 
                        settings.fullscreenMode == Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH

        // THE ANCHOR: 
        // If we are immersive, our "World" is the physical screen. 
        // If we are NOT, our "World" is limited to the usable window area (no lying to AA).
        val defaultAnchorW = if (immersive) finalRealW else finalUsableW
        val defaultAnchorH = if (immersive) finalRealH else finalUsableH
        
        density = displayMetrics.density
        densityDpi = displayMetrics.densityDpi

        // Initial Insets: For non-immersive, the bars are already baked into the anchor (realSize = 736),
        // so we start with 0 system insets and just add manual settings.
        systemInsetLeft = settings.insetLeft
        systemInsetTop = settings.insetTop
        systemInsetRight = settings.insetRight
        systemInsetBottom = settings.insetBottom
        
        // Check if we have cached surface dimensions from a previous session.
        // If the settings haven't changed (same hash), use the cached values
        // to avoid a mid-session UpdateUiConfigRequest and potential flicker.
        val cachedW = settings.cachedSurfaceWidth
        val cachedH = settings.cachedSurfaceHeight
        val cachedHash = settings.cachedSurfaceSettingsHash

        if (cachedW > 0 && cachedH > 0 && cachedHash == currentHash) {
            // Normalise the cache to the forced orientation exactly as the live measurement above
            // is normalised. A surface can be sampled while the activity is still rotating into
            // its forced orientation, and that portrait sample is then cached; used verbatim it
            // would override the correctly normalised size and make us negotiate a portrait video
            // configuration on a landscape head unit — the phone then streams its portrait UI and
            // it gets squashed into the landscape window.
            val normalisedW: Int
            val normalisedH: Int
            when (screenOrientation) {
                Settings.ScreenOrientation.LANDSCAPE, Settings.ScreenOrientation.LANDSCAPE_REVERSE -> {
                    normalisedW = Math.max(cachedW, cachedH)
                    normalisedH = Math.min(cachedW, cachedH)
                }
                Settings.ScreenOrientation.PORTRAIT, Settings.ScreenOrientation.PORTRAIT_REVERSE -> {
                    normalisedW = Math.min(cachedW, cachedH)
                    normalisedH = Math.max(cachedW, cachedH)
                }
                else -> {
                    normalisedW = cachedW
                    normalisedH = cachedH
                }
            }
            if (normalisedW != cachedW || normalisedH != cachedH) {
                AppLog.w("[UI_DEBUG_FIX] HeadUnitScreenConfig: Cached surface ${cachedW}x${cachedH} does not match the forced $screenOrientation orientation — using ${normalisedW}x${normalisedH} instead.")
            }
            // Cached surface dimensions are the usable area. The anchor includes insets.
            realScreenWidthPx = normalisedW + systemInsetLeft + systemInsetRight
            realScreenHeightPx = normalisedH + systemInsetTop + systemInsetBottom
            AppLog.i("[UI_DEBUG_FIX] HeadUnitScreenConfig: Using cached surface dimensions: ${normalisedW}x${normalisedH} (anchor: ${realScreenWidthPx}x${realScreenHeightPx})")
        } else {
            realScreenWidthPx = defaultAnchorW
            realScreenHeightPx = defaultAnchorH
            if (cachedW > 0) {
                AppLog.i("[UI_DEBUG_FIX] HeadUnitScreenConfig: Cache invalidated (hash mismatch: stored=$cachedHash, current=$currentHash)")
            }
        }

        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Honest Init | Mode: ${settings.fullscreenMode} | Anchor: ${realScreenWidthPx}x${realScreenHeightPx} | Seeded Insets: L$systemInsetLeft T$systemInsetTop R$systemInsetRight B$systemInsetBottom")
        
        recalculate()
    }

    fun updateInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (systemInsetLeft == left && systemInsetTop == top && systemInsetRight == right && systemInsetBottom == bottom) {
            return
        }
        
        systemInsetLeft = left
        systemInsetTop = top
        systemInsetRight = right
        systemInsetBottom = bottom
        
        if (isInitialized) {
            recalculate()
        }
    }

    private fun recalculate() {
        // Calculate USABLE area
        screenWidthPx = realScreenWidthPx - systemInsetLeft - systemInsetRight
        screenHeightPx = realScreenHeightPx - systemInsetTop - systemInsetBottom

        if (screenWidthPx <= 0 || screenHeightPx <= 0) {
            screenWidthPx = realScreenWidthPx
            screenHeightPx = realScreenHeightPx
        }

        val isPortraitDisplay = screenHeightPx > screenWidthPx
        val canNegotiateHevc = canNegotiateHevcHighResolution()

        // 1. Determine base negotiated resolution
        if (isResolutionLocked) {
            // Safety Check: If the locked resolution's orientation (Landscape/Portrait) 
            // no longer matches the display orientation, the lock is stale and must be dropped.
            val isPortraitRes = getNegotiatedHeight() > getNegotiatedWidth()
            if (isPortraitRes != isPortraitDisplay) {
                AppLog.i("[UI_DEBUG] CarScreen: Orientation mismatch detected (Res: ${if(isPortraitRes) "P" else "L"}, Display: ${if(isPortraitDisplay) "P" else "L"}). DROPPING LOCK.")
                unlockResolution()
            } else {
                AppLog.i("[UI_DEBUG] CarScreen: RESOLUTION LOCKED to $negotiatedResolutionType. Usable area is ${screenWidthPx}x${screenHeightPx}. Skipping re-negotiation.")
            }
        }
        
        // Once the resolution is locked it must not be re-derived at all. The manual branch below
        // falls back to 800x480 whenever the selection has no codec of its own — which is the case
        // for AUTO — so reaching it while locked silently collapsed an automatically chosen
        // resolution to 800x480 on the next recalculate (a surface change or an inset update).
        if (isResolutionLocked) {
            // keep negotiatedResolutionType as agreed with the phone
        } else {
            if (isPortraitDisplay) {
                negotiatedResolutionType = if (screenWidthPx > 720 || screenHeightPx > 1280) {
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
                } else {
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
                }
            } else {
                negotiatedResolutionType = when {
                    screenWidthPx <= 800 && screenHeightPx <= 480 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
                    (screenWidthPx >= 3840 || screenHeightPx >= 2160) && VideoDecoder.isHevcSupported() && Build.VERSION.SDK_INT >= 24 -> 
                        Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160
                    (screenWidthPx >= 2560 || screenHeightPx >= 1440) && canNegotiateHevc && Build.VERSION.SDK_INT >= 24 ->
                        Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440
                    screenWidthPx > 1280 || screenHeightPx > 720 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
                    else -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
                }
                negotiatedResolutionType = applyUiSizePreference(negotiatedResolutionType)
            }
        }

        // 2. Perform scaling calculations (now safe because negotiatedResolutionType is set)
        AppLog.i("[UI_DEBUG] CarScreen: usable area ${screenWidthPx}x${screenHeightPx}, using $negotiatedResolutionType")

        // How much the negotiated frame has to grow to cover the screen. This is what produces the
        // margins we declare to the phone, so that Android Auto keeps its own UI inside the part of
        // the frame that will actually be visible after the projection view scales it up.
        //
        // This used to be skipped for anything at or below 1920x1080, which meant a screen that was
        // small but not 16:9 (a 1560x720 phone, say) declared no margins at all: the phone laid its
        // UI out across the full frame, the view scaled that frame to cover the screen anyway, and
        // the search bar and app row ended up cropped off the top and bottom. The size of a screen
        // says nothing about its aspect ratio, so compute this for every screen. Where the aspect
        // already matches the negotiated frame the factor comes out as 1.0 and the margins as 0,
        // exactly as before.
        scaleFactor = 1.0f
        val sWidth = screenWidthPx.toFloat()
        val sHeight = screenHeightPx.toFloat()
        if (getNegotiatedWidth() > 0 && getNegotiatedHeight() > 0) {
            scaleFactor = if (sWidth / sHeight < getAspectRatio()) {
                sHeight / getNegotiatedHeight().toFloat()
            } else {
                sWidth / getNegotiatedWidth().toFloat()
            }
        }

        AppLog.i("[UI_DEBUG] CarScreen scaleFactor: $scaleFactor, margins: w=${getWidthMargin()}, h=${getHeightMargin()}, density: ${getDensityDpi()}, uiSize: ${currentSettings.uiSize} (${getNegotiatedWidth() * 160 / getDensityDpi()}dp canvas)")
    }

    fun getAdjustedHeight(): Int {
        return (getNegotiatedHeight() * scaleFactor).roundToInt()
    }

    fun getAdjustedWidth(): Int {
        return (getNegotiatedWidth() * scaleFactor).roundToInt()
    }

    private fun getAspectRatio(): Float {
        return getNegotiatedWidth().toFloat() / getNegotiatedHeight().toFloat()
    }

    fun getNegotiatedHeight(): Int {
        val resString = negotiatedResolutionType.toString().replace("_", "")
        return try {
            resString.split("x")[1].toInt()
        } catch (e: Exception) {
            480
        }
    }

    private fun canNegotiateHevcHighResolution(): Boolean {
        if (VideoDecoder.isHevcSupported()) return true
        if (currentSettings.videoCodec != "H.265" || !currentSettings.forceSoftwareDecoding) return false
        return when (currentSettings.softwareVideoDecoder) {
            Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG -> VideoDecoder.isBundledHevcDecoderAvailable()
            Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC -> VideoDecoder.isHevcDecoderAvailable(includeSoftware = true)
        }
    }

    fun getNegotiatedWidth(): Int {
        val resString = negotiatedResolutionType.toString().replace("_", "")
        return try {
            resString.split("x")[0].toInt()
        } catch (e: Exception) {
            800
        }
    }

    fun getHeightMargin(): Int {
        val margin = ((getAdjustedHeight() - screenHeightPx) / scaleFactor).roundToInt()
        return margin.coerceAtLeast(0)
    }

    fun getWidthMargin(): Int {
        val margin = ((getAdjustedWidth() - screenWidthPx) / scaleFactor).roundToInt()
        return margin.coerceAtLeast(0)
    }

    private fun divideOrOne(numerator: Float, denominator: Float): Float {
        return if (denominator == 0.0f) 1.0f else numerator / denominator
    }

    /**
     * Steps the automatically chosen resolution down, if that is what it takes to draw Android
     * Auto's controls at the requested size.
     *
     * The size of those controls comes from the density we advertise, not from the resolution: a
     * narrower dp canvas means everything is drawn proportionally bigger. But density is not a free
     * variable — real head units report something in the 160-240 range, nothing in this app has ever
     * sent more than 240, and what a phone does with an unusually high value is untested. So rather
     * than push density past that to shrink the canvas, drop to a smaller frame, where a
     * conventional density gives the same canvas. Glove-sized controls on a 2340px screen come out
     * as 1280x720 at 240dpi — which is exactly the combination that was set by hand and worked.
     *
     * Candidates start at what the ladder picked for this screen and only ever step down, so a
     * screen never gets offered a frame larger than it can use.
     */
    private fun applyUiSizePreference(
        ladderPick: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType
    ): Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType {
        if (!this::currentSettings.isInitialized) return ladderPick
        val targetDp = currentSettings.uiSize.canvasDpWidth
        if (targetDp <= 0f) return ladderPick

        val ladderIndex = LANDSCAPE_LADDER.indexOf(ladderPick)
        if (ladderIndex < 0) return ladderPick // portrait or an exotic pick — leave it alone

        for (candidate in LANDSCAPE_LADDER.drop(ladderIndex)) {
            val density = (160f * widthOf(candidate) / targetDp).roundToInt()
            if (density in CONVENTIONAL_DENSITY_RANGE) return candidate
        }
        return ladderPick
    }

    private fun widthOf(
        type: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType
    ): Int = type.toString().replace("_", "").split("x").firstOrNull()?.toIntOrNull() ?: 800

    /*
     * The projection view fills the screen and the video frame is mapped onto it, which by itself
     * squashes the frame to the screen's aspect ratio. These two correct that back to a uniform
     * scale: the frame keeps its shape and grows by [scaleFactor] until it covers the screen, and
     * whatever then falls outside is the band we declared to the phone as a margin, so Android Auto
     * has already kept its own UI out of it.
     *
     * They used to compare the negotiated size against the screen and, when the frame was the
     * larger of the two, scale *up* by the ratio — separately per axis. A 1920x1080 frame on a
     * 1560x720 screen came out at 1.23 across and 1.50 down: zoomed in and distorted at once.
     *
     * Sanity check against the configuration that has always looked right (Mi9T, 2340x1080 screen,
     * 1920x1080 negotiated): scaleFactor 1.21875, so scaleX = 1920*1.21875/2340 = 1.0 and
     * scaleY = 1080*1.21875/1080 = 1.21875 — exactly what it produced before.
     */
    fun getScaleX(): Float {
        if (forcedScale) {
            return 1.0f
        }
        return divideOrOne(getNegotiatedWidth() * scaleFactor, screenWidthPx.toFloat())
    }

    fun getScaleY(): Float {
        if (forcedScale) {
            return 1.0f
        }
        return divideOrOne(getNegotiatedHeight() * scaleFactor, screenHeightPx.toFloat())
    }

    /**
     * The density reported to the phone, which decides how large Android Auto draws its own UI
     * inside the negotiated frame.
     *
     * On automatic this deliberately does **not** report the panel's own density. That number
     * describes a phone held at arm's length — 320 on one test unit, 440 on the other — and passing
     * it on makes Android Auto lay out a phone-sized UI, far too large for a frame being viewed as
     * a dashboard. Derive it from the frame and the requested control size instead: the density
     * that makes the frame represent [Settings.UiSize.canvasDpWidth] dp across.
     *
     * applyUiSizePreference has usually already dropped the frame to one where that lands on a
     * conventional value; the clamp is only a backstop for the combinations it could not satisfy.
     */
    fun getDensityDpi(): Int {
        val negotiatedWidth = getNegotiatedWidth()
        if (negotiatedWidth <= 0 || !this::currentSettings.isInitialized) return densityDpi
        val targetDp = currentSettings.uiSize.canvasDpWidth
        if (targetDp <= 0f) return densityDpi
        return ((160f * negotiatedWidth) / targetDp).roundToInt().coerceIn(120, 320)
    }

    fun getUsableWidth(): Int = screenWidthPx
    fun getUsableHeight(): Int = screenHeightPx

    // These are half the total margin, distributed symmetrically.
    fun getLeftMargin(): Int = getWidthMargin() / 2
    fun getRightMargin(): Int = getWidthMargin() - getLeftMargin()
    fun getTopMargin(): Int = getHeightMargin() / 2
    fun getBottomMargin(): Int = getHeightMargin() - getTopMargin()

    /**
     * Called when the actual rendering surface dimensions become known (from onSurfaceChanged).
     * Compares with the current usable area and updates the anchor if they differ.
     * @return true if the dimensions changed and margins need to be re-sent to AA.
     */
    fun updateSurfaceDimensions(surfaceW: Int, surfaceH: Int): Boolean {
        val finalSurfaceW: Int
        val finalSurfaceH: Int

        val screenOrientation = if (this::currentSettings.isInitialized) currentSettings.screenOrientation else Settings.ScreenOrientation.SYSTEM
        if (screenOrientation == Settings.ScreenOrientation.LANDSCAPE || 
            screenOrientation == Settings.ScreenOrientation.LANDSCAPE_REVERSE) {
            finalSurfaceW = Math.max(surfaceW, surfaceH)
            finalSurfaceH = Math.min(surfaceW, surfaceH)
        } else if (screenOrientation == Settings.ScreenOrientation.PORTRAIT || 
                   screenOrientation == Settings.ScreenOrientation.PORTRAIT_REVERSE) {
            finalSurfaceW = Math.min(surfaceW, surfaceH)
            finalSurfaceH = Math.max(surfaceW, surfaceH)
        } else {
            finalSurfaceW = surfaceW
            finalSurfaceH = surfaceH
        }

        val diffW = kotlin.math.abs(finalSurfaceW - screenWidthPx)
        val diffH = kotlin.math.abs(finalSurfaceH - screenHeightPx)

        if (diffW <= SURFACE_MISMATCH_TOLERANCE && diffH <= SURFACE_MISMATCH_TOLERANCE) {
            return false
        }

        if( (diffW > 0 && getNegotiatedWidth() == finalSurfaceW) || (diffH > 0 && getNegotiatedHeight() == finalSurfaceH)) {
            AppLog.i("[UI_DEBUG_FIX] Surface mismatch detected but matches negotiated resolution. Usable: ${screenWidthPx}x${screenHeightPx}, Actual surface: ${finalSurfaceW}x${finalSurfaceH}. Ignoring.")
            return false
        }

        AppLog.i("[UI_DEBUG_FIX] Surface mismatch detected! Usable: ${screenWidthPx}x${screenHeightPx}, Actual surface: ${finalSurfaceW}x${finalSurfaceH} (diff: ${diffW}x${diffH})")

        // Update anchor: the surface dimensions ARE the real usable area,
        // so the anchor is the usable area plus insets.
        realScreenWidthPx = finalSurfaceW + systemInsetLeft + systemInsetRight
        realScreenHeightPx = finalSurfaceH + systemInsetTop + systemInsetBottom

        recalculate()

        AppLog.i("[UI_DEBUG_FIX] Recalculated: usable=${screenWidthPx}x${screenHeightPx}, margins: w=${getWidthMargin()}, h=${getHeightMargin()}, per-side: L=${getLeftMargin()} T=${getTopMargin()} R=${getRightMargin()} B=${getBottomMargin()}")
        return true
    }

    /**
     * Computes a hash of all settings that affect screen dimensions.
     * Used to invalidate the cached surface dimensions when settings change.
     */
    fun computeSettingsHash(settings: Settings): Int {
        var hash = 17
        hash = 31 * hash + settings.uiSize.id
        hash = 31 * hash + settings.insetLeft
        hash = 31 * hash + settings.insetTop
        hash = 31 * hash + settings.insetRight
        hash = 31 * hash + settings.insetBottom
        hash = 31 * hash + settings.viewMode.ordinal
        hash = 31 * hash + settings.screenOrientation.ordinal
        hash = 31 * hash + settings.fullscreenMode.value
        hash = 31 * hash + (if (settings.stretchToFill) 1 else 0)
        hash = 31 * hash + (if (settings.forcedScale) 1 else 0)
        // Include physical dimensions in the hash. If the screen rotates or a foldable is unfolded,
        // the hash will change, triggering a clean unlock and recalculation.
        hash = 31 * hash + realScreenWidthPx
        hash = 31 * hash + realScreenHeightPx
        return hash
    }

    /**
     * Freezes the negotiated resolution so a later surface or inset update cannot re-derive a
     * figure the phone was never told about.
     *
     * Refuses to lock before the screen has been measured. Until [init] runs,
     * [negotiatedResolutionType] is still the field initialiser (_800x480), and freezing that
     * announced 800x480 to the phone - which also defines the touch coordinate space, so every tap
     * landed away from where it was made. A caller that hits this warning wants an [init] first.
     */
    fun lockResolution() {
        if (!isInitialized) {
            AppLog.w("[UI_DEBUG] HeadUnitScreenConfig: lockResolution() before init - refusing to freeze the default $negotiatedResolutionType. Call init() first.")
            return
        }
        if (!isResolutionLocked) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Locking resolution at $negotiatedResolutionType")
            isResolutionLocked = true
        }
    }

    fun unlockResolution() {
        if (isResolutionLocked) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Unlocking resolution (was $negotiatedResolutionType)")
            isResolutionLocked = false
        }
    }

    private const val SURFACE_MISMATCH_TOLERANCE = 4

    /** Landscape frames, largest first — the order applyUiSizePreference steps down through. */
    private val LANDSCAPE_LADDER = listOf(
        Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160,
        Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440,
        Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
        Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720,
        Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
    )

    /** What real head units report, and the only range this app has ever been known to work in. */
    private val CONVENTIONAL_DENSITY_RANGE = 140..260
}
