# Design

## Context

The workspace has OpenSpec configuration but no Android application or existing code conventions. The V1 is Android-first and offline-only, serves groups of at most eight people including the host, and keeps originals on their owners' phones. The host relays room traffic. The app has no account service, internet mode, or permanent shared-photo store.

## Goals / Non-Goals

**Goals:**

- Create an offline room from a QR invitation and let the host control admission and lifecycle.
- Keep discovery and candidate review local; share only previews the owner explicitly selects.
- Transfer a full-size original only on request and only while its owner is reachable.
- Keep all room data temporary, clear it on end or expiry, and preserve original and recipient-saved photos.
- Keep a room usable in the background with a visible notification.

**Non-Goals:**

- Internet-based rooms, accounts, cloud backup, or a central photo database.
- iOS support, groups larger than eight, permanent shared albums, or automatic full-size downloads.
- Recalling a full-size copy already saved by a recipient.

## Decisions

### Native Android app and local identity

Build V1 as a native Android app in Kotlin. Store the profile name and a locally generated installation identity on the device; the identity distinguishes members who choose the same display name and is not an account credential. Keep active room state in a room-scoped app-private manifest and files rather than a server or permanent shared-photo database. Delete the room directory at teardown. Keep source photo URIs on the owner's device and copy only room previews into temporary room cache.

### Nearby Connections with the host as the hub

Use Google Play services Nearby Connections for offline discovery, connection, and payload transfer. Select its `P2P_STAR` strategy: the host advertises as the hub and guests connect as spokes. This matches the agreed host-relay topology and lets one host serve up to seven guests. Nearby Connections uses local Bluetooth, BLE, and Wi-Fi capabilities and supports fully offline, encrypted connections; it does not require a shared internet connection. The strategy and Android permission requirements must be checked against the app's selected Android support range. See [Nearby Connections overview](https://developers.google.com/nearby/connections/overview), [connection strategies](https://developers.google.com/nearby/connections/strategies), and [Android strategy reference](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/Strategy).

The QR code carries an opaque room identifier and invitation secret. Scanning starts Nearby discovery automatically; the app filters for the matching host and initiates the connection without asking the guest to choose a device or type a code. The connection handshake is distinct from room admission: after the connection is authenticated and established, the guest submits a join request under their profile name, and the host must approve it before the guest sees room content. The host's accepting-requests toggle gates new requests; the QR does not need to be refreshed. Bind each connection to the room invitation and reject payloads from endpoints that are not current members. Nearby's connection API requires the application to accept the connection on both endpoints, and its documentation recommends authentication; keep that transport handshake separate from the host's explicit app-level guest approval. See [manage connections](https://developers.google.com/nearby/connections/android/manage-connections).

After joining, stop active discovery to reduce radio work while keeping established connections. Queue room payloads through the host. Send a generated preview once when a photo is shared and cache it at recipients so opening the feed does not repeatedly transfer image data. The host routes a full-size request to the owner and relays the response only for that request. Do not retain a persistent full-size host copy; use a stream or bounded app-private temporary buffer if the transport API requires staging, and delete it after completion or failure. Serialize large transfers to limit battery and radio contention.

### Room states, background work, and expiry

Represent a room as `active`, `host-disconnected/paused`, `ended`, or `expired`. The host begins the event time window when creating the room. An Android foreground service with an ongoing notification maintains the active connection while the app is backgrounded, subject to the device's current Android permission and background-execution rules.

Record the disconnect time and a 10-minute expiry deadline locally when a connection is lost. A guest that reconnects before its deadline resumes and reconciles room state; after the deadline its session cannot be restored and the app removes its room data. A host disconnect pauses the room for everyone; reconnecting before the room deadline resumes it, while reaching the deadline expires the room. Check deadlines before restoring any room after process restart. Android may defer physical cleanup while the app is stopped or the device is powered off, so expired content must remain inaccessible and be deleted before the next display when the app next runs.

An explicit host end sends a close event to reachable members and triggers immediate local teardown. Disconnected members delete room data when they reconnect and learn that the room ended, or when their 10-minute session lease expires. A guest's disconnect/expiry does not end the room for other members.

### Local photo discovery and review

Ask for media access only when the member enables event photo discovery, after explaining that access is needed to find event photos. Use Android media metadata to discover captures locally from room creation to the current room time; do not upload candidate lists or originals. Keep candidates and their private/shared marker on the owner device. If permission is denied or limited, offer Android's system photo picker and verify the selected image's capture time against the same room window. Exclude a selection whose time is missing or outside the window.

Create a bounded preview locally only after the owner turns on sharing. Send the preview, capture time, owner identity, and room-scoped photo ID through the host. The shared feed sorts by recorded capture time and labels the owner. An unshare update removes the preview from connected members; use versioned room state or a removal marker so reconnecting members cannot restore stale previews. Room teardown deletes the candidate manifest, share markers, preview cache, and removal markers. Never delete originals during cleanup.

Keep the full-size original unchanged on the owner's device. Do not strip metadata from the original before a requested transfer; the recipient's saved copy therefore retains any metadata embedded in the source. A recipient's saved copy is independent of room membership and cannot be recalled by unsharing or ending the room.

### Time and compatibility assumptions

V1 targets Android 10 (API 29) or newer. It compares the capture timestamp recorded on each device with the room's creation/end timestamps and assumes device clocks are set automatically and are close enough for this use. It does not correct clock skew. The connection path depends on Google Play services Nearby Connections and the Android permissions available on the supported device; devices lacking that capability receive a clear unsupported/permission message rather than an online fallback. Verify the API 29 baseline and current Android versions with a two-device prototype before release.

## Risks / Trade-offs

- **Nearby radio and device variability** → test actual Android devices without internet, including a maximum-size room, before fixing the supported device range; surface unavailable permissions or services clearly.
- **Host is a throughput and availability bottleneck** → cap rooms at eight, queue large transfers, cache previews, and pause/expire the room after a 10-minute host disconnect.
- **QR invitation can be copied or shown to an unintended person** → make the token room-scoped, gate all guest membership on host approval, and reject requests when the host pauses admission or the room is full.
- **Full-size relay may need transient staging and can use battery** → send only on request, stream or bound temporary buffering, clean it on every transfer outcome, and serialize transfers.
- **Android may stop background work or delay cleanup** → use an ongoing foreground notification while the room is active, persist expiry deadlines, reject expired sessions on resume, and delete stale room data before showing it.
- **Clock differences can misclassify an event photo** → document the V1 assumption that device clocks are automatically set; do not claim clock-skew correction.
- **An offline device cannot receive an end or unshare event immediately** → keep session leases and reconcile on reconnect; its local cache expires after the agreed 10 minutes.
- **Originals can contain location or other metadata** → transfer full-size originals unchanged only after an explicit share and request; the app does not promise to remove embedded metadata.

## Migration Plan

There is no existing application data or service to migrate. Create the initial Android app and keep all profile and active-room data local. If the app is removed, Android removes its app-private temporary room data; original photos and recipient copies saved outside the app remain. A rollback removes the app's room cache and does not modify photo-library originals.

## Open Questions

- What preview dimensions and compression target give a useful preview while keeping transfers small? Choose after reviewing real-device samples; this does not change the preview-only behavior in the spec.
