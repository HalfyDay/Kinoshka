package app.marlboroadvance.mpvex.ui.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import hd.kinoshka.app.databinding.PlayerLayoutBinding
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KManager
import app.marlboroadvance.mpvex.domain.anime4k.clearShaderChainRuntime
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.controls.PlayerControls
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import app.marlboroadvance.mpvex.utils.history.RecentlyPlayedOps
import app.marlboroadvance.mpvex.utils.media.HttpUtils
import app.marlboroadvance.mpvex.utils.media.SubtitleOps
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import app.marlboroadvance.mpvex.utils.storage.FileFilterUtils
import com.github.k1rakishou.fsaf.FileManager
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import hd.kinoshka.app.BuildConfig
import hd.kinoshka.app.data.api.ApiClient
import hd.kinoshka.app.data.download.toAnimeMediaStream
import hd.kinoshka.app.data.local.ShikimoriAuthStore
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.model.AnimeEpisode
import hd.kinoshka.app.data.model.AnimeMediaStream
import hd.kinoshka.app.data.model.AnimeSourceType
import hd.kinoshka.app.data.model.FlatTranslation
import hd.kinoshka.app.data.model.MovieSeriesPlaybackContext
import hd.kinoshka.app.data.model.MovieEpisodeRef
import hd.kinoshka.app.data.model.MovieStreamResult
import hd.kinoshka.app.data.model.NativePlaybackMode
import hd.kinoshka.app.data.model.PendingMovieRequestStore
import hd.kinoshka.app.data.model.QUALITY_PREFERENCE_DESC
import hd.kinoshka.app.data.playback.MovieNativeLauncher
import hd.kinoshka.app.data.diagnostics.AppDiagnostics
import hd.kinoshka.app.data.source.AnimeStreamResolver
import hd.kinoshka.app.data.source.DdbbStreamResolver
import hd.kinoshka.app.data.source.MovieStreamResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject
import java.io.File

/**
 * Main player activity that handles video playback using the MPV library.
 *
 * This activity manages:
 * - Video playback using MPV library
 * - System UI visibility (immersive mode)
 * - Audio focus management
 * - Picture-in-Picture (PiP) mode
 * - Background playback service
 * - MediaSession for external controls (Android Auto, Bluetooth, etc.)
 * - Playback state persistence and restoration
 * - Subtitle and audio track management
 * - Hardware key event handling
 *
 * @see PlayerViewModel for UI state management
 * @see MediaPlaybackService for background playback functionality
 */
