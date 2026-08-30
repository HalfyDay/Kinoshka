package hd.kinoshka.app.data.update

import androidx.core.content.FileProvider

/**
 * Отдельный класс для FileProvider с authority ".fileprovider". Когда у двух провайдеров
 * в манифесте один и тот же класс, платформа может маршрутизировать запрос по одному
 * authority в transport другого (SecurityException «authority does not match»), и шаринг
 * файлов молча ломается.
 */
class UpdateFileProvider : FileProvider()
