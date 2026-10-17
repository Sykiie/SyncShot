# Proposal

## Why

Friends at the same gathering often take photos on separate phones, then struggle to collect and browse them without creating a permanent shared album or repeatedly uploading full-size files. This change defines an Android-first, offline room that lets a small group review and share event photos while originals remain on their owners' devices.

## What Changes

- Add a no-login app profile with a display name set on first launch.
- Let a host create a temporary room, invite people by QR code, approve join requests, stop or resume accepting requests, remove guests, and end the room.
- Connect up to eight participants, including the host, over a nearby peer-to-peer connection with the host relaying room data.
- Discover photo candidates locally on each phone for the room's active time window; let each owner review candidates and explicitly share or unshare them.
- Show shared previews in chronological order with the owner's display name. Let recipients request a full-size copy from its owner when that phone is reachable.
- Keep room data temporary and offline. Ending or expiring a room closes it and removes room-managed state and previews; downloaded full-size copies and original photos remain in each user's local storage.
- Keep a room active while the app is backgrounded with a visible notification. Expire a disconnected guest's room session after 10 minutes; expire the room after a host disconnection that lasts 10 minutes.

## Capabilities

### New Capabilities

- `event-room-lifecycle`: Local profile names, QR invitations, host-approved membership, participant limits, background operation, disconnection expiry, and room teardown.
- `room-photo-sharing`: Local event-window photo discovery, owner review and sharing controls, preview feed, full-size requests, and room-scoped cleanup.

### Modified Capabilities

None.

## Impact

- This workspace currently contains OpenSpec configuration but no app source, so implementation will establish the Android application and its initial architecture.
- Android UI and local profile, room, candidate, and preview state.
- Google Play services Nearby Connections for offline discovery and transport; Android camera, nearby-device, and media-library permissions as required by supported Android versions.
- Android background execution and notification behavior for an active room.
- No server, account system, internet-based room mode, or permanent shared-photo database is introduced.
