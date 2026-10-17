package com.gather.room

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var store: RoomStore
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var roomActionAfterPermission: String? = null
    private var lastRendered: String? = null
    private var discoveryEnabled = false

    private val refreshTask = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                clearExpiredLocalRoom()
                val room = store.current()
                val key = "${store.profileName()}|$room|${getSharedPreferences(RoomService.PREFS, MODE_PRIVATE).getString("last_message", "")}"
                if (key != lastRendered) render()
                handler.postDelayed(this, 1_500L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RoomStore(this)
        discoveryEnabled = getSharedPreferences(RoomService.PREFS, MODE_PRIVATE).getBoolean("photo_discovery", false)
        buildShell()
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, 1_500L)
        clearExpiredLocalRoom()
        if (store.current() != null && !RoomService.isRunning) RoomService.start(this)
        render()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun buildShell() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xfff7f5f0.toInt()) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(14))
            setBackgroundColor(0xfff7f5f0.toInt())
        }
        header.addView(text("Gather", 26, true, 0xff17372f.toInt()))
        header.addView(text("Nearby moments, shared together", 14, false, 0xff66736e.toInt()))
        root.addView(header)
        scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(36)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun clearExpiredLocalRoom() {
        val room = store.current() ?: return
        val prefs = getSharedPreferences(RoomService.PREFS, MODE_PRIVATE)
        val heartbeatAt = prefs.getLong("heartbeat_at", 0L)
        val heartbeatExpired = !RoomService.isRunning && prefs.getString("heartbeat_room", null) == room.id &&
            heartbeatAt > 0L && System.currentTimeMillis() - heartbeatAt >= SESSION_MILLIS
        val guestLeaseExpired = !room.isHost && room.status == RoomStatus.PAUSED && room.disconnectedAt != null &&
            System.currentTimeMillis() >= room.disconnectedAt + SESSION_MILLIS
        if (heartbeatExpired || guestLeaseExpired) {
            store.clear()
            prefs.edit().putString("last_message", "This room expired after 10 minutes offline").apply()
        }
    }

    private fun render() {
        if (!::body.isInitialized) return
        clearExpiredLocalRoom()
        val y = if (::scroll.isInitialized) scroll.scrollY else 0
        body.removeAllViews()
        val profile = store.profileName()
        val room = store.current()
        val msg = getSharedPreferences(RoomService.PREFS, MODE_PRIVATE).getString("last_message", null)
        lastRendered = "$profile|$room|${msg.orEmpty()}"
        when {
            profile.isNullOrBlank() -> renderProfileSetup()
            room != null -> renderRoom(room)
            else -> renderHome(profile, msg)
        }
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    private fun renderProfileSetup() {
        sectionTitle("A name for this phone")
        body.addView(text("Set a display name once. It will identify your phone to people in nearby rooms; no account or login is used.", 16, false, 0xff53645e.toInt()))
        val name = EditText(this).apply { hint = "Your display name"; setSingleLine(true); textSize = 17f }
        body.addView(name, margin(0, 12, 0, 12))
        button("Continue") {
            val value = name.text.toString().trim()
            if (value.isBlank()) toast("Enter a name to continue") else { store.saveProfile(value); render() }
        }
    }

    private fun renderHome(name: String, message: String?) {
        sectionTitle("Welcome, $name")
        body.addView(text("Create a temporary room for friends nearby. Photos stay on their owners’ phones; only the previews you share enter the room.", 16, false, 0xff53645e.toInt()))
        if (!message.isNullOrBlank()) body.addView(infoCard(message), margin(0, 18, 0, 4))
        button("Create a room") { ensureNearbyPermissions("host") }
        button("Scan a room QR") { ensureNearbyPermissions("scan") }
        body.addView(infoCard("Offline by design · Up to 8 people · Room data clears when the room ends or expires"), margin(0, 14, 0, 0))
    }

    private fun renderRoom(room: RoomState) {
        val status = when (room.status) {
            RoomStatus.ACTIVE -> if (room.isHost) "You’re hosting" else "You’re in the room"
            RoomStatus.PENDING -> "Waiting for host approval"
            RoomStatus.PAUSED -> "Reconnecting to host"
            RoomStatus.ENDED -> "Room ended"
            RoomStatus.EXPIRED -> "Room expired"
        }
        sectionTitle(status)
        body.addView(text("${room.members.size} of ${RoomService.MAX_MEMBERS} people · Started ${formatTime(room.startedAt)}", 14, false, 0xff66736e.toInt()))
        if (room.status == RoomStatus.PAUSED || room.status == RoomStatus.PENDING) {
            body.addView(infoCard(if (room.status == RoomStatus.PAUSED) "Your room data is held for 10 minutes while the app tries to reconnect." else "The host must approve your request before room photos are visible."), margin(0, 12, 0, 8))
            button("Try nearby connection again") { ensureNearbyPermissions("resume") }
        }
        if (room.isHost) renderHostControls(room)
        else button(if (room.status == RoomStatus.ACTIVE) "Leave room" else "Cancel room session") {
            confirm("Leave this room?", "Your room-only data will be cleared from this phone.") { RoomService.command(this, "leave") }
        }
        if (room.status == RoomStatus.ACTIVE) {
            sectionTitle("Shared photos")
            body.addView(text("Previews are ordered by capture time. Tap a photo to save its full-size original when its owner is reachable.", 14, false, 0xff66736e.toInt()))
            if (room.photos.isEmpty()) body.addView(infoCard("No shared photos yet"), margin(0, 10, 0, 4))
            room.photos.sortedBy { it.capturedAt }.forEach { addPhotoCard(room, it) }
            sectionTitle("Your event photos")
            renderDiscoveryControls(room)
            room.candidates.sortedBy { it.capturedAt }.forEach { addCandidateCard(room, it) }
            if (room.candidates.isEmpty()) body.addView(infoCard("Photos taken during this room will appear here after discovery is enabled."), margin(0, 8, 0, 0))
        }
        val last = getSharedPreferences(RoomService.PREFS, MODE_PRIVATE).getString("last_message", null)
        if (!last.isNullOrBlank()) body.addView(infoCard(last), margin(0, 16, 0, 0))
    }

    private fun renderHostControls(room: RoomState) {
        val qr = ImageView(this).apply { setImageBitmap(Invitation.qr(Invitation.encode(room), 540)); adjustViewBounds = true; contentDescription = "Room invitation QR code" }
        body.addView(infoCard("Friends scan this QR in Gather. You approve each person before they can see room photos."), margin(0, 12, 0, 10))
        body.addView(qr, LinearLayout.LayoutParams(dp(250), dp(250)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
        val accept = Switch(this).apply {
            text = if (room.acceptingRequests) "Accepting new requests" else "Not accepting new requests"
            isChecked = room.acceptingRequests
            setOnCheckedChangeListener { _, checked -> RoomService.command(this@MainActivity, "accepting", value = checked) }
        }
        body.addView(accept, margin(0, 8, 0, 4))
        sectionTitle("Join requests")
        if (room.pendingRequests.isEmpty()) body.addView(text("No one is waiting to join.", 14, false, 0xff66736e.toInt()))
        room.pendingRequests.forEach { request ->
            body.addView(text(request.name, 17, true, 0xff17372f.toInt()), margin(0, 10, 0, 2))
            val actions = horizontal()
            actions.addView(buttonView("Approve") { RoomService.command(this, "approve", memberId = request.memberId) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(buttonView("Decline") { RoomService.command(this, "reject", memberId = request.memberId) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            body.addView(actions)
        }
        sectionTitle("People in the room")
        room.members.forEach { member ->
            val label = if (member.isHost) "${member.name} · host" else "${member.name}${if (member.online) "" else " · reconnecting"}"
            val row = horizontal()
            row.addView(text(label, 15, false, 0xff33463f.toInt()), LinearLayout.LayoutParams(0, dp(48), 1f))
            if (!member.isHost) row.addView(buttonView("Remove") { confirm("Remove ${member.name}?", "They will lose access to this room.") { RoomService.command(this, "remove", memberId = member.id) } })
            body.addView(row, margin(0, 2, 0, 2))
        }
        button("End room") { confirm("End this room?", "Room photos and temporary room data will be deleted from members’ phones. Original and saved full-size photos stay on their phones.") { RoomService.command(this, "end") } }
    }

    private fun renderDiscoveryControls(room: RoomState) {
        val hasAccess = PhotoCatalog.hasAutomaticAccess(this)
        val enable = Switch(this).apply {
            text = if (hasAccess) "Automatic event-photo discovery" else "Enable automatic event-photo discovery"
            isChecked = hasAccess && discoveryEnabled
            setOnCheckedChangeListener { _, checked ->
                if (!hasAccess && checked) {
                    isChecked = false
                    explainPhotoAccess()
                } else {
                    discoveryEnabled = checked
                    getSharedPreferences(RoomService.PREFS, MODE_PRIVATE).edit().putBoolean("photo_discovery", checked).apply()
                    RoomService.command(this@MainActivity, "discovery", value = checked)
                    if (checked) {
                        info("Photos captured during this room will be checked on this phone only")
                    }
                }
            }
        }
        body.addView(enable, margin(0, 10, 0, 4))
        val pickerLabel = if (PhotoCatalog.hasLimitedAccess(this)) "Choose photos manually (limited access)" else "Choose photos manually"
        body.addView(buttonView(pickerLabel) { launchPhotoPicker() }, margin(0, 0, 0, 4))
    }

    private fun addCandidateCard(room: RoomState, candidate: Candidate) {
        val box = card()
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val loaded = PhotoCatalog.decodeThumbnail(this@MainActivity, Uri.parse(candidate.uri), 600)
            if (loaded != null) setImageBitmap(loaded) else setImageResource(android.R.drawable.ic_menu_report_image)
        }
        box.addView(image, LinearLayout.LayoutParams(-1, dp(190)))
        val row = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(formatTime(candidate.capturedAt), 13, false, 0xff66736e.toInt()), LinearLayout.LayoutParams(0, dp(52), 1f))
        val share = Switch(this).apply {
            text = if (candidate.shared) "Shared" else "Share"
            isChecked = candidate.shared
            setOnCheckedChangeListener { _, checked -> RoomService.command(this@MainActivity, "share", value = checked, photoId = candidate.id) }
        }
        row.addView(share)
        box.addView(row, margin(12, 4, 12, 4))
        body.addView(box, margin(0, 8, 0, 0))
    }

    private fun addPhotoCard(room: RoomState, photo: SharedPhoto) {
        val box = card()
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bitmap = runCatching { BitmapFactory.decodeFile(photo.previewPath) }.getOrNull()
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(android.R.drawable.ic_menu_report_image)
            setOnClickListener { RoomService.command(this@MainActivity, "request_full", photoId = photo.id) }
            contentDescription = "Preview shared by ${photo.ownerName}. Tap to request full-size photo."
        }
        box.addView(image, LinearLayout.LayoutParams(-1, dp(220)))
        box.addView(text("${photo.ownerName} · ${formatTime(photo.capturedAt)}", 14, true, 0xff33463f.toInt()), margin(12, 8, 12, 10))
        body.addView(box, margin(0, 10, 0, 0))
    }

    private fun explainPhotoAccess() {
        AlertDialog.Builder(this)
            .setTitle("Find photos from this room")
            .setMessage("Gather uses photo access only to find captures from this room’s time window. Candidate discovery and review stay on this phone. Nothing is shared until you turn on a photo’s Share control. If access is denied or limited, you can choose photos with Android’s photo picker.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Continue") { _, _ -> requestPermissions(PhotoCatalog.requiredPermissions(), REQUEST_PHOTOS) }
            .show()
    }

    private fun launchPhotoPicker() {
        val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        else Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        runCatching { startActivityForResult(pick, REQUEST_PICK) }.onFailure { toast("Android photo picker is unavailable") }
    }

    private fun ensureNearbyPermissions(action: String) {
        val needed = nearbyPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (needed.isEmpty()) { beginRoomAction(action); return }
        roomActionAfterPermission = action
        AlertDialog.Builder(this)
            .setTitle("Connect nearby, without internet")
            .setMessage("Gather needs Android Nearby and Bluetooth permissions to find and connect to the room host. Android versions 10–12 may label the scan permission as location; Gather does not read GPS or use your location. Notification permission keeps the active room notice visible in the background.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> requestPermissions(needed, REQUEST_NEARBY) }
            .show()
    }

    private fun nearbyPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT in 29..32) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun beginRoomAction(action: String) {
        when (action) {
            "host" -> {
                val room = store.newHostRoom(store.profileName().orEmpty())
                getSharedPreferences(RoomService.PREFS, MODE_PRIVATE).edit().putString("last_message", "Room created").apply()
                RoomService.start(this)
                render()
            }
            "scan" -> scanInvitation()
            "resume" -> RoomService.retry(this)
        }
    }

    private fun scanInvitation() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        runCatching {
            GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener { barcode ->
                    val (roomId, secret) = Invitation.decode(barcode.rawValue.orEmpty()) ?: run { toast("That QR code is not a Gather invitation"); return@addOnSuccessListener }
                    val room = store.guestRoom(roomId, secret, store.profileName().orEmpty())
                    RoomService.start(this)
                    render()
                }
                .addOnFailureListener { toast("Could not open the QR scanner. Check Google Play services and try again.") }
        }.onFailure { toast("QR scanning is unavailable on this phone") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NEARBY -> {
                val action = roomActionAfterPermission.also { roomActionAfterPermission = null }
                if (action != null && nearbyPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) beginRoomAction(action)
                else toast("Nearby access was not granted. You can allow it in Android settings.")
            }
            REQUEST_PHOTOS -> {
                if (PhotoCatalog.hasAutomaticAccess(this)) {
                    discoveryEnabled = true
                    getSharedPreferences(RoomService.PREFS, MODE_PRIVATE).edit().putBoolean("photo_discovery", true).apply()
                    RoomService.command(this, "discovery", value = true)
                    render()
                } else {
                    AlertDialog.Builder(this).setTitle(if (PhotoCatalog.hasLimitedAccess(this)) "Limited photo access" else "Photo access not granted")
                        .setMessage("Automatic discovery cannot see the complete event photo list. Choose eligible photos manually with Android’s photo picker.")
                        .setPositiveButton("Choose photos") { _, _ -> launchPhotoPicker() }.setNegativeButton("Later", null).show()
                }
            }
        }
    }

    @Deprecated("Android photo-picker activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val room = store.current() ?: return
            val candidate = PhotoCatalog.addPicked(this, room, uri)
            if (candidate == null) toast("This photo has no usable capture time or was taken outside the room’s time window")
            else store.save(room.copy(candidates = (room.candidates.filterNot { it.id == candidate.id } + candidate).sortedBy { it.capturedAt }))
            render()
        }
    }

    private fun confirm(title: String, details: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(details).setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> yes() }.show()
    }

    private fun info(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

    private fun sectionTitle(value: String) { body.addView(text(value, 21, true, 0xff17372f.toInt()), margin(0, 18, 0, 8)) }
    private fun text(value: String, size: Int, bold: Boolean, color: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xffffffff.toInt()); elevation = dp(2).toFloat(); clipToOutline = true }
    private fun infoCard(value: String) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(0xff40564e.toInt()); setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(0xffe9eee9.toInt())
    }
    private fun button(label: String, action: () -> Unit) { body.addView(buttonView(label, action), margin(0, 12, 0, 0)) }
    private fun buttonView(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun margin(left: Int, top: Int, right: Int, bottom: Int) = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_NEARBY = 100
        private const val REQUEST_PHOTOS = 101
        private const val REQUEST_PICK = 102
        private const val SESSION_MILLIS = 10 * 60 * 1000L
    }
}
