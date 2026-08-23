package app.marlboroadvance.mpvex.domain.anime4k

import android.content.Context
import `is`.xyz.mpv.MPVLib

import java.io.File
import java.io.FileOutputStream

/**
 * Anime4K Manager
 * Manages GLSL shaders for real-time anime upscaling
 */
class Anime4KManager(private val context: Context) {

  companion object {
    private const val SHADER_DIR = "shaders"
  }

  // Shader quality levels
  enum class Quality(val suffix: String, val titleRes: Int) {
    FAST("S", hd.kinoshka.app.R.string.anime4k_quality_fast),
    BALANCED("M", hd.kinoshka.app.R.string.anime4k_quality_balanced),
    HIGH("L", hd.kinoshka.app.R.string.anime4k_quality_high)
  }

  // Anime4K modes
  enum class Mode(val titleRes: Int) {
    OFF(hd.kinoshka.app.R.string.anime4k_mode_off),
    A(hd.kinoshka.app.R.string.anime4k_mode_a),
    B(hd.kinoshka.app.R.string.anime4k_mode_b),
    C(hd.kinoshka.app.R.string.anime4k_mode_c),
    A_PLUS(hd.kinoshka.app.R.string.anime4k_mode_a_plus),
    B_PLUS(hd.kinoshka.app.R.string.anime4k_mode_b_plus),
    C_PLUS(hd.kinoshka.app.R.string.anime4k_mode_c_plus)
  }

  private var shaderDir: File? = null
  private var isInitialized = false

  /**
   * Initialize: copy shaders from assets to internal storage
   * This must be called and complete successfully before using getShaderChain()
   */
  fun initialize(): Boolean {
    if (isInitialized) {
      return true
    }
    
    return try {
      // Create shader directory
      shaderDir = File(context.filesDir, SHADER_DIR)
      if (!shaderDir!!.exists()) {
        val created = shaderDir!!.mkdirs()
        if (!created) {
          return false
        }
      }

      // List and copy all shader files from assets
      val shaderFiles = context.assets.list(SHADER_DIR) ?: emptyArray()

      for (fileName in shaderFiles) {
        if (fileName.endsWith(".glsl")) {
          copyShaderFromAssets(fileName)
        }
      }
      
      isInitialized = true
      true
    } catch (e: Exception) {
      isInitialized = false
      false
    }
  }

  private fun copyShaderFromAssets(fileName: String): Boolean {
    val destFile = File(shaderDir, fileName)
    
    // Skip if file already exists and is valid
    if (destFile.exists() && destFile.length() > 0) {
      return false
    }

    try {
      context.assets.open("$SHADER_DIR/$fileName").use { input ->
        FileOutputStream(destFile).use { output ->
          input.copyTo(output)
        }
      }
      return true
    } catch (e: Exception) {
      return false
    }
  }

  /**
   * Get shader chain for the specified mode and quality
   * Returns empty string if mode is OFF or initialization failed
   */
  fun getShaderChain(mode: Mode, quality: Quality): String {
    if (mode == Mode.OFF) {
      return ""
    }

    if (!isInitialized) {
      return ""
    }

    if (shaderDir == null || !shaderDir!!.exists()) {
      return ""
    }

    val shaders = mutableListOf<String>()
    val q = quality.suffix

    // Always add Clamp_Highlights (prevent ringing)
    shaders.add(getShaderPath("Anime4K_Clamp_Highlights.glsl"))

    // Add shaders based on mode
    when (mode) {
      Mode.A -> {
        // Mode A: Restore -> Upscale -> Upscale
        shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.B -> {
        // Mode B: Restore_Soft -> Upscale -> Upscale
        shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.C -> {
        // Mode C: Upscale_Denoise -> Upscale
        shaders.add(getShaderPath("Anime4K_Upscale_Denoise_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.A_PLUS -> {
        // Mode A+A: Restore -> Upscale -> Restore -> Upscale
        shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.B_PLUS -> {
        // Mode B+B: Restore_Soft -> Upscale -> Restore_Soft -> Upscale
        shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.C_PLUS -> {
        // Mode C+A: Upscale_Denoise -> Restore -> Upscale
        shaders.add(getShaderPath("Anime4K_Upscale_Denoise_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.OFF -> {
        // Already handled
      }
    }

    // Validate that all shader files exist
    val missingShaders = shaders.filter { path ->
      !File(path).exists()
    }
    
    if (missingShaders.isNotEmpty()) {
      return ""
    }

    // Join with colon separator
    val chain = shaders.joinToString(":")
    return chain
  }

  private fun getShaderPath(fileName: String): String {
    return File(shaderDir, fileName).absolutePath
  }
}

/**
 * Applies (or clears) the Anime4K shader chain to a running libmpv instance at runtime.
 *
 * libmpv's `glsl-shaders` is a path-list *option*: writing it via the property-string API
 * (mpv_set_property_string) after a file has loaded does NOT reliably replace the active
 * shader chain — on most builds it is ignored or deferred to the next file load. The
 * Uses the runtime `change-list` API because this bundled libmpv does not expose
 * the newer glsl-shaders-set/glsl-shaders-clear commands.
 *
 * Returns true if a command was actually dispatched (the caller can use this to decide
 * whether to refresh UI state).
 */
fun Anime4KManager.applyShaderChainRuntime(mode: Anime4KManager.Mode, quality: Anime4KManager.Quality): Boolean {
  return runCatching {
    require(initialize()) { "Unable to initialize Anime4K shader files" }
    // glsl-shaders is a path-list option. The portable runtime API is to clear
    // the list and append every path separately; do not also write the option
    // as a string or restart the video output.
    MPVLib.command("change-list", "glsl-shaders", "clr", "")
    if (mode != Anime4KManager.Mode.OFF) {
      val chain = getShaderChain(mode, quality)
      require(chain.isNotBlank()) { "Anime4K shader chain is unavailable" }
      chain.split(':').forEach { shaderPath ->
        MPVLib.command("change-list", "glsl-shaders", "append", shaderPath)
      }
    }
    true
  }.getOrDefault(false)
}

/** Clears any active user shaders on a running instance. */
fun Anime4KManager.clearShaderChainRuntime() {
  runCatching { MPVLib.command("change-list", "glsl-shaders", "clr", "") }
}
