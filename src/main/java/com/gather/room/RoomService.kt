package com.gather.room

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Owns Nearby connections for the lifetime of a room and persists only temporary room state. */
class RoomService : Service() {
    private lateinit var store: RoomStore
    private lateinit var client: ConnectionsClient
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val endpointToMember = ConcurrentHashMap<String, String>()
    private val memberToEndpoint = ConcurrentHashMap<String, String>()
    private val pendingEndpoint = ConcurrentHashMap<String, String>()
    private val incomingFiles = ConcurrentHashMap<Long, Uri>()
    private val incomingPayloads = ConcurrentHashMap<Long, Payload>()
    private val incomingTransfer = ConcurrentHashMap<Long, String>()
    private val outgoingFiles = ConcurrentHashMap<Long, File>()
    private val outgoingTransferIds = ConcurrentHashMap<Long, String>()
    private val transferInfo = ConcurrentHashMap<String, JSONObject>()
    private val receiveTransferByEndpoint = ConcurrentHashMap<String, String>()
    private val guestCleanup = ConcurrentHashMap<String, Runnable>()
    private val pendingShareIds = ConcurrentHashMap.newKeySet<String>()
    private var hostEndpoint: String? = null
    private var scanning = false
    private var advertising = false
    private var heartbeatScheduledFor: String? = null
    private var checkHeartbeatOnStart = true
    private var photoObserver: ContentObserver? = null
    private var photoScanInProgress = false
    private var photoRescanRequested = false
    private var firstStart = true

