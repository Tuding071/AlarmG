package com.alarmsilent.app

import android.app.AlarmManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────
//  THEME
// ─────────────────────────────────────────────────────────────

private val Bg       = Color(0xFF121212)
private val Surf1    = Color(0xFF1E1E1E)
private val Surf2    = Color(0xFF2C2C2E)
private val Surf3    = Color(0xFF3A3A3C)
private val TxtPri   = Color(0xFFEEEEEE)
private val TxtSec   = Color(0xFF8E8E93)
private val TxtDim   = Color(0xFF48484A)
private val AccWhite = Color(0xFFFFFFFF)
private val AccGreen = Color(0xFF32D74B)
private val AccRed   = Color(0xFFFF453A)

// ─────────────────────────────────────────────────────────────
//  MAIN ACTIVITY
// ─────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        setContent { AlarmSilentApp() }
    }
}

// ─────────────────────────────────────────────────────────────
//  ROOT COMPOSABLE
// ─────────────────────────────────────────────────────────────

@Composable
fun AlarmSilentApp() {
    val ctx = LocalContext.current
    var alarms      by remember { mutableStateOf(AlarmStore.loadAll(ctx)) }
    var editTarget  by remember { mutableStateOf<AlarmData?>(null) }
    var showEditor  by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AlarmData?>(null) }

    fun reload() { alarms = AlarmStore.loadAll(ctx) }

    fun saveAndSchedule(alarm: AlarmData, isNew: Boolean) {
        val list = AlarmStore.loadAll(ctx)
        if (isNew) list.add(alarm)
        else {
            val idx = list.indexOfFirst { it.id == alarm.id }
            if (idx != -1) list[idx] = alarm
        }
        AlarmStore.saveAll(ctx, list)
        if (alarm.isEnabled) AlarmScheduler.schedule(ctx, alarm)
        else AlarmScheduler.cancel(ctx, alarm)
        reload()
    }

    fun deleteAlarm(alarm: AlarmData) {
        AlarmScheduler.cancel(ctx, alarm)
        val list = AlarmStore.loadAll(ctx)
        list.removeAll { it.id == alarm.id }
        AlarmStore.saveAll(ctx, list)
        reload()
    }

    fun toggleAlarm(alarm: AlarmData) {
        val updated = alarm.copy(isEnabled = !alarm.isEnabled)
        val list    = AlarmStore.loadAll(ctx)
        val idx     = list.indexOfFirst { it.id == alarm.id }
        if (idx != -1) list[idx] = updated
        AlarmStore.saveAll(ctx, list)
        if (updated.isEnabled) AlarmScheduler.schedule(ctx, updated)
        else AlarmScheduler.cancel(ctx, updated)
        reload()
    }

    Box(Modifier.fillMaxSize().background(Bg)) {

        Column(Modifier.fillMaxSize()) {

            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AlarmSilent",
                    color = TxtPri,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    editTarget = null
                    showEditor = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = AccWhite)
                }
            }

            HorizontalDivider(color = Surf2, thickness = 0.5.dp)

            if (alarms.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⏰", fontSize = 56.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No alarms", color = TxtSec, fontSize = 16.sp)
                        Text("Tap + to add one", color = TxtDim, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmCard(
                            alarm    = alarm,
                            onToggle = { toggleAlarm(alarm) },
                            onEdit   = { editTarget = alarm; showEditor = true },
                            onDelete = { deleteTarget = alarm }
                        )
                    }
                }
            }
        }

        // Editor overlay
        if (showEditor) {
            AlarmEditor(
                existing  = editTarget,
                onSave    = { alarm ->
                    saveAndSchedule(alarm, isNew = editTarget == null)
                    showEditor = false
                },
                onDismiss = { showEditor = false },
                nextId    = { AlarmStore.nextId(AlarmStore.loadAll(ctx)) }
            )
        }

        // Delete confirmation
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                containerColor   = Surf1,
                title = {
                    Text(
                        "Delete alarm?",
                        color      = TxtPri,
                        fontSize   = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    val lbl = if (target.label.isNotEmpty()) " \"${target.label}\"" else ""
                    Text("Remove$lbl at ${target.displayTime()}?", color = TxtSec, fontSize = 14.sp)
                },
                confirmButton = {
                    TextButton(onClick = { deleteAlarm(target); deleteTarget = null }) {
                        Text("Delete", color = AccRed, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text("Cancel", color = TxtSec)
                    }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ALARM CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun AlarmCard(
    alarm: AlarmData,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val alpha = if (alarm.isEnabled) 1f else 0.4f

    Surface(
        color  = Surf1,
        shape  = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: time + meta
            Column(Modifier.weight(1f)) {
                if (alarm.label.isNotEmpty()) {
                    Text(
                        alarm.label,
                        color    = TxtSec.copy(alpha = alpha),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    alarm.displayTime(),
                    color      = TxtPri.copy(alpha = alpha),
                    fontSize   = 34.sp,
                    fontWeight = FontWeight.Light
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment    = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(alarm.repeatLabel(), color = TxtSec.copy(alpha = alpha), fontSize = 12.sp)
                    if (alarm.useVibration) Text("📳", fontSize = 11.sp)
                }
            }

            // Right: delete | edit | toggle
            Row(
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint     = TxtDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint     = TxtSec,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Switch(
                    checked          = alarm.isEnabled,
                    onCheckedChange  = { onToggle() },
                    colors           = SwitchDefaults.colors(
                        checkedThumbColor   = AccWhite,
                        checkedTrackColor   = AccGreen,
                        uncheckedThumbColor = TxtDim,
                        uncheckedTrackColor = Surf3
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ALARM EDITOR
// ─────────────────────────────────────────────────────────────

@Composable
fun AlarmEditor(
    existing: AlarmData?,
    onSave: (AlarmData) -> Unit,
    onDismiss: () -> Unit,
    nextId: () -> Int
) {
    val ctx     = LocalContext.current
    var hour    by remember { mutableStateOf(existing?.hour   ?: 8) }
    var minute  by remember { mutableStateOf(existing?.minute ?: 0) }
    var isAm    by remember { mutableStateOf(existing?.isAm   ?: true) }
    var label   by remember { mutableStateOf(existing?.label  ?: "") }
    var useVib  by remember { mutableStateOf(existing?.useVibration ?: false) }
    var repeat  by remember { mutableStateOf(existing?.repeatDays   ?: emptySet()) }
    var ringtoneUri  by remember { mutableStateOf(existing?.ringtoneUri) }
    var ringtoneName by remember { mutableStateOf(existing?.ringtoneName ?: "Default") }

    val dayLabels = listOf("S","M","T","W","T","F","S")

    // System alarm tone picker
    val systemRingtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.let { uri ->
            val name = RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) ?: "Alarm tone"
            ringtoneUri  = uri.toString()
            ringtoneName = name
        }
    }

    // File picker for custom audio
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            ctx.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val name = it.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: "Custom"
            ringtoneUri  = it.toString()
            ringtoneName = name
        }
    }

    var showRingtoneMenu by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xBB000000))
            .clickable { onDismiss() }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clickable(enabled = false) {},
            color  = Surf1,
            shape  = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    if (existing != null) "Edit Alarm" else "New Alarm",
                    color      = TxtPri,
                    fontSize   = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(20.dp))

                // ── Time picker ──────────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    NumPicker("Hr", hour, 1, 12) { hour = it }
                    Text(
                        ":",
                        color    = TxtPri,
                        fontSize = 32.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    NumPicker("Min", minute, 0, 59) { minute = it }
                    Spacer(Modifier.width(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AmPmChip("AM",  isAm) { isAm = true  }
                        AmPmChip("PM", !isAm) { isAm = false }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Label ────────────────────────────────────
                OutlinedTextField(
                    value           = label,
                    onValueChange   = { label = it },
                    placeholder     = { Text("Label (optional)", color = TxtDim, fontSize = 13.sp) },
                    singleLine      = true,
                    colors          = OutlinedTextFieldDefaults.colors(
                        focusedTextColor     = TxtPri,
                        unfocusedTextColor   = TxtPri,
                        focusedBorderColor   = Surf3,
                        unfocusedBorderColor = Surf2,
                        cursorColor          = TxtPri
                    ),
                    shape    = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                // ── Repeat days ──────────────────────────────
                Text("Repeat", color = TxtSec, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    dayLabels.forEachIndexed { idx, lbl ->
                        val sel = idx in repeat
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (sel) Surf3 else Color.Transparent)
                                .border(1.dp, if (sel) AccWhite else TxtDim, CircleShape)
                                .clickable {
                                    repeat = if (sel) repeat - idx else repeat + idx
                                }
                        ) {
                            Text(
                                lbl,
                                color      = if (sel) AccWhite else TxtDim,
                                fontSize   = 13.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Ringtone picker ──────────────────────────
                Text("Ringtone", color = TxtSec, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))

                Box {
                    Surface(
                        color  = Surf2,
                        shape  = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRingtoneMenu = true }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint     = TxtSec,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                ringtoneName,
                                color    = TxtPri,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = TxtSec
                            )
                        }
                    }

                    DropdownMenu(
                        expanded        = showRingtoneMenu,
                        onDismissRequest = { showRingtoneMenu = false },
                        containerColor  = Surf2
                    ) {
                        DropdownMenuItem(
                            text    = { Text("Alarm tones", color = TxtPri, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Alarm, null, tint = TxtSec, modifier = Modifier.size(16.dp))
                            },
                            onClick = {
                                showRingtoneMenu = false
                                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                                        RingtoneManager.TYPE_ALARM)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alarm Tone")
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                    if (ringtoneUri != null)
                                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                            Uri.parse(ringtoneUri))
                                }
                                systemRingtoneLauncher.launch(intent)
                            }
                        )
                        DropdownMenuItem(
                            text    = { Text("Pick from files", color = TxtPri, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.FolderOpen, null, tint = TxtSec, modifier = Modifier.size(16.dp))
                            },
                            onClick = {
                                showRingtoneMenu = false
                                fileLauncher.launch(arrayOf("audio/*"))
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Vibration switch ─────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📳", fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Vibration",
                        color    = TxtPri,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked         = useVib,
                        onCheckedChange = { useVib = it },
                        colors          = SwitchDefaults.colors(
                            checkedThumbColor   = AccWhite,
                            checkedTrackColor   = AccGreen,
                            uncheckedThumbColor = TxtDim,
                            uncheckedTrackColor = Surf3
                        )
                    )
                }

                Spacer(Modifier.height(20.dp))

                // ── Save ─────────────────────────────────────
                Button(
                    onClick = {
                        onSave(
                            AlarmData(
                                id           = existing?.id ?: nextId(),
                                label        = label.trim(),
                                hour         = hour,
                                minute       = minute,
                                isAm         = isAm,
                                repeatDays   = repeat,
                                useVibration = useVib,
                                ringtoneUri  = ringtoneUri,
                                ringtoneName = ringtoneName,
                                isEnabled    = true
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape  = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Surf2)
                ) {
                    Text("Set Alarm", color = TxtPri, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  UI HELPERS
// ─────────────────────────────────────────────────────────────

@Composable
fun NumPicker(label: String, value: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TxtDim, fontSize = 10.sp)
        IconButton(
            onClick  = { onValue(if (value < max) value + 1 else min) },
            modifier = Modifier.size(34.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = TxtSec)
        }
        Text(
            "%02d".format(value),
            color      = TxtPri,
            fontSize   = 30.sp,
            fontWeight = FontWeight.Light
        )
        IconButton(
            onClick  = { onValue(if (value > min) value - 1 else max) },
            modifier = Modifier.size(34.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TxtSec)
        }
    }
}

@Composable
fun AmPmChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(48.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Surf3 else Color.Transparent)
            .border(1.dp, if (selected) AccWhite else TxtDim, RoundedCornerShape(8.dp))
            .clickable { onClick() }
    ) {
        Text(
            label,
            color      = if (selected) AccWhite else TxtDim,
            fontSize   = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
