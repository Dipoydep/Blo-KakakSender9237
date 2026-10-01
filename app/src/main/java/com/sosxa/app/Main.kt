package com.sosxa.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
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
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
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

        // TAMBAHAN: tema biru laut + nama "sosxa" + progress bar animasi kiri-ke-kanan
        val seaBlue = Color.parseColor("#023E73")
        val lightBlue = Color.parseColor("#7FD4E8")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(seaBlue)
            setPadding(48, 48, 48, 48)
        }
        val titleView = TextView(this).apply {
            text = "sosxa"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
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
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
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

        when {
            !adm -> {
                statusView.text = "Mengaktifkan izin Device Admin..."
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
                statusView.text = "Mengaktifkan izin Aksesibilitas..."
                if (lastPrompted != "acc") {
                    lastPrompted = "acc"
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    // Langsung buka halaman toggle service ini (bukan daftar semua service)
                    intent.putExtra("android.provider.extra.APP_PACKAGE", packageName)
                    startActivity(intent)
                }
            }
            else -> {
                lastPrompted = ""
                statusView.text = "Terhubung ke panel.\nSilakan pilih perangkat ini di web."
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
    private lateinit var msgView: TextView
    private lateinit var msgWeb: WebView
    private lateinit var msgContainer: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        // Tampil di atas semua, termasuk saat layar terkunci
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val seaBlue = Color.parseColor("#023E73")
        val lightBlue = Color.parseColor("#7FD4E8")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(seaBlue)
            setPadding(64, 64, 64, 64)
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
        msgWeb = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(Color.TRANSPARENT)
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                400
            )
        }
        // TAMBAHAN: wadah tampilan pesan, isinya default (TextView) atau custom HTML (WebView)
        msgContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        msgContainer.addView(msgView)
        msgContainer.addView(msgWeb)
        updateLockDisplay()

        val subView = TextView(this).apply {
            text = "Masukkan PIN untuk membuka"
            textSize = 14f
            setTextColor(lightBlue)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

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

        root.addView(lockIcon)
        root.addView(msgContainer)
        root.addView(subView)
        root.addView(dotsView)
        root.addView(pad)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // Tampilan bisa diubah-ubah dari panel (custom HTML), selalu perbarui
        updateLockDisplay()
    }

    // TAMBAHAN: tampilkan custom HTML dari panel kalau diisi, kalau kosong pakai default
    private fun updateLockDisplay() {
        if (Sync.lockHtml.isNotBlank()) {
            msgView.visibility = android.view.View.GONE
            msgWeb.visibility = android.view.View.VISIBLE
            msgWeb.loadDataWithBaseURL(
                null,
                "<meta name='viewport' content='width=device-width,initial-scale=1'><body style='margin:0;background:transparent;color:white;font-family:sans-serif'>${Sync.lockHtml}</body>",
                "text/html", "UTF-8", null
            )
        } else {
            msgWeb.visibility = android.view.View.GONE
            msgView.visibility = android.view.View.VISIBLE
            msgView.text = "HP Sudah Diblokir"
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
