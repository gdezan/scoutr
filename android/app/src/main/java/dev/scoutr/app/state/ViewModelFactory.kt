package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import dev.scoutr.app.ScoutrApp

/**
 * One factory helper for the whole app: pulls [ScoutrApp] from the
 * [CreationExtras] (the standard APPLICATION_KEY slot) so call sites never
 * cast `applicationContext as ScoutrApp` themselves, and the eight
 * per-VM companion `factory` blocks collapse into one line each:
 *
 * ```kotlin
 * viewModel(factory = viewModelFactory<BoardViewModel> { app ->
 *     BoardViewModel(app.container.bridge, app.container.connectionStore)
 * })
 * ```
 */
inline fun <reified VM : ViewModel> viewModelFactory(
    crossinline create: (ScoutrApp) -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            ?: error("application missing for ${VM::class.simpleName} factory")
        return create(app as ScoutrApp) as T
    }
}