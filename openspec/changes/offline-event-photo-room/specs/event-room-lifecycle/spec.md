# Spec Delta

## Purpose

Provides temporary offline rooms for small gatherings, with a simple invitation flow, host-controlled membership, and predictable cleanup when the gathering ends or a device disconnects.

## ADDED Requirements

### Requirement: Local display profile
The app SHALL let a person set a display name on first launch and SHALL use that name to identify their device in rooms without requiring an account or login.

#### Scenario: First launch sets a profile
- **WHEN** a person opens the app without a saved profile
- **THEN** the app asks for a display name before they create or join a room

#### Scenario: Existing profile is reused
- **WHEN** the person opens the app again
- **THEN** the saved display name identifies them without another login step

### Requirement: Create and join by QR invitation
The app SHALL let a host create a temporary room and invite nearby people by scanning its QR code; scanning SHALL initiate the in-app connection and join request without requiring the guest to type a code or select a device manually.

#### Scenario: Host creates an inviting room
- **WHEN** the host creates a room
- **THEN** the room starts its active time window and displays a QR invitation while it is accepting requests

#### Scenario: Guest scans the invitation
- **WHEN** a guest scans the room QR code while the room is active
- **THEN** the app attempts the nearby connection and submits a join request under the guest's saved display name

#### Scenario: Room is not accepting requests
- **WHEN** a guest scans the QR code while the host has paused new requests
- **THEN** the guest is told that the room is not accepting requests and is not added

### Requirement: Host-controlled admission and capacity
The app SHALL require host approval before a guest becomes a room member, let the host pause or resume new requests and remove a guest, and limit a room to eight members including the host.

#### Scenario: Host approves a request
- **WHEN** the host approves a pending join request and a seat is available
- **THEN** the guest becomes a member and can view room content

#### Scenario: Host rejects a request
- **WHEN** the host rejects a pending join request
- **THEN** the guest is not added to the room

#### Scenario: Room reaches its limit
- **WHEN** eight people including the host are already members
- **THEN** another request cannot be approved and the guest is told that the room is full

#### Scenario: Host removes a guest
- **WHEN** the host removes a member
- **THEN** that member loses access to live room content and their room session is closed

#### Scenario: Guest leaves
- **WHEN** a guest chooses to leave
- **THEN** their room session closes and the remaining room continues

### Requirement: Offline room transport
Room creation, joining, membership changes, and photo-room communication SHALL work without internet access between supported Android devices in nearby range.

#### Scenario: Internet is unavailable
- **WHEN** supported devices have no internet connection but nearby connectivity and required permissions are available
- **THEN** they can create, join, and use the room

#### Scenario: Nearby capability is unavailable
- **WHEN** the device lacks a required nearby capability or permission
- **THEN** the app explains that it cannot connect and does not imply that the room is online

### Requirement: Background room continuity
An active room SHALL continue while its app is backgrounded and SHALL show a visible ongoing notification that identifies the active room.

#### Scenario: App is backgrounded
- **WHEN** a member backgrounds the app while the nearby connection remains active
- **THEN** the room stays active and its ongoing notification remains visible

### Requirement: Guest disconnection expiry
When a guest loses the room connection, the app SHALL start a 10-minute session expiry timer; a reconnection before expiry resumes that guest's room session, while expiry closes that guest's session and clears its room-managed local state without ending the room for others.

#### Scenario: Guest reconnects before expiry
- **WHEN** a disconnected guest reconnects within 10 minutes
- **THEN** the guest resumes the existing room session and receives current room state

#### Scenario: Guest remains disconnected
- **WHEN** a guest stays disconnected for 10 minutes
- **THEN** that guest's room session expires and its room-managed local state is cleared while the host and other members continue

#### Scenario: Expired guest requests to return
- **WHEN** an expired guest scans the still-active room invitation
- **THEN** they submit a new join request and require host approval again

### Requirement: Host disconnection expiry
When the host loses the nearby connection, the room SHALL enter a paused state and start a 10-minute expiry timer; reconnection before expiry resumes the room, while expiry closes the room and removes room-managed state.

#### Scenario: Host reconnects before expiry
- **WHEN** the host reconnects within 10 minutes
- **THEN** the room resumes with its existing members and state

#### Scenario: Host remains disconnected
- **WHEN** the host stays disconnected for 10 minutes
- **THEN** connected guests treat the room as expired and clear its room-managed local state

### Requirement: End and delete a room
The host SHALL be able to end an active room; ending or expiring a room SHALL close membership and delete room-managed state, candidate records, and cached previews on reachable member devices. A disconnected device SHALL clear its room data when it reconnects to the ended room or when its 10-minute session expiry is reached. Original photos and full-size copies already saved by recipients SHALL remain on their respective devices.

#### Scenario: Host ends a room
- **WHEN** the host ends the room
- **THEN** reachable members are told that it ended, the room closes, and room-managed data is cleared

#### Scenario: Disconnected device learns the room ended
- **WHEN** a disconnected member reconnects after the room ended
- **THEN** the member clears the ended room and its cached previews

#### Scenario: Recipient already saved a full-size copy
- **WHEN** the room ends after a recipient saved a full-size photo locally
- **THEN** the saved copy remains on the recipient's device while the room preview and room-managed state are removed
