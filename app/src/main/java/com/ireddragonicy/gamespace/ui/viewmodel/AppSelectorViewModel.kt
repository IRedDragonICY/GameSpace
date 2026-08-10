/*
 * Copyright (C) 2026 IRedDragonICY
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ireddragonicy.gamespace.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.data.model.AppRef
import com.ireddragonicy.gamespace.data.repo.InstalledAppsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppSelectorViewModel @Inject constructor(
    private val app: Application,
    private val settings: SystemSettings,
    private val installedApps: InstalledAppsRepository,
) : ViewModel() {

    private val _apps = MutableStateFlow<List<AppRef>>(emptyList())
    val apps = _apps.asStateFlow()

    private var all: List<AppRef> = emptyList()

    init { loadApps() }

    private fun loadApps() {
        viewModelScope.launch {
            val registered = settings.userGames.map { it.packageName }.toSet()
            all = installedApps.launchableApps().filter {
                it.packageName != app.packageName && it.packageName !in registered
            }
            _apps.value = all
        }
    }

    fun filterApps(query: String) {
        _apps.value = if (query.isEmpty()) all
        else all.filter {
            it.label.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
        }
    }
}