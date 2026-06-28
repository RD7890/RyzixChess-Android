package com.ryzix.chess.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg          = Color(0xFF0D0D0D)
private val Surf        = Color(0xFF181818)
private val Surf2       = Color(0xFF1E1E1E)
private val Primary     = Color(0xFFFF2541)
private val PrimaryDark = Color(0xFFC01D30)
private val PrimaryBg   = Color(0xFF1E0A0B)
private val SfBlue      = Color(0xFF4FC3F7)
private val SfBlueBg    = Color(0xFF0A1A2E)
private val SfBlueDark  = Color(0xFF1A3A5E)
private val Border      = Color(0xFF3C3C3C)
private val Muted       = Color(0xFF888888)

@Composable
fun HomeScreen(
    onPlayVsRyzix:  (playerIsWhite: Boolean) -> Unit,
    onPlayOtb:      () -> Unit = {},
    onEngineBattle: (sfPlaysWhite: Boolean, thinkSecs: Int) -> Unit = { _, _ -> },
) {
    var selectedTime    by remember { mutableStateOf("5+0") }
    var selectedTimeSub by remember { mutableStateOf("5 min · Blitz") }
    var selectedLevel   by remember { mutableIntStateOf(3) }

    val levelLabels  = listOf("800","1200","1600","2000","2400","2800")
    val levelDetails = listOf("Skill 0","Skill 4","Skill 8","Skill 12","Skill 16","Skill 20")
    val timeOptions  = listOf("1+0" to "1 min · Bullet","3+0" to "3 min · Blitz","5+0" to "5 min · Blitz","10+0" to "10 min · Rapid")

    // Tracks which card is showing color-picker or other sub-UI
    var showBattleColorPicker by remember { mutableStateOf(false) }
    var battleThinkSecs       by remember { mutableIntStateOf(5) }
    val thinkOptions = listOf(1, 3, 5, 10, 15, 30)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState()),
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Primary)
                            .border(1.5.dp, PrimaryDark, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Casino, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Text("Ryzix Chess", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
                Text("Powered by Ryzix Engine", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp, start = 48.dp))
            }
            MattIconBtn(Icons.Rounded.Bolt, "Activity")
        }

        // ── Quick Play card ───────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Surf)
                .border(1.5.dp, Border, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column {
                    Text("QUICK PLAY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Muted, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(selectedTime, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text(selectedTimeSub, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    timeOptions.forEach { (t, sub) ->
                        ChipButton(label = t, selected = selectedTime == t, onClick = { selectedTime = t; selectedTimeSub = sub })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onPlayVsRyzix(true) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Play Now", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── Engine Level ──────────────────────────────────────────────────────
        Text("ENGINE LEVEL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Muted,
            letterSpacing = 1.sp, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
        Row(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            levelLabels.forEachIndexed { i, lbl ->
                ChipButton(label = lbl, selected = selectedLevel == i, onClick = { selectedLevel = i },
                    modifier = Modifier.weight(1f), fontSize = 11)
            }
        }
        Row(modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Memory, null, tint = Primary, modifier = Modifier.size(13.dp))
            Text("${levelDetails[selectedLevel]} · ${listOf(500,700,900,1200,1500,2000)[selectedLevel]}ms think time",
                fontSize = 12.sp, color = Muted)
        }

        // ── Game Modes ────────────────────────────────────────────────────────
        Text("GAME MODES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Muted,
            letterSpacing = 1.sp, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ModeRow(
                icon = Icons.Rounded.SmartToy,
                iconBg = PrimaryBg, iconTint = Primary, iconBorderColor = Color(0xFF5A1520),
                title = "vs Ryzix", sub = "Play vs Ryzix Engine — no hints",
                onClick = { onPlayVsRyzix(true) },
            )
            ModeRow(
                icon = Icons.Rounded.People,
                iconBg = Surf2, iconTint = Muted, iconBorderColor = Border,
                title = "Two Players", sub = "Pass & play — engine shows hints",
                onClick = onPlayOtb,
            )
            ModeRow(
                icon = Icons.Rounded.Extension,
                iconBg = Surf2, iconTint = Muted, iconBorderColor = Border,
                title = "Puzzles", sub = "Train with daily puzzles",
                onClick = {},
            )
        }

        // ── Engine Battle card ────────────────────────────────────────────────
        Text("ENGINE BATTLE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SfBlue,
            letterSpacing = 1.sp, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SfBlueBg)
                .border(1.5.dp, SfBlueDark, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Title row
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0D2840))
                        .border(1.5.dp, SfBlueDark, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚔", fontSize = 20.sp)
                }
                Column {
                    Text("Stockfish 16 vs Ryzix 1000", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("Watch two AIs battle it out", fontSize = 12.sp, color = SfBlue.copy(alpha = 0.7f), modifier = Modifier.padding(top = 2.dp))
                }
            }

            // Info badges
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoBadge("SF Full Strength", SfBlueBg, SfBlue, SfBlueDark)
                InfoBadge("Ryzix 1000 ELO", PrimaryBg, Primary, Color(0xFF5A1520))
                InfoBadge("Humanoid", Surf2, Muted, Border)
            }

            Text(
                "Both engines think before moving. Stockfish plays at full power — Ryzix plays at ~1000 ELO with human-like delays and imperfect decisions.",
                fontSize = 12.sp, color = Muted, lineHeight = 18.sp,
            )

            if (!showBattleColorPicker) {
                Button(
                    onClick = { showBattleColorPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SfBlue),
                    contentPadding = PaddingValues(vertical = 13.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp), tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Engine Battle", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            } else {
                // ── Max think time picker ─────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Max think time / move", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Medium)
                    Text(
                        if (battleThinkSecs < 60) "${battleThinkSecs}s" else "${battleThinkSecs / 60}m",
                        fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    thinkOptions.forEach { secs ->
                        ChipButton(
                            label = if (secs < 60) "${secs}s" else "${secs / 60}m",
                            selected = battleThinkSecs == secs,
                            onClick = { battleThinkSecs = secs },
                            modifier = Modifier.weight(1f),
                            fontSize = 11,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // ── Color picker ──────────────────────────────────────────────
                Text("Who plays White?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Stockfish plays White
                    Surface(
                        onClick = { showBattleColorPicker = false; onEngineBattle(true, battleThinkSecs) },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0D2840),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, SfBlueDark),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("SF", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = SfBlue)
                            Text("Stockfish 16", fontSize = 11.sp, color = SfBlue, fontWeight = FontWeight.SemiBold)
                            Text("♔ plays White", fontSize = 10.sp, color = SfBlue.copy(alpha = 0.65f))
                        }
                    }
                    // Ryzix plays White
                    Surface(
                        onClick = { showBattleColorPicker = false; onEngineBattle(false, battleThinkSecs) },
                        shape = RoundedCornerShape(12.dp),
                        color = PrimaryBg,
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF5A1520)),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("R", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Primary)
                            Text("Ryzix", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                            Text("♔ plays White", fontSize = 10.sp, color = Primary.copy(alpha = 0.65f))
                        }
                    }
                }
                TextButton(
                    onClick = { showBattleColorPicker = false },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Cancel", color = Muted, fontSize = 12.sp) }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoBadge(text: String, bg: Color, fg: Color, border: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun MattIconBtn(icon: ImageVector, desc: String, modifier: Modifier = Modifier) {
    Surface(
        onClick = {},
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = Surf2,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Border),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, desc, tint = Muted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ChipButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, fontSize: Int = 12) {
    Surface(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) Primary else Surf2,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (selected) PrimaryDark else Border),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(label, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Muted)
        }
    }
}

@Composable
private fun ModeRow(icon: ImageVector, iconBg: Color, iconTint: Color, iconBorderColor: Color, title: String, sub: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(14.dp), color = Surf,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Border), tonalElevation = 0.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(iconBg).border(1.5.dp, iconBorderColor, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(sub, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Border, modifier = Modifier.size(18.dp))
        }
    }
}
