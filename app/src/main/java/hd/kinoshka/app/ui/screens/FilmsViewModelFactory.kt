package hd.kinoshka.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import hd.kinoshka.app.data.local.ShikimoriAuthStore
import hd.kinoshka.app.data.local.UserStateStore
import hd.kinoshka.app.data.repo.AnimeRepository
import hd.kinoshka.app.data.repo.FilmsRepository

// Android-обвязка ViewModelProvider. Живёт в app, а не в shared: сигнатура
// Factory.create(Class) есть только в android-варианте lifecycle. Экраны desktop
// конструируют FilmsViewModel напрямую.
class FilmsViewModelFactory(
    private val repository: FilmsRepository,
    private val animeRepository: AnimeRepository,
    private val userStateStore: UserStateStore,
    private val shikimoriAuthStore: ShikimoriAuthStore? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FilmsViewModel(repository, animeRepository, userStateStore, shikimoriAuthStore) as T
    }
}
