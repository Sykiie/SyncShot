# Tasks

## 1. Android foundation and permissions

- [ ] 1.1 Create the native Kotlin Android app with Android 10 (API 29) as the minimum version, local first-run profile setup, and room navigation; verify a clean install asks for a display name and a relaunch reuses it without login.
- [ ] 1.2 Add camera, nearby-device, and event-photo permission flows with the photo-access rationale and system-picker fallback; verify full, limited, and denied access paths on API 29 and a current Android version, and document setup requirements for Google Play services and permissions.

## 2. Offline room connection and lifecycle

- [ ] 2.1 Implement QR-scoped Nearby Connections using the host-centered star topology; verify two Android 10+ devices can scan, connect, and exchange room messages with internet disabled and no manual device selection.
- [ ] 2.2 Implement host approval, pause/resume of new requests, the eight-person limit, guest removal, and guest leave; verify approval and rejection paths, capacity boundaries, and access removal with focused logic tests and device walkthroughs.
- [ ] 2.3 Keep an active room running in the background with an ongoing notification and implement 10-minute guest and host disconnect leases; verify background continuity, reconnection before expiry, and cleanup after expiry, including after process restart.

## 3. Local event-photo discovery and sharing

- [ ] 3.1 Discover candidates locally from room creation through room end and validate capture-time boundaries, including missing timestamps and picker selections; verify older photos never enter the candidate list and candidate metadata stays on the owner's device.
- [ ] 3.2 Build the owner review list and explicit share toggle, generate a bounded preview on share, and show the room feed by capture time with owner names; verify private candidates stay private, shared markers are visible, and feed ordering is stable.
- [ ] 3.3 Relay each preview through the host, cache it for later viewing, and propagate unshare/removal state; verify reopening the feed does not retransmit previews and connected or rejoining guests no longer see an unshared item.

## 4. Full-size requests, teardown, and integration

- [ ] 4.1 Implement full-size requests routed through the host to the owner and back to the recipient, with bounded transient relay storage and a recipient-local saved copy; verify no second owner approval is requested, the original remains unchanged, and an unreachable owner produces an unavailable message.
- [ ] 4.2 Implement room-end, room-expiry, guest-expiry, and removal cleanup for room manifests, candidate records, previews, and temporary transfer data; verify originals and recipient-saved copies remain while room-managed data is removed locally and reconciled after reconnect.
- [ ] 4.3 Verify the complete offline flow on API 29 and current Android devices, including a maximum eight-person room and host interruption; document supported device requirements, permission limitations, and any verified Nearby transfer limits.
