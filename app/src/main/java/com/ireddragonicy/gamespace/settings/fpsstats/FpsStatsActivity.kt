/*
 * Copyright (C) 2026 IRedDragonICY
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ireddragonicy.gamespace.settings.fpsstats

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemProperties
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.AndroidEntryPoint
import com.ireddragonicy.gamespace.data.fpsstats.FpsStatsRepository
import com.ireddragonicy.gamespace.data.fpsstats.SessionListItem
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint(ComponentActivity::class)
class FpsStatsActivity : Hilt_FpsStatsActivity() {

    @Inject lateinit var repository: FpsStatsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0D0E1A),
                    surface = ChartCardBg,
                    primary = ChartCyan,
                ),
            ) {
                FpsStatsListScreen(
                    repository = repository,
                    onBack = { finish() },
                    onSessionClick = { sessionId ->
                        startActivity(
                            Intent(this, FpsStatsDetailActivity::class.java)
                                .putExtra("session_id", sessionId)
                        )
                    },
                    packageManager = packageManager,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsStatsListScreen(
    repository: FpsStatsRepository,
    onBack: () -> Unit,
    onSessionClick: (String) -> Unit,
    packageManager: PackageManager,
) {
    var sessions by remember { mutableStateOf(repository.listSessions()) }

    Scaffold(
        containerColor = Color(0xFF0D0E1A),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "FPS Stats",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D0E1A),
                    titleContentColor = ChartWhite,
                    navigationIconContentColor = ChartWhite,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Device Info Header
            item {
                DeviceInfoCard(
                    platform = SystemProperties.get("ro.board.platform", "unknown").uppercase(),
                    model = Build.MODEL,
                    osVersion = "Android ${Build.VERSION.RELEASE}",
                    profile = "---",
                )
            }

            // Sessions
            if (sessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No recordings yet.\nStart recording from the game panel.",
                            color = ChartTextDim,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        packageManager = packageManager,
                        onClick = { onSessionClick(session.id) },
                        onDelete = {
                            repository.deleteSession(session.id)
                            sessions = repository.listSessions()
                        },
                    )
                }
            }

            // Bottom spacer
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun SessionRow(
    session: SessionListItem,
    packageManager: PackageManager,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val today = remember { dayFormat.format(Date()) }
    val sessionDay = dayFormat.format(Date(session.startTimeMs))
    val isToday = today == sessionDay

    // Try loading app icon
    val appIcon = remember(session.packageName) {
        try {
            packageManager.getApplicationIcon(session.packageName).toBitmap(48, 48).asImageBitmap()
        } catch (_: Exception) { null }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChartCardBg, RoundedCornerShape(12.dp))
            .border(0.5.dp, ChartCardBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // App icon
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = session.appName,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(ChartCardBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = session.appName.take(1),
                    color = ChartWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isToday) {
                    Text(
                        text = "today",
                        color = Color.Black,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(ChartCyan, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = session.appName,
                    color = ChartWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row {
                Text(
                    text = dateFormat.format(Date(session.startTimeMs)),
                    color = ChartTextDim,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "%.1f".format(session.fpsAvg),
                    color = ChartCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "%.2fW".format(session.powerAvgW),
                    color = ChartTextDim,
                    fontSize = 11.sp,
                )
            }
        }

        // Duration
        Text(
            text = "${session.durationSec}s",
            color = ChartTextDim,
            fontSize = 11.sp,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Delete button
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "Delete",
                tint = ChartTextDim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
