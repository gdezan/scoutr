package dev.scoutr.app.state

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
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
 *     BoardViewModel(app.container.hostClients, app.container.hostRegistry)
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

/**
 * Same, for a view model that keeps state worth surviving process death — an
 * ask card's half-filled draft, say. The [SavedStateHandle] comes from the
 * standard `createSavedStateHandle()` extension, so the caller supplies
 * nothing beyond the constructor call.
 */
inline fun <reified VM : ViewModel> savedStateViewModelFactory(
    crossinline create: (ScoutrApp, SavedStateHandle) -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            ?: error("application missing for ${VM::class.simpleName} factory")
        return create(app as ScoutrApp, extras.createSavedStateHandle()) as T
    }
}