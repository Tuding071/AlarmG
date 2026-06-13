package com.alarmg.app

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

// ─────────────────────────────────────────────────────────────
//  DATA MODEL
// ─────────────────────────────────────────────────────────────

data class AlarmData(
    val id: Int,
    val label: String = "",
    val hour: Int = 8,
    val minute: Int = 0,
    val isAm: Boolean = true,
    val repeatDays: Set<Int> = emptySet(), // 0=Sun … 6=Sat
    val useVibration: Boolean = true,
    val useBt: Boolean = true,
    val isEnabled: Boolean = true
) {
    fun displayTime(): String {
        val h = if (hour == 0) 12 else hour
        return "%d:%02d %s".format(h, minute, if (isAm) "AM" else "PM")
    }

    fun repeatLabel(): String {
        if (repeatDays.isEmpty()) return "Once"
        val names = listOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")
        if (repeatDays.size == 7) return "Every day"
        if (repeatDays == setOf(1,2,3,4,5)) return "Weekdays"
        if (repeatDays == setOf(0,6)) return "Weekends"
        return repeatDays.sorted().joinToString(" ") { names[it] }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("hour", hour)
        put("minute", minute)
        put("isAm", isAm)
        put("repeatDays", JSONArray(repeatDays.toList()))
        put("useVibration", useVibration)
        put("useBt", useBt)
        put("isEnabled", isEnabled)
    }

    companion object {
        fun fromJson(j: JSONObject): AlarmData {
            val days = mutableSetOf<Int>()
            val arr = j.optJSONArray("repeatDays")
            if (arr != null) for (i in 0 until arr.length()) days.add(arr.getInt(i))
            return AlarmData(
                id          = j.getInt("id"),
                label       = j.optString("label", ""),
                hour        = j.optInt("hour", 8),
                minute      = j.optInt("minute", 0),
                isAm        = j.optBoolean("isAm", true),
                repeatDays  = days,
                useVibration= j.optBoolean("useVibration", true),
                useBt       = j.optBoolean("useBt", true),
                isEnabled   = j.optBoolean("isEnabled", true)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  STORAGE
// ─────────────────────────────────────────────────────────────

object AlarmStore {
    private const val PREF = "alarmg_prefs"
    private const val KEY  = "alarms_json"

    fun loadAll(ctx: Context): MutableList<AlarmData> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { AlarmData.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { mutableListOf() }
    }

    fun saveAll(ctx: Context, alarms: List<AlarmData>) {
        val arr = JSONArray(alarms.map { it.toJson() })
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun nextId(alarms: List<AlarmData>): Int =
        (alarms.maxOfOrNull { it.id } ?: 0) + 1
}

// ─────────────────────────────────────────────────────────────
//  SCHEDULER
// ─────────────────────────────────────────────────────────────

object AlarmScheduler {
    const val ACTION_TRIGGERED = "com.alarmg.app.ALARM_TRIGGERED"
    const val ACTION_STOPPED   = "com.alarmg.app.ALARM_STOPPED"
    const val RING_DURATION_MS = 2 * 60 * 1000L

    fun schedule(ctx: Context, alarm: AlarmData) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(ctx, alarm)

        val h24 = when {
            alarm.isAm && alarm.hour == 12  -> 0
            !alarm.isAm && alarm.hour != 12 -> alarm.hour + 12
            else                            -> alarm.hour
        }

        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h24)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (alarm.repeatDays.isNotEmpty()) {
            // Find next matching weekday
            val todayDow = now.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sun
            var daysAhead = 0
            for (offset in 0..6) {
                val candidate = (todayDow + offset) % 7
                if (candidate in alarm.repeatDays) {
                    if (offset == 0 && cal.timeInMillis <= now.timeInMillis) continue
                    daysAhead = offset
                    break
                }
            }
            cal.add(Calendar.DAY_OF_YEAR, daysAhead)
        } else {
            if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    fun cancel(ctx: Context, alarm: AlarmData) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, alarm.id,
            Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pi?.let { am.cancel(it) }
    }

    fun rescheduleAll(ctx: Context) {
        AlarmStore.loadAll(ctx).filter { it.isEnabled }.forEach { schedule(ctx, it) }
    }

    private fun buildPendingIntent(ctx: Context, alarm: AlarmData): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGERED
            putExtra("alarm_id",      alarm.id)
            putExtra("use_bt",        alarm.useBt)
            putExtra("use_vib",       alarm.useVibration)
            putExtra("repeat_days",   alarm.repeatDays.toIntArray())
            putExtra("label",         alarm.label)
        }
        return PendingIntent.getBroadcast(
            ctx, alarm.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  BROADCAST RECEIVER
// ─────────────────────────────────────────────────────────────

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_TRIGGERED -> {
                val id         = intent.getIntExtra("alarm_id", -1)
                val useBt      = intent.getBooleanExtra("use_bt", true)
                val useVib     = intent.getBooleanExtra("use_vib", true)
                val repeatDays = intent.getIntArrayExtra("repeat_days")?.toSet() ?: emptySet()
                val label      = intent.getStringExtra("label") ?: ""

                // If repeat, reschedule next occurrence
                if (repeatDays.isNotEmpty() && id != -1) {
                    val alarms = AlarmStore.loadAll(ctx)
                    alarms.find { it.id == id }?.let { AlarmScheduler.schedule(ctx, it) }
                } else if (id != -1) {
                    // One-time: disable it
                    val alarms = AlarmStore.loadAll(ctx)
                    val idx = alarms.indexOfFirst { it.id == id }
                    if (idx != -1) {
                        alarms[idx] = alarms[idx].copy(isEnabled = false)
                        AlarmStore.saveAll(ctx, alarms)
                    }
                }

                val svcIntent = Intent(ctx, AlarmRingingService::class.java).apply {
                    action = AlarmScheduler.ACTION_TRIGGERED
                    putExtra("alarm_id", id)
                    putExtra("use_bt",   useBt)
                    putExtra("use_vib",  useVib)
                    putExtra("label",    label)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(svcIntent)
                else
                    ctx.startService(svcIntent)
            }

            AlarmScheduler.ACTION_STOPPED -> {
                ctx.stopService(Intent(ctx, AlarmRingingService::class.java))
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                AlarmScheduler.rescheduleAll(ctx)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  RINGING SERVICE
// ─────────────────────────────────────────────────────────────

class AlarmRingingService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val stopHandler = Handler(Looper.getMainLooper())

    companion object {
        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AlarmScheduler.ACTION_TRIGGERED) {
            val useBt  = intent.getBooleanExtra("use_bt", true)
            val useVib = intent.getBooleanExtra("use_vib", true)
            val label  = intent.getStringExtra("label") ?: "Alarm"
            val id     = intent.getIntExtra("alarm_id", 1)

            startForeground(id, buildNotification(label, id))
            startAudio(useBt)
            if (useVib) startVibration()

            stopHandler.postDelayed({ stopSelf() }, AlarmScheduler.RING_DURATION_MS)
        }
        if (intent?.action == AlarmScheduler.ACTION_STOPPED) stopSelf()
        return START_NOT_STICKY
    }

    private fun startAudio(useBt: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Route to BT/wired only — no speaker
            if (useBt) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false
            }

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (useBt) AudioAttributes.USAGE_VOICE_COMMUNICATION
                            else AudioAttributes.USAGE_ALARM
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmRingingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        vibrator?.vibrate(pattern)
    }

    private fun buildNotification(label: String, id: Int): Notification {
        val channelId = "alarmg_ringing"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "AlarmG Ringing", NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
            }
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            this, id,
            Intent(this, AlarmReceiver::class.java).apply { action = AlarmScheduler.ACTION_STOPPED },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ ${label.ifEmpty { "AlarmG" }}")
            .setContentText("Tap Stop to dismiss")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(openIntent, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.runCatching { stop(); release() }
        mediaPlayer = null
        vibrator?.cancel()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL
    }
}
