package com.ghost.agent.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ghost.agent.core.agent.GhostPhase
import com.ghost.agent.service.GhostSession

@Composable
fun MainScreen(
    spokenGoal: MutableState<String?>,
    onRequestVoice: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onGoalDispatched: () -> Unit,
) {
    val context = LocalContext.current
    val state by GhostSession.state.collectAsStateWithLifecycle()

    var goal by rememberSaveable { mutableStateOf("") }
    var accessibilityOn by remember { mutableStateOf(false) }
    var overlayOn by remember { mutableStateOf(false) }
    var calendarOn by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            accessibilityOn = SetupChecks.isAccessibilityEnabled(context)
            overlayOn = SetupChecks.canDrawOverlays(context)
            calendarOn = SetupChecks.hasCalendarPermissions(context)
        }
    }

    LaunchedEffect(spokenGoal.value) {
        spokenGoal.value?.let {
            goal = it
            spokenGoal.value = null
        }
    }

    Scaffold(
        containerColor = VintageColors.Cream,
        bottomBar = {
            VintageBottomNav(selectedTab) { index ->
                selectedTab = index
                when (index) {
                    1 -> {
                        try {
                            context.startActivity(SetupChecks.appSettingsIntent(context))
                        } catch (e: Exception) {
                            context.startActivity(SetupChecks.accessibilitySettingsIntent())
                        }
                    }
                    2 -> {
                        android.widget.Toast.makeText(context, "History feature coming soon!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Header
            Text(
                "VINTAGE ASSIST",
                style = MaterialTheme.typography.headlineMedium,
                letterSpacing = 1.sp
            )

            // 3x3 Grid
            Box(modifier = Modifier.weight(1f)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        VintageTile("HIGH CONTRAST", Icons.Default.Search, VintageColors.Beige) {
                            try {
                                context.startActivity(SetupChecks.colorCorrectionSettingsIntent())
                            } catch (e: Exception) {
                                context.startActivity(SetupChecks.accessibilitySettingsIntent())
                            }
                        }
                    }
                    item {
                        VintageTile("SCREEN READER", Icons.Default.Headphones, VintageColors.Olive) {
                            try {
                                context.startActivity(SetupChecks.accessibilityServiceSettingsIntent("com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"))
                            } catch (e: Exception) {
                                context.startActivity(SetupChecks.accessibilitySettingsIntent())
                            }
                        }
                    }
                    item {
                        VintageTile("FONT SIZE", Icons.Default.FormatSize, VintageColors.Beige) {
                            try {
                                context.startActivity(SetupChecks.fontSizeSettingsIntent())
                            } catch (e: Exception) {
                                context.startActivity(SetupChecks.accessibilitySettingsIntent())
                            }
                        }
                    }
                    item {
                        VintageTile("LIVE CAPTIONS", Icons.Default.Keyboard, VintageColors.Olive) {
                            try {
                                context.startActivity(SetupChecks.captioningSettingsIntent())
                            } catch (e: Exception) {
                                context.startActivity(SetupChecks.accessibilitySettingsIntent())
                            }
                        }
                    }
                    item {
                        VintageTile("CALENDAR", Icons.Default.CalendarMonth, VintageColors.Mustard) {
                            goal = "Create a calendar event for tomorrow at 10am called 'Important Meeting'"
                            if (GhostSession.start(goal)) onGoalDispatched()
                        }
                    }
                    item {
                        VintageTile("ONE-TAP SOS", Icons.Default.Warning, VintageColors.Red) {
                            try {
                                context.startActivity(SetupChecks.sosIntent())
                            } catch (e: Exception) {
                                // Fallback
                            }
                        }
                    }
                    item {
                        VintageTile("COLOR FILTERS", Icons.Default.Palette, VintageColors.Orange) {
                            try {
                                context.startActivity(SetupChecks.colorCorrectionSettingsIntent())
                            } catch (e: Exception) {
                                context.startActivity(SetupChecks.accessibilitySettingsIntent())
                            }
                        }
                    }
                    item {
                        VintageTile("GUIDED ACCESS", Icons.Default.SmartButton, VintageColors.Blue) {
                            try {
                                context.startActivity(SetupChecks.screenPinningSettingsIntent())
                            } catch (e: Exception) {
                                context.startActivity(SetupChecks.accessibilitySettingsIntent())
                            }
                        }
                    }
                    item {
                        VintageTile("VIBRATION", Icons.Default.Smartphone, VintageColors.Mustard) {
                            performHapticFeedback(context)
                        }
                    }
                }
            }

            // Command Input
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("COMMAND INPUT", fontWeight = FontWeight.Bold) },
                    placeholder = { Text("WHAT CAN I DO?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(8.dp)),
                    enabled = !state.isRunning,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = VintageColors.Olive,
                        unfocusedBorderColor = Color.LightGray,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    minLines = 2,
                    trailingIcon = {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Speak",
                            modifier = Modifier.clickable { onRequestVoice() },
                            tint = VintageColors.Olive
                        )
                    }
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VintageButton(
                        "EXECUTE",
                        VintageColors.Olive,
                        modifier = Modifier.weight(1f),
                        enabled = goal.isNotBlank() && !state.isRunning && accessibilityOn
                    ) {
                        if (GhostSession.start(goal.trim())) onGoalDispatched()
                    }

                    if (state.isRunning) {
                        VintageButton(
                            "STOP",
                            VintageColors.Red,
                            modifier = Modifier.weight(0.5f)
                        ) {
                            GhostSession.stop()
                        }
                    }
                }
            }

            // Permission Cards (Show only if missing)
            if (!accessibilityOn || !overlayOn || !calendarOn) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!accessibilityOn) {
                        SetupCard(
                            title = "SERVICE DISABLED",
                            body = "ENABLE GHOST IN ACCESSIBILITY SETTINGS",
                            onClick = onOpenAccessibilitySettings
                        )
                    }
                    if (!overlayOn) {
                        SetupCard(
                            title = "OVERLAY REQUIRED",
                            body = "ALLOW DRAW OVER OTHER APPS",
                            onClick = onOpenOverlaySettings
                        )
                    }
                    if (!calendarOn) {
                        SetupCard(
                            title = "CALENDAR DENIED",
                            body = "GRANT CALENDAR ACCESS IN SETTINGS",
                            onClick = { context.startActivity(SetupChecks.calendarPermissionsIntent(context)) }
                        )
                    }
                }
            }

            if (state.isRunning || state.phase == GhostPhase.FINISHED) {
                LiveStatus(state)
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, body: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(2.dp, VintageColors.Red, RoundedCornerShape(4.dp)),
        color = VintageColors.Red.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = VintageColors.Red)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = VintageColors.Red)
                Text(body, style = MaterialTheme.typography.bodySmall, color = VintageColors.Ink.copy(alpha = 0.8f))
            }
        }
    }
}

