package com.gather.room

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class RoomRole { HOST, GUEST }
enum class RoomStatus { ACTIVE, PAUSED, ENDED, EXPIRED, PENDING }

data class Member(
    val id: String,
    val name: String,
    val isHost: Boolean = false,
    val online: Boolean = true,
    val disconnectedAt: Long? = null
)

data class Candidate(
    val id: String,
    val uri: String,
    val capturedAt: Long,
    val shared: Boolean = false,
    val previewPath: String? = null,
    val sharedPhotoId: String? = null
)

data class SharedPhoto(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val capturedAt: Long,
    val previewPath: String
)

data class JoinRequest(val memberId: String, val name: String, val receivedAt: Long)

data class RoomState(
    val id: String,
    val secret: String,
    val startedAt: Long,
    val role: RoomRole,
    val localMemberId: String,
    val localName: String,
    val status: RoomStatus = RoomStatus.ACTIVE,
    val acceptingRequests: Boolean = true,
    val members: List<Member> = emptyList(),
    val pendingRequests: List<JoinRequest> = emptyList(),
    val candidates: List<Candidate> = emptyList(),
    val photos: List<SharedPhoto> = emptyList(),
    val disconnectedAt: Long? = null
) {
    val isHost: Boolean get() = role == RoomRole.HOST
    val roomDirectoryName: String get() = "room-$id"
}

/** Stores one temporary room manifest and app-private room files; originals stay in MediaStore. */
class RoomStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val roomFile = File(appContext.filesDir, "active-room.json")

    fun profileName(): String? = prefs.getString("display_name", null)

    fun saveProfile(name: String) {
        prefs.edit().putString("display_name", name.trim()).apply()
    }

    fun memberId(): String = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("installation_id", it).apply()
    }

    fun newHostRoom(name: String): RoomState {
        val room = RoomState(
            id = UUID.randomUUID().toString(),
            secret = UUID.randomUUID().toString().replace("-", ""),
            startedAt = System.currentTimeMillis(),
            role = RoomRole.HOST,
            localMemberId = memberId(),
            localName = name,
            members = listOf(Member(memberId(), name, isHost = true))
        )
        roomDirectory(room).mkdirs()
        save(room)
        return room
    }

    fun guestRoom(id: String, secret: String, name: String): RoomState = RoomState(
        id = id,
        secret = secret,
        startedAt = System.currentTimeMillis(),
        role = RoomRole.GUEST,
        localMemberId = memberId(),
        localName = name,
        status = RoomStatus.PENDING,
        members = listOf(Member(memberId(), name, online = true))
    ).also {
        roomDirectory(it).mkdirs()
        save(it)
    }

    fun current(): RoomState? {
        if (!roomFile.isFile) return null
        return runCatching { decode(JSONObject(roomFile.readText())) }.getOrNull()
    }

    @Synchronized fun save(room: RoomState) {
        roomFile.parentFile?.mkdirs()
        val tmp = File(roomFile.parentFile, "${roomFile.name}.tmp")
        tmp.writeText(encode(room).toString())
        if (!tmp.renameTo(roomFile)) {
            roomFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun roomDirectory(room: RoomState): File = File(appContext.filesDir, room.roomDirectoryName)
    fun previewFile(room: RoomState, photoId: String): File = File(File(roomDirectory(room), "previews"), "$photoId.jpg")
    fun transferFile(room: RoomState, transferId: String): File = File(File(roomDirectory(room), "transfers"), "$transferId.bin")

    @Synchronized fun clear() {
        RoomExpiryScheduler.cancel(appContext)
        appContext.filesDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("room-") }?.forEach { it.deleteRecursively() }
        roomFile.delete()
        File(appContext.filesDir, "active-room.json.tmp").delete()
    }

    private fun encode(room: RoomState) = JSONObject().apply {
        put("id", room.id); put("secret", room.secret); put("startedAt", room.startedAt)
        put("role", room.role.name); put("localMemberId", room.localMemberId); put("localName", room.localName)
        put("status", room.status.name); put("acceptingRequests", room.acceptingRequests)
        put("disconnectedAt", room.disconnectedAt ?: JSONObject.NULL)
        put("members", JSONArray().apply { room.members.forEach { put(JSONObject().apply {
            put("id", it.id); put("name", it.name); put("isHost", it.isHost); put("online", it.online)
            put("disconnectedAt", it.disconnectedAt ?: JSONObject.NULL)
        }) } })
        put("pending", JSONArray().apply { room.pendingRequests.forEach { put(JSONObject().apply {
            put("memberId", it.memberId); put("name", it.name); put("receivedAt", it.receivedAt)
        }) } })
        put("candidates", JSONArray().apply { room.candidates.forEach { put(JSONObject().apply {
            put("id", it.id); put("uri", it.uri); put("capturedAt", it.capturedAt); put("shared", it.shared)
            put("previewPath", it.previewPath ?: JSONObject.NULL)
            put("sharedPhotoId", it.sharedPhotoId ?: JSONObject.NULL)
        }) } })
        put("photos", JSONArray().apply { room.photos.forEach { put(JSONObject().apply {
            put("id", it.id); put("ownerId", it.ownerId); put("ownerName", it.ownerName)
            put("capturedAt", it.capturedAt); put("previewPath", it.previewPath)
        }) } })
    }

    private fun decode(json: JSONObject): RoomState {
        fun JSONArray.toMembers() = (0 until length()).map { getJSONObject(it) }.map {
            Member(it.getString("id"), it.getString("name"), it.optBoolean("isHost"), it.optBoolean("online"), it.nullableLong("disconnectedAt"))
        }
        fun JSONArray.toRequests() = (0 until length()).map { getJSONObject(it) }.map {
            JoinRequest(it.getString("memberId"), it.getString("name"), it.getLong("receivedAt"))
        }
        fun JSONArray.toCandidates() = (0 until length()).map { getJSONObject(it) }.map {
            Candidate(it.getString("id"), it.getString("uri"), it.getLong("capturedAt"), it.optBoolean("shared"), it.nullableString("previewPath"), it.nullableString("sharedPhotoId"))
        }
        fun JSONArray.toPhotos() = (0 until length()).map { getJSONObject(it) }.map {
            SharedPhoto(it.getString("id"), it.getString("ownerId"), it.getString("ownerName"), it.getLong("capturedAt"), it.getString("previewPath"))
        }
        return RoomState(
            id = json.getString("id"), secret = json.getString("secret"), startedAt = json.getLong("startedAt"),
            role = RoomRole.valueOf(json.getString("role")), localMemberId = json.getString("localMemberId"),
            localName = json.getString("localName"), status = RoomStatus.valueOf(json.getString("status")),
            acceptingRequests = json.optBoolean("acceptingRequests", true), members = json.optJSONArray("members")?.toMembers().orEmpty(),
            pendingRequests = json.optJSONArray("pending")?.toRequests().orEmpty(), candidates = json.optJSONArray("candidates")?.toCandidates().orEmpty(),
            photos = json.optJSONArray("photos")?.toPhotos().orEmpty(), disconnectedAt = json.nullableLong("disconnectedAt")
        )
    }

    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key) || !has(key)) null else optString(key).takeIf(String::isNotBlank)
}
