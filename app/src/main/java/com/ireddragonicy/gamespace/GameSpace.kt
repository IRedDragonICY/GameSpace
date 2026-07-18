/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2025 AxionOS
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
package com.ireddragonicy.gamespace

import android.app.Application
import android.content.Intent
import android.os.UserHandle
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import com.ireddragonicy.gamespace.gamebar.GameSpaceService
import com.ireddragonicy.gamespace.thermal.ThermalController
import javax.inject.Inject

@HiltAndroidApp(Application::class)
class GameSpace : Hilt_GameSpace() {

    private val TAG = "GameSpace"

    /**
     * mithermald control plane — hosted here (persistent, android.uid.system)
     * instead of system_server. Started with the process so profile/charging
     * policy is live from boot, exactly like the old framework service.
     */
    @Inject lateinit var thermalController: ThermalController

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application created")
        thermalController.start()
        startGameSpaceService()
    }

    private fun startGameSpaceService() {
        try {
            val intent = Intent(this, GameSpaceService::class.java)
            startServiceAsUser(intent, UserHandle.CURRENT)
            Log.i(TAG, "GameSpaceService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GameSpaceService", e)
        }
    }
}