@Suppress("TooManyFunctions", "LargeClass")
class PlayerActivity :
  AppCompatActivity(),
  PlayerHost {
  // ==================== ViewModels and Bindings ====================

  /**
   * View model for managing player UI state.
   */
  private val viewModel: PlayerViewModel by viewModels<PlayerViewModel> {
    PlayerViewModelProviderFactory(this)
  }

  /**
   * Binding for the player layout.
   */
  private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }

  /**
   * Observer for MPV events.
   */
  private val playerObserver by lazy { PlayerObserver(this) }

  // ==================== Dependency Injection ====================

  /**
   * Repository for managing playback state.
   */
  private val playbackStateRepository: PlaybackStateRepository by inject()

  /**
   * Repository for managing playlists.
   */
  private val playlistRepository: app.marlboroadvance.mpvex.database.repository.PlaylistRepository by inject()

  /**
   * Preferences for player settings.
   */
  private val playerPreferences: PlayerPreferences by inject()

  /**
   * Preferences for audio settings.
   */
  private val audioPreferences: AudioPreferences by inject()

  /**
   * Preferences for subtitle settings.
   */
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  /**
   * Preferences for advanced settings.
   */
  private val advancedPreferences: AdvancedPreferences by inject()

  /**
   * Preferences for browser settings.
   */
  private val browserPreferences: BrowserPreferences by inject()

  /**
   * Manager for file operations.
   */
  private val fileManager: FileManager by inject()

  /**
   * Preferences for decoder settings.
   */
  private val decoderPreferences: app.marlboroadvance.mpvex.preferences.DecoderPreferences by inject()

  /**
   * Anime4K shader manager.
   */
  private val anime4kManager: Anime4KManager by inject()

  /**
   * Track selector for automatic audio/subtitle selection
   */
  private val trackSelector: TrackSelector by lazy {
    TrackSelector(audioPreferences, subtitlesPreferences)
  }

  /**
   * Monitors shader-induced frame drops and auto-downgrades quality.
   */
  private val shaderPerformanceMonitor by lazy {
    ShaderPerformanceMonitor(
      decoderPreferences = decoderPreferences,
      anime4kManager = anime4kManager,
      onWarning = { msg -> viewModel.setShaderWarning(msg) },
      onPermanentWarning = { msg -> viewModel.setShaderWarningPermanent(msg) },
    )
  }

  // ==================== Views ====================

  /**
   * The MPV player view.
   */
  val player by lazy { binding.player }

  // ==================== State Management ====================

  /**
   * Current video file name being played.
   */
  private var fileName = ""

  /**
   * Unique identifier for the current media, used for saving/loading playback state.
   * For network streams, this includes a hash of the URI to ensure uniqueness.
   */
  private var mediaIdentifier = ""

  /**
   * Playlist of URIs for sequential playback
   */
  internal var playlist: List<Uri> = emptyList()

  /**
   * Current index in the playlist
   */
  internal var playlistIndex: Int = 0

  /**
   * Shuffled order of playlist indices (when shuffle is enabled)
   */
  private var shuffledIndices: List<Int> = emptyList()

  /**
   * Current position in shuffled playlist (when shuffle is enabled)
   */
  private var shuffledPosition: Int = 0

  /**
   * Playlist ID for tracking play history (optional, only for custom playlists)
   */
  private var playlistId: Int? = null

  /**
   * Tracks the starting offset of the loaded playlist window in the full playlist.
   * Used for windowed loading to prevent ANR with large playlists.
   */
  private var playlistWindowOffset: Int = 0

  /**
   * Total count of items in the full playlist (when using windowed loading).
   * -1 means unknown or not using windowed loading.
   */
  var playlistTotalCount: Int = -1
    private set

  /**
   * Indicates whether the current playlist is an M3U playlist sourced from database.
   * Used to skip thumbnail/metadata extraction for network streams.
   */
  private var isM3uPlaylist: Boolean = false

  /**
   * Helper for managing Picture-in-Picture mode.
   */
  private lateinit var pipHelper: MPVPipHelper

  private var isReady = false // Single flag: true when video loaded and ready
  private var isUserFinishing = false
  private var isManualBackgroundPlayback = false // Track manual background playback trigger
  private var noisyReceiverRegistered = false
  private var mpvInitialized = false // Track MPV initialization state

  // mpv core quit itself ("event: shutdown") while this activity is still showing. After that
  // the handle accepts property writes but silently eats every loadfile — retries, fallbacks
  // and «Повторить» all spin forever. Lazy recovery: the next loadfile rebuilds the core first.
  @Volatile private var mpvCoreDead = false
  private val mpvReinitLock = Any()
  private var savePlaybackStateJob: kotlinx.coroutines.Job? = null // Track ongoing save job
  // Saves must not live on lifecycleScope: androidx dispatches ON_DESTROY from
  // onActivityPreDestroyed, so lifecycleScope is already cancelled when onDestroy's join
  // runs and the final save dies with JobCancellationException, losing the resume position.
  private val playbackStateSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var wasPlayingBeforePause = false // Track if video was playing before pause
  private var pendingSeekPosition: Double? = null // Track position to seek back to after quality change
  private var currentAnimeSourceType: AnimeSourceType = AnimeSourceType.KODIK
  private var currentAnimeStream: AnimeMediaStream? = null
  private var movieSeriesContext: MovieSeriesPlaybackContext? = null

  // PENDING_MOVIE launches stay "pending" in the intent forever; once the background resolve
  // succeeds the activity effectively plays as QUALITY_ONLY_MOVIE or MOVIE_SERIES, and progress
  // commits must follow the resolved mode rather than the launch marker.
  private var effectiveNativePlaybackMode: NativePlaybackMode? = null

  // Stream-loading overlay lifecycle: episode/dub switches resolve almost instantly for direct
  // sources, so clearing the flag right after `loadfile` flashed the indicator for ~50ms while
  // mpv still buffered for seconds. The overlay now stays up until MPV_EVENT_FILE_LOADED.
  private var pendingStreamLoadIndicator = false
  private var streamLoadIndicatorTimeoutJob: kotlinx.coroutines.Job? = null
  private var nextEpisodeCountdownJob: kotlinx.coroutines.Job? = null

  // Auto-quality watchdog: while the quality selector sits on "Auto", poll mpv's demuxer cache;
  // sustained stalls step the stream down the quality ladder (best-first) preserving position.
  private var qualityWatchdogJob: kotlinx.coroutines.Job? = null
  private var currentPlayingUrl: String? = null
  private var autoStallStrikes = 0

  // Бесшовная смена качества (video-add + swap): url рунга, ожидающего закрепления. Любая
  // полная загрузка файла ([mpvLoadFile] replace) его сбрасывает — незавершённое переключение
  // не должно «прилипать» к следующей серии/озвучке.
  private var pendingSeamlessQualityUrl: String? = null
  private var seamlessSwitchJob: kotlinx.coroutines.Job? = null

  // QOM (QUALITY_ONLY_MOVIE) active-voiceover state: url/headers/ladder of THE DUB CURRENTLY
  // PLAYING. The launch-time stream (currentAnimeStream) belongs to whichever voiceover won
  // startup — reading its ladder on a quality switch used to reload the ORIGINAL dub and
  // silently reset the user's voiceover choice.
  private var qomActiveStream: AnimeMediaStream? = null

  // Failed-loadfile recovery: mpv reports dead urls via MPV_EVENT_END_FILE(reason=error) while
  // the loading overlay is up. The action re-does the load (fresh resolve); after the retry
  // budget is spent the error card takes over instead of a silent black screen.
  private var streamLoadRetryAction: (() -> Unit)? = null
  private var streamLoadRetries = 0

  /** Last tracked load, kept for the manual «Повторить» on the error card. */
  private var lastStreamLoadRetry: (() -> Unit)? = null

  // Automatic cross-source recovery for QOM movies: when the active dub's stream keeps failing
  // (mpv END_FILE errors) or stalling (slow-start timeouts), the fallback walks the remaining
  // dub rows — a DIFFERENT provider first, since another row of the same dead CDN rarely
  // behaves better. Manual picks clear the memory; a chain never revisits a row it tried.
  private val autoFallbackTriedIds = java.util.concurrent.CopyOnWriteArraySet<String>()

  // One automatic Auto-rung step-down per recovery chain: once a stepped rung also times out,
  // a lower rung of the same dead CDN is no longer the best move — switch sources instead.
  private var autoSlowStartStepped = false

  // Segment-skip guard: when the HLS demuxer gives up on dead segments (vpn tunnel flapping,
  // live log kp=5437614 segments 379-387), mpv jumps playback FORWARD past the skipped content.
  // The guard remembers the last played position and pulls playback back once the stream moves
  // again, so the movie resumes where it froze instead of half a minute ahead.
  private var segmentSkipGuardJob: kotlinx.coroutines.Job? = null

  // Real-playback library commit: only ≥5 minutes of viewing turns a title into "Смотрю".
  private var playbackProgressJob: kotlinx.coroutines.Job? = null
  private var watchingCommittedFor: String? = null

  /**
   * Thermal and performance monitoring
   */
  private val thermalStatusListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    PowerManager.OnThermalStatusChangedListener { status ->
      val warning = when (status) {
        PowerManager.THERMAL_STATUS_MODERATE -> "Устройство нагревается"
        PowerManager.THERMAL_STATUS_SEVERE -> "Устройство сильно нагрелось"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Критический перегрев! Снизьте нагрузку"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Аварийный перегрев!"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Устройство выключается из-за перегрева!"
        else -> null
      }
      viewModel.setThermalWarning(warning)

      if (status >= PowerManager.THERMAL_STATUS_SEVERE && decoderPreferences.enableAnime4K.get()) {
        val currentMode = decoderPreferences.anime4kMode.get()
        if (currentMode != "OFF") {
          anime4kManager.clearShaderChainRuntime()
          decoderPreferences.anime4kMode.set("OFF")
          viewModel.setShaderWarningPermanent("Anime4K отключён из-за перегрева устройства.")
        }
      }
    }
  } else null

  private var batteryTempJob: Job? = null

  private fun startBatteryTempMonitoring() {
    if (batteryTempJob != null) return
    batteryTempJob = lifecycleScope.launch(Dispatchers.IO) {
      while (isActive) {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val celsius = temp / 10f
        
        if (celsius >= 45f) {
          withContext(Dispatchers.Main) {
            viewModel.setThermalWarning("Батарея перегрета: ${celsius}°C")
            if (celsius >= 48f && decoderPreferences.enableAnime4K.get()) {
              val currentMode = decoderPreferences.anime4kMode.get()
              if (currentMode != "OFF") {
                anime4kManager.clearShaderChainRuntime()
                decoderPreferences.anime4kMode.set("OFF")
                viewModel.setShaderWarningPermanent("Anime4K отключён из-за перегрева батареи.")
              }
            }
          }
        }
        delay(10000) // Every 10 seconds
      }
    }
  }

  private fun stopBatteryTempMonitoring() {
    batteryTempJob?.cancel()
    batteryTempJob = null
  }

  private var lastDropCount = 0L
  private var lastDropTime = 0L

  private fun checkLag(currentDropCount: Long) {
    val now = System.currentTimeMillis()
    if (lastDropTime > 0) {
      val diff = currentDropCount - lastDropCount
      val timeDiff = now - lastDropTime
      if (timeDiff >= 2000 && diff > 15) { // More than 15 frames per 2 seconds dropped
        viewModel.setLagWarning("Плеер перегружен: пропущено кадров $diff за 2 с")
        if (decoderPreferences.enableAnime4K.get()) {
          viewModel.setShaderWarning("Шейдеры создают нагрузку, снижаю качество")
        }
      }
    }
    lastDropCount = currentDropCount
    lastDropTime = now

    // Also feed the shader performance monitor so it can auto-downgrade/disable
    shaderPerformanceMonitor.checkPerformance(currentDropCount)
  }

  // ==================== Background Playback ====================

  /**
   * Reference to the background playback service.
   */
  private var mediaPlaybackService: MediaPlaybackService? = null

  /**
   * Tracks whether we're currently bound to the background playback service.
   */
  private var serviceBound = false

  // ==================== MediaSession ====================

  /**
   * MediaSession for integration with system media controls, Android Auto, and Wear OS.
   */
  private lateinit var mediaSession: MediaSession

  /**
   * Tracks whether MediaSession has been successfully initialized.
   */
  private var mediaSessionInitialized = false

  /**
   * Builder for MediaSession playback states.
   */
  private lateinit var playbackStateBuilder: PlaybackState.Builder

  // ==================== Audio Focus ====================

  /**
   * Audio focus request for API 26+.
   */
  private var audioFocusRequest: AudioFocusRequest? = null

  /**
   * Callback to restore audio focus after it's been lost and regained.
   */
  private var restoreAudioFocus: () -> Unit = {}

  // ==================== Broadcast Receivers ====================

  /**
   * Receiver for handling noisy audio events.
   */
  private val noisyReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
          viewModel.pause()
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      }
    }

  /**
   * Listener for audio focus changes.
   */
  private val audioFocusChangeListener =
    AudioManager.OnAudioFocusChangeListener { focusChange ->
      when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
          -> {
          // Save current state to restore later
          val oldRestore = restoreAudioFocus
          val wasPlayerPaused = viewModel.paused ?: false
          viewModel.pause()
          restoreAudioFocus = {
            oldRestore()
            if (!wasPlayerPaused) viewModel.unpause()
          }
        }

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
          // Lower volume temporarily
          MPVLib.command("multiply", "volume", "0.5")
          restoreAudioFocus = {
            MPVLib.command("multiply", "volume", "2")
          }
        }

        AudioManager.AUDIOFOCUS_GAIN -> {
          // Restore previous audio state
          restoreAudioFocus()
          restoreAudioFocus = {}
        }

        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
          Log.d(TAG, "Audio focus request failed")
        }
      }
    }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContentView(binding.root)

    // OPTIMIZATION: Set volume control stream so hardware buttons control media volume
    volumeControlStream = AudioManager.STREAM_MUSIC

    setupMPV()
    MediaPlaybackService.createNotificationChannel(this)
    setupAudio()
    setupBackPressHandler()
    setupPlayerControls()
    setupPipHelper()
    setupMediaSession()

    setAnimeExtras(intent.extras)
    setMovieSeriesExtras(intent.extras)
    setQualityOnlyMovieExtras(intent.extras)
    setPendingMovieExtras(intent.extras)
    startPlaybackProgressLoop()

    playlistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
    playlistIndex = intent.getIntExtra("playlist_index", 0)

    // Load playlist from intent extras first (fast path - backward compatibility)
    playlist = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    // If playlist is empty but playlist_id is provided, load asynchronously from database
    // Load all items - LazyColumn handles pagination/virtualization efficiently
    if (playlist.isEmpty() && playlistId != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        val pid = playlistId ?: return@launch
        try {
          // Check if this is an M3U playlist
          val playlistEntity = playlistRepository.getPlaylistById(pid)
          isM3uPlaylist = playlistEntity?.isM3uPlaylist ?: false

          // Load all items - LazyColumn will handle virtualization/pagination efficiently
          val items = playlistRepository.getPlaylistItemsAsUris(pid)
          val totalCount = items.size

          withContext(Dispatchers.Main) {
            playlist = items
            playlistWindowOffset = 0
            playlistTotalCount = totalCount
            Log.d(TAG, "Loaded all $totalCount items from playlist $pid (isM3U: $isM3uPlaylist)")
            // Re-initialize shuffle now that playlist is available
            if (viewModel.shuffleEnabled.value) {
              onShuffleToggled(true)
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load playlist from database", e)
        }
      }
    }

    // Only auto-generate playlist from folder if playlist mode is enabled and no playlist_id
    if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get()) {
      val path = parsePathFromIntent(intent)
      if (path != null) {
        generatePlaylistFromFolder(path)
      }
    }

    // Extract fileName early so it's available when video loads
    fileName = getFileName(intent)
    if (fileName.isBlank()) {
      fileName = intent.data?.lastPathSegment ?: "Unknown Video"
    }
    mediaIdentifier = getMediaIdentifier(intent, fileName)

    // Set network options and headers before the demuxer opens the stream.
    if (intent.getBooleanExtra("vod_stream", false)) {
      // VOD-трейлеры (HLS КП/Rutube): reconnect_streamed зацикливает конечные chunked-плейлисты —
      // естественный EOF трактуется как обрыв, демuxер вечно переподключается и «не находит поток».
      MPVLib.setPropertyString("demuxer-lavf-o", "")
      Log.d(TAG, "VOD stream transport: default lavf options (no reconnect hardening)")
    } else {
      applyAnimeTransportOptions(intent.getBooleanExtra("anime_disable_http_reuse", false))
    }
    setHttpHeadersFromExtras(intent.extras)

    // Manual «Повторить» on the error card re-issues the last tracked stream load and grants
    // a fresh auto-retry budget.
    viewModel.onStreamLoadRetry = {
      resetStreamLoadRetries()
      lastStreamLoadRetry?.invoke()
    }

    getPlayableUri(intent)?.let { playableUri ->
      when (currentNativePlaybackMode()) {
        NativePlaybackMode.QUALITY_ONLY_MOVIE,
        NativePlaybackMode.MOVIE_SERIES,
        -> {
          resetStreamLoadRetries()
          beginTrackedStreamLoad(retry = { player.playFile(playableUri) })
        }
        else -> Unit
      }
      player.playFile(playableUri)
    }

    // Set orientation immediately on launch (defaults to landscape for Video mode)
    setOrientation()

    // Apply persisted shuffle state after playlist is loaded
    viewModel.applyPersistedShuffleState()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val powerManager = getSystemService(POWER_SERVICE) as PowerManager
      thermalStatusListener?.let { powerManager.addThermalStatusListener(it) }
    }
    startBatteryTempMonitoring()

    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
  }

  override fun attachBaseContext(newBase: Context?) {
    if (newBase == null) {
      super.attachBaseContext(null)
      return
    }

    val originalConfiguration = newBase.resources.configuration
    val contextToUse =
      if (originalConfiguration.fontScale == 1f) {
        newBase
      } else {
        val updatedConfiguration = Configuration(originalConfiguration).apply { fontScale = 1f }
        val configurationContext = newBase.createConfigurationContext(updatedConfiguration)
        val configurationDisplayMetrics = configurationContext.resources.displayMetrics
        @Suppress("DEPRECATION") // фиксация масштаба шрифтов для плеера; замены поля нет
        configurationDisplayMetrics.scaledDensity = updatedConfiguration.fontScale * configurationDisplayMetrics.density
        configurationContext
      }

    super.attachBaseContext(contextToUse)
  }

  private fun setupBackPressHandler() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        @RequiresApi(Build.VERSION_CODES.P)
        override fun handleOnBackPressed() {
          handleBackPress()
        }
      },
    )
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun handleBackPress() {
    // Dismiss overlays first
    if (viewModel.sheetShown.value != Sheets.None) {
      viewModel.sheetShown.update { Sheets.None }
      viewModel.showControls()
      return
    }

    if (viewModel.panelShown.value != Panels.None) {
      viewModel.panelShown.update { Panels.None }
      viewModel.showControls()
      return
    }

    // Check if auto PIP is enabled - enter PIP mode instead of finishing
    if (playerPreferences.autoPiPOnNavigation.get() && isReady) {
      pipHelper.enterPipMode()
      return
    }

    isUserFinishing = true
    finish()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun setupPlayerControls() {
    binding.controls.setContent {
      MpvexTheme {
        PlayerControls(
          viewModel = viewModel,
          onBackPress = {
            isUserFinishing = true
            finish()
          },
          modifier = Modifier,
        )
      }
    }
  }

  /**
   * Initializes the Picture-in-Picture helper.
   */
  private fun setupPipHelper() {
    pipHelper = MPVPipHelper(activity = this, mpvView = player)
  }

  private fun setupAudio() {
    audioPreferences.audioChannels.get().let {
      runCatching {
        MPVLib.setPropertyString(it.property, it.value)
      }.onFailure { e ->
        Log.e(TAG, "Error setting audio channels: ${it.property}=${it.value}", e)
      }
    }

    if (!serviceBound) {
      audioFocusRequest =
        AudioFocusRequest
          .Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(
            AudioAttributes
              .Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
              .build(),
          ).setOnAudioFocusChangeListener(audioFocusChangeListener)
          .setAcceptsDelayedFocusGain(true)
          .setWillPauseWhenDucked(true)
          .build()
      requestAudioFocus()
    }
  }

  /**
   * @return true if audio focus was granted immediately, false otherwise
   */
  override fun requestAudioFocus(): Boolean {
    val req = audioFocusRequest ?: return false
    val result = audioManager.requestAudioFocus(req)
    return when (result) {
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
        restoreAudioFocus = {}
        true
      }

      AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
        restoreAudioFocus = { requestAudioFocus() }
        false
      }

      else -> {
        restoreAudioFocus = {}
        false
      }
    }
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    // Enter PIP mode when user presses home button if auto PIP is enabled
    if (playerPreferences.autoPiPOnNavigation.get() && isReady && !isFinishing) {
      pipHelper.enterPipMode()
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onDestroy() {
    Log.d(TAG, "PlayerActivity onDestroy")

    runCatching {
      // OPTIMIZATION: Prevent any further UI updates or callbacks
      isReady = false

      // Only stop the service if we're not doing manual background playback
      if ((isUserFinishing || isFinishing) && !isManualBackgroundPlayback) {
        if (serviceBound) {
          runCatching { unbindService(serviceConnection) }
          serviceBound = false
        }
        stopService(Intent(this, MediaPlaybackService::class.java))
        mediaPlaybackService = null
      }

      // Wait for any pending save operation to complete before destroying MPV
      // This prevents the race condition where the save coroutine tries to access
      // MPV properties after MPVLib.destroy() has been called
      savePlaybackStateJob?.let { job ->
        Log.d(TAG, "Waiting for save playback state job to complete...")
        runCatching {
          // Use runBlocking to ensure we wait for the job to finish
          // This is safe here as onDestroy is already on the main thread
          kotlinx.coroutines.runBlocking {
            job.join()
          }
        }
        Log.d(TAG, "Save playback state job completed")
      }
      playbackStateSaveScope.cancel()

      cleanupMPV()
      cleanupAudio()
      cleanupReceivers()
      releaseMediaSession()

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        thermalStatusListener?.let { powerManager?.removeThermalStatusListener(it) }
      }
      stopBatteryTempMonitoring()
    }.onFailure { e ->
      Log.e(TAG, "Error during onDestroy", e)
    }

    super.onDestroy()
  }

  private fun cleanupMPV() {
    if (!mpvInitialized) return

    player.isExiting = true

    // Stop media notification service when activity is destroyed
    endBackgroundPlayback()

    // Don't cleanup MPV if we're doing manual background playback
    if (!isFinishing || isManualBackgroundPlayback) return

    runCatching {
      MPVLib.removeObserver(playerObserver)

      if (isReady) {
        // Pause playback first to reduce thread activity
        MPVLib.setPropertyBoolean("pause", true)

        // Send quit command to gracefully shut down MPV
        MPVLib.command("quit")

        // Wait briefly for MPV to process quit and clean up internal threads
        // This prevents race conditions where hardware UI threads try to access
        // mutexes/queues that are destroyed by MPVLib.destroy()
        // We use a short blocking wait here as onDestroy is already on the main thread
        // and this ensures proper cleanup before activity destruction
        Thread.sleep(100)
      }

      // Now safe to destroy MPV as internal threads have had time to shut down
      MPVLib.destroy()
      mpvInitialized = false
    }.onFailure { e ->
      Log.e(TAG, "Error cleaning up MPV", e)
    }
  }

  override fun abandonAudioFocus() {
    if (restoreAudioFocus != {}) {
      audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
      restoreAudioFocus = {}
    }
  }

  private fun cleanupAudio() {
    abandonAudioFocus()
  }

  private fun cleanupReceivers() {
    if (noisyReceiverRegistered) {
      runCatching {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onPause() {
    runCatching {
      val isInPip = isInPictureInPictureMode
      val shouldPause = (!audioPreferences.automaticBackgroundPlayback.get() && !isManualBackgroundPlayback) || 
                        (isUserFinishing && !isManualBackgroundPlayback)

      // OPTIMIZATION: Stop playback immediately if finishing to reduce cleanup overhead
      if (isFinishing && !isManualBackgroundPlayback) {
        viewModel.pause()
        // Tell MPV to stop processing to reduce busywork during cleanup
        MPVLib.command("stop")
      } else if (!isInPip && shouldPause) {
        wasPlayingBeforePause = !(viewModel.paused ?: true)
        viewModel.pause()
      }

      // Restore UI immediately when user is finishing for instant feedback
      if (isUserFinishing && !isInPip && !isManualBackgroundPlayback) {
        restoreSystemUI()
      }

      // OPTIMIZATION: Only save if not finishing (onDestroy will handle final save)
      if (!isFinishing) {
        saveVideoPlaybackState(fileName)
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onPause", e)
    }

    super.onPause()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun finish() {
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      
      // Clean up service when finishing
      if (serviceBound || mediaPlaybackService != null) {
        endBackgroundPlayback()
      }
      
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finish", e)
    }

    super.finish()
  }

  // finishAndRemoveTask() was added in API 21, but since our minSdk is 26, it's always available
  override fun finishAndRemoveTask() {
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      isUserFinishing = true
      
      // Clean up service when finishing
      if (serviceBound || mediaPlaybackService != null) {
        endBackgroundPlayback()
      }
      
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finishAndRemoveTask", e)
    }

    super.finishAndRemoveTask()
  }

  override fun onStop() {
    runCatching {
      pipHelper.onStop()
      saveVideoPlaybackState(fileName)

      if (noisyReceiverRegistered) {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }

      // Handle background playback based on preferences
      val shouldAllowBackgroundPlayback = isManualBackgroundPlayback || 
                                          audioPreferences.automaticBackgroundPlayback.get()
      
      // Pause playback if background playback is not enabled and user is finishing
      if (!shouldAllowBackgroundPlayback && (isUserFinishing || isFinishing)) {
        viewModel.pause()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onStop", e)
    }

    super.onStop()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onStart() {
    super.onStart()

    runCatching {
      setupWindowFlags()
      setupSystemUI()

      if (!noisyReceiverRegistered) {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
        noisyReceiverRegistered = true
      }

      if (playerPreferences.rememberBrightness.get()) {
        val brightness = playerPreferences.defaultBrightness.get()
        if (brightness != BRIGHTNESS_NOT_SET) {
          viewModel.changeBrightnessTo(brightness)
        }
      }
      
      // Reset manual background playback flag when returning to foreground
      isManualBackgroundPlayback = false
    }.onFailure { e ->
      Log.e(TAG, "Error during onStart", e)
    }
  }

  private fun setupWindowFlags() {
    pipHelper.updatePictureInPictureParams()
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setFlags(
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun setupSystemUI() {
    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

    // Set status bar color for when it will be shown (with controls)
    if (playerPreferences.showSystemStatusBar.get()) {
      @Suppress("DEPRECATION") // setter игнорируется на API 35+, но нужен для старых версий
      window.statusBarColor = android.graphics.Color.parseColor("#80000000") // Semi-transparent black
    }

    // Always start with status bar hidden - it will show when controls are shown
    try {
      windowInsetsController.apply {
        hide(WindowInsetsCompat.Type.statusBars())
        hide(WindowInsetsCompat.Type.navigationBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to setup system UI insets", e)
    }

    // Don't use LOW_PROFILE if we plan to show status bar with controls
    // LOW_PROFILE causes only icons to show without background
    @Suppress("DEPRECATION")
    binding.root.systemUiVisibility =
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        if (playerPreferences.showSystemStatusBar.get()) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun restoreSystemUI() {
    // Clear flags first for immediate effect
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Set cutout mode before showing bars for smoother transition
    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT

    // Update window insets configuration
    WindowCompat.setDecorFitsSystemWindows(window, true)

    // Restore default behavior and show bars in one go
    try {
      windowInsetsController.apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to restore system UI insets", e)
    }
  }

  /**
   * Initializes the MPV player with the necessary paths and observers.
   */
  private fun setupMPV() {
    // Copy essential files FIRST, before MPV initialization
    runCatching {
      Utils.copyAssets(this@PlayerActivity)
      syncFromUserMpvDirectory()
      Log.d(TAG, "MPV config and scripts prepared successfully")
    }.onFailure { e ->
      Log.e(TAG, "Error copying MPV config and scripts", e)
    }

    // NOW initialize MPV - it will find and load the scripts we just copied
    player.initialize(filesDir.path, cacheDir.path)
    mpvInitialized = true
    Log.d(TAG, "MPV initialized")

    // Add observer after initialization
    MPVLib.addObserver(playerObserver)
  }

  /**
   * mpv core died while the activity is alive (live log kp=5437614, warp: a loadfile that 404s
   * with an empty playlist terminated the whole core — "event: shutdown" — and every later
   * loadfile/auto-retry/cross-source fallback silently no-op'd for the rest of the session;
   * only reopening the player helped). The prebuilt libplayer.so cannot be patched, so
   * recovery is lazy: [mpvLoadFile] rebuilds the core right before the next real load.
   */
  private fun onMpvCoreShutdown() {
    if (isFinishing || isDestroyed || isManualBackgroundPlayback) return
    viewModel.setPropertyPollingEnabled(false)
    if (mpvCoreDead) return
    mpvCoreDead = true
    Log.w(TAG, "MPV core shutdown while activity is alive — next loadfile will re-init the player")
    AppDiagnostics.event("MPV core shutdown while activity alive")
  }

  /** Destroys the dead handle and re-runs the full [setupMPV] sequence on the same activity. */
  private fun reinitMpvCore() {
    synchronized(mpvReinitLock) {
      if (!mpvCoreDead || isFinishing || isDestroyed) return
      mpvCoreDead = false
      Log.w(TAG, "Re-initializing MPV core after shutdown")
      runCatching {
        MPVLib.removeObserver(playerObserver)
        MPVLib.destroy()
      }.onFailure { e -> Log.e(TAG, "MPV destroy during re-init failed", e) }
      mpvInitialized = false
      runCatching {
        setupMPV()
        // The SurfaceView's surfaceCreated fired long ago — the fresh core knows no surface
        // until the current one is handed over explicitly (the same attach surfaceCreated does).
        player.holder?.surface?.takeIf { it.isValid }?.let { MPVLib.attachSurface(it) }
        applyAnimeTransportOptions(disableHttpReuse = false)
        (qomActiveStream ?: currentAnimeStream)?.let { applyHttpHeaders(it.headers) }
        Log.i(TAG, "MPV core re-initialized after shutdown")
        AppDiagnostics.event("MPV core re-initialized")
      }.onFailure { e ->
        mpvCoreDead = true
        Log.e(TAG, "MPV re-init failed", e)
      }
    }
  }

  /**
   * Single loadfile entry point: a dead mpv core (post-shutdown zombie) accepts the command
   * and does nothing with it, so every load goes through here — the core is rebuilt first
   * when it died while the activity is still alive.
   */
  private fun mpvLoadFile(vararg args: String) {
    // Полная загрузка файла сбрасывает незавершённое бесшовное переключение качества; сам
    // video-add идёт мимо этой функции прямым MPVLib.command.
    pendingSeamlessQualityUrl = null
    if (mpvCoreDead) reinitMpvCore()
    AppDiagnostics.event("loadfile: ${args.firstOrNull()?.take(160) ?: "?"}")
    MPVLib.command("loadfile", *args)
  }

  /**
   * Syncs ALL MPV assets from the user's configured MPV directory to internal storage.
   * Handles: mpv.conf, input.conf, scripts/, script-opts/, shaders/, fonts/
   *
   * Uses case-insensitive subfolder matching and falls back to root scanning
   * if standard subfolders don't exist. Falls back to preferences-based config
   * if no user directory is configured.
   */
  private fun syncFromUserMpvDirectory() {
    val mpvConfStorageUri = advancedPreferences.mpvConfStorageUri.get()

    // Try to open the user's MPV directory
    val tree = if (mpvConfStorageUri.isNotBlank()) {
      runCatching {
        DocumentFile.fromTreeUri(this, mpvConfStorageUri.toUri())
      }.getOrNull()?.takeIf { it.exists() && it.canRead() }
    } else null

    if (tree != null) {
      Log.d(TAG, "Syncing from user MPV directory: ${tree.uri}")
      syncConfigFiles(tree)
      syncFonts(tree)
      Log.d(TAG, "Full MPV directory sync completed")
    } else {
      // Fallback: use preferences-based config (no user directory set)
      Log.d(TAG, "No MPV directory configured, using preferences fallback")
      copyMPVConfigFromPreferences()
    }
  }

  // ==================== Config Files Sync ====================

  /**
   * Syncs mpv.conf and input.conf from the user's MPV directory.
   * Also caches the content in preferences for the config editor.
   */
  private fun syncConfigFiles(tree: DocumentFile) {
    for (configName in listOf("mpv.conf", "input.conf")) {
      runCatching {
        val configFile = findFileCaseInsensitive(tree, configName)
        if (configFile != null && configFile.exists() && configFile.canRead()) {
          contentResolver.openInputStream(configFile.uri)?.use { input ->
            val content = input.bufferedReader().readText()
            File(filesDir, configName).writeText(content)
            // Cache in preferences for the config editor
            when (configName) {
              "mpv.conf" -> advancedPreferences.mpvConf.set(content)
              "input.conf" -> advancedPreferences.inputConf.set(content)
            }
            Log.d(TAG, "Synced config: $configName (${content.length} chars)")
          }
        } else {
          // Config not in directory, fall back to preferences
          val prefContent = when (configName) {
            "mpv.conf" -> advancedPreferences.mpvConf.get()
            "input.conf" -> advancedPreferences.inputConf.get()
            else -> ""
          }
          File(filesDir, configName).apply {
            if (!exists()) createNewFile()
            if (prefContent.isNotBlank()) writeText(prefContent)
          }
          Log.d(TAG, "Config not found in directory, used preferences: $configName")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing config: $configName", e)
      }
    }
  }

  // ==================== Fonts Sync ====================

  /**
   * Syncs font files (.ttf, .otf, .ttc, .woff, .woff2) from the user's MPV directory.
   * Looks in fonts/ subfolder first (case-insensitive), falls back to root.
   * Also syncs from the subtitle preferences font folder if set.
   */
  private fun syncFonts(tree: DocumentFile) {
    val internalFontsDir = File(filesDir, "fonts")
    internalFontsDir.mkdirs()

    val fontsSubdir = findSubdirCaseInsensitive(tree, "fonts")
    val sourceDir = fontsSubdir ?: tree
    val fontExtensions = setOf("ttf", "otf", "ttc", "woff", "woff2")
    var count = 0

    sourceDir.listFiles().forEach { file ->
      if (!file.isFile) return@forEach
      val name = file.name ?: return@forEach
      val ext = name.substringAfterLast('.', "").lowercase()
      if (ext !in fontExtensions) return@forEach

      val target = File(internalFontsDir, name)
      // Skip if font already exists (fonts can be large)
      if (target.exists()) return@forEach

      runCatching {
        contentResolver.openInputStream(file.uri)?.use { input ->
          target.outputStream().use { output ->
            input.copyTo(output)
          }
          count++
          Log.d(TAG, "Synced font: $name")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing font: $name", e)
      }
    }

    // Also sync from subtitle preferences font folder if set
    runCatching {
      val fontsFolderUri = subtitlesPreferences.fontsFolder.get()
      if (fontsFolderUri.isNotBlank()) {
        val destDir = fileManager.fromPath("${filesDir.path}/fonts")
        if (!fileManager.exists(destDir)) {
          fileManager.createDir(fileManager.fromPath(filesDir.path), "fonts")
        }
        val fontsDir = fileManager.fromUri(fontsFolderUri.toUri())
        if (fontsDir != null && fileManager.exists(fontsDir)) {
          fileManager.copyDirectoryWithContent(fontsDir, destDir, false)
        }
      }
    }.onFailure { e ->
      Log.e(TAG, "Error syncing subtitle fonts: ${e.message}")
    }

    Log.d(TAG, "Fonts sync: $count file(s) from MPV directory")
  }

  // ==================== Helpers ====================

  /**
   * Fallback: copies config from preferences when no user MPV directory is set.
   */
  private fun copyMPVConfigFromPreferences() {
    runCatching {
      File(filesDir, "mpv.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.mpvConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      File(filesDir, "input.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.inputConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      // Ensure fonts directory exists even without user dir
      File(filesDir, "fonts").mkdirs()
    }.onFailure { e ->
      Log.e(TAG, "Error creating fallback config files", e)
    }
  }

  /**
   * Finds a subdirectory by name (case-insensitive) within a DocumentFile.
   */
  private fun findSubdirCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? =
    parent.listFiles().firstOrNull {
      it.isDirectory && it.name?.equals(name, ignoreCase = true) == true
    }

  /**
   * Finds a file by name (case-insensitive) within a DocumentFile.
   */
  private fun findFileCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? =
    parent.listFiles().firstOrNull {
      it.isFile && it.name?.equals(name, ignoreCase = true) == true
    }

  override fun onResume() {
    super.onResume()
    updateVolume()
  }

  /**
   * Updates the volume level to match the system volume.
   *
   * This method updates the current volume level by getting the current system volume
   * and adjusting the MPV volume accordingly. It ensures that the MPV volume is set
   * to the maximum allowed value if the system volume is lower than the maximum.
   */
  private fun updateVolume() {
    viewModel.currentVolume.update {
      audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).also { volume ->
        if (volume < viewModel.maxVolume) {
          viewModel.changeMPVVolumeTo(MAX_MPV_VOLUME)
        }
      }
    }
  }

  /**
   * Processes intent extras that must be applied after a file is loaded.
   *
   * This method checks the intent extras for the following keys:
   * - "position": The initial playback position in seconds.
   * - "subs": A list of subtitle URIs to add.
   * - "subs.enable": A list of subtitle URIs to enable.
   *
   * @param extras Bundle containing intent extras
   */
  private fun setIntentExtras(extras: Bundle?) {
    if (extras == null) return

    extras.getInt("position", POSITION_NOT_SET).takeIf { it != POSITION_NOT_SET }?.let {
      noteUserSeek()
      MPVLib.setPropertyInt("time-pos", it / MILLISECONDS_TO_SECONDS)
    }

    addSubtitlesFromExtras(extras)
  }

  private fun setAnimeExtras(extras: Bundle?) {
    if (extras == null) return
    val episodesJson = extras.getString("anime_episodes")
    val translationsJson = extras.getString("anime_translations")
    val qualitiesJson = extras.getString("anime_qualities")
    val currentEp = extras.getInt("anime_current_episode", -1).takeIf { it != -1 }
    val currentTr = extras.getString("anime_current_translation_id")
    val currentQ = extras.getString("anime_current_quality") ?: "Auto"
    currentAnimeSourceType = try {
      AnimeSourceType.valueOf(extras.getString("anime_source_type") ?: AnimeSourceType.KODIK.name)
    } catch (e: Exception) {
      AnimeSourceType.KODIK
    }

    val episodes = if (!episodesJson.isNullOrEmpty()) {
      try { Json.decodeFromString<List<AnimeEpisode>>(episodesJson) } catch (e: Exception) { emptyList() }
    } else emptyList()

    val translations = if (!translationsJson.isNullOrEmpty()) {
      try { Json.decodeFromString<List<FlatTranslation>>(translationsJson) } catch (e: Exception) { emptyList() }
    } else emptyList()

    val qualities = if (!qualitiesJson.isNullOrEmpty()) {
      try { Json.decodeFromString<Map<String, String>>(qualitiesJson) } catch (e: Exception) { emptyMap() }
    } else emptyMap()

    val initialUrl = extras.getString("anime_auto_url") ?: extras.getString("uri") ?: intent.dataString
    if (!initialUrl.isNullOrBlank()) {
      currentAnimeStream = AnimeMediaStream(
        url = initialUrl,
        qualities = qualities,
        headers = headersFromExtras(extras),
        quality = currentQ,
      )
    }

    if (episodes.isNotEmpty() || translations.isNotEmpty()) {
      // Seasons are a movie-series-only concept; clear stale state from a previous singleTask reuse.
      viewModel.setAnimeSeasons(emptyList(), null)
      viewModel.onAnimeSeasonSelected = null
      // The anime path re-resolves streams on quality change — the URL watchdog is movie-only.
      qualityWatchdogJob?.cancel()
      viewModel.setAnimeData(episodes, translations, currentEp, currentTr, qualities, currentQ)
      val userStateStore = UserStateStore(this)
      libraryProfileKey()?.let { key ->
        viewModel.setWatchedEpisodesCount(userStateStore.getProfile(key)?.watchedEpisodes ?: 0)
      }

      viewModel.onAnimeEpisodeSelected = episodeSelected@{ epNum ->
        if (epNum == viewModel.currentAnimeEpisodeNumber.value) {
          Log.d(TAG, "Ignoring duplicate anime episode selection: $epNum")
          return@episodeSelected
        }
        // The outgoing file keeps its media identifier only until applyAnimeStream swaps it, so
        // record whether it was watched through before starting the next episode.
        flushOutgoingEpisodeProgress()
        val shikimoriId = extras.getInt("anime_shikimori_id", 0)
        val animeTitle = extras.getString("anime_title", "")
        val srcType = currentAnimeSourceType
        val trId = viewModel.currentAnimeTranslationId.value ?: currentTr ?: ""
        val userStateStore = UserStateStore(this)
        val prefQuality = userStateStore.getPreferredQuality()

        beginStreamLoadIndicator()
        viewModel.setAnimeData(episodes, translations, epNum, trId, viewModel.animeQualities.value, viewModel.currentAnimeQualityId.value)

        lifecycleScope.launch(Dispatchers.IO) {
          // Local-first: скачанная серия играет из офлайн-библиотеки без сети и резолва.
          val stream = hd.kinoshka.app.data.download.EpisodeDownloadManager
            .findLocal(shikimoriId, extras.getInt("movie_kinopoisk_id", 0), srcType.name, trId, epNum)
            ?.toAnimeMediaStream()
            ?: AnimeStreamResolver.resolveStream(shikimoriId, animeTitle, srcType, trId, epNum)
          withContext(Dispatchers.Main) {
            if (stream != null) {
              applyAnimeStream(stream, srcType, prefQuality, animeTitle, epNum, trId, episodes, translations)
            } else {
              finishStreamLoadIndicator()
            }
          }
        }
      }

      viewModel.onAnimeTranslationSelected = translationSelected@{ trId ->
        if (trId == viewModel.currentAnimeTranslationId.value) {
          Log.d(TAG, "Ignoring duplicate anime translation selection: $trId")
          return@translationSelected
        }
        val shikimoriId = extras.getInt("anime_shikimori_id", 0)
        val animeTitle = extras.getString("anime_title", "")
        val selectedTranslation = viewModel.animeTranslations.value.firstOrNull { it.translationId == trId }
        selectedTranslation?.let { recordPlaybackUsage(it.source, it.title) }
        val srcType = selectedTranslation?.source ?: currentAnimeSourceType
        val epNum = viewModel.currentAnimeEpisodeNumber.value ?: currentEp ?: 1
        val userStateStore = UserStateStore(this)
        val prefQuality = userStateStore.getPreferredQuality()

        beginStreamLoadIndicator()
        viewModel.setAnimeData(episodes, translations, epNum, trId, viewModel.animeQualities.value, viewModel.currentAnimeQualityId.value)

        lifecycleScope.launch(Dispatchers.IO) {
          // Local-first: скачанная серия играет из офлайн-библиотеки без сети и резолва.
          val stream = hd.kinoshka.app.data.download.EpisodeDownloadManager
            .findLocal(shikimoriId, extras.getInt("movie_kinopoisk_id", 0), srcType.name, trId, epNum)
            ?.toAnimeMediaStream()
            ?: AnimeStreamResolver.resolveStream(shikimoriId, animeTitle, srcType, trId, epNum)
          withContext(Dispatchers.Main) {
            if (stream != null) {
              applyAnimeStream(stream, srcType, prefQuality, animeTitle, epNum, trId, episodes, translations)
            } else {
              finishStreamLoadIndicator()
            }
          }
        }
      }

      viewModel.onAnimeQualitySelected = qualitySelected@{ qId ->
        if (qId == viewModel.currentAnimeQualityId.value) {
          Log.d(TAG, "Ignoring duplicate anime quality selection: $qId")
          return@qualitySelected
        }
        // Выбран уже играющий рунг (в т.ч. Auto·1080 → 1080): закрепляем выбор без перезагрузки.
        val activeStream = currentAnimeStream
        if (qId != "Auto" && activeStream != null && isQualityRungPlaying(activeStream, qId)) {
          UserStateStore(this).setPreferredQuality(qId)
          viewModel.setAnimeData(episodes, translations, viewModel.currentAnimeEpisodeNumber.value, viewModel.currentAnimeTranslationId.value, activeStream.qualities, qId)
          return@qualitySelected
        }
        beginStreamLoadIndicator()
        UserStateStore(this).setPreferredQuality(qId)

        val animeTitle = extras.getString("anime_title", "")
        val trId = viewModel.currentAnimeTranslationId.value ?: currentTr ?: ""
        val epNum = viewModel.currentAnimeEpisodeNumber.value ?: currentEp ?: 1

        if (qId == "Auto") {
          // Auto поверх текущего стрима: играющий рунг НЕ перезагружается — включается только
          // watchdog (ступени вниз с сохранением позиции). Резолв нужен лишь стриму без лестницы.
          val activeStreamNow = currentAnimeStream
          if (activeStreamNow != null && orderedConcreteQualities(activeStreamNow.qualities).isNotEmpty()) {
            finishStreamLoadIndicator()
            viewModel.setAnimeData(episodes, translations, epNum, trId, activeStreamNow.qualities, "Auto")
            updateAutoRungHint(activeStreamNow.qualities, currentPlayingUrl)
            startAutoQualityWatchdog()
          } else {
            val shikimoriId = extras.getInt("anime_shikimori_id", 0)
            val srcType = currentAnimeSourceType

            lifecycleScope.launch(Dispatchers.IO) {
              // Local-first: скачанная серия играет из офлайн-библиотеки без сети и резолва.
              val stream = hd.kinoshka.app.data.download.EpisodeDownloadManager
                .findLocal(shikimoriId, extras.getInt("movie_kinopoisk_id", 0), srcType.name, trId, epNum)
                ?.toAnimeMediaStream()
                ?: AnimeStreamResolver.resolveStream(shikimoriId, animeTitle, srcType, trId, epNum)
              withContext(Dispatchers.Main) {
                if (stream != null) {
                  pendingSeekPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                  applyAnimeStream(stream, srcType, "Auto", animeTitle, epNum, trId, episodes, translations)
                } else {
                  finishStreamLoadIndicator()
                }
              }
            }
          }
        } else {
          val stream = currentAnimeStream
          if (stream != null && stream.qualities.containsKey(qId)) {
            // Бесшовная смена (video-add + swap) вместо полной перезагрузки; оверлей гасим —
            // он не нужен, видео не прерывается.
            finishStreamLoadIndicator()
            viewModel.setAnimeData(episodes, translations, epNum, trId, stream.qualities, qId)
            currentAnimeStream = stream
            updateAutoRungHint(stream.qualities, stream.qualities[qId])
            if (!switchQualitySeamlessly(stream, qId)) {
              pendingSeekPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
              applyAnimeStream(stream, currentAnimeSourceType, qId, animeTitle, epNum, trId, episodes, translations)
            }
          } else {
            finishStreamLoadIndicator()
          }
        }
      }
    }
  }

  private fun setQualityOnlyMovieExtras(extras: Bundle?) {
    if (extras?.getString("playback_mode") != NativePlaybackMode.QUALITY_ONLY_MOVIE.name) return
    val stream = currentAnimeStream ?: return
    val currentQuality = extras.getString("anime_current_quality") ?: "Auto"
    // Voiceover options ride in as FlatTranslations whose single episode link is either a ready
    // CDN url (turbo) or a raw Kodik player link that needs lazy HLS extraction on switch.
    val translations = extras.getString("anime_translations")
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { Json.decodeFromString<List<FlatTranslation>>(it) }.getOrDefault(emptyList()) }
      .orEmpty()
    // Use the episode/translation the user picked on the source page — not just the first one.
    val selectedTranslationId = extras.getString("anime_current_translation_id")
      ?: translations.firstOrNull()?.translationId
    val bySource = translations.groupBy { it.source }.entries
      .joinToString { (src, rows) -> "${src.name}=${rows.size}" }
    Log.i(TAG, "QOM extras: mode=${extras.getString("playback_mode")}, translations=${translations.size} ($bySource), qualities=${stream.qualities.keys}")
    applyQualityOnlyMovieSetup(stream, translations, selectedTranslationId, currentQuality)
    // Любимая/последняя озвучка: intent-стрим — дефолтная (первая) строка списка, а PENDING-путь
    // тут же стартует на запомненном дубе. Без этого возврат к фильму каждый раз игнорировал
    // память воспроизведения, и её затирала launch-запись дефолтной строки выше.
    startQomOnRememberedDub(translations)
  }

  /**
   * Voiceover-only movie playback: dropdown wiring plus stream application. Shared by the
   * intent-extras path ([setQualityOnlyMovieExtras]) and the PENDING_MOVIE background resolve,
   * so both launch paths stay behaviourally identical.
   */
  private fun applyQualityOnlyMovieSetup(
    stream: AnimeMediaStream,
    translations: List<FlatTranslation>,
    selectedTranslationId: String?,
    requestedQuality: String,
  ) {
    val currentQuality = requestedQuality.ifBlank { "Auto" }
    // Launch-time usage: the voiceover the movie starts under counts toward the memory.
    translations.firstOrNull { it.translationId == selectedTranslationId }?.let {
      recordPlaybackUsage(it.source, it.title)
    }
    // The launch stream IS the first active voiceover: quality switches must read ITS ladder,
    // not a stale one from a previous session of this activity instance.
    qomActiveStream = stream
    viewModel.setAnimeSeasons(emptyList(), null)
    viewModel.onAnimeSeasonSelected = null
    viewModel.setAnimeData(emptyList(), translations, null, selectedTranslationId, stream.qualities, currentQuality)
    viewModel.onAnimeEpisodeSelected = null
    // The activity loaded the intent URL (MpvExPlayerScreen already resolved Auto to the
    // resolver's best concrete variant) — remember it so the watchdog knows the current rung.
    currentPlayingUrl = if (currentQuality != "Auto") stream.qualities[currentQuality] ?: stream.url
      else autoQualityRungUrl(stream)
    updateAutoRungHint(stream.qualities, currentPlayingUrl)
    startAutoQualityWatchdog()
    viewModel.onAnimeTranslationSelected = translationSelected@{ trId ->
      // Live list, not the launch-time capture: the late voiceover merge can grow the dropdown
      // after playback started, and a stale capture would silently drop taps on the new rows.
      val liveTranslations = viewModel.animeTranslations.value.ifEmpty { translations }
      val track = liveTranslations.firstOrNull { it.translationId == trId } ?: return@translationSelected
      if (trId == viewModel.currentAnimeTranslationId.value && qomActiveStream != null) {
        Log.d(TAG, "Ignoring duplicate QOM translation selection: $trId")
        return@translationSelected
      }
      // Manual pick: any automatic cross-source fallback chain starts over from here.
      autoFallbackTriedIds.clear()
      recordPlaybackUsage(track.source, track.title)
      // MovieNativeLauncher resolved every picker row before this activity was opened. Use that
      // prepared stream verbatim: switching a dub must never start a new Kodik/turbo resolve.
      val prepared = intent.getIntExtra("movie_kinopoisk_id", 0)
        .takeIf { it > 0 }
        ?.let { hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.get(it)[trId] }
      if (prepared != null) {
        loadPreparedQomVoiceover(track, prepared, liveTranslations)
        return@translationSelected
      }
      val link = track.episodes.firstOrNull()?.link.orEmpty()
      if (link.isBlank()) return@translationSelected
      resetStreamLoadRetries()
      loadQomVoiceover(track, link, liveTranslations)
    }
    viewModel.onAnimeQualitySelected = qualitySelected@{ quality ->
      // Duplicate guard lives ONLY in the tap-facing wrapper: the END_FILE auto-retry calls
      // [performQomQualitySwitch] directly — the first attempt already updated
      // currentAnimeQualityId, so a guarded re-issue used to be a silent no-op and the loading
      // overlay just spun into a black screen ("смена качества зависает").
      if (quality == viewModel.currentAnimeQualityId.value && currentPlayingUrl != null) return@qualitySelected
      performQomQualitySwitch(quality)
    }
  }

  /** Body of a QOM quality switch; safe to re-run verbatim from the failed-load retry path. */
  private fun performQomQualitySwitch(quality: String) {
    val activeStream = qomActiveStream ?: currentAnimeStream ?: return
    // Выбран уже играющий рунг (в т.ч. Auto·1080 → 1080): закрепляем выбор без перезагрузки.
    if (quality != "Auto" && isQualityRungPlaying(activeStream, quality)) {
      UserStateStore(this).setPreferredQuality(quality)
      viewModel.setAnimeData(emptyList(), viewModel.animeTranslations.value, null, viewModel.currentAnimeTranslationId.value, activeStream.qualities, quality)
      return
    }
    pendingSeekPosition = null
    UserStateStore(this).setPreferredQuality(quality)
    // "Auto" must mean the resolver's best concrete variant (see [autoQualityRungUrl]): a
    // literal Auto entry in a turbo qualities map is an adaptive/master URL mpv often cannot
    // open, and a raw base url is a signed token that can 404.
    val effectiveQuality = quality.takeIf { it != "Auto" && activeStream.qualities.containsKey(it) } ?: "Auto"
    val url = qualityUrlFor(activeStream, effectiveQuality) ?: return
    applyHttpHeaders(activeStream.headers)
    viewModel.setAnimeData(emptyList(), viewModel.animeTranslations.value, null, viewModel.currentAnimeTranslationId.value, activeStream.qualities, effectiveQuality)
    updateAutoRungHint(activeStream.qualities, url)
    startAutoQualityWatchdog()
    resetStreamLoadRetries()
    if (switchQualitySeamlessly(activeStream, effectiveQuality)) return
    pendingSeekPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
    currentPlayingUrl = url
    beginTrackedStreamLoad(retry = {
      // Re-issue the same switch bypassing the duplicate guard (see wrapper above).
      performQomQualitySwitch(quality)
    })
    mpvLoadFile(url, "replace")
  }

  /**
   * Resolves and plays the picked voiceover of a QUALITY_ONLY_MOVIE. Centralised so the initial
   * switch, user taps and END_FILE-error auto-retries share one code path: headers are re-applied
   * per provider (turbo↔kodik need different Referer/UA), the ACTIVE stream state is swapped to
   * the new dub (so later quality switches keep the chosen voiceover), and a failed loadfile is
   * retried once with a fresh resolve before the error card shows.
   */
  private fun loadQomVoiceover(
    track: FlatTranslation,
    rawLink: String,
    translations: List<FlatTranslation>,
    fromAutoFallback: Boolean = false,
  ) {
    beginTrackedStreamLoad(retry = { loadQomVoiceover(track, rawLink, translations, fromAutoFallback) })
    lifecycleScope.launch(Dispatchers.IO) {
      val resolved = resolveVoiceoverLink(rawLink)
      withContext(Dispatchers.Main) {
        val url = resolved
        if (url.isNullOrBlank()) {
          streamLoadRetryAction = null
          finishStreamLoadIndicator()
          // An automatic fallback hop that fails to resolve keeps walking to the next row;
          // only a user-initiated pick ends in a toast.
          if (fromAutoFallback && tryAlternativeQomSource()) return@withContext
          Toast.makeText(this@PlayerActivity, "Не удалось открыть выбранную озвучку", Toast.LENGTH_SHORT).show()
          return@withContext
        }
        val ladder = voiceoverLadderFor(url)
        fileName = intent.getStringExtra("title")?.substringBefore(" •")?.ifBlank { "Фильм" } ?: "Фильм"
        mediaIdentifier = stableKinoshkaIdentifier()
          ?: getMediaIdentifierFromUri(Uri.parse(url), fileName)
        MPVLib.setPropertyString("media-title", fileName)
        val headers = voiceoverHeadersFor(track)
        applyHttpHeaders(headers)
        qomActiveStream = AnimeMediaStream(url = url, qualities = ladder, headers = headers)
        currentPlayingUrl = url
        updateAutoRungHint(ladder, url)
        viewModel.setAnimeData(
          emptyList(),
          translations,
          null,
          track.translationId,
          ladder,
          // Empty ladder (catalog miss) must read Auto: keeping a stale concrete label would
          // both mislead the picker and silently disable the stall watchdog.
          viewModel.currentAnimeQualityId.value.takeIf { ladder.isNotEmpty() && it != "Auto" && ladder.containsKey(it) } ?: "Auto",
        )
        startAutoQualityWatchdog()
        mpvLoadFile(url, "replace")
      }
    }
  }

  /** Quality ladder of an already-resolved voiceover url from the session turbo catalog. */
  private fun voiceoverLadderFor(resolvedUrl: String): Map<String, String> =
    DdbbStreamResolver.cachedLadderFor(resolvedUrl).orEmpty()

  /**
   * One parallel health-probe round over the prepared voiceover rows (2-byte Range GET per
   * stream url; the whole round costs a single probe timeout, not per-row attempts). Returns
   * the ids of rows whose token still answers. Rows without a prepared direct url cannot be
   * probed and are absent — callers keep the plain try-in-mpv behavior for those.
   *
   * Мотивация (live warp kp=5437614): перебор дорожек по одной стоил полный loadfile + 15 с
   * таймаута на каждую мёртвую; теперь все дорожки просматриваются разом, играет первая
   * живая, а тотальный «все мертвы» сразу показывает карточку ошибки с подсказкой про VPN.
   */
  private suspend fun probePreparedRowIds(translations: List<FlatTranslation>): Set<String> {
    val kpId = intent.getIntExtra("movie_kinopoisk_id", 0)
    if (kpId <= 0) return emptySet()
    val store = hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.get(kpId)
    val probeable = translations.mapNotNull { track ->
      val url = store[track.translationId]?.url ?: return@mapNotNull null
      val direct = url.contains(".mp4", true) || url.contains(".m3u8", true) ||
        url.contains(".webm", true) || url.contains("/stream/UTN", true)
      if (direct) track.translationId else null
    }
    if (probeable.isEmpty()) return emptySet()
    val alive = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    probeable.map { id ->
      lifecycleScope.async(Dispatchers.IO) {
        val stream = store[id] ?: return@async
        if (DdbbStreamResolver.isDirectUrlAlive(stream.url, stream.headers)) alive.add(id)
      }
    }.awaitAll()
    Log.i(TAG, "Row probe: ${alive.size}/${probeable.size} prepared rows alive" + alive.joinToString(prefix = ": "))
    AppDiagnostics.event("row probe: ${alive.size}/${probeable.size} prepared rows alive")
    return alive
  }

  private fun isPreparedQomRow(track: FlatTranslation): Boolean {
    val kpId = intent.getIntExtra("movie_kinopoisk_id", 0)
    if (kpId <= 0) return false
    return hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.get(kpId)[track.translationId] != null
  }

  /**
   * Automatic cross-source recovery for a QOM movie: the active dub's stream keeps failing or
   * stalling, so try another dub row — a DIFFERENT provider first (another row of the same
   * dead CDN rarely behaves better). All candidate rows are probe-checked in ONE parallel
   * round and the first alive one loads; only when nothing answers does the error card take
   * over. Returns true when a fallback attempt is underway (synchronously or in-flight).
   */
  private fun tryAlternativeQomSource(): Boolean {
    if (effectiveNativePlaybackMode != NativePlaybackMode.QUALITY_ONLY_MOVIE) return false
    val translations = viewModel.animeTranslations.value
    if (translations.size < 2) return false
    val currentId = viewModel.currentAnimeTranslationId.value
    val failedSource = translations.firstOrNull { it.translationId == currentId }?.source
    currentId?.let { autoFallbackTriedIds.add(it) }
    // A mid-playback stall keeps the watch position; a failed load has none to keep.
    MPVLib.getPropertyDouble("time-pos")?.takeIf { it > 0 }?.let { pendingSeekPosition = it }
    val candidates = translations
      .filter { track ->
        track.translationId != currentId &&
          !track.episodes.firstOrNull()?.link.isNullOrBlank() &&
          autoFallbackTriedIds.add(track.translationId)
      }
      .sortedByDescending { it.source != failedSource }
    if (candidates.isEmpty()) return false
    lifecycleScope.launch {
      val aliveIds = probePreparedRowIds(candidates)
      if (isFinishing || isDestroyed) return@launch
      val track = candidates.firstOrNull { it.translationId in aliveIds }
        // No prepared row answered: an unprobed (lazy) row is still worth one mpv attempt.
        ?: candidates.firstOrNull { !isPreparedQomRow(it) }
      if (track == null) {
        Log.w(TAG, "Cross-source fallback: all ${candidates.size} alternatives probe-dead")
        AppDiagnostics.event("cross-source fallback: all ${candidates.size} alternatives probe-dead")
        finishStreamLoadIndicator()
        showStreamLoadError(streamLoadErrorMessage(slowStart = false))
        return@launch
      }
      Log.i(TAG, "Cross-source fallback: ${failedSource?.name ?: "current"} failed → ${track.source} «${track.title}»")
      AppDiagnostics.event("cross-source fallback → ${track.source} «${track.title}»")
      val kpId = intent.getIntExtra("movie_kinopoisk_id", 0)
      val prepared = if (kpId > 0) hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.get(kpId)[track.translationId] else null
      if (prepared != null) {
        loadPreparedQomVoiceover(track, prepared, translations)
      } else {
        loadQomVoiceover(track, track.episodes.firstOrNull()?.link.orEmpty(), translations, fromAutoFallback = true)
      }
    }
    return true
  }

  /**
   * Late voiceover merge: the losing provider answered after playback already started. Only
   * GROWS the dropdown (never reshuffles rows mid-playback) and keeps the current dub and
   * quality; the QOM selection callback reads the live list, so new rows are switchable at once.
   */
  private fun refreshQomVoiceoverRows(merged: List<FlatTranslation>) {
    if (effectiveNativePlaybackMode != NativePlaybackMode.QUALITY_ONLY_MOVIE) return
    val current = viewModel.animeTranslations.value
    val added = merged.size - current.size
    if (added <= 0) return
    viewModel.setAnimeData(
      emptyList(),
      merged,
      null,
      viewModel.currentAnimeTranslationId.value,
      qomActiveStream?.qualities ?: currentAnimeStream?.qualities ?: emptyMap(),
      viewModel.currentAnimeQualityId.value
    )
    Log.i(TAG, "Late voiceover merge: +$added rows joined the dropdown after playback started")
  }

  /**
   * Default dub of a movie: the one from the user's playback memory (most recently used wins),
   * else the merged list's head — deterministic instead of "whatever won this race".
   */
  private fun preferredTranslationId(translations: List<FlatTranslation>): String? {
    if (translations.isEmpty()) return null
    val dubs = UserStateStore(this).getPlaybackUsage().dubs
    val best = translations.maxWithOrNull(
      compareBy<FlatTranslation> { (dubs[it.title.trim().lowercase()]?.lastUsedAt ?: 0L) > 0L }
        .thenByDescending { dubs[it.title.trim().lowercase()]?.lastUsedAt ?: 0L }
        .thenByDescending { dubs[it.title.trim().lowercase()]?.count ?: 0 }
    )
    return best?.translationId
  }

  /**
   * The dub the user actually played before (most recent wins, then most used) among
   * [translations] — or null when nothing is remembered yet. Unlike [preferredTranslationId]
   * it never falls back to a list position: "no memory" must keep today's default pick.
   */
  private fun rememberedDubId(translations: List<FlatTranslation>): String? {
    if (translations.isEmpty()) return null
    val dubs = UserStateStore(this).getPlaybackUsage().dubs
    return translations
      .filter { (dubs[it.title.trim().lowercase()]?.lastUsedAt ?: 0L) > 0L }
      .maxWithOrNull(
        compareByDescending<FlatTranslation> { dubs[it.title.trim().lowercase()]?.lastUsedAt ?: 0L }
          .thenByDescending { dubs[it.title.trim().lowercase()]?.count ?: 0 }
      )?.translationId
  }

  /**
   * Starts a movie on the remembered favorite dub instead of the race winner's default row
   * (prepared ladder plays at once; unresolved rows lazy-extract). Returns true when it issued
   * its own loadfile — the caller must skip its default loadfile then.
   */
  private fun startQomOnRememberedDub(
    translations: List<FlatTranslation>,
  ): Boolean {
    val rememberedId = rememberedDubId(translations) ?: return false
    // readyQualityMovie orders the winner's own row first — that row is already playing.
    if (rememberedId == translations.firstOrNull()?.translationId) return false
    val track = translations.firstOrNull { it.translationId == rememberedId } ?: return false
    val kpId = intent.getIntExtra("movie_kinopoisk_id", 0)
    val prepared = if (kpId > 0) {
      hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.get(kpId)[rememberedId]
    } else null
    // The remembered dub is what actually plays from now on: record it, otherwise the
    // launch-time record of the default row (applyQualityOnlyMovieSetup) stays the newest
    // memory entry and the next resume falls back to the default again.
    recordPlaybackUsage(track.source, track.title)
    if (prepared != null) {
      loadPreparedQomVoiceover(track, prepared, translations)
      return true
    }
    val link = track.episodes.firstOrNull()?.link
    if (!link.isNullOrBlank()) {
      loadQomVoiceover(track, link, translations)
      return true
    }
    return false
  }

  /**
   * Headers required by the voiceover's provider: raw Kodik links need kodik playback headers,
   * direct turbo/ddbb urls need their embed Referer (the launch stream's headers only match the
   * provider that won startup — cross-provider switches used to 403 silently).
   */
  private fun voiceoverHeadersFor(track: FlatTranslation): Map<String, String> {
    val link = track.episodes.firstOrNull()?.link.orEmpty()
    val looksKodik = listOf("kodik", "vsh.my", "kdkonl", "aniqit", "kodi.my", "obrut.show", "/seria/", "/video/")
      .any { link.contains(it, ignoreCase = true) }
    if (looksKodik) return AnimeStreamResolver.kodikPlaybackHeaders()
    intent.getIntExtra("movie_kinopoisk_id", 0)
      .takeIf { it > 0 }
      ?.let { kpId -> DdbbStreamResolver.directHeaders(kpId).takeIf { it.isNotEmpty() } }
      ?.let { return it }
    return currentAnimeStream?.headers ?: AnimeStreamResolver.kodikPlaybackHeaders()
  }

  /**
   * "Auto" must mean the BEST CONCRETE rung of the active ladder, never the stream's raw
   * base url and never a literal "Auto"/master entry (turbo masters are links mpv cannot
   * open; turbo bases are raw signed tokens that intermittently 404 — live log kp=5457758).
   */
  private fun autoQualityRungUrl(stream: AnimeMediaStream): String =
    QUALITY_PREFERENCE_DESC.firstNotNullOfOrNull { q -> stream.qualities[q]?.takeIf { it.startsWith("http") } }
      ?: stream.url

  /**
   * PENDING_MOVIE: the player opened before any stream existed. Pull the resolve request from
   * the process-local store, run the Kodik↔ddbb race under the loading overlay, then re-apply
   * this activity as QUALITY_ONLY_MOVIE or MOVIE_SERIES. Failure shows a retryable error card.
   */
  private fun setPendingMovieExtras(extras: Bundle?) {
    if (extras == null || extras.getString("playback_mode") != NativePlaybackMode.PENDING_MOVIE.name) return
    val kpId = extras.getInt("movie_kinopoisk_id", 0)
    val launch = PendingMovieRequestStore.get(kpId)
    if (launch == null) {
      // Store entry lost (process death or stale launch): nothing to resolve in-process.
      Log.w(TAG, "PENDING_MOVIE without a stored request for kpId=$kpId")
      viewModel.setPendingResolveError("Не удалось получить данные фильма для поиска потока")
      return
    }
    PendingMovieRequestStore.remove(kpId)
    viewModel.setPendingWebFallbackUrl(launch.webFallbackUrl)
    // Manual retry is an explicit "give me fresh tokens" (vpn toggled, CDN hiccup): it must
    // grant a FULL auto-retry budget again. Without the reset the budget stayed exhausted from
    // the pre-retry failures, so the next END_FILE error skipped straight to source-hopping on
    // stale prepared urls instead of re-resolving (live log kp=5437614: «Повторить» → вечная
    // загрузка).
    viewModel.onPendingRetry = {
      resetStreamLoadRetries()
      retryPendingResolve(launch, isRetry = true)
    }
    beginStreamLoadIndicator()
    resolvePendingLaunch(launch)
  }

  private fun retryPendingResolve(launch: PendingMovieRequestStore.PendingMovieLaunch, isRetry: Boolean = true) {
    beginStreamLoadIndicator()
    resolvePendingLaunch(launch, isRetry)
  }

  private fun resolvePendingLaunch(launch: PendingMovieRequestStore.PendingMovieLaunch, isRetry: Boolean = false) {
    // Profile decides which S/E to resume; read once per attempt on the main thread (prefs IO).
    val profile = libraryProfileKey()?.let { key -> UserStateStore(this).getProfile(key) }
    val resolveStartMs = System.currentTimeMillis()
    lifecycleScope.launch(Dispatchers.IO) {
      // An mpv-reported dead stream must not be re-served from the ddbb 3-minute memo: bust it
      // so the retry actually re-extracts fresh CDN urls instead of failing identically.
      if (isRetry) launch.request.kinopoiskId?.takeIf { it > 0 }?.let { DdbbStreamResolver.evictResolveCache(it) }
      val payload = MovieNativeLauncher.resolve(launch.request, profile, UserStateStore(this@PlayerActivity)) { merged ->
        // Late voiceover merge: the losing provider answered after playback already started.
        // The callback fires on the launcher's IO scope — hop to the main thread for state.
        lifecycleScope.launch(Dispatchers.Main) {
          if (isFinishing || isDestroyed) return@launch
          refreshQomVoiceoverRows(merged)
        }
      }
      Log.i(TAG, "PENDING_MOVIE resolve done in ${System.currentTimeMillis() - resolveStartMs}ms (${payload.javaClass.simpleName})")
      AppDiagnostics.event("resolve done in ${System.currentTimeMillis() - resolveStartMs}ms → ${payload.javaClass.simpleName}")
      withContext(Dispatchers.Main) {
        Log.i(TAG, "PENDING_MOVIE main-thread handoff at +${System.currentTimeMillis() - resolveStartMs}ms")
        if (isFinishing || isDestroyed) return@withContext
        when (payload) {
          is MovieNativeLauncher.NativeLaunchPayload.QualityOnlyMovie -> {
            // The QOM quality-switch closure reads the field, not the parameter.
            currentAnimeStream = payload.stream
            effectiveNativePlaybackMode = NativePlaybackMode.QUALITY_ONLY_MOVIE
            // Fresh payload: the automatic cross-source recovery chain starts over.
            autoFallbackTriedIds.clear()
            autoSlowStartStepped = false
            // Same handoff the blocking launch path performs: prepared per-dub ladders let
            // voiceover switches play instantly instead of re-resolving.
            launch.request.kinopoiskId?.takeIf { it > 0 }?.let { kpId ->
              hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.put(kpId, payload.preparedStreams)
            }
            applyQualityOnlyMovieSetup(
              payload.stream,
              payload.translations,
              preferredTranslationId(payload.translations),
              UserStateStore(this@PlayerActivity).getPreferredQuality()
            )
            fileName = launch.displayTitle
            applyHttpHeaders(payload.stream.headers)
            val resolvedUrl = currentPlayingUrl
            if (resolvedUrl.isNullOrBlank()) return@withContext
            mediaIdentifier = stableKinoshkaIdentifier()
              ?: getMediaIdentifierFromUri(Uri.parse(resolvedUrl), fileName)
            MPVLib.setPropertyString("media-title", fileName)
            // Retry = the previous attempt proved some tokens dead. Before loading anything,
            // probe EVERY prepared row at once and start on the first alive one: a dead
            // preferred row must not burn another loadfile (a failed load can even terminate
            // the whole mpv core) while an alive row exists; nothing alive at all → error card
            // immediately instead of a 15s-per-row walk.
            if (isRetry) {
              val desiredId = rememberedDubId(payload.translations)
                ?: payload.translations.firstOrNull()?.translationId
              if (desiredId != null) {
                val desiredStream = launch.request.kinopoiskId?.takeIf { kp -> kp > 0 }
                  ?.let { kp -> hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.get(kp)[desiredId] }
                if (desiredStream != null) {
                  val aliveIds = probePreparedRowIds(payload.translations)
                  if (desiredId !in aliveIds) {
                    val fallbackTrack = payload.translations.firstOrNull { it.translationId in aliveIds }
                    val prepared = fallbackTrack?.let {
                      launch.request.kinopoiskId?.takeIf { kp -> kp > 0 }
                        ?.let { kp -> hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.get(kp)[it.translationId] }
                    }
                    if (fallbackTrack != null && prepared != null) {
                      Log.i(TAG, "Retry probe: preferred row $desiredId is dead → starting on «${fallbackTrack.title}»")
                      loadPreparedQomVoiceover(fallbackTrack, prepared, payload.translations)
                      return@withContext
                    }
                    if (aliveIds.isEmpty()) {
                      // Всё проверяемое мертво: кормить mpv ещё одним мёртвым url нельзя —
                      // провал loadfile способен завершить всё ядро. Карточка ошибки сразу.
                      finishStreamLoadIndicator()
                      showStreamLoadError(streamLoadErrorMessage(slowStart = false))
                      return@withContext
                    }
                  }
                }
              }
            }
            // Favorite-dub start: play the remembered dub right away (its own tracked load);
            // only fall through to the winner's default url when nothing is remembered.
            if (startQomOnRememberedDub(payload.translations)) return@withContext
            // Tracked: a dead CDN url auto re-resolves instead of ending in a black screen.
            beginTrackedStreamLoad(retry = { retryPendingResolve(launch, isRetry = true) })
            mpvLoadFile(resolvedUrl, "replace")
          }
          is MovieNativeLauncher.NativeLaunchPayload.MovieSeries -> {
            effectiveNativePlaybackMode = NativePlaybackMode.MOVIE_SERIES
            setupMovieSeriesControls(payload.context, UserStateStore(this@PlayerActivity).getPreferredQuality())
            UserStateStore(this@PlayerActivity).updateSeriesProgress(
              payload.context.kinopoiskId,
              payload.context.currentEpisode.seasonNumber,
              payload.context.currentEpisode.episodeNumber
            )
            // Favorite-dub start for DIRECT (ddbb/turbo) catalogs: each candidate carries its
            // own urls, so the remembered dub's episode link can be swapped in for free. When
            // the favorite doesn't cover this episode the picker substitutes another dub (and
            // applyMovieSeriesStream re-points the highlight to whatever really plays).
            var seriesStream = payload.stream
            var seriesContext = payload.context
            var seriesTrId: String? = null
            if (payload.context.isDirectSource) {
              val rememberedId = rememberedDubId(seriesTranslationsFor(payload.context, payload.context.currentEpisode))
              if (rememberedId != null) {
                pickDirectSeriesStream(payload.context, payload.context.currentEpisode, rememberedId, allowFallback = true)
                  ?.let { picked ->
                    seriesStream = picked.first
                    seriesTrId = picked.second
                    seriesContext = payload.context.copy(
                      currentEpisode = payload.context.currentEpisode.copy(playerUrl = picked.first.url)
                    )
                  }
              }
            }
            // applyMovieSeriesStream issues loadfile itself; the overlay clears on FILE_LOADED.
            beginTrackedStreamLoad(retry = {
              launch.request.kinopoiskId?.takeIf { it > 0 }?.let { DdbbStreamResolver.evictResolveCache(it) }
              applyMovieSeriesStream(payload.stream, payload.context, UserStateStore(this@PlayerActivity).getPreferredQuality())
            })
            applyMovieSeriesStream(seriesStream, seriesContext, UserStateStore(this@PlayerActivity).getPreferredQuality(), seriesTrId)
          }
          is MovieNativeLauncher.NativeLaunchPayload.Failed -> {
            finishStreamLoadIndicator()
            Log.w(TAG, "PENDING_MOVIE resolve failed: ${payload.reason}")
            AppDiagnostics.event("resolve failed: ${payload.reason}")
            viewModel.setPendingResolveError(payload.reason.userMessage())
          }
        }
      }
    }
  }

  private fun loadPreparedQomVoiceover(
    track: FlatTranslation,
    stream: AnimeMediaStream,
    translations: List<FlatTranslation>,
  ) {
    val quality = UserStateStore(this).getPreferredQuality()
      .takeIf { it != "Auto" && stream.qualities.containsKey(it) } ?: "Auto"
    qomActiveStream = stream
    currentPlayingUrl = if (quality != "Auto") stream.qualities[quality] ?: stream.url
      else autoQualityRungUrl(stream)
    applyHttpHeaders(stream.headers)
    viewModel.setAnimeData(emptyList(), translations, null, track.translationId, stream.qualities, quality)
    updateAutoRungHint(stream.qualities, currentPlayingUrl)
    startAutoQualityWatchdog()
    resetStreamLoadRetries()
    beginTrackedStreamLoad(retry = { retryQomVoiceoverLoad(track, translations) })
    mpvLoadFile(currentPlayingUrl!!, "replace")
  }

  /**
   * END_FILE-error retry for a prepared QOM voiceover: its ladder urls can be dead (dated CDN
   * tokens). Replaying the SAME prepared stream just burned the retry budget on an identical
   * 404 — evict the memoized ddbb data for this title, drop the prepared row and re-extract
   * the dub's raw link lazily for a genuinely fresh url.
   */
  private fun retryQomVoiceoverLoad(track: FlatTranslation, translations: List<FlatTranslation>) {
    intent.getIntExtra("movie_kinopoisk_id", 0).takeIf { it > 0 }?.let { kpId ->
      DdbbStreamResolver.evictResolveCache(kpId)
      hd.kinoshka.app.data.model.MovieVoiceoverStreamStore.remove(kpId, track.translationId)
    }
    val link = track.episodes.firstOrNull()?.link.orEmpty()
    if (link.isBlank()) {
      finishStreamLoadIndicator()
      return
    }
    loadQomVoiceover(track, link, translations)
  }

  /**
   * Voiceover links come in two flavours: ready CDN urls (turbo/ddbb) play directly, raw Kodik
   * player pages go through the HLS extractor with the same ladder the anime path uses.
   *
   * Bounded by a hard timeout and returning NULL on extraction failure: feeding mpv the raw
   * player page used to fail the loadfile silently (END-FILE error was never handled), leaving
   * the user with a dead spinner that looked like an endless hang.
   */
  private suspend fun resolveVoiceoverLink(link: String): String? =
    kotlinx.coroutines.withTimeoutOrNull(VOICEOVER_RESOLVE_TIMEOUT_MS) {
      // Direct media files play as-is; Kodik player pages need HLS extraction.
      // UTN streams (ddbb turbo) are extensionless HLS playlists that mpv handles
      // directly via its lavf HLS demuxer — re-resolving them as Kodik corrupts
      // the token (binary � in log) and 404s.
      if (link.contains(".mp4") || link.contains(".m3u8") || link.contains(".webm") || link.contains("/stream/UTN")) return@withTimeoutOrNull link
      val looksKodik = listOf("kodik", "vsh.my", "kdkonl", "aniqit", "kodi.my", "obrut.show", "/seria/", "/video/").any { link.contains(it, ignoreCase = true) }
      if (!looksKodik) return@withTimeoutOrNull link
      val qualities = runCatching {
        AnimeStreamResolver.resolveKodikHls(AnimeStreamResolver.absoluteKodikUrl(link))
      }.getOrDefault(emptyMap())
      QUALITY_PREFERENCE_DESC.firstOrNull { qualities.containsKey(it) }?.let { qualities[it] }
        ?: qualities.values.firstOrNull()
      // Extraction produced nothing playable — a raw player-page link is NOT a fallback.
    }
  private fun setMovieSeriesExtras(extras: Bundle?) {
    extras ?: return
    // Preferred: in-process store (the full context is too large for an intent transaction);
    // fallback: the legacy JSON extra for small contexts.
    val context: MovieSeriesPlaybackContext = extras.getInt("movie_series_kp_id", 0)
      .takeIf { it > 0 }
      ?.let { hd.kinoshka.app.data.model.MovieSeriesContextStore.get(it) }
      ?: run {
        val contextJson = extras.getString("movie_series_context") ?: return
        runCatching { Json.decodeFromString<MovieSeriesPlaybackContext>(contextJson) }
          .onFailure { Log.e(TAG, "Failed to decode movie series context", it) }
          .getOrNull() ?: return
      }
    movieSeriesContext = context
    setupMovieSeriesControls(context, extras.getString("anime_current_quality") ?: "Auto")
  }

  /**
   * Seasons/episodes/dubs/quality dropdown wiring for MOVIE_SERIES playback. Shared by the
   * intent-extras path ([setMovieSeriesExtras]) and the PENDING_MOVIE background resolve —
   * without it the pending path renders populated dropdowns whose taps invoke null callbacks.
   */
  private fun setupMovieSeriesControls(
    context: MovieSeriesPlaybackContext,
    currentQuality: String,
  ) {
    movieSeriesContext = context

    val uiEpisodes = context.episodes.map { episode ->
      AnimeEpisode(
        number = episode.playerEpisodeKey,
        // Row layout: title line is "Сезон N, серия N" (built in the dropdown), the t1 name
        // rides in `title` and renders as the subtitle line.
        title = episode.title?.takeIf { it.isNotBlank() },
        link = episode.playerUrl,
        season = episode.seasonNumber,
      )
    }
    // Only dubs that actually carry the CURRENT episode: the catalog lists every dub of the
    // whole series, but each dub covers its own episode subset — offering the rest produced
    // "unavailable voiceover" picks.
    val translations = seriesTranslationsFor(context, context.currentEpisode)
    // Preferred-start (ddbb only): highlight + usage record follow the remembered favorite
    // dub; kodik contexts can't attribute the resolved stream to a dub, so they keep first.
    val currentTranslationId = (if (context.isDirectSource) rememberedDubId(translations) else null)
      ?: translations.firstOrNull()?.translationId
    // Launch-time usage: the dub the title starts under counts toward the preference memory.
    currentTranslationId?.let { trId ->
      context.candidates.firstOrNull { it.translationId == trId }?.let { dub ->
        recordPlaybackUsage(
          if (context.isDirectSource) AnimeSourceType.DDBB else AnimeSourceType.KODIK,
          dub.translationTitle ?: trId
        )
      }
    }

    viewModel.setAnimeData(
      uiEpisodes,
      translations,
      context.currentEpisode.playerEpisodeKey,
      currentTranslationId,
      currentAnimeStream?.qualities.orEmpty(),
      currentQuality,
    )

    // Season dropdown: distinct seasons from the episode metadata; picking a season only
    // re-filters the episode list (no playback change) until an episode is chosen.
    val seasons = context.episodes.map { it.seasonNumber }.distinct().sorted()
    viewModel.setAnimeSeasons(seasons, context.currentEpisode.seasonNumber)
    // Галочки «просмотрено» в списке серий повторяют отметку из библиотечного «Прогресса просмотра».
    libraryProfileKey()?.let { key ->
      val profile = UserStateStore(this).getProfile(key)
      viewModel.setWatchedSeriesProgress(profile?.watchedSeasons ?: 0, profile?.watchedEpisodes ?: 0)
    }
    viewModel.onAnimeSeasonSelected = seasonSelected@{ season ->
      if (season == viewModel.currentAnimeSeason.value) return@seasonSelected
      viewModel.setAnimeSeasons(seasons, season)
    }
    startAutoQualityWatchdog()

    viewModel.onAnimeEpisodeSelected = episodeSelected@{ episodeKey ->
      val activeContext = movieSeriesContext ?: return@episodeSelected
      if (episodeKey == activeContext.currentEpisode.playerEpisodeKey) return@episodeSelected
      val selected = activeContext.episodes.firstOrNull { it.playerEpisodeKey == episodeKey } ?: return@episodeSelected
      // Commit the outgoing episode's watched state while its identifier still points at it.
      flushOutgoingEpisodeProgress()
      beginStreamLoadIndicator()
      pendingSeekPosition = null
      lifecycleScope.launch(Dispatchers.IO) {
        if (activeContext.isDirectSource) {
          // ddbb/turbo catalog: every candidate carries ready CDN urls — no HLS extraction.
          val requestedTrId = viewModel.currentAnimeTranslationId.value
          val picked = pickDirectSeriesStream(activeContext, selected, requestedTrId, allowFallback = true)
          withContext(Dispatchers.Main) {
            if (picked != null) {
              val (stream, trId) = picked
              val updatedContext = activeContext.copy(
                currentEpisode = selected.copy(playerUrl = stream.url)
              )
              movieSeriesContext = updatedContext
              currentAnimeStream = stream
              viewModel.setAnimeSeasons(
                updatedContext.episodes.map { it.seasonNumber }.distinct().sorted(),
                selected.seasonNumber,
              )
              UserStateStore(this@PlayerActivity).updateSeriesProgress(
                updatedContext.kinopoiskId,
                selected.seasonNumber,
                selected.episodeNumber,
              )
              // Overlay clears on MPV_EVENT_FILE_LOADED, once the new file really buffers.
              applyMovieSeriesStream(stream, updatedContext, UserStateStore(this@PlayerActivity).getPreferredQuality(), trId)
              if (trId != requestedTrId) {
                // The current dub doesn't cover this episode and another one was substituted —
                // say so, otherwise the user hears a "wrong" voiceover with no explanation.
                val fromName = activeContext.candidates
                  .firstOrNull { it.translationId == requestedTrId }?.translationTitle ?: requestedTrId
                val toName = activeContext.candidates
                  .firstOrNull { it.translationId == trId }?.translationTitle ?: trId
                Toast.makeText(
                  this@PlayerActivity,
                  "Озвучки «$fromName» нет для этой серии — включена «$toName»",
                  Toast.LENGTH_LONG
                ).show()
              }
            } else {
              finishStreamLoadIndicator()
              Toast.makeText(this@PlayerActivity, "Не удалось открыть выбранную серию", Toast.LENGTH_SHORT).show()
            }
          }
        } else {
          val result = MovieStreamResolver.resolveEpisode(
            activeContext.request, selected, activeContext.candidates,
            translationId = viewModel.currentAnimeTranslationId.value,
          )
          withContext(Dispatchers.Main) {
            if (result is MovieStreamResult.Success) {
              val updatedContext = activeContext.copy(currentEpisode = selected)
              movieSeriesContext = updatedContext
              currentAnimeStream = result.stream
              viewModel.setAnimeSeasons(
                updatedContext.episodes.map { it.seasonNumber }.distinct().sorted(),
                selected.seasonNumber,
              )
              UserStateStore(this@PlayerActivity).updateSeriesProgress(
                updatedContext.kinopoiskId,
                selected.seasonNumber,
                selected.episodeNumber,
              )
              applyMovieSeriesStream(result.stream, updatedContext, UserStateStore(this@PlayerActivity).getPreferredQuality())
            } else {
              finishStreamLoadIndicator()
              Toast.makeText(this@PlayerActivity, "Не удалось открыть выбранную серию", Toast.LENGTH_SHORT).show()
            }
          }
        }
      }
    }

    viewModel.onAnimeTranslationSelected = translationSelected@{ trId ->
      val activeContext = movieSeriesContext ?: return@translationSelected
      if (trId == viewModel.currentAnimeTranslationId.value) return@translationSelected
      activeContext.candidates.firstOrNull { it.translationId == trId }?.let { dub ->
        recordPlaybackUsage(
          if (activeContext.isDirectSource) AnimeSourceType.DDBB else AnimeSourceType.KODIK,
          dub.translationTitle ?: trId
        )
      }
      // Commit progress of the outgoing stream, then re-resolve the SAME episode under the new dub.
      flushOutgoingEpisodeProgress()
      beginStreamLoadIndicator()
      pendingSeekPosition = null
      lifecycleScope.launch(Dispatchers.IO) {
        if (activeContext.isDirectSource) {
          // Strict: the user tapped a SPECIFIC dub — silently playing another one's audio is
          // exactly the "several dubs sound the same" bug. Absent episode → toast, keep playing.
          val picked = pickDirectSeriesStream(activeContext, activeContext.currentEpisode, trId, allowFallback = false)
          withContext(Dispatchers.Main) {
            if (picked != null) {
              val (stream, _) = picked
              currentAnimeStream = stream
              applyMovieSeriesStream(stream, activeContext, UserStateStore(this@PlayerActivity).getPreferredQuality(), trId)
            } else {
              finishStreamLoadIndicator()
              Toast.makeText(this@PlayerActivity, "Озвучка недоступна для этой серии", Toast.LENGTH_SHORT).show()
            }
          }
        } else {
          val result = MovieStreamResolver.resolveEpisode(
            activeContext.request, activeContext.currentEpisode, activeContext.candidates,
            translationId = trId,
          )
          withContext(Dispatchers.Main) {
            if (result is MovieStreamResult.Success) {
              movieSeriesContext = activeContext
              currentAnimeStream = result.stream
              applyMovieSeriesStream(result.stream, activeContext, UserStateStore(this@PlayerActivity).getPreferredQuality(), trId)
            } else {
              finishStreamLoadIndicator()
              Toast.makeText(this@PlayerActivity, "Озвучка недоступна для этой серии", Toast.LENGTH_SHORT).show()
            }
          }
        }
      }
    }

    viewModel.onAnimeQualitySelected = qualitySelected@{ quality ->
      val activeContext = movieSeriesContext ?: return@qualitySelected
      val stream = currentAnimeStream ?: return@qualitySelected
      if (quality == viewModel.currentAnimeQualityId.value) return@qualitySelected
      UserStateStore(this).setPreferredQuality(quality)
      // Выбран уже играющий рунг (в т.ч. Auto·1080 → 1080): закрепляем выбор без перезагрузки.
      if (quality != "Auto" && isQualityRungPlaying(stream, quality)) {
        viewModel.setAnimeData(
          viewModel.animeEpisodes.value,
          viewModel.animeTranslations.value,
          viewModel.currentAnimeEpisodeNumber.value,
          viewModel.currentAnimeTranslationId.value,
          stream.qualities,
          quality,
        )
        return@qualitySelected
      }
      // Бесшовная смена (video-add + swap) — до общей перезагрузки.
      val effectiveQuality = quality.takeIf { it != "Auto" && stream.qualities.containsKey(it) } ?: "Auto"
      if (switchQualitySeamlessly(stream, effectiveQuality)) {
        viewModel.setAnimeData(
          viewModel.animeEpisodes.value,
          viewModel.animeTranslations.value,
          viewModel.currentAnimeEpisodeNumber.value,
          viewModel.currentAnimeTranslationId.value,
          stream.qualities,
          effectiveQuality,
        )
        updateAutoRungHint(stream.qualities, currentPlayingUrl)
        startAutoQualityWatchdog()
        return@qualitySelected
      }
      pendingSeekPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
      // Tracked so a dead variant url auto-retries; the retry re-runs applyMovieSeriesStream
      // directly — it has no duplicate guard, unlike this callback (the first attempt already
      // updated currentAnimeQualityId, and a guarded re-issue used to no-op into a black screen).
      beginTrackedStreamLoad(retry = { applyMovieSeriesStream(stream, activeContext, quality) })
      applyMovieSeriesStream(stream, activeContext, quality)
    }
  }

  /**
   * Resolves a direct-source (ddbb/turbo) episode under [translationId]: picks that dub's own
   * CDN url from the context candidates. The concrete quality ladder rides along from the
   * resolver's session catalog when available.
   *
   * [allowFallback]: on an EPISODE switch a dub may simply not cover the new season — falling
   * back to the nearest dub that does keeps binge-watching going (the caller toasts the swap).
   * On an explicit DUB switch a fallback would play a DIFFERENT voiceover than the one the user
   * tapped — the "several dubs sound the same" bug — so it is disabled there.
   */
  private fun pickDirectSeriesStream(
    context: MovieSeriesPlaybackContext,
    episode: MovieEpisodeRef,
    translationId: String?,
    allowFallback: Boolean,
  ): Pair<AnimeMediaStream, String>? {
    val dubs = context.candidates.filter { !it.translationId.isNullOrBlank() }
    if (dubs.isEmpty()) return null
    val ordered = if (allowFallback) {
      dubs.filter { it.translationId == translationId } + dubs.filter { it.translationId != translationId }
    } else {
      dubs.filter { it.translationId == translationId }
    }
    for (dub in ordered) {
      val ref = dub.episodes.firstOrNull {
        it.seasonNumber == episode.seasonNumber && it.episodeNumber == episode.episodeNumber
      } ?: continue
      val url = ref.playerUrl
      if (url.isBlank()) continue
      val qualities = LinkedHashMap(DdbbStreamResolver.directQualities(context.kinopoiskId, url).orEmpty())
      val headers = context.directHeaders.ifEmpty { DdbbStreamResolver.directHeaders(context.kinopoiskId) }
      val stream = AnimeMediaStream(
        url = url,
        qualities = qualities,
        headers = headers,
        quality = qualities.entries.firstOrNull { it.value == url }?.key ?: "Auto",
      )
      return stream to dub.translationId!!
    }
    return null
  }

  /** Shows the stream-loading overlay until the switched file actually loads in mpv. */
  private fun beginStreamLoadIndicator() {
    // A plain (untracked) switch must not inherit the PREVIOUS tracked load's retry action:
    // a stale action firing on an unrelated END_FILE error reloaded an old target out of
    // nowhere. Tracked loads re-arm it right after this call.
    cancelNextEpisodeCountdown()
    streamLoadRetryAction = null
    viewModel.setLoadingStream(true)
    pendingStreamLoadIndicator = true
    streamLoadIndicatorTimeoutJob?.cancel()
    streamLoadIndicatorTimeoutJob = lifecycleScope.launch {
      kotlinx.coroutines.delay(15_000)
      if (pendingStreamLoadIndicator) {
        pendingStreamLoadIndicator = false
        viewModel.setLoadingStream(false)
        // Файл не открылся за 15 c (лог live kp=30276: CDN отдаёт сегменты по 20 c и рвёт
        // TLS). В Auto делаем одноразовый спуск рунга; в QOM-фильмах спуск ограничен одним
        // шагом — второй медленный таймаут на том же мёртвом CDN отвечаем переключением
        // на другой источник, а не прогулкой по лестнице до дна. Флаг читается ДО спуска:
        // autoDowngradeOnSlowStart сам его ставит, и чтение после вызова делало первый же
        // таймаут одновременными «спуск + смена источника» (лог live warp kp=5437614).
        val steppedBefore = autoSlowStartStepped
        AppDiagnostics.event("slow-start timeout (15s), steppedBefore=$steppedBefore")
        if (autoDowngradeOnSlowStart() &&
          (effectiveNativePlaybackMode != NativePlaybackMode.QUALITY_ONLY_MOVIE || !steppedBefore)
        ) return@launch
        // Текущая попытка не открылась за окно: 3-минутный ddbb-кэш мог держать токены,
        // добытые в другой сети (включённый/выключенный VPN) — следующий resolve должен
        // извлечь свежие, а не переигрывать мёртвые.
        intent.getIntExtra("movie_kinopoisk_id", 0)
          .takeIf { it > 0 }
          ?.let { DdbbStreamResolver.evictResolveCache(it) }
        if (tryAlternativeQomSource()) return@launch
        if (lastStreamLoadRetry == null) {
          // ANIME-launch path не трекает загрузку — «Повторить» реплеит текущий url.
          lastStreamLoadRetry = {
            viewModel.setPendingResolveError(null)
            beginStreamLoadIndicator()
            (currentPlayingUrl ?: currentAnimeStream?.url)
              ?.let { mpvLoadFile(it, "replace") }
          }
        }
        showStreamLoadError(streamLoadErrorMessage(slowStart = true))
      }
    }
  }

  /**
   * Медленный старт при Auto: одноразовый спуск на рунг ниже того, что Auto обслуживает.
   * Режим остаётся Auto — пилюля продолжает писать «Auto · <рунг>» (через rung-hint), как при
   * обычном степ-дауне [startAutoQualityWatchdog]; этот хендлер нужен потому, что watchdog
   * ждёт time-pos > 0 и молчит, пока файл вообще не открылся. Возвращает true, если спуск
   * запущен (новое окно загрузки уже открыто).
   */
  private fun autoDowngradeOnSlowStart(): Boolean {
    if (viewModel.currentAnimeQualityId.value?.equals("Auto", ignoreCase = true) != true) return false
    val stream = currentAnimeStream ?: return false
    val ladder = orderedConcreteQualities(stream.qualities)
    if (ladder.size < 2) return false
    val idx = ladder.indexOfFirst { it.second == currentPlayingUrl }.takeIf { it >= 0 } ?: 0
    val next = ladder.getOrNull(idx + 1) ?: return false
    Log.i(TAG, "Stream load timed out in Auto — stepping down ${ladder[idx].first} -> ${next.first}")
    autoSlowStartStepped = true
    // Файл не начал играть — продолжать нечего, позицию не восстанавливаем.
    pendingSeekPosition = null
    currentPlayingUrl = next.second
    viewModel.setAutoQualityRungHint(next.first)
    beginTrackedStreamLoad(retry = {
      applyHttpHeaders(stream.headers)
      mpvLoadFile(next.second, "replace")
    })
    mpvLoadFile(next.second, "replace")
    return true
  }

  /**
   * Like [beginStreamLoadIndicator], but arms the END_FILE-error recovery: if mpv reports a
   * failed load while this load attempt is pending, [retry] runs with a fresh resolve until
   * [MAX_STREAM_LOAD_RETRIES] is spent, then the retryable error card takes over (a dead CDN
   * url must not end in a black screen). The retry BUDGET is NOT reset here — an automatic
   * retry re-arms through the same path and must not loop forever; manual retries reset it.
   */
  private fun beginTrackedStreamLoad(retry: () -> Unit) {
    viewModel.setPendingResolveError(null)
    beginStreamLoadIndicator()
    // Arm AFTER beginStreamLoadIndicator: that call now clears any stale action first.
    streamLoadRetryAction = retry
    lastStreamLoadRetry = retry
  }

  /** Manual «Повторить» (error card / new launch): grant a full auto-retry budget again. */
  private fun resetStreamLoadRetries() {
    streamLoadRetries = 0
    autoSlowStartStepped = false
  }

  /** Clears the stream-loading overlay (file loaded, or switch failed). */
  private fun finishStreamLoadIndicator() {
    if (!pendingStreamLoadIndicator) {
      viewModel.setLoadingStream(false)
      return
    }
    pendingStreamLoadIndicator = false
    streamLoadIndicatorTimeoutJob?.cancel()
    viewModel.setLoadingStream(false)
  }

  /**
   * Dub options actually available for [episode]: the catalog lists every dub of the whole
   * series, but each dub covers only its own episode subset — the rest can't play this episode
   * and must not appear in the dropdown.
   */
  private fun seriesTranslationsFor(
    context: MovieSeriesPlaybackContext,
    episode: MovieEpisodeRef,
  ): List<FlatTranslation> =
    context.candidates
      .filter { candidate ->
        !candidate.translationId.isNullOrBlank() && candidate.episodes.any {
          it.seasonNumber == episode.seasonNumber && it.episodeNumber == episode.episodeNumber
        }
      }
      .map { dub ->
        // DIRECT (ddbb/turbo) series catalogs must surface as DDBB rows — hardcoding KODIK
        // put every turbo dub under the wrong source chip ("у сериалов вся озвучка Kodik").
        // splitDubTrack also strips "(субтитры)"/Original markers into proper types.
        val rawTitle = dub.translationTitle ?: dub.translationId.orEmpty()
        val (dubTitle, kind) = MovieNativeLauncher.splitDubTrack(rawTitle)
        FlatTranslation(
          source = if (context.isDirectSource) AnimeSourceType.DDBB else AnimeSourceType.KODIK,
          translationId = dub.translationId ?: rawTitle,
          title = if (rawTitle.isBlank()) "Озвучка" else dubTitle,
          type = kind,
          episodes = emptyList()
        )
      }

  private fun applyMovieSeriesStream(
    stream: AnimeMediaStream,
    context: MovieSeriesPlaybackContext,
    requestedQuality: String,
    translationId: String? = null,
  ) {
    val effectiveQuality = requestedQuality.takeIf { it != "Auto" && stream.qualities.containsKey(it) } ?: "Auto"
    // Same Auto guard as setQualityOnlyMovieExtras: a literal Auto entry can be an unplayable
    // master URL — fall back to the stream's own default instead.
    val url = if (effectiveQuality == "Auto") stream.url
      else stream.qualities[effectiveQuality] ?: stream.url
    val episode = context.currentEpisode
    fileName = "${context.displayTitle} • S${episode.seasonNumber}E${episode.episodeNumber}"
    mediaIdentifier = "ks_series_${context.kinopoiskId}_s${episode.seasonNumber}e${episode.episodeNumber}"
    MPVLib.setPropertyString("media-title", fileName)
    applyAnimeTransportOptions(false)
    applyHttpHeaders(stream.headers)
    val uiEpisodes = context.episodes.map {
      // NAMED args: AnimeEpisode's 4th positional is `id`, not `season` — passing seasonNumber
      // positionally silently nulled every row's season and broke the episode-list filter.
      AnimeEpisode(
        number = it.playerEpisodeKey,
        title = it.title?.takeIf { name -> name.isNotBlank() },
        link = it.playerUrl,
        season = it.seasonNumber
      )
    }
    // Re-filter the dub list for the NEW episode (each dub covers its own episode subset) and
    // keep the highlight on the dub that is actually playing.
    val availableTranslations = seriesTranslationsFor(context, episode)
    val highlightId = translationId
      ?.takeIf { trId -> availableTranslations.any { it.translationId == trId } }
      ?: availableTranslations.firstOrNull()?.translationId
      ?: translationId
      ?: viewModel.currentAnimeTranslationId.value
    viewModel.setAnimeData(
      uiEpisodes,
      availableTranslations,
      episode.playerEpisodeKey,
      highlightId,
      stream.qualities,
      effectiveQuality,
    )
    viewModel.setAnimeSeasons(
      context.episodes.map { it.seasonNumber }.distinct().sorted(),
      episode.seasonNumber,
    )
    currentPlayingUrl = url
    // Качество-меню и watchdog читают currentAnimeStream — без обновления первое нажатие
    // качества после запуска сериала молча игнорировалось (поле было null до первого
    // переключения озвучки/серии), а авто-спуск рунга вообще не работал.
    currentAnimeStream = stream
    updateAutoRungHint(stream.qualities, url)
    mpvLoadFile(url, "replace")
  }

  private fun applyAnimeStream(
    stream: AnimeMediaStream,
    sourceType: AnimeSourceType,
    requestedQuality: String,
    animeTitle: String,
    episodeNumber: Int,
    translationId: String,
    episodes: List<AnimeEpisode>,
    translations: List<FlatTranslation>,
  ) {
    val effectiveQuality = requestedQuality.takeIf { it != "Auto" && stream.qualities.containsKey(it) } ?: "Auto"
    // Рунг-мусор ("null" из JSON-null источника) не играется — играет текущий url стрима.
    val url = stream.qualities[effectiveQuality]?.takeIf { it.startsWith("http") } ?: stream.url

    currentAnimeSourceType = sourceType
    currentAnimeStream = stream
    // Watchdog и no-op guard'ы качества читают currentPlayingUrl: без записи первый выбор
    // конкретного качества после загрузки серии сравнивался с null/устаревшим url и
    // бесшовно переоткрывал тот же рунг (жалоба: Auto играет 720 — выбор 720 перезапускал видео).
    currentPlayingUrl = url
    fileName = "$animeTitle • Серия $episodeNumber"
    mediaIdentifier = stableKinoshkaIdentifier(episodeOverride = episodeNumber)
      ?: getMediaIdentifierFromUri(Uri.parse(url), fileName)
    MPVLib.setPropertyString("media-title", fileName)
    applyAnimeTransportOptions(sourceType == AnimeSourceType.ANILIBERTY)
    applyHttpHeaders(stream.headers)
    viewModel.setAnimeData(
      episodes,
      translations,
      episodeNumber,
      translationId,
      stream.qualities,
      effectiveQuality,
    )
    mpvLoadFile(url, "replace")
  }

  private fun applyAnimeTransportOptions(disableHttpReuse: Boolean) {
    val reuseOptions = if (disableHttpReuse) "http_persistent=0,http_multiple=0," else ""
    val options = "${reuseOptions}rw_timeout=15000000,reconnect=1,reconnect_streamed=1,reconnect_delay_max=15"
    val result = MPVLib.setPropertyString("demuxer-lavf-o", options)
    Log.d(TAG, "Anime transport hardened (http reuse ${if (disableHttpReuse) "disabled" else "default"}): $options, result=$result")
  }

  /** Concrete qualities of [stream] sorted best-first (Auto excluded). */
  private fun orderedConcreteQualities(qualities: Map<String, String>): List<Pair<String, String>> =
    qualities.entries
      .filter { it.key != "Auto" }
      .sortedByDescending { it.key.removeSuffix("p").toIntOrNull() ?: 0 }
      .map { it.key to it.value }

  /**
   * Remembers which concrete rung "Auto" is currently serving (url → ladder key). The quality
   * pill shows "Auto · 1080p" immediately — before mpv reports video-params, which used to leave
   * a bare "Auto" with no indication of what had been picked.
   */
  private fun updateAutoRungHint(qualities: Map<String, String>, playingUrl: String?) {
    val rung = playingUrl?.let { url -> qualities.entries.firstOrNull { it.value == url }?.key }
    viewModel.setAutoQualityRungHint(rung)
  }

  // ==================== Бесшовная смена качества (как на YouTube) ====================
  //
  // Новый рунг добавляется ВТОРОЙ видео-дорожкой ("video-add <url> cached"): mpv сразу
  // переключает видео на неё, а мы точным seek'ом ставим новый demuxer на текущую позицию —
  // картинка доигрывает старый вариант, пока открывается новый, без перезагрузки файла.
  // Если вариант так и не открылся (мёртвый токен CDN), тихий откат на обычную перезагрузку.
  // ВАЖНО: "video-add" открывает рунг по сети и блокирует вызывающий поток до конца
  // демuxer-open (на медленном CDN — до ~7 с), поэтому выполняется строго вне главного потока.

  /**
   * Снимок дорожек mpv через поиндексные чтения свойств (тот же путь, что и в
   * TrackSelector). Ключ track-list — "external-filename": поля "file" в track-list НЕТ,
   * поэтому прежний вариант на getPropertyNode("track-list") с map["file"] никогда не
   * совпадал и каждое переключение по таймауту откатывалось на полную перезагрузку.
   */
  private class MpvTrackSnapshot(val type: String, val id: Int, val filename: String?, val selected: Boolean)

  private fun mpvTracks(type: String? = null): List<MpvTrackSnapshot> {
    val count = runCatching { MPVLib.getPropertyInt("track-list/count") }.getOrNull() ?: return emptyList()
    val result = ArrayList<MpvTrackSnapshot>(count)
    for (i in 0 until count) {
      val trackType = MPVLib.getPropertyString("track-list/$i/type") ?: continue
      if (type != null && trackType != type) continue
      val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
      val filename = MPVLib.getPropertyString("track-list/$i/external-filename")
      val selected = MPVLib.getPropertyBoolean("track-list/$i/selected") ?: false
      result.add(MpvTrackSnapshot(trackType, id, filename, selected))
    }
    return result
  }

  private fun mpvVideoTracks(): List<MpvTrackSnapshot> = mpvTracks("video")

  private fun isVideoTrackSelectedByUrl(url: String): Boolean =
    mpvVideoTracks().any { it.filename == url && it.selected }

  /** Убирает НЕ выбранный видео-трек, добавленный для [url] (зачистка зависших переключений). */
  private fun removeVideoTrackByUrl(url: String) {
    for (track in mpvVideoTracks()) {
      if (track.filename != url || track.selected) continue
      runCatching { MPVLib.command("video-remove", track.id.toString()) }
    }
  }

  /**
   * После успешного переключения выбрасывает все не выбранные треки, добавленные извне
   * (external-filename != null). "video-add" HLS добавляет вместе с видео-треком и внешний
   * аудио-трек: если оставить его — demuxer старого варианта останется жив и продолжит
   * качать сегменты уже ненужного качества впустую.
   */
  private fun sweepUnselectedExternalTracks() {
    for (track in mpvTracks()) {
      if (track.selected || track.filename == null) continue
      val command = when (track.type) {
        "video" -> "video-remove"
        "audio" -> "audio-remove"
        "sub" -> "sub-remove"
        else -> continue
      }
      runCatching { MPVLib.command(command, track.id.toString()) }
    }
  }

  /** Url рунга [quality] у [stream] ("Auto" = лучший конкретный рунг); null — играть нечего.
   *  Рунг-мусор (пусто/"null" из JSON-null источника) не играется — берётся текущий url стрима. */
  private fun qualityUrlFor(stream: AnimeMediaStream, quality: String): String? = when {
    quality == "Auto" -> autoQualityRungUrl(stream).takeIf { it.isNotBlank() }
    else -> stream.qualities[quality]?.takeIf { it.startsWith("http") } ?: stream.url.takeIf { it.isNotBlank() }
  }

  /**
   * Играет ли сейчас рунг [quality] ("Auto" = лучший конкретный рунг). Сравнение по пути URL без
   * query: у повторных ресолвов того же рунга меняется только CDN-токен в query, а путь один —
   * и повторный выбор играющего качества не должен ничего перезагружать (жалоба: Auto играет
   * 720, выбор 720 в меню рестартовал видео как полноценную перезагрузку).
   */
  private fun isQualityRungPlaying(stream: AnimeMediaStream, quality: String): Boolean {
    val targetUrl = qualityUrlFor(stream, quality) ?: return false
    val targetPath = targetUrl.substringBefore('?')
    currentPlayingUrl?.let { playing ->
      if (playing == targetUrl || playing.substringBefore('?') == targetPath) return true
    }
    // Учёт мог устареть (свежий ресолв с другим хэшем в пути, гонка с перезагрузкой) — спрашиваем
    // сам mpv: выбранный внешний видео-трек или путь открытого файла. Совпадение пути означает,
    // что этот же рунг уже играет и video-add лишь перезапустил бы видео.
    val playingNow = mpvPlayingUrl() ?: return false
    return playingNow.substringBefore('?') == targetPath
  }

  /** Что mpv играет прямо сейчас: external-filename выбранного видео-трека, иначе "path"
   *  главного файла (только http — локальные файлы к лестнице качеств не относятся). */
  private fun mpvPlayingUrl(): String? {
    mpvTracks("video").firstOrNull { it.selected && it.filename != null }?.let { return it.filename }
    return runCatching { MPVLib.getPropertyString("path") }.getOrNull()?.takeIf { it.startsWith("http") }
  }

  /**
   * Бесшовная смена качества. Возвращает false, когда путь невозможен (мёртвое ядро, файл ещё
   * не открыт) — вызывающий делает обычный loadfile replace. Если рунг [quality] уже играет,
   * возвращает true без действий: повторный выбор того же качества (в т.ч. Auto·720 → 720 и
   * выбор после ресолва с новым токеном) не должен перезапускать видео.
   */
  private fun switchQualitySeamlessly(stream: AnimeMediaStream, quality: String): Boolean {
    if (mpvCoreDead) return false
    val targetUrl = qualityUrlFor(stream, quality) ?: return false
    if (isQualityRungPlaying(stream, quality)) return true
    if (MPVLib.getPropertyDouble("time-pos") == null) return false // файл ещё не открылся в mpv

    Log.i(TAG, "Seamless quality switch to $quality")
    AppDiagnostics.event("seamless quality switch to $quality")
    // Зачистка зависшего трека от предыдущего незавершённого переключения выполняется в фоновой
    // корутине (video-remove тоже ходит в mpv-ядро).
    val cleanupUrl = pendingSeamlessQualityUrl
    pendingSeamlessQualityUrl = targetUrl
    currentPlayingUrl = targetUrl
    seamlessSwitchJob?.cancel()
    seamlessSwitchJob = lifecycleScope.launch(Dispatchers.Default) {
      cleanupUrl?.let { removeVideoTrackByUrl(it) }
      // Блокирующий сетевой open — только вне главного потока, иначе ANR (видео и UI замирают).
      // Флаг "cached" в этой сборке mpv добавляет дорожку и СРАЗУ переключает видео на неё.
      // Но свежий demuxer никогда не перематывается к позиции воспроизведения (refresh-seek для
      // видео подавлен через after_seek=true у нового demuxer), а HLS-поток читается
      // последовательно: без перемотки demuxer полз бы от нуля к текущей позиции, замораживая
      // картинку на десятки секунд при живом аудио. Глобальный seek двигает demuxer'ы всех
      // выбранных внешних треков, включая только что добавленный. Флаг "exact" обязателен:
      // обычный seek садится на keyframe до ~5 с позади цели (GOP у Aniliberty редкий), аудио
      // остаётся у цели — mpv держит звук ("delaying audio start") и прокручивает просмотренное.
      runCatching { MPVLib.command("video-add", targetUrl, "cached") }
      if (mpvVideoTracks().any { it.filename == targetUrl }) {
        // Аудио переключаем на новый рунг вместе с видео: mpv синхронизирует видео ПО аудио
        // (аудио = мастер-часы). Если аудио остаётся на старом demuxer'е, то пропуск одного
        // сегмента (TLS-сбой, Packet corrupt) рвёт его PTS ("Invalid audio PTS") — mpv делает
        // reset и видео форсажем догоняет аудио-мастер: рассинхрон и ускорение картинки.
        // Плюс оставшись без выбранных треков, старый demuxer перестаёт качать сегменты.
        // Меняем только если у основного файла один аудио-трек или уже выбран внешний
        // (иначе можно потерять пользовательский дубляж из многоголосого файла).
        val audioTracks = mpvTracks("audio")
        val selectedAudio = audioTracks.firstOrNull { it.selected }
        if (selectedAudio != null && (selectedAudio.filename != null || audioTracks.count { it.filename == null } == 1)) {
          val newAudio = audioTracks.lastOrNull { it.filename == targetUrl }
          if (newAudio != null && !newAudio.selected) {
            runCatching { MPVLib.setPropertyString("aid", newAudio.id.toString()) }
          }
        }
        val pos = MPVLib.getPropertyDouble("time-pos")
        if (pos != null && pos > 0) {
          runCatching { MPVLib.command("seek", pos.toString(), "absolute+exact") }
        }
      }

      val startTime = System.currentTimeMillis()
      var settled = false
      while (isActive && System.currentTimeMillis() - startTime < 10_000) {
        kotlinx.coroutines.delay(150)
        // Переключение вытеснено (новый файл/новый рунг) — коммитить нечего.
        if (pendingSeamlessQualityUrl != targetUrl) return@launch
        if (isVideoTrackSelectedByUrl(targetUrl)) {
          settled = true
          break
        }
      }
      if (settled) {
        // Новый рунг играет — старые внешние треки можно выбросить (mpv сам их не удаляет,
        // а наложенные незавершённые переключения оставляли по дорожке каждая).
        sweepUnselectedExternalTracks()
        pendingSeamlessQualityUrl = null
      } else if (pendingSeamlessQualityUrl == targetUrl) {
        // Вариант не открылся за 10 с (мёртвый токен): тихий откат на обычную перезагрузку.
        Log.w(TAG, "Seamless switch to $quality did not settle — falling back to reload")
        AppDiagnostics.event("seamless switch fallback to reload")
        removeVideoTrackByUrl(targetUrl)
        pendingSeamlessQualityUrl = null
        val pos = MPVLib.getPropertyDouble("time-pos")
        withContext(Dispatchers.Main) {
          pendingSeekPosition = pos
          beginTrackedStreamLoad(retry = { switchQualityWithReload(stream, quality) })
          mpvLoadFile(targetUrl, "replace")
        }
      }
    }
    return true
  }

  /** Откат бесшовного пути: полная перезагрузка рунга с сохранением позиции. */
  private fun switchQualityWithReload(stream: AnimeMediaStream, quality: String) {
    val targetUrl = qualityUrlFor(stream, quality) ?: return
    pendingSeekPosition = MPVLib.getPropertyDouble("time-pos")
    currentPlayingUrl = targetUrl
    beginTrackedStreamLoad(retry = { switchQualityWithReload(stream, quality) })
    mpvLoadFile(targetUrl, "replace")
  }


  /**
   * Watches playback while the quality selector is on "Auto": three consecutive 2s samples with
   * under ~1.5s of buffered-ahead video mean the network can't sustain the current variant, so
   * step one rung down the ladder (position preserved via pendingSeekPosition). A concrete user
   * choice disables the watchdog until Auto is picked again.
   */
  private fun startAutoQualityWatchdog() {
    qualityWatchdogJob?.cancel()
    autoStallStrikes = 0
    qualityWatchdogJob = lifecycleScope.launch {
      while (isActive) {
        kotlinx.coroutines.delay(2000)
        if (viewModel.currentAnimeQualityId.value != "Auto") continue
        val stream = currentAnimeStream ?: continue
        val ladder = orderedConcreteQualities(stream.qualities)
        if (ladder.size < 2) continue
        val pos = MPVLib.getPropertyDouble("time-pos") ?: continue
        if (pos <= 0) continue // still opening the file
        val cacheAhead = (MPVLib.getPropertyDouble("demuxer-cache-time") ?: 0.0) - pos
        autoStallStrikes = if (cacheAhead < 1.5) autoStallStrikes + 1 else 0
        if (autoStallStrikes < 3) continue
        autoStallStrikes = 0
        // Рунг ищем и по учёту, и по факту mpv (путь без query): устаревший currentPlayingUrl
        // давал idx<0 и молча отключал авто-деградацию.
        val currentUrl = currentPlayingUrl
        val playingPath = mpvPlayingUrl()?.substringBefore('?')
        val idx = ladder.indexOfFirst { pair ->
          pair.second == currentUrl || pair.second.substringBefore('?') == playingPath
        }
        if (idx < 0) continue
        val next = ladder.getOrNull(idx + 1) ?: run {
          Log.i(TAG, "Auto watchdog: already at the lowest quality (${ladder.last().first})")
          // Bottom rung still stalls: another dub/provider is the last automatic remedy.
          tryAlternativeQomSource()
          return@launch
        }
        Log.i(TAG, "Auto watchdog: ${ladder[idx].first} stalls, stepping down to ${next.first}")
        viewModel.setAutoQualityRungHint(next.first)
        if (!switchQualitySeamlessly(stream, next.first)) {
          pendingSeekPosition = MPVLib.getPropertyDouble("time-pos")
          currentPlayingUrl = next.second
          mpvLoadFile(next.second, "replace")
        }
      }
    }
  }

  /** Marks an app-issued position change so the segment-skip guard ignores the jump it causes. */
  private fun noteUserSeek() {
    viewModel.noteUserSeek()
  }

  /**
   * Watches played position while a file is loaded: a forward jump far beyond real-time speed
   * means mpv skipped dead HLS segments — seek back to the last good position once the network
   * (or vpn route) recovered. Corrections are rate-limited through [noteUserSeek]; paused
   * playback never jumps, so it needs no explicit handling.
   */
  private fun startSegmentSkipGuard() {
    segmentSkipGuardJob?.cancel()
    segmentSkipGuardJob = lifecycleScope.launch {
      var lastPos = -1.0
      while (isActive) {
        kotlinx.coroutines.delay(1500)
        // Во время бесшовного переключения позиция на мгновение дёргается (новый demuxer
        // открывается с нуля и сразу перематывается) — не принимаем по ней решений.
        if (pendingSeamlessQualityUrl != null) continue
        val pos = MPVLib.getPropertyDouble("time-pos") ?: continue
        val prev = lastPos
        lastPos = pos
        val now = android.os.SystemClock.elapsedRealtime()
        if (prev >= 0 && pos > prev + 12.0 && now - viewModel.lastUserSeekAtMs > 10_000) {
          Log.i(TAG, "Playback jumped forward ${(pos - prev).toInt()}s (skipped segments) — seeking back to ${prev.toInt()}s")
          noteUserSeek()
          MPVLib.command("seek", prev.toString(), "absolute")
        }
      }
    }
  }

  private fun applyHttpHeaders(headers: Map<String, String>) {
    headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.let {
      MPVLib.setPropertyString("user-agent", it.value)
    }
    val headersString = headers
      .filterKeys { !it.equals("user-agent", ignoreCase = true) }
      .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
      .joinToString(",")
    MPVLib.setPropertyString("http-header-fields", headersString)
  }

  private fun headersFromExtras(extras: Bundle): Map<String, String> {
    val values = extras.getStringArray("headers") ?: return emptyMap()
    return values.asSequence()
      .chunked(2)
      .filter { it.size == 2 && it[1].isNotBlank() }
      .associate { it[0] to it[1] }
  }

  /**
   * Adds subtitle tracks from intent extras.
   *
   * This method checks the intent extras for the "subs" key, which contains a list
   * of subtitle URIs to add. It also checks for the "subs.enable" key, which contains
   * a list of subtitle URIs to enable.
   *
   * @param extras Bundle containing subtitle URIs
   */
  private fun addSubtitlesFromExtras(extras: Bundle) {
    if (!extras.containsKey("subs")) return

    val subList = Utils.getParcelableArray<Uri>(extras, "subs")
    val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

    lifecycleScope.launch(Dispatchers.Default) {
      for (suburi in subList) {
        val subfile = suburi.resolveUri(this@PlayerActivity) ?: continue
        val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

        Log.v(TAG, "Adding subtitles from intent extras: $subfile")
        MPVLib.command("sub-add", subfile, flag)
      }
    }
  }

  /**
   * Sets HTTP headers from intent extras for network playback.
   *
   * This method checks the intent extras for the "headers" key, which contains a list
   * of HTTP headers to set. It sets the User-Agent header and any additional headers
   * specified in the list.
   *
   * Also automatically adds Referer header based on the URL origin if not already provided.
   *
   * @param extras Bundle containing HTTP headers
   */
  private fun setHttpHeadersFromExtras(extras: Bundle?) {
    // Build header map starting with auto-detected referer
    val headerMap = mutableMapOf<String, String>()

    // Automatically extract and set referer domain from the URL
    val uri = extractUriFromIntent(intent)
    if (uri != null && HttpUtils.isNetworkStream(uri)) {
      HttpUtils.extractRefererDomain(uri)?.let { referer ->
        headerMap["Referer"] = referer
        Log.d(TAG, "Auto-detected Referer: $referer")
      }
    }

    // Process headers from extras (these can override the auto-detected referer)
    extras?.getStringArray("headers")?.let { headers ->
      if (headers.isEmpty()) return@let

      if (headers[0].startsWith("User-Agent", ignoreCase = true)) {
        MPVLib.setPropertyString("user-agent", headers[1])
      }

      if (headers.size > 2) {
        headers
          .asSequence()
          .drop(2)
          .chunked(2)
          .filter { it.size == 2 }
          .forEach { (key, value) ->
            headerMap[key] = value
          }
      }
    }

    // Set all headers in MPV
    if (headerMap.isNotEmpty()) {
      val headersString = headerMap
        .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
        .joinToString(",")

      MPVLib.setPropertyString("http-header-fields", headersString)
      Log.d(TAG, "Set HTTP headers: $headersString")
    }
  }

  /**
   * Sets HTTP headers for a specific URI (used for playlist items).
   * Automatically extracts and sets the Referer header based on the URI origin.
   *
   * @param uri The URI to extract referer from and set headers for
   */
  private fun setHttpHeadersForUri(uri: Uri) {
    if (!HttpUtils.isNetworkStream(uri)) return

    val headerMap = mutableMapOf<String, String>()

    // Automatically extract and set referer domain from the URI
    HttpUtils.extractRefererDomain(uri)?.let { referer ->
      headerMap["Referer"] = referer
      Log.d(TAG, "Auto-detected Referer for playlist item: $referer")
    }

    // Set all headers in MPV
    if (headerMap.isNotEmpty()) {
      val headersString = headerMap
        .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
        .joinToString(",")

      MPVLib.setPropertyString("http-header-fields", headersString)
      Log.d(TAG, "Set HTTP headers for playlist item: $headersString")
    }
  }

  /**
   * Parses the file path from the intent.
   *
   * This method checks the intent action and data to determine the file path.
   * It supports the following actions:
   * - ACTION_VIEW: The file path is contained in the intent data.
   * - ACTION_SEND: The file path is contained in the intent extras.
   *
   * @param intent The intent containing the file URI
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromIntent(intent: Intent): String? =
    when (intent.action) {
      Intent.ACTION_VIEW -> intent.data?.resolveUri(this)
      Intent.ACTION_SEND -> parsePathFromSendIntent(intent)
      else -> intent.getStringExtra("uri")
    }

  /**
   * Parses the file path from a SEND intent.
   *
   * This method checks the intent extras for the file path.
   *
   * @param intent The SEND intent
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromSendIntent(intent: Intent): String? =
    if (intent.hasExtra(Intent.EXTRA_STREAM)) {
      val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
      }
      uri?.resolveUri(this@PlayerActivity)
    } else {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
        val uri = text.trim().toUri()
        if (uri.isHierarchical && !uri.isRelative) {
          uri.resolveUri(this)
        } else {
          null
        }
      }
    }

  /**
   * Extracts and resolves the file name from the intent.
   *
   * @param intent The intent containing the file URI
   * @return The display name of the file, or empty string if not found
   */
  private fun getFileName(intent: Intent): String {
    // First check if a custom title/filename was provided via intent extras
    intent.getStringExtra("title")?.let { return it }
    intent.getStringExtra("filename")?.let { return it }

    val uri = extractUriFromIntent(intent) ?: return ""

    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Extracts filename from URI, handling URL encoding and network URLs properly.
   * For network streams, returns a temporary name that will be updated async via HTTP headers.
   *
   * @param uri The URI to extract filename from
   * @return The extracted filename
   */
  private fun extractFileNameFromUri(uri: Uri): String {
    // For HTTP/HTTPS URLs, extract from path (will be updated async via HTTP headers)
    if (HttpUtils.isNetworkStream(uri)) {
      // Get the last path segment and decode URL encoding
      val path = uri.path ?: return uri.host ?: "Network Stream"
      val lastSegment = path.substringAfterLast("/")

      if (lastSegment.isNotBlank()) {
        // Decode URL encoding (e.g., %20 -> space)
        return try {
          java.net.URLDecoder.decode(lastSegment, "UTF-8")
            .substringBefore("?") // Remove query parameters
            .substringBefore("#") // Remove fragments (only for network streams)
            .takeIf { it.isNotBlank() } ?: uri.host ?: "Network Stream"
        } catch (e: Exception) {
          lastSegment
            .substringBefore("?")
            .substringBefore("#")
        }
      }

      // If no filename in path, use hostname
      return uri.host ?: "Network Stream"
    }

    // For file:// and content:// URIs - preserve # characters as they're part of the filename
    val lastSegment = uri.lastPathSegment?.substringAfterLast("/") ?: uri.path ?: "Unknown Video"
    
    // For local files, only decode URL encoding but preserve # characters
    return try {
      java.net.URLDecoder.decode(lastSegment, "UTF-8")
    } catch (e: Exception) {
      lastSegment
    }
  }

  /**
   * Gets the display title for a playlist item URI.
   *
   * @param uri The URI to get the title for
   * @return The display name/title of the file
   */
  internal fun getPlaylistItemTitle(uri: Uri): String {
    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Plays a playlist item by index.
   *
   * @param index The index of the playlist item to play
   */
  internal fun playPlaylistItem(index: Int) {
    if (index in playlist.indices) {
      loadPlaylistItem(index)
    }
  }

  /**
   * Extracts the URI from the intent based on intent type.
   *
   * @param intent The intent to extract URI from
   * @return The extracted URI, or null if not found
   */
  private fun extractUriFromIntent(intent: Intent): Uri? =
    if (intent.type == "text/plain") {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri()
    } else {
      intent.data ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
      }
    }

  /**
   * Queries the content resolver to get the display name for a URI.
   *
   * @param uri The URI to query
   * @return The display name, or null if not found
   */
  private fun getDisplayNameFromUri(uri: Uri): String? =
    runCatching {
      contentResolver
        .query(
          uri,
          arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.onFailure { e ->
      Log.e(TAG, "Error getting display name from URI", e)
    }.getOrNull()

  /**
   * Converts the intent URI to a playable URI string for MPV.
   *
   * @param intent The intent containing the file URI
   * @return A playable URI string, or null if unable to resolve
   */
  private fun getPlayableUri(intent: Intent): String? {
    val uri = parsePathFromIntent(intent) ?: return null
    return if (uri.startsWith("content://")) {
      uri.toUri().openContentFd(this)
    } else {
      uri
    }
  }

  /**
   * Handles device configuration changes.
   *
   * @param newConfig The new configuration
   */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    if (isReady) {
      handleConfigurationChange()
    }
  }

  /**
   * Handles configuration changes by updating video aspect ratio.
   */
  private fun handleConfigurationChange() {
    if (!isInPictureInPictureMode) {
      // Configuration changes don't affect aspect ratio
    } else {
      viewModel.hideControls()
    }
  }

  // ==================== MPV Event Observers ====================

  /**
   * Observer callback for MPV property changes (Long values).
   * Handles video width and height changes.
   *
   * @param property The property name that changed
   * @param value The new Long value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: Long,
  ) {
    when (property) {
      "video-params/w",
      "video-params/h" -> {
        // Safety check: don't access MPV during cleanup
        if (!mpvInitialized || player.isExiting || isFinishing) return

        viewModel.setVideoResolution(
          MPVLib.getPropertyInt("video-params/w"),
          MPVLib.getPropertyInt("video-params/h"),
        )
        val aspect = player.getVideoOutAspect()
        Log.d(TAG, "Video dimension changed: $property, aspect: $aspect")
        pipHelper.updatePictureInPictureParams()
        // Update orientation when video dimensions change (fixes Video orientation mode)
        if (playerPreferences.orientation.get() == PlayerOrientation.Video && aspect != null) {
          setOrientation()
        }

        // NOTE: Anime4K shaders are NOT re-applied here. Re-issuing glsl-shaders on every
        // dimension change forces a VO reconfiguration (visible flicker) and the option-string
        // path used by applyAnime4KShaders() is a no-op once mpv is initialized anyway.
        // Shaders are applied once at init and only on explicit user mode/quality changes.
      }
      "vo-drop-frame-count",
      "frame-drop-count" -> {
        if (!mpvInitialized || player.isExiting || isFinishing) return
        checkLag(value)
      }
    }
  }

  /**
   * Observer callback for MPV property changes (Boolean values).
   * Handles pause state and end-of-file events.
   *
   * @param property The property name that changed
   * @param value The new Boolean value
   */
  internal fun onObserverEvent(
    property: String,
    value: Boolean,
  ) {
    when (property) {
      "pause" -> {
        handlePauseStateChange(value)
        // Ensure isReady is set when playback starts
        if (!value && !isReady) {
          isReady = true
        }
      }
      "eof-reached" -> handleEndOfFile(value)
    }
  }

  /**
   * Handles pause state changes by managing screen-on flag and MediaSession state.
   *
   * @param isPaused true if playback is paused, false if playing
   */
  private fun handlePauseStateChange(isPaused: Boolean) {
    if (isPaused) {
      // Only clear keep-screen-on if the preference is NOT enabled
      if (!playerPreferences.keepScreenOnWhenPaused.get()) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    } else {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    updateMediaSessionPlaybackState(!isPaused)
    runCatching {
      if (isInPictureInPictureMode) {
        pipHelper.updatePictureInPictureParams()
      }
    }.onFailure { /* Silently ignore PiP update failures */ }
  }

  /**
   * Handles end-of-file event by playing next in playlist if available, otherwise finishing activity if configured.
   *
   * @param isEof true if end of file reached
   */
  private fun handleEndOfFile(isEof: Boolean) {
    if (isEof) {
      // Check if we should repeat the current file
      if (viewModel.shouldRepeatCurrentFile()) {
        noteUserSeek()
        MPVLib.command("seek", "0", "absolute")
        viewModel.unpause()
        return
      }

      // Handle playlist playback
      if (playlist.isNotEmpty()) {
        val hasNextItem = if (viewModel.shuffleEnabled.value) {
          shuffledPosition < shuffledIndices.size - 1
        } else {
          playlistIndex < playlist.size - 1
        }

        // Check if autoplay next video is enabled
        val autoplayEnabled = playerPreferences.autoplayNextVideo.get()

        if (hasNextItem && (autoplayEnabled || viewModel.shouldRepeatPlaylist())) {
          // Play next item in playlist
          playNext()
        } else if (viewModel.shouldRepeatPlaylist()) {
          // At end of playlist with repeat ALL: restart from beginning
          if (viewModel.shuffleEnabled.value) {
            // Regenerate shuffle order and start from beginning
            generateShuffledIndices()
            shuffledPosition = 0
            playlistIndex = shuffledIndices[0]
            loadPlaylistItem(playlistIndex)
          } else {
            // Normal mode: restart from index 0
            playlistIndex = 0
            loadPlaylistItem(0)
          }
        } else if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
          // No autoplay or no next item, end of playlist: close if setting is enabled
          finishAndRemoveTask()
        }
        // If autoplay is off and closeAfterReachingEndOfVideo is off, just stay on current video
      } else {
        // Single video playback (no playlist): конец серии аниме/сериала не закрывает плеер —
        // показываем оверлей «Следующая серия» с обратным отсчётом (нажатие или истечение
        // включают её). Закрытие по префу остаётся для последних серий и одиночных видео.
        val nextEpisode = nextEpisodeNumberOrNull()
        if (nextEpisode != null) {
          flushOutgoingEpisodeProgress()
          viewModel.showNextEpisodeOverlay(nextEpisode, NEXT_EPISODE_COUNTDOWN_SECONDS)
          viewModel.showControls()
          startNextEpisodeCountdown(nextEpisode)
        } else if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
          finishAndRemoveTask()
        }
      }
    }
  }

  /** Номер следующей серии в списке эпизодов игрока; null, когда текущая — последняя (или списка нет). */
  private fun nextEpisodeNumberOrNull(): Int? {
    val current = viewModel.currentAnimeEpisodeNumber.value ?: return null
    val episodes = viewModel.animeEpisodes.value
    if (episodes.isEmpty()) return null
    val idx = episodes.indexOfFirst { it.number == current }
    if (idx < 0 || idx + 1 >= episodes.size) return null
    return episodes[idx + 1].number
  }

  /** Тикает раз в секунду и по нулю запускает следующую серию через штатный выбор эпизода. */
  private fun startNextEpisodeCountdown(nextEpisode: Int) {
    nextEpisodeCountdownJob?.cancel()
    nextEpisodeCountdownJob = lifecycleScope.launch {
      while (viewModel.nextEpisodeCountdown.value > 0) {
        kotlinx.coroutines.delay(1000)
        if (isFinishing || isDestroyed) return@launch
        viewModel.setNextEpisodeCountdown(viewModel.nextEpisodeCountdown.value - 1)
      }
      viewModel.hideNextEpisodeOverlay()
      viewModel.onAnimeEpisodeSelected?.invoke(nextEpisode)
    }
  }

  /** Любой запуск новой загрузки гасит оверлей и отсчёт. */
  internal fun cancelNextEpisodeCountdown() {
    nextEpisodeCountdownJob?.cancel()
    nextEpisodeCountdownJob = null
    viewModel.hideNextEpisodeOverlay()
  }

  /**
   * Observer callback for MPV property changes (MPVNode values).
   *
   * This method is called when an MPV property (with MPVNode value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new MPVNode value
   */
  internal fun onObserverEvent(
    property: String,
    value: MPVNode,
  ) {
    // Currently no MPVNode properties are handled
  }

  /**
   * Observer callback for MPV property changes (Double values).
   *
   * This method is called when an MPV property (with Double value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new Double value
   */
  internal fun onObserverEvent(
    property: String,
    value: Double,
  ) {
    // Handle Double properties
    when (property) {
      "video-params/aspect" -> {
        // Safety check: don't access MPV during cleanup
        if (!mpvInitialized || player.isExiting || isFinishing) return

        val aspect = player.getVideoOutAspect()
        Log.d(TAG, "video-params/aspect changed: $aspect")
        pipHelper.updatePictureInPictureParams()
        // Update orientation when video aspect ratio changes (fixes Video orientation mode)
        // BUT: Don't update if aspect is being overridden (stretch/custom aspect mode)
        // to prevent infinite orientation switching loop
        val aspectOverride = MPVLib.getPropertyDouble("video-aspect-override") ?: -1.0
        if (playerPreferences.orientation.get() == PlayerOrientation.Video && 
            aspect != null && 
            aspectOverride <= 0.0) {
          setOrientation()
        }
      }
    }
  }

  /**
   * Observer callback for MPV property changes (String values).
   *
   * This method is called when an MPV property (with String value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new String value
   */
  internal fun onObserverEvent(
    property: String,
    value: String,
  ) {
    // Currently no String properties are handled
  }

  /**
   * Observer callback for MPV property changes (no value parameter).
   * Handles properties with no value parameter.
   *
   * @param property The property name that changed
   */
  internal fun onObserverEvent(property: String) {
    // Currently no properties use this signature
  }

  /**
   * Handles MPV core events such as file loaded and playback restart.
   *
   * Called by the player when critical playback events occur.
   *
   * @param eventId The MPV event ID
   * @param data Event payload node (reason of END_FILE, etc.)
   */
  internal fun event(
    eventId: Int,
    data: MPVNode? = null,
  ) {
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
        // A pending episode/dub switch finished buffering — drop the loading overlay now.
        AppDiagnostics.event("FILE_LOADED")
        streamLoadRetryAction = null
        streamLoadRetries = 0
        finishStreamLoadIndicator()
        viewModel.setPropertyPollingEnabled(true)
        startSegmentSkipGuard()
        handleFileLoaded()
        isReady = true
      }

      // Playback returned to idle (failed load, playlist end): time-pos/duration polling
      // would only flood logcat with "was unavailable" lines from the prebuilt mpv JNI.
      @Suppress("DEPRECATION") // mpv пометил событие deprecated, но событие всё ещё приходит из JNI
      MPVLib.MpvEvent.MPV_EVENT_IDLE -> viewModel.setPropertyPollingEnabled(false)

      MPVLib.MpvEvent.MPV_EVENT_END_FILE -> eventEndFile(data)

      // The core terminated itself — without this the session keeps issuing loadfiles into a
      // zombie handle (see [onMpvCoreShutdown] for the live failure this recovers from).
      MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> onMpvCoreShutdown()

      MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
        player.isExiting = false
        if (!isReady) {
          isReady = true
        }
      }
    }
  }

  /**
   * END_FILE with its reason node: a loadfile that mpv could not open (dead/expired CDN url,
   * HTML page handed to the demuxer, network blackhole). Previously unhandled — the loading
   * overlay just timed out into a black screen with no error, no retry.
   *
   * Reacts ONLY while our own tracked load attempt is pending: ordinary end-of-playback and
   * user-issued replaces arrive here too (reason=eof/stop) and must be ignored.
   */
  internal fun eventEndFile(data: MPVNode?) {
    if (!pendingStreamLoadIndicator) return
    val reason = runCatching { data?.asMap()?.get("reason")?.asString() }.getOrNull()
    Log.w(TAG, "MPV_EVENT_END_FILE while loading (reason=$reason)")
    AppDiagnostics.event("END_FILE while loading, reason=$reason")
    if (!reason.equals("error", ignoreCase = true)) return
    // mpv признал url мёртвым: 3-минутная ddbb-памка не должна отдавать те же токены
    // следующей попытке или запуску — токен, добытый под VPN, мёртв и после его выключения.
    intent.getIntExtra("movie_kinopoisk_id", 0)
      .takeIf { it > 0 }
      ?.let { DdbbStreamResolver.evictResolveCache(it) }

    val retry = streamLoadRetryAction
    if (retry != null && streamLoadRetries < MAX_STREAM_LOAD_RETRIES) {
      streamLoadRetries += 1
      lifecycleScope.launch {
        kotlinx.coroutines.delay(800)
        if (isFinishing || isDestroyed) return@launch
        Log.i(TAG, "Stream load failed in mpv — auto-retry $streamLoadRetries/$MAX_STREAM_LOAD_RETRIES")
        AppDiagnostics.event("stream auto-retry $streamLoadRetries/$MAX_STREAM_LOAD_RETRIES")
        retry()
      }
    } else {
      streamLoadRetryAction = null
      // Same-target retries are spent: another dub/provider is the next thing to try before
      // giving up — a dead CDN must not end in an error card while alternatives are untried.
      if (tryAlternativeQomSource()) return
      finishStreamLoadIndicator()
      showStreamLoadError(streamLoadErrorMessage(slowStart = false))
    }
  }

  /**
   * Error card text. When every dub row comes from ONE provider, no dub switch can help — the
   * CDN itself is unreachable (Warp/AmneziaWG egress IPs are commonly rejected by it), so the
   * card points at the VPN explicitly instead of offering switches that will fail the same way.
   */
  /** Error card + diagnostic event: the exact text the user saw must land in the report. */
  private fun showStreamLoadError(message: String) {
    AppDiagnostics.event("error card: ${message.take(140)}")
    viewModel.setPendingResolveError(message)
  }

  private fun streamLoadErrorMessage(slowStart: Boolean): String {
    val base = if (slowStart) "Поток не удаётся загрузить: сеть или CDN слишком медленные."
      else "Не удалось открыть видеопоток."
    val singleSource = viewModel.animeTranslations.value.map { it.source }.distinct().size <= 1
    return if (singleSource) base +
      " Если включён VPN (Warp/AmneziaWG) — выключите его и нажмите «Повторить»: туннель может блокировать CDN."
    else base + " Попробуйте другую озвучку или качество."
  }

  /**
   * Handles the file loaded event from MPV.
   * Initializes playback state, loads saved playback data, restores custom settings,
   * applies user preferences, and sets up metadata and media session.
   */
  private fun handleFileLoaded() {
    // video-params of the previous file are stale until the new one reports its own
    viewModel.setVideoResolution(null, null)
    // Extract fileName from intent only if not already set
    // This preserves fileName set in onNewIntent or onCreate
    if (fileName.isBlank()) {
      fileName = getFileName(intent)
      // Ensure fileName is not blank - use a fallback if necessary
      if (fileName.isBlank()) {
        fileName = intent.data?.lastPathSegment ?: "Unknown Video"
      }
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    } else if (mediaIdentifier.isBlank()) {
      // If fileName was already set, but mediaIdentifier is missing, set it for safety
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    }

    // Start media notification service (like YouTube - always show notification)
    startBackgroundPlayback()

    // Reset AB loop values when video changes
    viewModel.clearABLoop()

    setIntentExtras(intent.extras)

    lifecycleScope.launch(Dispatchers.IO) {
      // Load playback state (will skip track restoration if preferred language configured)
      val hasState = loadVideoPlaybackState(fileName)

      // Apply track selection logic (defaults only apply when no saved state)
      trackSelector.onFileLoaded(hasState)

      // Apply default zoom only if there's no saved state
      if (!hasState) {
        withContext(Dispatchers.Main) {
          val zoomPreference = playerPreferences.defaultVideoZoom.get()
          MPVLib.setPropertyDouble("video-zoom", zoomPreference.toDouble())
          viewModel.setVideoZoom(zoomPreference)
        }
      }

      // Apply saved aspect ratio setting
      withContext(Dispatchers.Main) {
        val savedAspect = playerPreferences.defaultVideoAspect.get()
        val savedCustomRatio = playerPreferences.defaultCustomAspectRatio.get()
        
        if (savedCustomRatio > 0) {
          // Apply custom aspect ratio
          viewModel.setCustomAspectRatio(savedCustomRatio)
        } else {
          // Apply standard aspect mode (Fit, Crop, or Stretch)
          viewModel.changeVideoAspect(savedAspect, showUpdate = false)
        }
      }
    }

    // Save to recently played when video actually loads and plays
    lifecycleScope.launch(Dispatchers.IO) {
      if (playlist.isNotEmpty()) {
        // For playlist items, save using the current URI
        // All items are loaded, so playlistIndex is the direct index
        if (playlistIndex >= 0 && playlistIndex < playlist.size) {
          saveRecentlyPlayedForUri(playlist[playlistIndex], fileName)
        } else {
          Log.w(TAG, "Cannot save recently played: invalid playlist index $playlistIndex (playlist size: ${playlist.size})")
        }
      } else {
        // For non-playlist videos, use the original saveRecentlyPlayed
        saveRecentlyPlayed()
      }
    }

    // Set orientation immediately (defaults to landscape for Video mode)
    setOrientation()
    if (playerPreferences.orientation.get() == PlayerOrientation.Video) {
      // For Video mode, try to update orientation after a short delay if
      // video dimensions changed
      lifecycleScope.launch {
        kotlinx.coroutines.delay(100)
        if (mpvInitialized && !player.isExiting && !isFinishing) {
          val aspect = player.getVideoOutAspect()
          Log.d(TAG, "handleFileLoaded - Video mode, aspect after delay: $aspect")
          if (aspect != null && aspect > 0) {
            setOrientation()
          }
        }
      }
    }

    applySubtitlePreferences()

    // Kinoshka titles must never show raw URLs/playlist junk from mpv's media-title: our own
    // fileName always wins, even for HLS where the old code let mpv "provide" the URL instead.
    if (!isCurrentStreamM3U() || fileName.isNotBlank()) {
      MPVLib.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    viewModel.unpause()

    // Restore the saved position. Setting time-pos directly on a freshly loaded HLS stream
    // (before segments are buffered) forces a seek that reads as a visible "skip a few seconds".
    // Defer it briefly so the demuxer has pulled some data first; use absolute+exact seek for
    // accuracy. Only applied once per file via pendingSeekPosition being cleared.
    pendingSeekPosition?.let { pos ->
      pendingSeekPosition = null
      if (pos > 0f) {
        lifecycleScope.launch {
          kotlinx.coroutines.delay(400)
          if (mpvInitialized && !player.isExiting && !isFinishing) {
            noteUserSeek()
            MPVLib.command("seek", pos.toString(), "absolute+exact")
          }
        }
      }
    }

    if (subtitlesPreferences.autoloadMatchingSubtitles.get()) {
      lifecycleScope.launch {
        // For network files played via proxy (SMB/WebDAV/FTP), use the original network file path
        val networkFilePath = intent.getStringExtra("network_file_path")
        val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

        if (networkFilePath != null && networkConnectionId != -1L) {
          // Pass network file path and connection ID for subtitle discovery
          SubtitleOps.autoloadSubtitles(
            videoFilePath = networkFilePath,
            videoFileName = fileName,
            networkConnectionId = networkConnectionId,
          )
        } else {
          // Regular file or direct network stream
          val filePath = parsePathFromIntent(intent)
          if (filePath != null) {
            SubtitleOps.autoloadSubtitles(
              videoFilePath = filePath,
              videoFileName = fileName,
            )
          }
        }
      }
    }

    updateMediaSessionMetadata(
      title = fileName,
      durationMs = (MPVLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L,
    )
    updateMediaSessionPlaybackState(isPlaying = true)

    // Asynchronously fetch better filename from HTTP headers for network streams
    fetchNetworkStreamTitle()
  }

  /**
   * Fetches a better title from HTTP headers for network streams asynchronously.
   * Updates the title in UI, MPV, and media session if a better name is found.
   */
  private fun fetchNetworkStreamTitle() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val uri = extractUriFromIntent(intent)
        if (uri == null || !HttpUtils.isNetworkStream(uri)) {
          return@launch
        }

        // Skip fetching for m3u/m3u8 streams - let MPV provide the title
        if (isCurrentStreamM3U()) {
          Log.d(TAG, "Skipping title fetch for m3u/m3u8 stream: $uri")
          return@launch
        }

        // Skip fetching if title was provided in intent extras (e.g. from Jellyfin or other external launchers)
        // This prevents overwriting the correct title with a generic filename from the URL (like "stream")
        if (intent.hasExtra("title") || intent.hasExtra("filename")) {
          Log.d(TAG, "Skipping title fetch because title was explicitly provided in intent: $fileName")
          return@launch
        }

        // Skip fetching for local proxy URLs (SMB/WebDAV/FTP files)
        // These already have correct filename from intent extras
        val host = uri.host?.lowercase()
        if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
          Log.d(TAG, "Skipping title fetch for local proxy URL: $uri")
          return@launch
        }

        val url = uri.toString()
        Log.d(TAG, "Fetching title from network stream: $url")

        val betterFilename = HttpUtils.extractFilenameFromUrl(url)
        if (betterFilename != null && betterFilename.isNotBlank() &&
          betterFilename != fileName &&
          betterFilename != uri.host &&
          betterFilename != "Network Stream"
        ) {

          Log.d(TAG, "Found better filename from HTTP headers: $betterFilename")

          // Update fileName
          fileName = betterFilename

          // DO NOT update mediaIdentifier - keep the original identifier for playback state consistency
          // The URI hash in mediaIdentifier ensures position is saved/loaded correctly even if filename changes

          // Update MPV title
          withContext(Dispatchers.Main) {
            MPVLib.setPropertyString("force-media-title", fileName)
            viewModel.setMediaTitle(fileName)

            // Update media session
            val durationMs = (MPVLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
            updateMediaSessionMetadata(
              title = fileName,
              durationMs = durationMs,
            )

            // Update background service if connected
            if (serviceBound && mediaPlaybackService != null) {
              val artist = runCatching { MPVLib.getPropertyString("metadata/artist") }.getOrNull() ?: ""
              val thumbnail = runCatching { MPVLib.grabThumbnail(1080) }.getOrNull()
              mediaPlaybackService?.setMediaInfo(title = fileName, artist = artist, thumbnail = thumbnail)
            }
          }

          // Update recently played with the parsed video title, duration, and file size
          val filePath = when (uri.scheme) {
            "file" -> uri.path ?: uri.toString()
            "content" -> {
              contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else null
              } ?: uri.toString()
            }

            else -> uri.toString()
          }

          // Get duration and file size from MPV
          val updatedDuration = runCatching {
            (MPVLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
          }.getOrDefault(0L)

          val updatedFileSize = runCatching {
            // Try multiple properties to get file size
            MPVLib.getPropertyDouble("file-size")?.toLong()
              ?: MPVLib.getPropertyDouble("stream-end")?.toLong()
              ?: 0L
          }.getOrDefault(0L)

          // Get video resolution from MPV
          val updatedWidth = runCatching {
            MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
          }.getOrDefault(0)

          val updatedHeight = runCatching {
            MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
          }.getOrDefault(0)

          // Update metadata without thumbnail
          runCatching {
            RecentlyPlayedOps.updateVideoMetadata(
              filePath,
              fileName,
              updatedDuration,
              updatedFileSize,
              updatedWidth,
              updatedHeight,
            )
            Log.d(
              TAG,
              "Updated recently played metadata: $fileName (duration: ${updatedDuration}ms, size: ${updatedFileSize}B, resolution: ${updatedWidth}x${updatedHeight}) for $filePath",
            )
          }.onFailure { e ->
            Log.e(TAG, "Error updating video metadata in recently played", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error fetching network stream title", e)
      }
    }
  }

  /**
   * Applies all saved subtitle preferences when a file is loaded.
   * This ensures subtitle customizations (font, colors, position, etc.) persist across videos.
   */
  private fun applySubtitlePreferences() {
    // Typography settings
    MPVLib.setPropertyString("sub-font", subtitlesPreferences.font.get())
    MPVLib.setPropertyString("secondary-sub-font", subtitlesPreferences.font.get())
    MPVLib.setPropertyInt("sub-font-size", subtitlesPreferences.fontSize.get())
    MPVLib.setPropertyBoolean("sub-bold", subtitlesPreferences.bold.get())
    MPVLib.setPropertyBoolean("sub-italic", subtitlesPreferences.italic.get())
    MPVLib.setPropertyString("sub-justify", subtitlesPreferences.justification.get().value)
    MPVLib.setPropertyString("sub-border-style", subtitlesPreferences.borderStyle.get().value)
    MPVLib.setPropertyInt("sub-outline-size", subtitlesPreferences.borderSize.get())
    MPVLib.setPropertyInt("sub-shadow-offset", subtitlesPreferences.shadowOffset.get())

    // Color settings
    MPVLib.setPropertyString("sub-color", subtitlesPreferences.textColor.get().toColorHexString())
    MPVLib.setPropertyString("sub-border-color", subtitlesPreferences.borderColor.get().toColorHexString())
    MPVLib.setPropertyString("sub-back-color", subtitlesPreferences.backgroundColor.get().toColorHexString())

    // Miscellaneous settings
    val overrideAssSubs = subtitlesPreferences.overrideAssSubs.get()
    MPVLib.setPropertyString("sub-ass-override", if (overrideAssSubs) "force" else "scale")
    MPVLib.setPropertyString("secondary-sub-ass-override", if (overrideAssSubs) "force" else "scale")

    val scaleByWindow = subtitlesPreferences.scaleByWindow.get()
    val scaleValue = if (scaleByWindow) "yes" else "no"
    MPVLib.setPropertyString("sub-scale-by-window", scaleValue)
    MPVLib.setPropertyString("sub-use-margins", scaleValue)

    MPVLib.setPropertyFloat("sub-scale", subtitlesPreferences.subScale.get())
    MPVLib.setPropertyInt("sub-pos", subtitlesPreferences.subPos.get())

    Log.d(TAG, "Applied subtitle preferences")
  }

  /**
   * Helper extension function to convert Int color to hex string for MPV
   */
  @OptIn(ExperimentalStdlibApi::class)
  private fun Int.toColorHexString() = "#" + this.toHexString().uppercase()

  /**
   * Saves the current playback state to the database.
   *
   * Uses lifecycleScope to save state; cancels previous pending saves.
   *
   * @param mediaTitle The title of the media being played
   */
  private fun saveVideoPlaybackState(mediaTitle: String) {
    if (mediaIdentifier.isBlank()) return

    // Cancel any previous pending save operation
    savePlaybackStateJob?.cancel()

    // Snapshot everything that depends on live mpv state synchronously. Callers switch files
    // immediately after this returns (episode change, playlist navigation), and reading
    // MPVLib inside the async block raced with loadfile, saving positions of the new file
    // under the old identifier.
    val snapshotPos = viewModel.pos ?: 0
    val snapshotDuration = viewModel.duration ?: 0
    val snapshotSpeed = runCatching { MPVLib.getPropertyDouble("speed") }.getOrNull() ?: DEFAULT_PLAYBACK_SPEED
    val snapshotZoom = runCatching { MPVLib.getPropertyDouble("video-zoom")?.toFloat() }.getOrNull() ?: 0f
    val snapshotSid = player.sid
    val snapshotSecondarySid = player.secondarySid
    val snapshotSubDelay = ((runCatching { MPVLib.getPropertyDouble("sub-delay") }.getOrNull() ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt()
    val snapshotSubSpeed = runCatching { MPVLib.getPropertyDouble("sub-speed") }.getOrNull() ?: DEFAULT_SUB_SPEED
    val snapshotAid = player.aid
    val snapshotAudioDelay =
      (
        (runCatching { MPVLib.getPropertyDouble("audio-delay") }.getOrNull() ?: 0.0) * MILLISECONDS_TO_SECONDS
        ).toInt()
    val snapshotExternalSubs = viewModel.externalSubtitles.joinToString("|")
    val oldIdentifier = mediaIdentifier

    // Launch new save job and track it
    savePlaybackStateJob = playbackStateSaveScope.launch {
      runCatching {
        val oldState = playbackStateRepository.getVideoDataByTitle(oldIdentifier)
        Log.d(TAG, "Saving playback state for: $mediaTitle (identifier: $oldIdentifier)")

        val lastPosition = calculateSavePosition(oldState)
        val duration = snapshotDuration
        val timeRemaining = if (duration > lastPosition) duration - lastPosition else 0

        val finalWatchedState = computeWatchedState(snapshotPos, duration, lastPosition, oldState?.hasBeenWatched == true)

        playbackStateRepository.upsert(
          PlaybackStateEntity(
            mediaTitle = oldIdentifier,
            lastPosition = lastPosition,
            playbackSpeed = snapshotSpeed,
            videoZoom = snapshotZoom,
            sid = snapshotSid,
            secondarySid = snapshotSecondarySid,
            subDelay = snapshotSubDelay,
            subSpeed = snapshotSubSpeed,
            aid = snapshotAid,
            audioDelay = snapshotAudioDelay,
            timeRemaining = timeRemaining,
            externalSubtitles = snapshotExternalSubs,
            hasBeenWatched = finalWatchedState,
          ),
        )

        if (finalWatchedState) {
          commitTitleLevelProgress(finalWatched = true)
        }
      }.onFailure { e ->
        Log.e(TAG, "Error saving playback state", e)
      }
    }
  }

  /**
   * Watched-state decision shared by every save path: the current position crossed the user's
   * watched threshold, the file ran to the end, or it was already flagged as watched.
   */
  private fun computeWatchedState(currentPos: Int, duration: Int, lastPosition: Int, previouslyWatched: Boolean): Boolean {
    val watchedThreshold = browserPreferences.watchedThreshold.get()
    val durationSeconds = duration.toFloat()

    // Check if we are at the end (effectively watched)
    // Using a small buffer (1s) to account for float inaccuracies or near-end stops
    val isFinished = (durationSeconds > 0) && (currentPos >= durationSeconds - 1)

    val progress = if (durationSeconds > 0) currentPos.toFloat() / durationSeconds else 0f
    val isCurrentlyWatched = progress >= (watchedThreshold / 100f)

    // Also check lastPosition in case we are saving partway through (though lastPosition might be 0 if finished)
    val oldProgress = if (durationSeconds > 0) lastPosition.toFloat() / durationSeconds else 0f
    val wasWatchedThisSession = oldProgress >= (watchedThreshold / 100f)

    return isCurrentlyWatched || isFinished || wasWatchedThisSession || previouslyWatched
  }

  /**
   * Pushes a "this title made progress" event into the Kinoshka library so the library folder,
   * the details header and the episode list stay in sync with what actually played.
   *
   * Anime goes through [UserStateStore.updateWatchedEpisode]; native movies mark the title as
   * completed; native series persist season/episode position and auto-complete when the final
   * episode of the run was watched through.
   */
  private fun commitTitleLevelProgress(finalWatched: Boolean) {
    if (!finalWatched) return
    val store = UserStateStore(this@PlayerActivity)

    when (currentNativePlaybackMode()) {
      NativePlaybackMode.MOVIE_SERIES -> {
        val context = movieSeriesContext ?: return
        val episode = context.currentEpisode
        val lastKey = context.episodes.maxOfOrNull { it.playerEpisodeKey }
        val finishedRun = lastKey != null && episode.playerEpisodeKey >= lastKey
        store.updateSeriesProgress(
          context.kinopoiskId,
          episode.seasonNumber,
          episode.episodeNumber,
          finished = finishedRun,
        )
        hd.kinoshka.app.data.cloud.CloudBackupManager.onLibraryChanged(this@PlayerActivity)
        // Диалог «Выберите серию» должен увидеть новую галочку без перезапуска плеера.
        viewModel.setWatchedSeriesProgress(episode.seasonNumber, episode.episodeNumber)
      }

      NativePlaybackMode.QUALITY_ONLY_MOVIE -> {
        val kinopoiskId = intent.getIntExtra("movie_kinopoisk_id", 0)
        if (kinopoiskId > 0) {
          store.markTitleWatched(kinopoiskId)
          hd.kinoshka.app.data.cloud.CloudBackupManager.onLibraryChanged(this@PlayerActivity)
        }
      }

      // Resolve never completed — no title-level progress exists to commit.
      NativePlaybackMode.PENDING_MOVIE -> return

      NativePlaybackMode.ANIME -> {
        val profileKey = libraryProfileKey() ?: return
        val animeTitle = intent.getStringExtra("anime_title").orEmpty()
        val currentEp = viewModel.currentAnimeEpisodeNumber.value ?: intent.getIntExtra("anime_current_episode", 1)
        val totalEps = viewModel.animeEpisodes.value.maxOfOrNull { it.number } ?: viewModel.animeEpisodes.value.size

        UserStateStore(this@PlayerActivity).updateWatchedEpisodeByKey(
          kinopoiskId = profileKey,
          animeTitle = animeTitle,
          episodeNum = currentEp,
          totalEpisodes = totalEps
        )
        viewModel.setWatchedEpisodesCount(currentEp)

        // Shikimori sync (only meaningful when the title actually maps to a Shikimori entry)
        val shikimoriId = intent.getIntExtra("anime_shikimori_id", 0)
        if (shikimoriId > 0) {
          val authStore = ShikimoriAuthStore(this@PlayerActivity)
          val authState = authStore.getAuthState()
          if (authState.isLoggedIn && authState.accessToken != null) {
            lifecycleScope.launch(Dispatchers.IO) {
              val api = ApiClient.shikimoriApi(this@PlayerActivity.cacheDir)
              val rates = runCatching { api.getUserAnimeRates(authState.userId) }.getOrNull()
              val existingRate = rates?.firstOrNull { it.targetId == shikimoriId }
              val newStatus = if (totalEps in 1..currentEp) "completed" else "watching"
              val authHeader = if (authState.accessToken.startsWith("Bearer ")) authState.accessToken else "Bearer ${authState.accessToken}"

              if (existingRate != null) {
                runCatching {
                  val updateRequest = hd.kinoshka.app.data.model.UserRateUpdateRequest(
                    userRate = hd.kinoshka.app.data.model.UserRateUpdateData(
                      status = newStatus,
                      episodes = currentEp
                    )
                  )
                  api.updateUserRate(authHeader, existingRate.id, updateRequest)
                }
              } else {
                runCatching {
                  val createRequest = hd.kinoshka.app.data.model.UserRateRequest(
                    userRate = hd.kinoshka.app.data.model.UserRateData(
                      userId = authState.userId,
                      targetId = shikimoriId,
                      targetType = "Anime",
                      status = newStatus,
                      episodes = currentEp
                    )
                  )
                  api.createUserRate(authHeader, createRequest)
                }
              }
            }
          }
        }
      }
    }
  }

  private fun currentNativePlaybackMode(): NativePlaybackMode {
    effectiveNativePlaybackMode?.let { return it }
    val name = intent.getStringExtra("playback_mode")
    return runCatching { NativePlaybackMode.valueOf(name ?: "") }.getOrDefault(NativePlaybackMode.ANIME)
  }

  /**
   * Library profile key this playback session must read/write. Anime launched from the Shikimori
   * section maps to shikimoriId + ANIME_ID_OFFSET; titles opened from regular search have no
   * shikimori mapping and their profiles live under the raw Kinopoisk id — without this fallback
   * every such title silently lost episode sync, library status and resume progress.
   */
  private fun libraryProfileKey(): Int? {
    val shikimoriId = intent.getIntExtra("anime_shikimori_id", 0)
    if (shikimoriId > 0) return shikimoriId + hd.kinoshka.app.data.model.ANIME_ID_OFFSET
    val kinopoiskId = intent.getIntExtra("movie_kinopoisk_id", 0)
    return kinopoiskId.takeIf { it > 0 }
  }

  /**
   * Feeds the global preference memory: sources/dubs picked in the player rise to the top of
   * future lists (the player's voiceover dropdown and the source-selection sheet).
   */
  private fun recordPlaybackUsage(source: hd.kinoshka.app.data.model.AnimeSourceType, dubTitle: String) {
    val store = UserStateStore(this)
    store.recordSourceUsage(source)
    // Readers (rememberedDubId, dropdown ranking) look up the splitDubTrack display title, so raw
    // titles like "Original" must be folded the same way or the favorite dub never restores.
    store.recordDubUsage(MovieNativeLauncher.splitDubTrack(dubTitle).first)
  }

  /**
   * Watches accrued playback time and, once a single file passes [MIN_WATCH_SECONDS_FOR_LIBRARY],
   * commits REAL progress to the library: flips the seeded profile to WATCHING and adds the
   * history entry. Merely pressing "Смотреть" must not surface anything in the library — only
   * actual viewing does. Keyed per media identifier, so every episode re-arms the commit.
   */
  private fun startPlaybackProgressLoop() {
    playbackProgressJob?.cancel()
    playbackProgressJob = lifecycleScope.launch {
      while (isActive) {
        kotlinx.coroutines.delay(5000)
        if (!mpvInitialized || player.isExiting || isFinishing) continue
        val identifier = mediaIdentifier
        if (identifier.isBlank() || identifier == watchingCommittedFor) continue
        val pos = MPVLib.getPropertyDouble("time-pos") ?: continue
        if (pos < MIN_WATCH_SECONDS_FOR_LIBRARY) continue
        val key = libraryProfileKey() ?: continue

        watchingCommittedFor = identifier
        val store = UserStateStore(this@PlayerActivity)
        when (currentNativePlaybackMode()) {
          NativePlaybackMode.MOVIE_SERIES -> movieSeriesContext?.let { ctx ->
            store.updateSeriesProgress(
              ctx.kinopoiskId,
              ctx.currentEpisode.seasonNumber,
              ctx.currentEpisode.episodeNumber
            )
          }
          NativePlaybackMode.ANIME -> store.updateWatchedEpisodeByKey(
            kinopoiskId = key,
            animeTitle = intent.getStringExtra("anime_title").orEmpty(),
            episodeNum = viewModel.currentAnimeEpisodeNumber.value
              ?: intent.getIntExtra("anime_current_episode", 1),
            totalEpisodes = viewModel.animeEpisodes.value.maxOfOrNull { it.number } ?: 0,
            allowComplete = false,
          )
          NativePlaybackMode.QUALITY_ONLY_MOVIE -> {}
          // Resolve never completed — nothing meaningful to commit.
          NativePlaybackMode.PENDING_MOVIE -> {}
        }
        store.commitRealPlayback(key)
      }
    }
  }

  /**
   * Session-stable media identifier for Kinoshka streams. Resolved stream URLs rotate between
   * launches (kodik/ddbb race winner alternates, turbo CDN urls are signed), so hashing the URL
   * orphaned every saved position on the very next launch. Key by title/episode identity instead.
   * Null for non-Kinoshka playback — callers keep the legacy uri/fileName behaviour.
   */
  private fun stableKinoshkaIdentifier(episodeOverride: Int? = null): String? {
    // Effective mode (not the raw intent): PENDING_MOVIE resolves into QOM/MOVIE_SERIES and must
    // key identifiers by the resolved kind, or resume positions would rotate with the stream urls.
    return when (currentNativePlaybackMode()) {
      NativePlaybackMode.MOVIE_SERIES ->
        movieSeriesContext?.let {
          "ks_series_${it.kinopoiskId}_s${it.currentEpisode.seasonNumber}e${it.currentEpisode.episodeNumber}"
        }
      NativePlaybackMode.QUALITY_ONLY_MOVIE ->
        intent.getIntExtra("movie_kinopoisk_id", 0).takeIf { it > 0 }?.let { "ks_movie_$it" }
      NativePlaybackMode.ANIME -> {
        val episode = episodeOverride
          ?: intent.getIntExtra("anime_current_episode", -1).takeIf { it > 0 }
          ?: viewModel.currentAnimeEpisodeNumber.value
        val key = libraryProfileKey()
        if (episode != null && episode > 0 && key != null) "ks_anime_${key}_e$episode" else null
      }
      NativePlaybackMode.PENDING_MOVIE -> null
    }
  }

  /**
   * Persists progress of the episode that is about to be replaced BEFORE its media identifier is
   * swapped for the next one. Without this, switching episodes straight from a finished one lost
   * the fact it was ever watched: the Room flag and the library both ended up describing only the
   * new file.
   */
  private fun flushOutgoingEpisodeProgress() {
    if (mediaIdentifier.isBlank()) return
    val pos = viewModel.pos ?: 0
    val duration = viewModel.duration ?: 0
    if (duration <= 0 || pos <= 0) return

    // saveVideoPlaybackState snapshots mpv state synchronously and keys everything by the still-
    // current mediaIdentifier, so this must run before callers reassign it.
    saveVideoPlaybackState(fileName)
  }

  /**
   * Calculates the position to save based on user preferences.
   *
   * If "savePositionOnQuit" is not enabled, returns the previous saved position or 0.
   * If enabled, saves the current playback position unless at end of video.
   *
   * @param oldState Previous playback state if it exists
   * @return Position in seconds to save
   */
  private fun calculateSavePosition(oldState: PlaybackStateEntity?): Int {
    if (!playerPreferences.savePositionOnQuit.get()) {
      return oldState?.lastPosition ?: 0
    }

    val pos = viewModel.pos ?: 0
    val duration = viewModel.duration ?: 0
    return if (pos < duration - 1) pos else 0
  }

  /**
   * Loads and applies saved playback state from the database.
   *
   * @param mediaTitle The title of the media being played
   * @return true if saved state was found and applied, false otherwise
   */
  private suspend fun loadVideoPlaybackState(mediaTitle: String): Boolean {
    if (mediaIdentifier.isBlank()) return false

    return runCatching {
      val state = playbackStateRepository.getVideoDataByTitle(mediaIdentifier)

      applyPlaybackState(state)
      applyDefaultSettings(state)

      state != null
    }.onFailure { e ->
      Log.e(TAG, "Error loading playback state", e)
    }.getOrDefault(false)
  }

  /**
   * Applies saved playback state to MPV.
   *
   * Restores subtitle delay, audio delay, audio and track selections, and playback speed.
   * Also restores saved time position if enabled.
   *
   * @param state The saved playback state entity
   */
  private fun applyPlaybackState(state: PlaybackStateEntity?) {
    if (state == null) return

    val subDelay = state.subDelay / DELAY_DIVISOR
    val audioDelay = state.audioDelay / DELAY_DIVISOR

    // Restore external subtitles first
    if (state.externalSubtitles.isNotBlank()) {
      val externalSubUris = state.externalSubtitles.split("|").filter { it.isNotBlank() }
      Log.d(TAG, "Restoring ${externalSubUris.size} external subtitle(s)")

      for (subUri in externalSubUris) {
        viewModel.addSubtitle(Uri.parse(subUri), select = false, silent = true)
      }
    }

    // Always restore subtitle and audio tracks from saved state
    // User's manual selection has highest priority
    if (state.sid > 0) {
      player.sid = state.sid
      Log.d(TAG, "Restored primary subtitle track: ${state.sid} (user selection)")
    }

    if (state.secondarySid > 0) {
      player.secondarySid = state.secondarySid
      Log.d(TAG, "Restored secondary subtitle track: ${state.secondarySid} (user selection)")
    }

    if (state.aid > 0) {
      player.aid = state.aid
      Log.d(TAG, "Restored audio track: ${state.aid} (user selection)")
    }

    MPVLib.setPropertyDouble("sub-delay", subDelay)
    MPVLib.setPropertyDouble("speed", state.playbackSpeed)
    MPVLib.setPropertyDouble("audio-delay", audioDelay)
    MPVLib.setPropertyDouble("sub-speed", state.subSpeed)

    // Restore video zoom from saved state
    MPVLib.setPropertyDouble("video-zoom", state.videoZoom.toDouble())
    viewModel.setVideoZoom(state.videoZoom)

    if (playerPreferences.savePositionOnQuit.get() && state.lastPosition != 0) {
      noteUserSeek()
      MPVLib.setPropertyInt("time-pos", state.lastPosition)
    }
  }

  /**
   * Applies default settings when no saved state exists.
   *
   * Sets subtitle speed to user default if not present in saved state.
   *
   * @param state The saved playback state entity (null if no saved state)
   */
  private fun applyDefaultSettings(state: PlaybackStateEntity?) {
    if (state == null) {
      val defaultSubSpeed = subtitlesPreferences.defaultSubSpeed.get().toDouble()
      MPVLib.setPropertyDouble("sub-speed", defaultSubSpeed)
    }
  }

  /**
   * Saves the currently playing file to recently played history.
   *
   * Handles various URI schemes and infers launch source.
   */
  private suspend fun saveRecentlyPlayed() {
    runCatching {
      val uri = extractUriFromIntent(intent)
        // PENDING_MOVIE launches open with an empty intent URI; the resolved stream url is the
        // only identity available — without this fallback the title never reaches recents.
        ?: currentPlayingUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

      if (uri == null) {
        Log.w(TAG, "Cannot save recently played: URI is null")
        return@runCatching
      }

      if (uri.scheme == null) {
        Log.w(TAG, "Cannot save recently played: URI has null scheme: $uri")
        return@runCatching
      }

      val filePath =
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }

      val launchSource =
        when {
          intent.getStringExtra("launch_source") != null -> intent.getStringExtra("launch_source")
          intent.action == Intent.ACTION_SEND -> "share"
          else -> "normal"
        }

      // Get parsed video title from MPV
      val videoTitle = runCatching {
        MPVLib.getPropertyString("media-title")
      }.getOrNull()?.takeIf { it.isNotBlank() && it != fileName }

      // Get duration and file size from MPV
      val duration = runCatching {
        (MPVLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      val fileSize = runCatching {
        // Try multiple properties to get file size
        MPVLib.getPropertyDouble("file-size")?.toLong()
          ?: MPVLib.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      // Get video resolution from MPV
      val width = runCatching {
        MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = fileName,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = launchSource,
      )

      Log.d(TAG, "Saved recently played: $filePath")
      Log.d(TAG, "  - fileName: $fileName")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - source: $launchSource")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played", e)
    }
  }

  // ==================== Intent and Result Management ====================

  /**
   * Sets the result intent with current playback position and duration.
   * Called when activity is finishing to return data to caller.
   */
  private fun setReturnIntent() {
    Log.d(TAG, "Setting return intent")

    val resultIntent =
      Intent(RESULT_INTENT).apply {
        viewModel.pos?.let { putExtra("position", it * MILLISECONDS_TO_SECONDS) }
        viewModel.duration?.let { putExtra("duration", it * MILLISECONDS_TO_SECONDS) }
      }

    setResult(RESULT_OK, resultIntent)
  }

  /**
   * Handles new intents to load a different file without recreating the activity.
   *
   * @param intent The new intent
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)

    // Update the intent first so getFileName uses the new intent data
    setIntent(intent)

    // Check if this intent has playlist information
    val hasPlaylistExtras = intent.hasExtra("playlist_id") ||
      intent.hasExtra("playlist")

    // Load playlist from intent extras first (fast path)
    val playlistFromIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    // Only update playlist state if we have new playlist information
    // This prevents losing the playlist when coming back from notification/PiP
    if (hasPlaylistExtras || playlistFromIntent.isNotEmpty()) {
      val newPlaylistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
      playlistId = newPlaylistId
      playlistIndex = intent.getIntExtra("playlist_index", 0)
      playlistWindowOffset = 0
      playlistTotalCount = -1
      playlist = playlistFromIntent
    }

    // If playlist is empty but playlist_id is provided, load from database
    if (playlist.isEmpty() && playlistId != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        val pid = playlistId ?: return@launch
        try {
          val totalCount = playlistRepository.getPlaylistItemCount(pid)
          val items = playlistRepository.getPlaylistItemsAsUris(pid)
          withContext(Dispatchers.Main) {
            playlist = items
            playlistTotalCount = totalCount
            Log.d(TAG, "onNewIntent: Loaded ${items.size} items from playlist $pid")
          }
        } catch (e: Exception) {
          Log.e(TAG, "onNewIntent: Failed to load playlist from database", e)
        }
      }
    }

    // Auto-generate playlist from folder if playlist mode is enabled and no playlist_id
    if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get()) {
      val path = parsePathFromIntent(intent)
      if (path != null) {
        generatePlaylistFromFolder(path)
      }
    }

    // Extract the new fileName before loading the file
    fileName = getFileName(intent)
    if (fileName.isBlank()) {
      fileName = intent.data?.lastPathSegment ?: "Unknown Video"
    }
    mediaIdentifier = getMediaIdentifier(intent, fileName)

    // Reset or apply source-specific transport state before opening the new file.
    applyAnimeTransportOptions(intent.getBooleanExtra("anime_disable_http_reuse", false))
    setHttpHeadersFromExtras(intent.extras)

    // Load the new file
    getPlayableUri(intent)?.let { uri ->
      // Avoid blocking UI thread while mpv opens network streams (e.g., HLS).
      lifecycleScope.launch(Dispatchers.Default) {
        mpvLoadFile(uri)
      }
    }
  }

  // ==================== Picture-in-Picture Management ====================

  /**
   * Called when Picture-in-Picture mode changes.
   * Updates UI visibility and window configuration.
   *
   * @param isInPictureInPictureMode true if entering PiP, false if exiting
   * @param newConfig The new configuration
   */
  @RequiresApi(Build.VERSION_CODES.P)
  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

    pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode)

    binding.controls.alpha = if (isInPictureInPictureMode) 0f else 1f

    runCatching {
      if (isInPictureInPictureMode) {
        enterPipUIMode()
      } else {
        exitPipUIMode()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error handling PiP mode change", e)
    }
  }

  /**
   * Configures window for Picture-in-Picture mode.
   * Shows system UI and navigation bars.
   */
  private fun enterPipUIMode() {
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    WindowCompat.setDecorFitsSystemWindows(window, true)
    try {
      windowInsetsController.apply {
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to show system bars for PiP mode", e)
    }
  }

  /**
   * Restores window configuration when exiting Picture-in-Picture mode.
   * Hides system UI for immersive playback.
   */
  @RequiresApi(Build.VERSION_CODES.P)
  private fun exitPipUIMode() {
    setupWindowFlags()
    setupSystemUI()
  }

  /**
   * Enters Picture-in-Picture mode and hides all overlay controls.
   */
  fun enterPipModeHidingOverlay() {
    runCatching {
      enterPipUIMode()
    }.onFailure { e ->
      Log.e(TAG, "Error entering PiP mode with hidden overlay", e)
    }

    binding.controls.alpha = 0f

    pipHelper.enterPipMode()
  }

  // ==================== Orientation Management ====================

  /**
   * Sets the screen orientation based on user preferences.
   *
   * IMPORTANT: Preferences are the single source of truth for orientation.
   * This method applies the preference value when videos load.
   * The rotation button temporarily overrides this without changing preferences.
   *
   * For "Video" orientation mode, this will wait for video-params/aspect to update
   * to the correct orientation, starting with landscape as fallback.
   */
  private fun setOrientation() {
    val orientationPref = playerPreferences.orientation.get()

    requestedOrientation =
      when (orientationPref) {
        PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        PlayerOrientation.Video -> {
          // For video orientation, check if aspect is available
          val aspect = runCatching { player.getVideoOutAspect() }.getOrNull()
          Log.d(TAG, "setOrientation - Video mode: aspect=$aspect")
          if (aspect == null || aspect <= 0.0) {
            // Aspect not available yet - wait for video-params/aspect update
            Log.d(TAG, "setOrientation - Aspect not available, defaulting to landscape")
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
          } else {
            // Aspect available - set correct orientation now
            val orientation = if (aspect > 1.0) {
              Log.d(TAG, "setOrientation - Aspect $aspect > 1.0, setting landscape")
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
              Log.d(TAG, "setOrientation - Aspect $aspect <= 1.0, setting portrait")
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            orientation
          }
        }
        PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      }
  }

  // ==================== Key Event Handling ====================

  /**
   * Handles hardware key down events for player control.
   * Supports D-pad navigation, media keys, and volume controls.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
  override fun onKeyDown(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    val isTrackSheetOpen =
      viewModel.sheetShown.value == Sheets.SubtitleTracks ||
        viewModel.sheetShown.value == Sheets.AudioTracks
    val isNoSheetOpen = viewModel.sheetShown.value == Sheets.None

    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_UP -> {
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_LEFT,
        -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }

        if (isNoSheetOpen) {
          when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
              viewModel.handleRightDoubleTap()
              return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
              viewModel.handleLeftDoubleTap()
              return true
            }
          }
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_SPACE -> {
        viewModel.pauseUnpause()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_UP -> {
        viewModel.changeVolumeBy(1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        viewModel.changeVolumeBy(-1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_STOP -> {
        finishAndRemoveTask()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_REWIND -> {
        viewModel.handleLeftDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
        viewModel.handleRightDoubleTap()
        return true
      }

      else -> {
        event?.let { player.onKey(it) }
        return super.onKeyDown(keyCode, event)
      }
    }
  }

  /**
   * Handles hardware key up events for player control.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  override fun onKeyUp(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    event?.let {
      if (player.onKey(it)) return true
    }
    return super.onKeyUp(keyCode, event)
  }

  // ==================== System UI Management ====================

  /**
   * Restores system UI to normal state (shows status and navigation bars).
   * Called when finishing the activity to return to normal Android UI.
   */

  // ==================== MediaSession ====================

  /**
   * Initializes MediaSession for integration with system media controls.
   * Supports Android Auto, Wear OS, Bluetooth controls, and notification controls.
   */
  private fun setupMediaSession() {
    runCatching {
      mediaSession =
        MediaSession(this, TAG).apply {
          setCallback(
            object : MediaSession.Callback() {
              override fun onPlay() {
                viewModel.unpause()
                updateMediaSessionPlaybackState(isPlaying = true)
              }

              override fun onPause() {
                viewModel.pause()
                updateMediaSessionPlaybackState(isPlaying = false)
              }

              override fun onSeekTo(pos: Long) {
                viewModel.seekTo((pos / 1000).toInt())
                updateMediaSessionPlaybackState(isPlaying = viewModel.paused == false)
              }
            },
          )
          isActive = true
        }
      playbackStateBuilder =
        PlaybackState
          .Builder()
          .setActions(
            PlaybackState.ACTION_PLAY or
              PlaybackState.ACTION_PAUSE or
              PlaybackState.ACTION_PLAY_PAUSE or
              PlaybackState.ACTION_SEEK_TO,
          )
      mediaSessionInitialized = true
    }.onFailure { e ->
      Log.e(TAG, "Failed to initialize MediaSession", e)
      mediaSessionInitialized = false
    }
  }

  /**
   * Updates MediaSession playback state (playing/paused).
   *
   * @param isPlaying true if currently playing, false if paused
   */
  private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
    if (!mediaSessionInitialized) return
    runCatching {
      val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
      val positionMs = (viewModel.pos ?: 0) * 1000L
      mediaSession.setPlaybackState(
        playbackStateBuilder
          .setState(state, positionMs, if (isPlaying) 1.0f else 0f)
          .build(),
      )
    }.onFailure { e -> Log.e(TAG, "Error updating playback state", e) }
  }

  /**
   * Updates MediaSession metadata (title, duration, etc.).
   *
   * @param title The media title
   * @param durationMs The media duration in milliseconds
   */
  private fun updateMediaSessionMetadata(
    title: String,
    durationMs: Long,
  ) {
    if (!mediaSessionInitialized) return
    runCatching {
      val metadata =
        MediaMetadata
          .Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, title)
          .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
          .build()
      mediaSession.setMetadata(metadata)
    }.onFailure { e -> Log.e(TAG, "Error updating metadata", e) }
  }

  /**
   * Releases MediaSession resources.
   * Called during activity cleanup.
   */
  private fun releaseMediaSession() {
    if (!mediaSessionInitialized) return
    runCatching {
      mediaSession.isActive = false
      mediaSession.release()
    }.onFailure { e -> Log.e(TAG, "Error releasing MediaSession", e) }
    mediaSessionInitialized = false
  }

  // ==================== Background Playback Service ====================

  /**
   * Service connection for binding to background playback service.
   */
  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?,
      ) {
        val binder = service as? MediaPlaybackService.MediaPlaybackBinder ?: return
        mediaPlaybackService = binder.getService()
        serviceBound = true
        Log.d(TAG, "Service connected")
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "Service disconnected")
        mediaPlaybackService = null
        serviceBound = false
      }
    }

  /**
   * Starts the background playback service and binds to it.
   *
   * This should only be called if a video is loaded and playback is initialized.
   * Responsible for starting and binding to the MediaPlaybackService, which
   * handles background playback.
   */
  private fun startBackgroundPlayback() {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot start background playback: video not ready")
      return
    }

    // Prevent starting service multiple times
    if (serviceBound) {
      Log.d(TAG, "Service already bound, skipping start")
      return
    }

    Log.d(TAG, "Starting background playback for: $fileName")
    
    // Ensure notification channel exists
    MediaPlaybackService.createNotificationChannel(this)
    
    // Get media info before starting service
    val artist = runCatching { MPVLib.getPropertyString("metadata/artist") }.getOrNull() ?: ""
    val thumbnail = runCatching { MPVLib.grabThumbnail(1080) }.getOrNull()
    
    // Pass media info via intent extras
    val intent = Intent(this, MediaPlaybackService::class.java).apply {
      putExtra("media_title", fileName)
      putExtra("media_artist", artist)
    }
    
    // Store thumbnail in companion object for service to access
    MediaPlaybackService.thumbnail = thumbnail
    
    try {
      startForegroundService(intent)
      bindService(intent, serviceConnection, BIND_AUTO_CREATE)
      Log.d(TAG, "Service start and bind initiated")
    } catch (e: Exception) {
      Log.e(TAG, "Error starting/binding service", e)
    }
  }

  /**
   * Stops the background playback service and unbinds from it.
   *
   * Called when the activity is destroyed to remove the notification.
   */
  private fun endBackgroundPlayback() {
    Log.d(TAG, "Ending background playback service")
    
    if (serviceBound) {
      try {
        unbindService(serviceConnection)
        Log.d(TAG, "Service unbound successfully")
      } catch (e: Exception) {
        Log.e(TAG, "Error unbinding service", e)
      }
      serviceBound = false
    }
    
    // Stop the service which will trigger its onDestroy and cleanup
    try {
      stopService(Intent(this, MediaPlaybackService::class.java))
      Log.d(TAG, "Stop service command sent")
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping service", e)
    }
    
    mediaPlaybackService = null
  }

  /**
   * Manually triggers background playback when the user clicks the background playback button.
   * This works independently of the automaticBackgroundPlayback preference.
   */
  @RequiresApi(Build.VERSION_CODES.P)
  fun triggerBackgroundPlayback() {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot trigger background playback: video not ready")
      return
    }

    Log.d(TAG, "User triggered background playback")
    
    // Set flag to enable background playback (same logic as automatic)
    isManualBackgroundPlayback = true
    
    // Restore system UI before going to background
    restoreSystemUI()
    
    // Move to background by going to home screen (same behavior as automatic)
    val intent = Intent(Intent.ACTION_MAIN).apply {
      addCategory(Intent.CATEGORY_HOME)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(intent)
  }

  // ==================== PlayerHost ====================
  override val context: Context
    get() = this
  override val windowInsetsController: WindowInsetsControllerCompat
    get() = WindowCompat.getInsetsController(window, window.decorView)
  override val hostWindow: android.view.Window
    get() = window
  override val hostWindowManager: WindowManager
    get() = windowManager
  override val hostContentResolver: android.content.ContentResolver
    get() = contentResolver
  override val audioManager: AudioManager
    get() = getSystemService(AUDIO_SERVICE) as AudioManager
  override var hostRequestedOrientation: Int
    get() = requestedOrientation
    set(value) {
      requestedOrientation = value
    }

  // ==================== Playlist Management ====================

  /**
   * Check if there's a next video in the playlist
   */
  fun hasNext(): Boolean {
    if (playlist.isEmpty()) return false

    // With repeat ALL, there's always a "next" (loops back to beginning)
    if (viewModel.shouldRepeatPlaylist()) return true

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    return if (viewModel.shuffleEnabled.value) {
      shuffledPosition < shuffledIndices.size - 1
    } else {
      playlistIndex < effectiveSize - 1
    }
  }

  /**
   * Check if there's a previous video in the playlist
   */
  fun hasPrevious(): Boolean {
    if (playlist.isEmpty()) return false

    // With repeat ALL, there's always a "previous" (loops back to end)
    if (viewModel.shouldRepeatPlaylist()) return true

    return if (viewModel.shuffleEnabled.value) {
      shuffledPosition > 0
    } else {
      playlistIndex > 0
    }
  }

  /**
   * Generate shuffled indices for the playlist
   */
  private fun generateShuffledIndices() {
    if (playlist.isEmpty()) return

    // Create a list of all indices except the current one
    val indices = playlist.indices.filter { it != playlistIndex }.toMutableList()
    indices.shuffle()

    // Put current index at the beginning
    shuffledIndices = listOf(playlistIndex) + indices
    shuffledPosition = 0
  }

  /**
   * Called when shuffle is toggled on/off
   */
  fun onShuffleToggled(enabled: Boolean) {
    if (enabled && playlist.isNotEmpty()) {
      generateShuffledIndices()
    } else {
      shuffledIndices = emptyList()
      shuffledPosition = 0
    }
  }

  /**
   * Play the next video in the playlist
   */
  fun playNext() {
    if (playlist.isEmpty()) return

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    if (viewModel.shuffleEnabled.value) {
      // Initialize shuffle if not done yet
      if (shuffledIndices.isEmpty()) {
        generateShuffledIndices()
      }

      // Move to next position
      if (shuffledPosition < shuffledIndices.size - 1) {
        shuffledPosition++
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At end of shuffled playlist with repeat ALL: regenerate and restart
        generateShuffledIndices()
        shuffledPosition = 0
        playlistIndex = shuffledIndices[0]
        loadPlaylistItem(playlistIndex)
      }
    } else {
      // Normal sequential playback
      if (playlistIndex < effectiveSize - 1) {
        playlistIndex++
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At end of playlist with repeat ALL: restart from beginning
        playlistIndex = 0
        loadPlaylistItem(0)
      }
    }
  }

  /**
   * Play the previous video in the playlist
   */
  fun playPrevious() {
    if (playlist.isEmpty()) return

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    if (viewModel.shuffleEnabled.value) {
      // Initialize shuffle if not done yet
      if (shuffledIndices.isEmpty()) {
        generateShuffledIndices()
      }

      // Move to previous position
      if (shuffledPosition > 0) {
        shuffledPosition--
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At beginning of shuffled playlist with repeat ALL: go to end
        shuffledPosition = shuffledIndices.size - 1
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      }
    } else {
      // Normal sequential playback
      if (playlistIndex > 0) {
        playlistIndex--
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At beginning of playlist with repeat ALL: go to last item
        playlistIndex = effectiveSize - 1
        loadPlaylistItem(playlistIndex)
      }
    }
  }

  /**
   * Load a playlist item by index
   */
  private fun loadPlaylistItem(index: Int) {
    // All items are loaded - just validate index and load directly
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }
    loadPlaylistItemInternal(index)
  }

  /**
   * Internal method to load a playlist item
   */
  private fun loadPlaylistItemInternal(index: Int) {
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }

    // Save current video's playback state before switching
    if (fileName.isNotBlank()) {
      saveVideoPlaybackState(fileName)
    }

    val uri = playlist[index]
    val playableUri = uri.openContentFd(this) ?: uri.toString()

    // Update playlist index
    playlistIndex = index

    // Extract and set the new file name
    fileName = getFileNameFromUri(uri)
    // Generate new media identifier for playback state
    mediaIdentifier = getMediaIdentifierFromUri(uri, fileName)

    // Set HTTP headers (including referer) for network streams
    setHttpHeadersForUri(uri)

    // Update playlist play history if this is a custom playlist
    playlistId?.let { id ->
      lifecycleScope.launch(Dispatchers.IO) {
        val filePath = when (uri.scheme) {
          "file" -> uri.path ?: uri.toString()
          "content" -> {
            contentResolver.query(
              uri,
              arrayOf(MediaStore.MediaColumns.DATA),
              null,
              null,
              null,
            )?.use { cursor ->
              if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (columnIndex != -1) cursor.getString(columnIndex) else null
              } else null
            } ?: uri.toString()
          }

          else -> uri.toString()
        }

        runCatching {
          playlistRepository.updatePlayHistory(id, filePath)
          Log.d(TAG, "Updated playlist history for: $filePath in playlist $id")
        }.onFailure { e ->
          Log.e(TAG, "Error updating playlist history", e)
        }
      }
    }

    // Load the new video
    // Avoid blocking UI thread while mpv opens network streams (e.g., HLS).
    lifecycleScope.launch(Dispatchers.Default) {
      mpvLoadFile(playableUri)
    }

    // Update media title (this will trigger UI update)
    // Same rule as handleFileLoaded: our clean name wins over mpv's raw HLS/URL title.
    val isM3U = uri.toString().lowercase().contains(".m3u8") || uri.toString().lowercase().contains(".m3u")
    if (!isM3U || fileName.isNotBlank()) {
      MPVLib.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    // Update media session metadata
    lifecycleScope.launch {
      kotlinx.coroutines.delay(100) // Wait for MPV to load the file
      val durationMs = (MPVLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
      updateMediaSessionMetadata(
        title = fileName,
        durationMs = durationMs,
      )
      // Refresh playlist items to update the currently playing indicator
      viewModel.refreshPlaylistItems()
    }
  }

  /**
   * Get file name from URI (used for playlist items)
   */
  private fun getFileNameFromUri(uri: Uri): String {
    getDisplayNameFromUri(uri)?.let { return it }
    return extractFileNameFromUri(uri)
  }

  /**
   * Get the current video title for controls display.
   * Our resolved name always wins: mpv's raw media-title for HLS streams is the stream URL
   * (or a playlist entry), never a human title. MPV's value is only a last-resort fallback.
   */
  fun getTitleForControls(): String {
    if (fileName.isNotBlank() && fileName != "Unknown Video") return fileName
    val rawTitle = MPVLib.getPropertyString("media-title")
    if (!rawTitle.isNullOrBlank()) {
      return rawTitle
    }
    return fileName.ifBlank { "Unknown Video" }
  }

  /**
   * Check if the currently playing media is an m3u or m3u8 stream.
   * Checks both the intent URI and the current playlist item if playing from a playlist.
   */
  private fun isCurrentStreamM3U(): Boolean {
    // First check the intent URI
    val uri = extractUriFromIntent(intent)
    if (uri != null && isUriM3U(uri)) {
      return true
    }

    // Also check the current playlist item if playing from a playlist
    if (playlist.isNotEmpty() && playlistIndex >= 0 && playlistIndex < playlist.size) {
      return isUriM3U(playlist[playlistIndex])
    }

    return false
  }

  /**
   * Check if a specific URI is an m3u or m3u8 file/stream.
   */
  private fun isUriM3U(uri: Uri): Boolean {
    val lowerUrl = uri.toString().lowercase()
    return lowerUrl.contains(".m3u8") || lowerUrl.contains(".m3u") ||
      lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".m3u")
  }

  /**
   * Save recently played for a specific URI
   */
  private suspend fun saveRecentlyPlayedForUri(
    uri: Uri,
    name: String,
  ) {
    runCatching {
      val filePath =
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }

      // Get parsed video title from MPV
      val videoTitle = runCatching {
        MPVLib.getPropertyString("media-title")
      }.getOrNull()?.takeIf { it.isNotBlank() && it != name }

      // Get duration and file size from MPV
      val duration = runCatching {
        (MPVLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      val fileSize = runCatching {
        // Try multiple properties to get file size
        MPVLib.getPropertyDouble("file-size")?.toLong()
          ?: MPVLib.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      // Get video resolution from MPV
      val width = runCatching {
        MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = name,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = "playlist",
        playlistId = playlistId,
      )

      Log.d(TAG, "Saved recently played (playlist): $filePath")
      Log.d(TAG, "  - fileName: $name")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - playlistId: $playlistId")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played for playlist item", e)
    }
  }

  /**
   * Generate a unique identifier for this media for playback state/history.
   *
   * For local/offline files, uses fileName (display name or path).
   * For network streams via proxy (SMB/WebDAV/FTP), uses the stable network file path from intent extras.
   * For other network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
   */
  private fun getMediaIdentifier(intent: Intent, fileName: String): String {
    // Check if this is a network file played via proxy (SMB/WebDAV/FTP)
    // Use the stable network file path instead of the temporary proxy URL
    val networkFilePath = intent.getStringExtra("network_file_path")
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

    if (networkFilePath != null && networkConnectionId != -1L) {
      // For network files via proxy: use connection ID + file path for stable identifier
      val identifier = "network_${networkConnectionId}_${networkFilePath.hashCode()}"
      Log.d(
        TAG,
        "Using network file identifier: $identifier (connection: $networkConnectionId, path: $networkFilePath)",
      )
      return identifier
    }
      val uri = extractUriFromIntent(intent)
      return stableKinoshkaIdentifier()
        ?: if (uri != null && (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp"
            || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms")) {
          // For remote protocols: hash the URI so position is per-episode or per-stream.
          "${fileName}_${uri.toString().hashCode()}"
        } else {
          // For local/file uris and unknown: just use fileName.
          fileName
        }
    }

  /**
   * Generate a unique identifier for this media from a URI and name.
   *
   * For local/offline files, uses fileName (display name or path).
   * For network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
   */
  private fun getMediaIdentifierFromUri(uri: Uri, fileName: String): String {
    return if (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms") {
      "${fileName}_${uri.toString().hashCode()}"
    } else {
      fileName
    }
  }

  private fun generatePlaylistFromFolder(currentPath: String) {
    lifecycleScope.launch(Dispatchers.IO) {
      runCatching {
        val currentFile = File(currentPath)
        if (!currentFile.exists()) return@runCatching

        val parentFolder = currentFile.parentFile ?: return@runCatching

        val videoExtensions = FileTypeUtils.VIDEO_EXTENSIONS

        val files = parentFolder.listFiles { file ->
          file.isFile &&
            FileTypeUtils.isVideoFile(file) &&
            !FileFilterUtils.shouldSkipFile(file)
        } ?: return@runCatching

        val launchSource = intent.getStringExtra("launch_source") ?: ""
        val siblingFiles = if (launchSource == "video_list" || launchSource == "recently_played_button" || launchSource == "first_video_button") {
          val videoSortType = browserPreferences.videoSortType.get()
          val videoSortOrder = browserPreferences.videoSortOrder.get()
          val bucketId = parentFolder.absolutePath.replace("\\", "/")
          val videosInFolder =
            app.marlboroadvance.mpvex.repository.MediaFileRepository.getVideosForBuckets(
              context,
              setOf(bucketId)
            )
          val sortedVideos = app.marlboroadvance.mpvex.utils.sort.SortUtils.sortVideos(videosInFolder, videoSortType, videoSortOrder)
          sortedVideos.mapNotNull { video -> files.find { it.absolutePath == video.path } }
        } else {
          files.sortedWith { f1, f2 -> app.marlboroadvance.mpvex.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT.compare(f1.name, f2.name) }
        }

        if (siblingFiles.size <= 1) return@runCatching

        val newPlaylist = siblingFiles.map { it.toUri() }

        val newIndex = siblingFiles.indexOfFirst { it.absolutePath == currentFile.absolutePath }

        if (newIndex != -1) {
          withContext(Dispatchers.Main) {
            playlist = newPlaylist
            playlistIndex = newIndex
            Log.d(TAG, "Auto-playlist generated: ${playlist.size} videos")
            // Re-initialize shuffle now that playlist is available
            if (viewModel.shuffleEnabled.value) {
              onShuffleToggled(true)
            }
          }
        }
      }.onFailure { e ->
        Log.e(TAG, "Failed to auto-generate playlist", e)
      }
    }
  }

  /**
   * Check if the current playlist is an M3U playlist (sourced from database).
   */
  fun isCurrentPlaylistM3U(): Boolean = isM3uPlaylist


  companion object {
    /**
     * Intent action used to return playback result data to the calling activity.
     */
    private const val RESULT_INTENT = "app.marlboroadvance.mpvex.ui.player.PlayerActivity.result"

    /**
     * Constant for "brightness not set".
     */
    private const val BRIGHTNESS_NOT_SET = -1f

    /** Обратный отсчёт до автовключения следующей серии (секунды). */
    private const val NEXT_EPISODE_COUNTDOWN_SECONDS = 10

    /**
     * Constant used when playback position is not set.
     */
    private const val POSITION_NOT_SET = 0

    /**
     * Maximum volume for MPV in percent.
     */
    private const val MAX_MPV_VOLUME = 100

    /**
     * Milliseconds-to-seconds conversion factor.
     */
    private const val MILLISECONDS_TO_SECONDS = 1000

    /**
     * Factor to divide subtitle and audio delays to convert from ms to seconds.
     */
    private const val DELAY_DIVISOR = 1000.0

    /**
     * Minimum accrued playback time (seconds) before a title counts as actually watched and
     * lands in the library as "Смотрю" — pressing "Смотреть" alone commits nothing.
     */
    private const val MIN_WATCH_SECONDS_FOR_LIBRARY = 300

    /**
     * Default playback speed (1.0 = normal).
     */
    private const val DEFAULT_PLAYBACK_SPEED = 1.0

    /**
     * Default subtitle speed (1.0 = normal).
     */
    private const val DEFAULT_SUB_SPEED = 1.0

    /**
     * Auto-retries for a loadfile mpv reports as failed (END_FILE reason=error) while the
     * loading overlay is up; past this the retryable error card takes over.
     */
    private const val MAX_STREAM_LOAD_RETRIES = 2

    /**
     * Hard budget for resolving a raw Kodik player link into a playable HLS url when the user
     * switches voiceover — the multi-endpoint cascade must not outlast the loading overlay.
     */
    private const val VOICEOVER_RESOLVE_TIMEOUT_MS = 12_000L

    /**
     * General tag for logging from PlayerActivity.
     */
    const val TAG = "mpvex"
  }
}