private fun performHapticFeedback(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    vibrator.vibrate(VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE))
}

@Composable
private fun VintageTile(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val elevation = if (isPressed) 2.dp else 6.dp

    Surface(
        modifier = Modifier
            .aspectRatio(1f)
            .shadow(elevation, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        color = color,
        shape = RoundedCornerShape(12.dp),
        border = borderStroke(color)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (color == VintageColors.Beige) VintageColors.Ink else Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = if (color == VintageColors.Beige) VintageColors.Ink else Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 11.sp
            )
        }
    }
}

@Composable
private fun VintageButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(50.dp)
            .shadow(if (enabled) 4.dp else 0.dp, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) color else Color.LightGray,
        shape = RoundedCornerShape(8.dp),
        border = if (enabled) borderStroke(color) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = Color.White,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun VintageBottomNav(selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(64.dp)
            .shadow(8.dp, CircleShape),
        color = Color.White,
        shape = CircleShape
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp
        ) {
            NavigationBarItem(
                selected = selected == 0,
                onClick = { onSelect(0) },
                icon = { Icon(Icons.Default.Grid3x3, null) },
                label = { Text("DASHBOARD") },
                colors = navigationColors()
            )
            NavigationBarItem(
                selected = selected == 1,
                onClick = { onSelect(1) },
                icon = { Icon(Icons.Default.Settings, null) },
                label = { Text("SETTINGS") },
                colors = navigationColors()
            )
            NavigationBarItem(
                selected = selected == 2,
                onClick = { onSelect(2) },
                icon = { Icon(Icons.Default.History, null) },
                label = { Text("HISTORY") },
                colors = navigationColors()
            )
        }
    }
}

@Composable
private fun LiveStatus(state: com.ghost.agent.core.agent.GhostState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.7f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                state.phase.name,
                style = MaterialTheme.typography.labelSmall,
                color = VintageColors.Olive
            )
            Text(
                state.statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = VintageColors.Ink
            )
            if (state.isRunning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = VintageColors.Mustard,
                    trackColor = Color.Transparent
                )
            }
        }
    }
}

private fun borderStroke(color: Color) = androidx.compose.foundation.BorderStroke(
    width = 1.dp,
    color = color.copy(alpha = 0.2f)
)

@Composable
private fun navigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = VintageColors.Olive,
    selectedTextColor = VintageColors.Olive,
    unselectedIconColor = Color.Gray,
    unselectedTextColor = Color.Gray,
    indicatorColor = VintageColors.Beige
)
