package hd.kinoshka.app.data.source

internal actual suspend fun grabVideoFrameFiles(
    stream: HentaiStream,
    dir: java.io.File,
    prefix: String
): List<java.io.File?> = emptyList()
