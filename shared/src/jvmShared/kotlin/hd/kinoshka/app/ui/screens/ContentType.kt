package hd.kinoshka.app.ui.screens

/**
 * Тип контента каталога. Общий для Android и desktop: используется в
 * UserPreferences (shared). Извлечён из FilmsViewModel (app) при миграции.
 */
enum class ContentType {
    FILMS,
    ANIME
}
