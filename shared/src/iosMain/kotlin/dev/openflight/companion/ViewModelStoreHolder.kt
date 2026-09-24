// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass

/**
 * Owns one SwiftUI screen's shared ViewModel (ADR 0001, step R2). `ViewModel.onCleared()` is
 * protected, so Swift can't call it; instead the Swift `ViewModelHost` registers its ViewModel here
 * and calls [clear] when it's released, which runs `onCleared()` and cancels `viewModelScope`, the
 * same way an Android `ViewModelStoreOwner` does.
 */
class ViewModelStoreHolder {
    private val store = ViewModelStore()

    /** Registers [viewModel] with this store (once), so [clear] clears it. */
    fun adopt(viewModel: ViewModel) {
        val factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(
                    modelClass: KClass<T>,
                    extras: CreationExtras,
                ): T {
                    @Suppress("UNCHECKED_CAST") // The provider asks for exactly viewModel::class.
                    return viewModel as T
                }
            }
        ViewModelProvider.create(store, factory)[viewModel::class]
    }

    /** Clears every adopted ViewModel (`onCleared()`); call once, when the screen goes away. */
    fun clear() = store.clear()
}
