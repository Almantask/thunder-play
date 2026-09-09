package com.thunderplay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thunderplay.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The little bit of state the app shell itself needs: which optional tabs are switched on. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    settings: SettingsRepository,
) : ViewModel() {

    val abTestingEnabled: StateFlow<Boolean> = settings.settings
        .map { it.abTestingEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