    override fun onCreate() {
        super.onCreate()
        checkHeartbeatOnStart = !isRunning
        isRunning = true
        store = RoomStore(this)
        client = Nearby.getConnectionsClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val room = store.current()
        if (room == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (staleRoom(room, checkHeartbeatOnStart)) {
            store.clear()
            message("This room expired after 10 minutes offline")
            stopSelf()
            return START_NOT_STICKY
        }
        checkHeartbeatOnStart = false
        startRoomNotification(room)
        heartbeat(room)
        val startingNow = firstStart
        if (startingNow) {
            startTransport(room)
            firstStart = false
        }
        if (intent?.action == ACTION_COMMAND) handleCommand(intent)
        else if (intent?.action == ACTION_RETRY && !startingNow) startTransport(room)
        syncPhotoDiscovery()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        incomingFiles.values.forEach { contentResolver.delete(it, null, null) }
        incomingPayloads.values.forEach { it.close() }
        outgoingFiles.values.forEach { it.delete() }
        incomingFiles.clear(); incomingPayloads.clear(); incomingTransfer.clear()
        outgoingFiles.clear(); outgoingTransferIds.clear()
        photoObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        photoObserver = null
        handler.removeCallbacks(photoScanTask)
        ioExecutor.shutdownNow()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        isRunning = false
        super.onDestroy()
    }

    private fun startRoomNotification(room: RoomState) {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        notification.setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Gather room active")
            .setContentText(if (room.isHost) "Hosting an offline event room" else "Connected to an offline event room")
            .setContentIntent(intent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID, notification.build())
    }

    private fun startTransport(room: RoomState) {
        if (!hasNearbyPermissions()) {
            message("Nearby permissions are needed to connect. Open the room again to grant them.")
            return
        }
        if (room.isHost) {
            client.stopDiscovery()
            val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
            client.startAdvertising(room.localName, serviceId(room.id), connectionCallbacks, options)
                .addOnSuccessListener { advertising = true; message("Invitation is ready to scan") }
                .addOnFailureListener { message("Nearby advertising could not start: ${it.localizedMessage ?: "check Bluetooth and Nearby permissions"}") }
        } else {
            discoverHost(room)
        }
    }

    private fun discoverHost(room: RoomState) {
        if (scanning || room.status == RoomStatus.ENDED || room.status == RoomStatus.EXPIRED) return
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(serviceId(room.id), endpointDiscovery, options)
            .addOnSuccessListener { scanning = true }
            .addOnFailureListener { message("Nearby discovery could not start: ${it.localizedMessage ?: "check Bluetooth and Nearby permissions"}") }
    }

    private val endpointDiscovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val room = store.current() ?: return
            client.stopDiscovery()
            scanning = false
            hostEndpoint = endpointId
            client.requestConnection(room.localName, endpointId, connectionCallbacks)
                .addOnFailureListener { hostEndpoint = null; message("Could not request a nearby connection") }
        }
        override fun onEndpointLost(endpointId: String) = Unit
    }

    private val connectionCallbacks = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // The connection handshake only opens a transport. Membership is still gated by the host.
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode != com.google.android.gms.common.api.CommonStatusCodes.SUCCESS) {
                message("Nearby connection was not completed")
                if (store.current()?.isHost != true) handler.postDelayed({ store.current()?.let(::discoverHost) }, RETRY_MS)
                return
            }
            client.stopDiscovery(); scanning = false
            val room = store.current() ?: return
            if (!room.isHost) {
                hostEndpoint = endpointId
                if (room.status == RoomStatus.PENDING) {
                    send(endpointId, JSONObject().put("type", "join_request").put("room", room.id)
                        .put("secret", room.secret).put("memberId", room.localMemberId).put("name", room.localName))
                } else {
                    send(endpointId, JSONObject().put("type", "resume_request").put("memberId", room.localMemberId).put("name", room.localName))
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val room = store.current() ?: return
            if (room.isHost) {
                val memberId = endpointToMember.remove(endpointId)
                if (memberId == null) {
                    val pendingId = pendingEndpoint.entries.firstOrNull { it.value == endpointId }?.key ?: return
                    pendingEndpoint.remove(pendingId)
                    updateCurrent { it.copy(pendingRequests = it.pendingRequests.filterNot { req -> req.memberId == pendingId }) }
                    return
                }
                memberToEndpoint.remove(memberId)
                pendingEndpoint.remove(memberId)
                val now = System.currentTimeMillis()
                val updated = store.current() ?: return
                store.save(updated.copy(members = updated.members.map {
                    if (it.id == memberId) it.copy(online = false, disconnectedAt = now) else it
                }))
                broadcast(JSONObject().put("type", "member_update").put("members", membersJson(store.current()!!.members)))
                scheduleGuestExpiry(memberId, now + SESSION_MILLIS)
            } else {
                hostEndpoint = null
                val now = System.currentTimeMillis()
                val latest = store.current() ?: return
                store.save(latest.copy(status = RoomStatus.PAUSED, disconnectedAt = latest.disconnectedAt ?: now))
                message("Host connection lost. Reconnecting for up to 10 minutes…")
                handler.postDelayed({ store.current()?.let(::discoverHost) }, RETRY_MS)
                scheduleRoomExpiry(latest.disconnectedAt ?: now)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val raw = payload.asBytes() ?: return
                val message = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull() ?: return
                handleMessage(endpointId, message)
            } else if (payload.type == Payload.Type.FILE) {
                val room = store.current() ?: return
                val transferId = receiveTransferByEndpoint[endpointId]
                val authorized = transferId != null && if (room.isHost) endpointToMember.containsKey(endpointId) else endpointId == hostEndpoint
                val filePayload = payload.asFile()
                val uri = runCatching { filePayload?.asUri() }.getOrNull()
                if (!authorized || uri == null) {
                    uri?.let { contentResolver.delete(it, null, null) }
                    payload.close()
                    return
                }
                if ((filePayload?.size ?: 0L) > MAX_ORIGINAL_BYTES) {
                    client.cancelPayload(payload.id)
                    contentResolver.delete(uri, null, null)
                    payload.close()
                    receiveTransferByEndpoint.remove(endpointId, transferId!!)
                    if (room.isHost) relayTransferError(endpointId, JSONObject().put("transferId", transferId).put("text", "The original exceeds the 100 MiB transfer limit"))
                    else message("The original exceeds the 100 MiB transfer limit")
                    return
                }
                incomingFiles[payload.id] = uri
                incomingPayloads[payload.id] = payload
                incomingTransfer[payload.id] = transferId!!
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE || update.status == PayloadTransferUpdate.Status.CANCELED) {
                incomingFiles.remove(update.payloadId)?.let { contentResolver.delete(it, null, null) }
                incomingPayloads.remove(update.payloadId)?.close()
                val failedIncoming = incomingTransfer.remove(update.payloadId)
                failedIncoming?.let { receiveTransferByEndpoint.remove(endpointId, it) }
                outgoingFiles.remove(update.payloadId)?.delete()
                val failedOutgoing = outgoingTransferIds.remove(update.payloadId)
                if (failedIncoming != null && store.current()?.isHost == true) relayTransferError(endpointId, JSONObject().put("transferId", failedIncoming).put("text", "The full-size photo transfer failed"))
                if (failedOutgoing != null) transferFailed(failedOutgoing)
                return
            }
            if (update.status != PayloadTransferUpdate.Status.SUCCESS) return
            val received = incomingFiles.remove(update.payloadId)
            if (received != null) {
                val transferId = incomingTransfer.remove(update.payloadId)
                if (transferId != null) finishIncomingFile(endpointId, transferId, received, update.payloadId)
                else {
                    contentResolver.delete(received, null, null)
                    incomingPayloads.remove(update.payloadId)?.close()
                }
            } else {
                outgoingFiles.remove(update.payloadId)?.delete()
                outgoingTransferIds.remove(update.payloadId)?.let(transferInfo::remove)
            }
        }
    }

    private fun handleMessage(endpointId: String, msg: JSONObject) {
        val room = store.current() ?: return
        when (msg.optString("type")) {
            "join_request" -> if (room.isHost) handleJoinRequest(endpointId, msg)
            "resume_request" -> if (room.isHost) handleResume(endpointId, msg)
            "join_pending" -> if (!room.isHost) { updateCurrent { it.copy(status = RoomStatus.PENDING) }; message("Join request sent. Waiting for host approval.") }
            "join_approved", "room_snapshot" -> if (!room.isHost) receiveSnapshot(msg)
            "join_rejected" -> if (!room.isHost) { message(msg.optString("text", "The host did not approve this request")); cleanupLocalRoom() }
            "join_required" -> if (!room.isHost) {
                updateCurrent { it.copy(status = RoomStatus.PENDING) }
                send(endpointId, JSONObject().put("type", "join_request").put("room", room.id).put("secret", room.secret)
                    .put("memberId", room.localMemberId).put("name", room.localName))
            }
            "member_update" -> if (!room.isHost) receiveMembers(msg.optJSONArray("members"))
            "accepting" -> if (!room.isHost) updateCurrent { it.copy(acceptingRequests = msg.optBoolean("value")) }
            "leave" -> if (room.isHost) removeEndpointMember(endpointId, msg.optString("memberId"))
            "removed" -> if (!room.isHost && msg.optString("memberId") == room.localMemberId) {
                message("The host removed you from the room"); cleanupLocalRoom()
            }
            "room_ended" -> if (!room.isHost) { message("This event room has ended"); cleanupLocalRoom() }
            "photo_share" -> if (room.isHost) acceptShare(endpointId, msg) else receiveSharedPhoto(msg)
            "photo_unshare" -> if (room.isHost) acceptUnshare(endpointId, msg.optString("photoId"))
            "photo_removed" -> if (!room.isHost) removePhoto(msg.optString("photoId"))
            "full_request" -> if (room.isHost) routeFullRequest(endpointId, msg)
            "full_request_owner" -> if (!room.isHost && msg.optString("ownerId") == room.localMemberId) sendOriginal(msg)
            "full_file_start" -> receiveFileStart(endpointId, msg)
            "full_unavailable" -> if (room.isHost) relayTransferError(endpointId, msg)
            "error" -> {
                if (!room.isHost) receiveTransferByEndpoint.remove(endpointId)?.let(transferInfo::remove)
                message(msg.optString("text", "Room request could not be completed"))
            }
        }
    }

    private fun handleJoinRequest(endpointId: String, msg: JSONObject) {
        val room = store.current() ?: return
        if (msg.optString("room") != room.id || msg.optString("secret") != room.secret) {
            send(endpointId, JSONObject().put("type", "join_rejected")); client.disconnectFromEndpoint(endpointId); return
        }
        val memberId = msg.optString("memberId")
        val existing = room.members.firstOrNull { it.id == memberId }
        if (existing != null) { handleResume(endpointId, msg); return }
        if (!room.acceptingRequests || room.members.size >= MAX_MEMBERS) {
            send(endpointId, JSONObject().put("type", "join_rejected").put("text", if (!room.acceptingRequests) "The room is not accepting requests" else "This room is full"))
            handler.postDelayed({ client.disconnectFromEndpoint(endpointId) }, 600L); return
        }
        val name = msg.optString("name").ifBlank { "Guest" }
        if (room.pendingRequests.none { it.memberId == memberId }) {
            updateCurrent { it.copy(pendingRequests = it.pendingRequests + JoinRequest(memberId, name, System.currentTimeMillis())) }
        }
        pendingEndpoint[memberId] = endpointId
        send(endpointId, JSONObject().put("type", "join_pending"))
        message("$name wants to join")
    }

    private fun handleResume(endpointId: String, msg: JSONObject) {
        val room = store.current() ?: return
        val memberId = msg.optString("memberId")
        val member = room.members.firstOrNull { it.id == memberId }
        if (member == null) {
            send(endpointId, JSONObject().put("type", "join_required"))
            return
        }
        endpointToMember[endpointId] = memberId
        memberToEndpoint[memberId] = endpointId
        pendingEndpoint.remove(memberId)
        cancelGuestExpiry(memberId)
        updateCurrent { it.copy(members = it.members.map { old -> if (old.id == memberId) old.copy(online = true, disconnectedAt = null) else old }) }
        sendSnapshot(endpointId)
        broadcast(JSONObject().put("type", "member_update").put("members", membersJson(store.current()!!.members)), except = endpointId)
    }

    private fun receiveSnapshot(msg: JSONObject) {
        val room = store.current() ?: return
        val startedAt = msg.optLong("startedAt", room.startedAt)
        val members = readMembers(msg.optJSONArray("members"))
        val localMember = members.firstOrNull { it.id == room.localMemberId } ?: Member(room.localMemberId, room.localName)
        store.save(room.copy(startedAt = startedAt, status = RoomStatus.ACTIVE, disconnectedAt = null,
            acceptingRequests = msg.optBoolean("accepting", true), members = (members.filterNot { it.id == room.localMemberId } + localMember)))
        syncPhotoDiscovery()
        msg.optJSONArray("photos")?.let { array ->
            for (i in 0 until array.length()) receiveSharedPhoto(array.getJSONObject(i))
        }
        message("You are in the room")
    }

    private fun receiveMembers(array: JSONArray?) {
        val room = store.current() ?: return
        val members = readMembers(array)
        val local = room.members.firstOrNull { it.id == room.localMemberId } ?: Member(room.localMemberId, room.localName)
        store.save(room.copy(members = members.filterNot { it.id == local.id } + local))
    }

    private fun receiveSharedPhoto(msg: JSONObject) {
        val room = store.current() ?: return
        val photoId = msg.optString("photoId")
        val encoded = msg.optString("preview")
        if (photoId.isBlank() || encoded.isBlank()) return
        val output = store.previewFile(room, photoId)
        runCatching { output.parentFile?.mkdirs(); output.writeBytes(Base64.decode(encoded, Base64.DEFAULT)) }.getOrElse { return }
        val photo = SharedPhoto(photoId, msg.optString("ownerId"), msg.optString("ownerName", "Guest"), msg.optLong("capturedAt"), output.absolutePath)
        updateCurrent { current -> current.copy(photos = (current.photos.filterNot { it.id == photoId } + photo).sortedBy { it.capturedAt }) }
    }

    private fun acceptShare(endpointId: String, incoming: JSONObject) {
        val room = store.current() ?: return
        val ownerId = endpointToMember[endpointId] ?: return
        val member = room.members.firstOrNull { it.id == ownerId } ?: return
        val forwarded = JSONObject(incoming.toString()).put("ownerId", ownerId).put("ownerName", member.name)
        receiveSharedPhoto(forwarded)
        broadcast(forwarded, except = endpointId)
    }

    private fun acceptUnshare(endpointId: String, photoId: String) {
        val room = store.current() ?: return
        val ownerId = endpointToMember[endpointId] ?: return
        val photo = room.photos.firstOrNull { it.id == photoId } ?: return
        if (photo.ownerId != ownerId) return
        removePhoto(photoId)
        broadcast(JSONObject().put("type", "photo_removed").put("photoId", photoId))
    }

    private fun removePhoto(photoId: String) {
        val room = store.current() ?: return
        val photo = room.photos.firstOrNull { it.id == photoId }
        photo?.let { File(it.previewPath).delete() }
        updateCurrent { it.copy(photos = it.photos.filterNot { entry -> entry.id == photoId }, candidates = it.candidates.map { c -> if (c.sharedPhotoId == photoId) c.copy(shared = false, previewPath = null, sharedPhotoId = null) else c }) }
    }

    private fun routeFullRequest(requesterEndpoint: String, msg: JSONObject) {
        val room = store.current() ?: return
        if (requesterEndpoint.isNotBlank() && !endpointToMember.containsKey(requesterEndpoint)) return
        val photo = room.photos.firstOrNull { it.id == msg.optString("photoId") }
        if (photo == null) { transferMessage(requesterEndpoint, "This preview is no longer available"); return }
        if (transferInfo.isNotEmpty()) {
            transferMessage(requesterEndpoint, "Another full-size transfer is already in progress")
            return
        }
        val requestedTransferId = msg.optString("transferId")
        val transferId = requestedTransferId.takeIf { it.length in 32..36 } ?: UUID.randomUUID().toString()
        val ownerEndpoint = memberToEndpoint[photo.ownerId]
        if (photo.ownerId == room.localMemberId) {
            transferInfo[transferId] = JSONObject().put("requesterEndpoint", requesterEndpoint).put("name", photo.id)
            val candidate = room.candidates.firstOrNull { it.sharedPhotoId == photo.id } ?: run {
                transferInfo.remove(transferId)
                transferMessage(requesterEndpoint, "The original photo is unavailable"); return
            }
            sendLocalOriginal(candidate, transferId, requesterEndpoint)
        } else if (ownerEndpoint == null) {
            transferInfo.remove(transferId)
            transferMessage(requesterEndpoint, "The photo owner is unreachable")
        } else {
            transferInfo[transferId] = JSONObject().put("requesterEndpoint", requesterEndpoint)
                .put("requesterId", endpointToMember[requesterEndpoint] ?: room.localMemberId)
                .put("photoId", photo.id).put("ownerId", photo.ownerId)
            receiveTransferByEndpoint[ownerEndpoint] = transferId
            send(ownerEndpoint, JSONObject().put("type", "full_request_owner").put("transferId", transferId)
                .put("photoId", photo.id).put("ownerId", photo.ownerId).put("requesterId", endpointToMember[requesterEndpoint] ?: room.localMemberId))
        }
    }

    private fun sendOriginal(msg: JSONObject) {
        val room = store.current() ?: return
        val candidate = room.candidates.firstOrNull { it.sharedPhotoId == msg.optString("photoId") }
        if (candidate == null) { hostEndpoint?.let { send(it, JSONObject().put("type", "full_unavailable").put("transferId", msg.optString("transferId")).put("text", "The original is unavailable on the owner’s phone")) }; return }
        val transferId = msg.optString("transferId")
        val requesterId = msg.optString("requesterId")
        ioExecutor.execute {
            val file = copyOriginalToTemp(room, candidate, transferId)
            val mime = contentMime(candidate.uri)
            val name = contentName(candidate.uri)
            handler.post {
                if (store.current()?.id != room.id) { file?.delete(); return@post }
                if (file == null) {
                    store.transferFile(room, transferId).delete()
                    hostEndpoint?.let { send(it, JSONObject().put("type", "full_unavailable").put("transferId", transferId).put("text", "The original is unavailable on the owner’s phone")) }
                    return@post
                }
                val host = hostEndpoint ?: run { file.delete(); transferFailed(transferId); return@post }
                send(host, JSONObject().put("type", "full_file_start").put("transferId", transferId)
                    .put("requesterId", requesterId).put("name", name).put("mime", mime))
                sendFile(host, file, transferId)
            }
        }
    }

    private fun sendLocalOriginal(candidate: Candidate, transferId: String, recipient: String) {
        val room = store.current() ?: return
        ioExecutor.execute {
            val file = copyOriginalToTemp(room, candidate, transferId)
            val mime = contentMime(candidate.uri)
            val name = contentName(candidate.uri)
            handler.post {
                if (store.current()?.id != room.id) { file?.delete(); transferInfo.remove(transferId); return@post }
                if (file == null) {
                    store.transferFile(room, transferId).delete()
                    transferInfo.remove(transferId)
                    send(recipient, JSONObject().put("type", "error").put("text", "Original is unavailable"))
                    return@post
                }
                send(recipient, JSONObject().put("type", "full_file_start").put("transferId", transferId).put("name", name).put("mime", mime))
                sendFile(recipient, file, transferId)
            }
        }
    }

    private fun receiveFileStart(endpointId: String, msg: JSONObject) {
        val room = store.current() ?: return
        val transferId = msg.optString("transferId")
        if (transferId.isBlank()) return
        if (room.isHost && endpointToMember.containsKey(endpointId)) {
            val info = transferInfo[transferId] ?: return
            if (endpointToMember[endpointId] != info.optString("ownerId")) return
            info.put("requesterId", msg.optString("requesterId"))
                .put("requesterEndpoint", memberToEndpoint[msg.optString("requesterId")])
                .put("name", msg.optString("name", "photo")).put("mime", msg.optString("mime", "image/jpeg"))
            transferInfo[transferId] = info
        } else if (!room.isHost && endpointId == hostEndpoint) {
            transferInfo[transferId] = JSONObject().put("name", msg.optString("name", "photo"))
                .put("mime", msg.optString("mime", "image/jpeg"))
        } else return
        receiveTransferByEndpoint[endpointId] = transferId
    }

    private fun relayTransferError(endpointId: String, msg: JSONObject) {
        val transferId = msg.optString("transferId")
        val info = transferInfo[transferId] ?: return
        if (endpointToMember[endpointId] != info.optString("ownerId")) return
        transferInfo.remove(transferId)
        memberToEndpoint[info.optString("ownerId")]?.let { receiveTransferByEndpoint.remove(it, transferId) }
        val target = info.optString("requesterEndpoint").takeIf { it.isNotBlank() && it != "null" }
        val text = msg.optString("text", "The full-size photo is unavailable")
        if (target != null) transferMessage(target, text) else message(text)
    }

    private fun transferMessage(endpoint: String, text: String) {
        if (endpoint.isBlank()) message(text) else send(endpoint, JSONObject().put("type", "error").put("text", text))
    }

    private fun finishIncomingFile(endpointId: String, transferId: String, source: Uri, payloadId: Long) {
        val room = store.current()
        if (room == null) {
            contentResolver.delete(source, null, null)
            incomingPayloads.remove(payloadId)?.close()
            return
        }
        ioExecutor.execute {
            val staged = store.transferFile(room, transferId)
            val copied = runCatching {
                staged.parentFile?.mkdirs()
                contentResolver.openInputStream(source)?.use { input -> staged.outputStream().use { sink -> copyBounded(input, sink, MAX_ORIGINAL_BYTES) } } ?: error("Incoming transfer is unavailable")
            }.isSuccess
            contentResolver.delete(source, null, null)
            receiveTransferByEndpoint.remove(endpointId, transferId)
            incomingPayloads.remove(payloadId)?.close()
            if (!copied || store.current()?.id != room.id) {
                staged.delete()
                return@execute
            }

            if (room.isHost) {
                val info = transferInfo[transferId]
                if (info == null) { staged.delete(); return@execute }
                val target = info.optString("requesterEndpoint").takeIf { it.isNotBlank() && it != "null" }
                if (target != null) {
                    handler.post {
                        if (store.current()?.id == room.id && endpointToMember.containsKey(target)) {
                            send(target, JSONObject().put("type", "full_file_start").put("transferId", transferId)
                                .put("name", info.optString("name", "photo")).put("mime", info.optString("mime", "image/jpeg")))
                            sendFile(target, staged, transferId)
                        } else {
                            staged.delete()
                            transferInfo.remove(transferId)
                            message("The requester disconnected before the transfer completed")
                        }
                    }
                } else if (info.optString("requesterId") == room.localMemberId) {
                    val saved = PhotoCatalog.saveReceivedCopy(this, staged, info.optString("name", transferId), info.optString("mime", "image/jpeg"))
                    staged.delete()
                    transferInfo.remove(transferId)
                    handler.post { message(if (saved != null) "Full-size photo saved to Pictures/Gather" else "Could not save the full-size photo") }
                } else {
                    staged.delete()
                    transferInfo.remove(transferId)
                }
            } else {
                val info = transferInfo.remove(transferId)
                val saved = PhotoCatalog.saveReceivedCopy(this, staged, info?.optString("name", transferId) ?: transferId,
                    info?.optString("mime", "image/jpeg") ?: "image/jpeg")
                staged.delete()
                handler.post { message(if (saved != null) "Full-size photo saved to Pictures/Gather" else "Could not save the full-size photo") }
            }
        }
    }

    private fun copyOriginalToTemp(room: RoomState, candidate: Candidate, transferId: String): File? {
        val output = store.transferFile(room, transferId)
        return runCatching {
            contentResolver.query(android.net.Uri.parse(candidate.uri), arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0) && it.getLong(0) > MAX_ORIGINAL_BYTES) error("Original image is above the temporary transfer limit")
            }
            output.parentFile?.mkdirs()
            contentResolver.openInputStream(android.net.Uri.parse(candidate.uri))?.use { input ->
                output.outputStream().use { sink -> copyBounded(input, sink, MAX_ORIGINAL_BYTES) }
            } ?: error("Original image is unavailable")
            output
        }.getOrElse {
            output.delete()
            null
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (total + count > maxBytes) throw IOException("Photo exceeds the temporary transfer limit")
            output.write(buffer, 0, count)
            total += count
        }
    }

    private fun contentMime(uri: String): String = contentResolver.getType(android.net.Uri.parse(uri)) ?: "image/jpeg"
    private fun contentName(uri: String): String = contentResolver.query(android.net.Uri.parse(uri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else "photo"
    } ?: "photo"

    private fun handleCommand(intent: Intent) {
        val room = store.current() ?: return
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            "approve" -> approve(intent.getStringExtra("memberId").orEmpty())
            "reject" -> reject(intent.getStringExtra("memberId").orEmpty())
            "accepting" -> setAccepting(intent.getBooleanExtra("value", false))
            "discovery" -> syncPhotoDiscovery()
            "retry" -> store.current()?.let(::startTransport)
            "remove" -> removeMember(intent.getStringExtra("memberId").orEmpty())
            "leave" -> if (!room.isHost) {
                hostEndpoint?.let { send(it, JSONObject().put("type", "leave").put("memberId", room.localMemberId)) }
                handler.postDelayed({ cleanupLocalRoom() }, 600L)
            }
            "end" -> if (room.isHost) endRoom()
            "share" -> setShare(intent.getStringExtra("photoId").orEmpty(), intent.getBooleanExtra("value", false))
            "request_full" -> requestFull(intent.getStringExtra("photoId").orEmpty())
        }
    }

    private fun approve(memberId: String) {
        val room = store.current() ?: return
        if (!room.isHost || room.members.size >= MAX_MEMBERS) { message("Room is full"); reject(memberId); return }
        val endpoint = pendingEndpoint[memberId] ?: return
        val request = room.pendingRequests.firstOrNull { it.memberId == memberId } ?: return
        val updated = room.copy(members = room.members + Member(request.memberId, request.name), pendingRequests = room.pendingRequests.filterNot { it.memberId == memberId })
        store.save(updated)
        endpointToMember[endpoint] = memberId; memberToEndpoint[memberId] = endpoint; pendingEndpoint.remove(memberId)
        sendSnapshot(endpoint)
        broadcast(JSONObject().put("type", "member_update").put("members", membersJson(updated.members)), except = endpoint)
    }

    private fun reject(memberId: String) {
        val endpoint = pendingEndpoint.remove(memberId) ?: return
        send(endpoint, JSONObject().put("type", "join_rejected"))
        handler.postDelayed({ client.disconnectFromEndpoint(endpoint) }, 600L)
        updateCurrent { it.copy(pendingRequests = it.pendingRequests.filterNot { req -> req.memberId == memberId }) }
    }

    private fun setAccepting(value: Boolean) {
        val room = store.current() ?: return
        if (!room.isHost) return
        updateCurrent { it.copy(acceptingRequests = value) }
        broadcast(JSONObject().put("type", "accepting").put("value", value))
    }

    private fun removeMember(memberId: String) {
        val room = store.current() ?: return
        val endpoint = memberToEndpoint.remove(memberId)
        endpoint?.let {
            send(it, JSONObject().put("type", "removed").put("memberId", memberId))
            handler.postDelayed({ client.disconnectFromEndpoint(it) }, 600L)
            endpointToMember.remove(it)
        }
        cancelGuestExpiry(memberId)
        updateCurrent { it.copy(members = it.members.filterNot { m -> m.id == memberId }, pendingRequests = it.pendingRequests.filterNot { p -> p.memberId == memberId }) }
        broadcast(JSONObject().put("type", "member_update").put("members", membersJson(store.current()!!.members)))
    }

    private fun removeEndpointMember(endpointId: String, memberId: String) {
        if (endpointToMember[endpointId] != memberId) {
            if (pendingEndpoint[memberId] == endpointId) {
                pendingEndpoint.remove(memberId)
                updateCurrent { it.copy(pendingRequests = it.pendingRequests.filterNot { request -> request.memberId == memberId }) }
            }
            return
        }
        endpointToMember.remove(endpointId); memberToEndpoint.remove(memberId); cancelGuestExpiry(memberId)
        updateCurrent { it.copy(members = it.members.filterNot { m -> m.id == memberId }) }
        broadcast(JSONObject().put("type", "member_update").put("members", membersJson(store.current()!!.members)))
    }

    private fun setShare(photoId: String, enabled: Boolean) {
        val room = store.current() ?: return
        if (!room.isHost && room.status != RoomStatus.ACTIVE) return
        val candidate = room.candidates.firstOrNull { it.id == photoId } ?: return
        if (!enabled) {
            pendingShareIds.remove(photoId)
            if (!candidate.shared) return
            val sharedId = candidate.sharedPhotoId ?: return
            val updated = room.copy(candidates = room.candidates.map { if (it.id == photoId) it.copy(shared = false, previewPath = null, sharedPhotoId = null) else it }, photos = room.photos.filterNot { it.id == sharedId })
            store.save(updated)
            File(candidate.previewPath.orEmpty()).delete()
            if (room.isHost) broadcast(JSONObject().put("type", "photo_removed").put("photoId", sharedId))
            else hostEndpoint?.let { send(it, JSONObject().put("type", "photo_unshare").put("photoId", sharedId)) }
            return
        }
        if (candidate.shared || !pendingShareIds.add(photoId)) return
        val sharedId = UUID.randomUUID().toString()
        val output = store.previewFile(room, sharedId)
        ioExecutor.execute {
            val bytes = PhotoCatalog.createPreview(this, android.net.Uri.parse(candidate.uri), output)
            handler.post {
                val stillPending = pendingShareIds.remove(photoId)
                val latest = store.current()
                if (!stillPending || latest?.id != room.id) { output.delete(); return@post }
                val latestCandidate = latest.candidates.firstOrNull { it.id == photoId }
                if (latestCandidate == null || latestCandidate.shared) { output.delete(); return@post }
                if (bytes == null) { output.delete(); message("Could not create a small preview for this photo"); return@post }
                val updatedCandidate = latestCandidate.copy(shared = true, previewPath = output.absolutePath, sharedPhotoId = sharedId)
                val photo = SharedPhoto(sharedId, room.localMemberId, room.localName, latestCandidate.capturedAt, output.absolutePath)
                store.save(latest.copy(candidates = latest.candidates.map { if (it.id == photoId) updatedCandidate else it },
                    photos = (latest.photos.filterNot { it.id == sharedId } + photo).sortedBy { it.capturedAt }))
                val share = photoMessage(photo, Base64.encodeToString(bytes, Base64.NO_WRAP))
                if (room.isHost) broadcast(share) else hostEndpoint?.let { send(it, share) }
            }
        }
    }

    private fun requestFull(photoId: String) {
        val room = store.current() ?: return
        val photo = room.photos.firstOrNull { it.id == photoId } ?: return
        if (photo.ownerId == room.localMemberId) { message("The original is already on this phone"); return }
        if (room.isHost) routeFullRequest("", JSONObject().put("photoId", photoId))
        else {
            if (transferInfo.isNotEmpty()) { message("Another full-size transfer is already in progress"); return }
            val endpoint = hostEndpoint ?: run { message("The host is unreachable. Try again after reconnecting."); return }
            val transferId = UUID.randomUUID().toString()
            transferInfo[transferId] = JSONObject()
            receiveTransferByEndpoint[endpoint] = transferId
            send(endpoint, JSONObject().put("type", "full_request").put("photoId", photoId).put("transferId", transferId))
        }
    }

    private fun sendSnapshot(endpoint: String) {
        val room = store.current() ?: return
        val snapshot = JSONObject().put("type", "room_snapshot").put("startedAt", room.startedAt)
            .put("accepting", room.acceptingRequests).put("members", membersJson(room.members))
            .put("photos", JSONArray())
        send(endpoint, snapshot)
        room.photos.forEach { photo ->
            val file = File(photo.previewPath)
            if (file.isFile) send(endpoint, photoMessage(photo, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)))
        }
    }

    private fun endRoom() {
        val room = store.current() ?: return
        broadcast(JSONObject().put("type", "room_ended"))
        store.save(room.copy(status = RoomStatus.ENDED))
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_message", "Room ended and its temporary data was cleared").apply()
        handler.postDelayed({
            client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
            store.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 1_200L)
    }

    private fun cleanupLocalRoom() {
        client.stopDiscovery(); client.stopAdvertising(); client.stopAllEndpoints()
        store.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val photoScanTask = Runnable {
        val room = store.current() ?: return@Runnable
        val enabled = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("photo_discovery", false)
        if (room.status != RoomStatus.ACTIVE || !enabled || !PhotoCatalog.hasAutomaticAccess(this)) return@Runnable
        if (photoScanInProgress) { photoRescanRequested = true; return@Runnable }
        photoScanInProgress = true
        ioExecutor.execute {
            val found = runCatching { PhotoCatalog.scan(this, room) }.getOrDefault(emptyList())
            handler.post {
                photoScanInProgress = false
                val latest = store.current()
                if (latest?.id == room.id) {
                    val currentById = latest.candidates.associateBy { it.id }
                    val reconciled = found.map { scanned ->
                        val current = currentById[scanned.id]
                        if (current == null) scanned else scanned.copy(
                            shared = current.shared,
                            previewPath = current.previewPath,
                            sharedPhotoId = current.sharedPhotoId
                        )
                    }
                    val retained = latest.candidates.filter { candidate ->
                        (candidate.id.startsWith("picked:") || candidate.shared) && reconciled.none { it.id == candidate.id }
                    }
                    val candidates = (reconciled + retained).sortedBy { it.capturedAt }
                    if (candidates != latest.candidates) store.save(latest.copy(candidates = candidates))
                }
                if (photoRescanRequested) { photoRescanRequested = false; queuePhotoScan() }
            }
        }
    }

    private fun syncPhotoDiscovery() {
        val room = store.current()
        val enabled = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("photo_discovery", false)
        val shouldWatch = room?.status == RoomStatus.ACTIVE && enabled && PhotoCatalog.hasAutomaticAccess(this)
        if (!shouldWatch) {
            photoObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
            photoObserver = null
            handler.removeCallbacks(photoScanTask)
            return
        }
        if (photoObserver == null) {
            photoObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) { queuePhotoScan() }
            }
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, photoObserver!!)
        }
        queuePhotoScan()
    }

    private fun queuePhotoScan() {
        handler.removeCallbacks(photoScanTask)
        handler.postDelayed(photoScanTask, 700L)
    }

    private fun scheduleGuestExpiry(memberId: String, deadline: Long) {
        cancelGuestExpiry(memberId)
        val task = Runnable {
            val room = store.current() ?: return@Runnable
            if (room.isHost && room.members.any { it.id == memberId && !it.online }) {
                updateCurrent { it.copy(members = it.members.filterNot { member -> member.id == memberId }) }
                broadcast(JSONObject().put("type", "member_update").put("members", membersJson(store.current()!!.members)))
            }
        }
        guestCleanup[memberId] = task
        handler.postDelayed(task, (deadline - System.currentTimeMillis()).coerceAtLeast(0))
    }

    private fun cancelGuestExpiry(memberId: String) { guestCleanup.remove(memberId)?.let(handler::removeCallbacks) }

    private fun scheduleRoomExpiry(disconnectedAt: Long) {
        handler.postDelayed({
            val room = store.current() ?: return@postDelayed
            if (!room.isHost && room.status == RoomStatus.PAUSED && room.disconnectedAt == disconnectedAt && System.currentTimeMillis() >= disconnectedAt + SESSION_MILLIS) {
                store.save(room.copy(status = RoomStatus.EXPIRED))
                cleanupLocalRoom()
            }
        }, (disconnectedAt + SESSION_MILLIS - System.currentTimeMillis()).coerceAtLeast(0))
    }

    private fun send(endpoint: String, json: JSONObject) {
        runCatching { client.sendPayload(endpoint, Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))) }
            .onFailure { message("Nearby message could not be sent") }
    }

    private fun sendFile(endpoint: String, file: File, transferId: String) {
        runCatching {
            val payload = Payload.fromFile(file)
            payload.setSensitive(true)
            outgoingFiles[payload.id] = file
            outgoingTransferIds[payload.id] = transferId
            client.sendPayload(endpoint, payload).addOnFailureListener {
                outgoingFiles.remove(payload.id)?.delete()
                outgoingTransferIds.remove(payload.id)?.let(::transferFailed)
            }
        }.onFailure { file.delete(); transferFailed(transferId) }
    }

    private fun transferFailed(transferId: String) {
        val info = transferInfo.remove(transferId)
        val room = store.current()
        if (room?.isHost == true) {
            val target = info?.optString("requesterEndpoint").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            if (target != null) send(target, JSONObject().put("type", "error").put("text", "The full-size photo transfer failed"))
            else message("The full-size photo transfer failed")
        } else {
            hostEndpoint?.let { send(it, JSONObject().put("type", "full_unavailable").put("transferId", transferId).put("text", "The original could not be sent")) }
                ?: message("The full-size photo transfer failed")
        }
    }

    private fun staleRoom(room: RoomState, checkHeartbeat: Boolean): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedId = prefs.getString("heartbeat_room", null)
        val lastBeat = prefs.getLong("heartbeat_at", 0L)
        if (checkHeartbeat && savedId == room.id && lastBeat > 0 && System.currentTimeMillis() - lastBeat >= SESSION_MILLIS) return true
        return !room.isHost && room.status == RoomStatus.PAUSED && room.disconnectedAt != null &&
            System.currentTimeMillis() >= room.disconnectedAt + SESSION_MILLIS
    }

    private fun heartbeat(room: RoomState) {
        if (heartbeatScheduledFor == room.id) return
        heartbeatScheduledFor = room.id
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("heartbeat_room", room.id).putLong("heartbeat_at", System.currentTimeMillis()).apply()
        RoomExpiryScheduler.refresh(this, room)
        handler.postDelayed({
            val active = store.current()?.takeIf { it.id == room.id } ?: return@postDelayed
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("heartbeat_at", System.currentTimeMillis()).apply()
            RoomExpiryScheduler.refresh(this, active)
            heartbeatTick(active)
        }, 60_000L)
        if (!room.isHost && room.status == RoomStatus.PAUSED && room.disconnectedAt != null) scheduleRoomExpiry(room.disconnectedAt)
        if (room.isHost) room.members.filter { !it.isHost && !it.online && it.disconnectedAt != null }.forEach { scheduleGuestExpiry(it.id, it.disconnectedAt!!) }
    }

    private fun heartbeatTick(room: RoomState) {
        if (store.current()?.id != room.id) { heartbeatScheduledFor = null; return }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("heartbeat_at", System.currentTimeMillis()).apply()
        RoomExpiryScheduler.refresh(this, room)
        handler.postDelayed({ store.current()?.takeIf { it.id == room.id }?.let(::heartbeatTick) }, 60_000L)
    }

    private fun broadcast(json: JSONObject, except: String? = null) {
        endpointToMember.keys.filter { it != except }.forEach { send(it, json) }
    }

    private fun updateCurrent(transform: (RoomState) -> RoomState) {
        store.current()?.let { store.save(transform(it)) }
    }

    private fun message(text: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_message", text).apply()
    }

    private fun hasNearbyPermissions(): Boolean {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) required += listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33) required += android.Manifest.permission.NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT in 29..32) required += android.Manifest.permission.ACCESS_FINE_LOCATION
        return required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun serviceId(id: String) = "$packageName.room.${id.replace("-", "")}".take(180)

    private fun membersJson(members: List<Member>) = JSONArray().apply { members.forEach { put(JSONObject()
        .put("id", it.id).put("name", it.name).put("isHost", it.isHost).put("online", it.online)
        .put("disconnectedAt", it.disconnectedAt ?: JSONObject.NULL)) } }

    private fun readMembers(array: JSONArray?): List<Member> = array?.let { values ->
        (0 until values.length()).map { values.getJSONObject(it) }.map {
            Member(it.optString("id"), it.optString("name"), it.optBoolean("isHost"), it.optBoolean("online"), if (it.isNull("disconnectedAt")) null else it.optLong("disconnectedAt"))
        }
    }.orEmpty()

    private fun photoMessage(photo: SharedPhoto, preview: String) = JSONObject().put("type", "photo_share")
        .put("photoId", photo.id).put("ownerId", photo.ownerId).put("ownerName", photo.ownerName)
        .put("capturedAt", photo.capturedAt).put("preview", preview)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Active room", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val ACTION_START = "com.gather.room.START"
        const val ACTION_COMMAND = "com.gather.room.COMMAND"
        const val ACTION_RETRY = "com.gather.room.RETRY"
        const val EXTRA_COMMAND = "command"
        const val PREFS = "profile"
        const val MAX_MEMBERS = 8
        @Volatile var isRunning: Boolean = false
        private const val CHANNEL_ID = "active-room"
        private const val NOTIFICATION_ID = 71
        private const val SESSION_MILLIS = 10 * 60 * 1000L
        private const val RETRY_MS = 3_000L
        private const val MAX_ORIGINAL_BYTES = 100L * 1024 * 1024

        fun start(context: Context) {
            val intent = Intent(context, RoomService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun retry(context: Context) {
            val intent = Intent(context, RoomService::class.java).setAction(ACTION_RETRY)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun command(context: Context, command: String, memberId: String? = null, value: Boolean? = null, photoId: String? = null) {
            val intent = Intent(context, RoomService::class.java).setAction(ACTION_COMMAND).putExtra(EXTRA_COMMAND, command)
            memberId?.let { intent.putExtra("memberId", it) }; value?.let { intent.putExtra("value", it) }; photoId?.let { intent.putExtra("photoId", it) }
            context.startService(intent)
        }
    }
}
