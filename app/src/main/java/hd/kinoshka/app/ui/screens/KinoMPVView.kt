package hd.kinoshka.app.ui.screens

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode

data class MpvTrack(
    val id: Int,
    val type: String, // "video", "audio", "sub"
    val lang: String,
    val title: String,
    val selected: Boolean
)

class KinoMPVView(
    context: Context,
    attrs: AttributeSet
) : BaseMPVView(context, attrs) {

    var onProgress: ((Long) -> Unit)? = null
    var onDuration: ((Long) -> Unit)? = null
    var onBuffering: ((Boolean) -> Unit)? = null
    var onPlaybackState: ((Boolean) -> Unit)? = null // True = Playing, False = Paused
    var onFileLoaded: (() -> Unit)? = null
    var onEofReached: (() -> Unit)? = null

    private var mpvInitialized = false
    private var isNativeReady = false

    // Pending states to avoid race conditions before native libmpv is ready
    private var pendingFile: String? = null
    private var pendingHeaders: Map<String, String>? = null
    private var pendingSpeed: Float = 1.0f
    private var pendingDeband: Boolean = false
    private var pendingHwdec: Boolean = true
    private var pendingAnime4kMode = Anime4KManager.Mode.OFF
    private var pendingAnime4kQuality = Anime4KManager.Quality.BALANCED
    private var anime4kManagerInstance: Anime4KManager? = null

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
            post { handlePropertyChange(property) }
        }
        override fun eventProperty(property: String, value: Long) {
            post { handlePropertyChange(property, value) }
        }
        override fun eventProperty(property: String, value: Boolean) {
            post { handlePropertyChange(property, value) }
        }
        override fun eventProperty(property: String, value: String) {
            post { handlePropertyChange(property, value) }
        }
        override fun eventProperty(property: String, value: Double) {
            post { handlePropertyChange(property, value) }
        }
        override fun eventProperty(property: String, value: MPVNode) {
            post { handlePropertyChange(property, value) }
        }
        override fun event(eventId: Int, data: MPVNode) {
            post { handleEvent(eventId) }
        }
    }

    init {
        // Prepare assets configuration
        try {
            `is`.xyz.mpv.Utils.copyAssets(context)
        } catch (e: Exception) {
            Log.e("KinoMPVView", "Failed to copy MPV assets", e)
        }
    }

    fun initPlayer() {
        if (mpvInitialized) return
        initialize(context.filesDir.path, context.cacheDir.path)
        MPVLib.addObserver(observer)
        mpvInitialized = true
    }

    fun releasePlayer() {
        if (!mpvInitialized) return
        MPVLib.removeObserver(observer)
        destroy()
        isNativeReady = false
        mpvInitialized = false
    }

    override fun initOptions() {
        // Standard optimizations and configurations
        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("vo", "gpu")
        
        // Hardware decoding options — prefer mediacodec-COPY: direct (non-copy) mediacodec
        // renders to the SurfaceView and causes black/corrupt frames on HLS segment changes.
        val hwdecStr = if (pendingHwdec) "mediacodec-copy,mediacodec,no" else "no"
        MPVLib.setOptionString("hwdec", hwdecStr)
        MPVLib.setOptionString("hwdec-codecs", "all")

        // Cache configuration for smooth streaming
        val cacheMegs = 64
        MPVLib.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        // HLS smoothness: readahead so segment rebuffers don't read as skips/stalls.
        MPVLib.setOptionString("demuxer-readahead-secs", "12")
        MPVLib.setOptionString("cache-secs", "10")
        MPVLib.setOptionString("framedrop", "vo")
        
        MPVLib.setPropertyBoolean("keep-open", true)
        MPVLib.setPropertyBoolean("input-default-bindings", true)
        
        // SSL certificate verification - disable to avoid certificate failures on remote CDN links
        MPVLib.setOptionString("tls-verify", "no")
        
        // Disable automatic track selection so we can select manually or let user do it
        MPVLib.setOptionString("slang", "")
        MPVLib.setOptionString("alang", "")
        MPVLib.setOptionString("sub-auto", "no")
        
        // Performance options
        MPVLib.setOptionString("vd-lavc-dr", "yes")
        MPVLib.setOptionString("opengl-pbo", "yes")
        MPVLib.setOptionString("hr-seek", "yes")

        // Apply pending speed
        MPVLib.setOptionString("speed", pendingSpeed.toString())

        // Apply pending HTTP headers
        pendingHeaders?.let { headers ->
            if (headers.isNotEmpty()) {
                val headersString = headers
                    .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
                    .joinToString(",")
                MPVLib.setOptionString("http-header-fields", headersString)
            }
        }

        // Apply pending debanding
        if (pendingDeband) {
            MPVLib.setOptionString("deband", "yes")
        }

        // Apply pending Anime4K shaders
        if (pendingAnime4kMode != Anime4KManager.Mode.OFF) {
            val chain = anime4kManagerInstance?.getShaderChain(pendingAnime4kMode, pendingAnime4kQuality) ?: ""
            if (chain.isNotEmpty()) {
                MPVLib.setOptionString("glsl-shaders", chain)
            }
        }

        isNativeReady = true
    }

    override fun observeProperties() {
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    override fun postInitOptions() {
        // Apply deband filters on command line
        if (pendingDeband) {
            MPVLib.command("vf", "add", "@deband:gradfun=radius=12")
        }
        
        // Start playing the file if it was loaded before native engine initialization finished
        pendingFile?.let {
            playFile(it)
        }
    }

    private fun handlePropertyChange(property: String, value: Any? = null) {
        when (property) {
            "pause" -> {
                val paused = value as? Boolean ?: MPVLib.getPropertyBoolean("pause") ?: false
                onPlaybackState?.invoke(!paused)
            }
            "paused-for-cache" -> {
                val buffering = value as? Boolean ?: MPVLib.getPropertyBoolean("paused-for-cache") ?: false
                onBuffering?.invoke(buffering)
            }
            "time-pos" -> {
                val posSecs = value as? Long ?: MPVLib.getPropertyInt("time-pos")?.toLong() ?: 0L
                onProgress?.invoke(posSecs * 1000L) // Convert to ms
            }
            "duration" -> {
                val durSecs = value as? Long ?: MPVLib.getPropertyInt("duration")?.toLong() ?: 0L
                onDuration?.invoke(durSecs * 1000L) // Convert to ms
            }
            "eof-reached" -> {
                val eof = value as? Boolean ?: MPVLib.getPropertyBoolean("eof-reached") ?: false
                if (eof) {
                    onEofReached?.invoke()
                }
            }
        }
    }

    private fun handleEvent(eventId: Int) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
            onFileLoaded?.invoke()
        }
    }

    // --- Control API ---

    fun play() {
        if (!isNativeReady) return
        MPVLib.setPropertyBoolean("pause", false)
    }

    fun pause() {
        if (!isNativeReady) return
        MPVLib.setPropertyBoolean("pause", true)
    }

    fun seekTo(positionMs: Long) {
        if (!isNativeReady) return
        val seconds = positionMs / 1000
        MPVLib.command("seek", seconds.toString(), "absolute+exact")
    }

    fun setSpeed(speed: Float) {
        pendingSpeed = speed
        if (isNativeReady) {
            MPVLib.setPropertyDouble("speed", speed.toDouble())
        }
    }

    fun setHwdecEnabled(enabled: Boolean) {
        pendingHwdec = enabled
        if (isNativeReady) {
            val hwdecStr = if (enabled) "mediacodec,mediacodec-copy,no" else "no"
            MPVLib.setPropertyString("hwdec", hwdecStr)
        }
    }

    fun setDebandingEnabled(enabled: Boolean) {
        pendingDeband = enabled
        if (isNativeReady) {
            if (enabled) {
                MPVLib.setPropertyString("deband", "yes")
                MPVLib.command("vf", "add", "@deband:gradfun=radius=12")
            } else {
                MPVLib.setPropertyString("deband", "no")
                MPVLib.command("vf", "remove", "@deband")
            }
        }
    }

    fun setAnime4K(mode: Anime4KManager.Mode, quality: Anime4KManager.Quality, manager: Anime4KManager) {
        pendingAnime4kMode = mode
        pendingAnime4kQuality = quality
        anime4kManagerInstance = manager
        /* Runtime shader mutation is disabled for this libmpv build: changing
           glsl-shaders tears down the active video output. Preferences are
           applied when the player is initialized next time. */
        if (false && isNativeReady) {
            try {
                // Use the runtime command API (glsl-shaders-set / glsl-shaders-clear). Writing
                // the glsl-shaders option via the property-string API after playback started is
                // a no-op on most libmpv builds — that's why toggling modes had no visible effect.
                if (mode == Anime4KManager.Mode.OFF) {
                    MPVLib.command("change-list", "glsl-shaders", "clr", "")
                    MPVLib.setPropertyString("glsl-shaders", "")
                    return
                }
                val chain = manager.getShaderChain(mode, quality)
                if (chain.isNotEmpty()) {
                    MPVLib.command("change-list", "glsl-shaders", "set", chain)
                    MPVLib.setPropertyString("glsl-shaders", chain)
                } else {
                    MPVLib.command("change-list", "glsl-shaders", "clr", "")
                    MPVLib.setPropertyString("glsl-shaders", "")
                }
            } catch (e: Exception) {
                Log.e("KinoMPVView", "Failed to apply Anime4K shaders", e)
                // Fallback to the option-string path; works on some builds.
                try {
                    val chain = if (mode == Anime4KManager.Mode.OFF) "" else manager.getShaderChain(mode, quality)
                    MPVLib.command("change-list", "glsl-shaders", "set", chain)
                    MPVLib.setPropertyString("glsl-shaders", chain)
                } catch (_: Exception) {}
            }
        }
    }

    fun setHeaders(headers: Map<String, String>) {
        pendingHeaders = headers
        if (isNativeReady && headers.isNotEmpty()) {
            val headersString = headers
                .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
                .joinToString(",")
            MPVLib.setPropertyString("http-header-fields", headersString)
        }
    }

    fun playStream(path: String) {
        pendingFile = path
        if (isNativeReady) {
            playFile(path)
        }
    }

    fun getTracks(type: String): List<MpvTrack> {
        if (!isNativeReady) return emptyList()
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val list = mutableListOf<MpvTrack>()
        for (i in 0 until count) {
            val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
            val t = MPVLib.getPropertyString("track-list/$i/type") ?: continue
            if (t != type) continue
            val lang = MPVLib.getPropertyString("track-list/$i/lang") ?: "unknown"
            val title = MPVLib.getPropertyString("track-list/$i/title") ?: "Track #$id"
            val selected = MPVLib.getPropertyBoolean("track-list/$i/selected") ?: false
            list.add(MpvTrack(id, t, lang, title, selected))
        }
        return list
    }

    fun selectTrack(type: String, id: Int) {
        if (!isNativeReady) return
        val propName = when (type) {
            "sub" -> "sid"
            "audio" -> "aid"
            "video" -> "vid"
            else -> return
        }
        if (id == -1) {
            MPVLib.setPropertyString(propName, "no")
        } else {
            MPVLib.setPropertyInt(propName, id)
        }
    }

    fun getVideoOutAspect(): Double? {
        if (!isNativeReady) return null
        val rawAspect = MPVLib.getPropertyDouble("video-params/aspect")
        val rotate = MPVLib.getPropertyInt("video-params/rotate") ?: 0
        val finalAspect = if (rawAspect == null || rawAspect < 0.001) {
            val width = MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
            val height = MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
            if (width > 0 && height > 0) {
                width.toDouble() / height.toDouble()
            } else {
                null
            }
        } else {
            rawAspect
        }

        return finalAspect?.let { aspect ->
            if (aspect <= 0.001) return null
            val isRotated = (rotate % 180 == 90)
            if (isRotated) 1.0 / aspect else aspect
        }
    }
}
