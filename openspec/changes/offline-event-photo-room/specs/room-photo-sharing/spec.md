# Spec Delta

## Purpose

Lets each room member privately review photos captured during the gathering, explicitly share previews with the room, and request full-size copies while the original stays on its owner's phone.

## ADDED Requirements

### Requirement: Explain and scope photo access
Before enabling automatic photo discovery, the app SHALL explain that media access is used to find photos taken during the active room and SHALL process candidate discovery and review locally on that device. If access is denied or limited, the app SHALL offer manual selection through Android's system photo picker.

#### Scenario: Member enables discovery
- **WHEN** a member turns on event photo discovery
- **THEN** the app explains the purpose of media access before requesting the needed permission

#### Scenario: Access is denied or limited
- **WHEN** the member denies broad access or grants only limited access
- **THEN** the app offers manual photo selection and does not claim that automatic discovery is complete

#### Scenario: Candidate is reviewed
- **WHEN** the app discovers or reviews a candidate photo
- **THEN** its candidate record and review state remain on the owner's device until the owner explicitly shares it

### Requirement: Limit candidates to the room time window
Automatic discovery SHALL include only photos whose recorded capture time is at or after room creation and before room end. Photos with missing or unusable capture times SHALL be excluded from automatic discovery.

#### Scenario: Photo is captured during the room
- **WHEN** a photo's recorded capture time falls within the active room window
- **THEN** it appears in the owner's local candidate list

#### Scenario: Photo predates the room
- **WHEN** a photo's recorded capture time is earlier than room creation
- **THEN** it is not included as an event candidate, including when chosen manually

#### Scenario: Capture time is unavailable
- **WHEN** a photo has no usable capture time
- **THEN** automatic discovery excludes it and manual selection explains that it cannot be confirmed as taken during the room

### Requirement: Owner reviews and explicitly shares candidates
The app SHALL show the owner all eligible candidates in a room-local review list and SHALL share a candidate with the room only after the owner explicitly turns on its share control. The review list SHALL show which candidates are currently shared.

#### Scenario: Candidate remains private during review
- **WHEN** the owner opens or selects a candidate without enabling its share control
- **THEN** other room members cannot see it

#### Scenario: Owner shares a candidate
- **WHEN** the owner enables the candidate's share control
- **THEN** the app creates a room preview and marks that candidate as shared in the owner's review list

#### Scenario: Owner returns to the review list
- **WHEN** a candidate has been shared
- **THEN** the owner can identify its shared state without opening the photo

### Requirement: Show a chronological shared preview feed
The room SHALL display only previews explicitly shared by their owners, ordered by recorded capture time, and SHALL identify each photo's owner by their room display name.

#### Scenario: Member browses shared previews
- **WHEN** a member opens the room's shared-photo feed
- **THEN** shared previews appear in chronological order with each owner's display name

#### Scenario: Candidate has not been shared
- **WHEN** a candidate is still private on its owner's device
- **THEN** it does not appear in another member's feed

### Requirement: Keep originals on the owner device
Sharing a photo SHALL send a room preview while keeping the full-size original on the owner's device. The app SHALL not require another owner approval when a room member requests the full-size photo, and SHALL tell the requester when the owner device cannot provide it.

#### Scenario: Recipient requests a reachable original
- **WHEN** a member requests the full-size version of a currently shared photo and its owner is reachable
- **THEN** the app transfers the original through the room connection and lets the recipient save a local copy without another approval prompt

#### Scenario: Owner device is unreachable
- **WHEN** a member requests the full-size version but the owner device is disconnected or unavailable
- **THEN** the app keeps the preview visible and tells the requester that the full-size photo is unavailable

#### Scenario: Recipient saves a copy
- **WHEN** a full-size transfer completes
- **THEN** the recipient's local copy remains available on that device even if the room later ends

### Requirement: Unshare removes the room preview
The owner SHALL be able to turn off sharing for a shared candidate; this SHALL remove its preview from the room for connected members and SHALL be reconciled for disconnected members when they reconnect or their room session expires. Copies already saved by recipients SHALL not be recalled.

#### Scenario: Connected members receive an unshare
- **WHEN** the owner turns off sharing while other members are connected
- **THEN** the preview is removed from the room feed and the owner's candidate is marked unshared

#### Scenario: Disconnected member reconnects
- **WHEN** a member reconnects after a photo was unshared
- **THEN** the member's room feed no longer contains that preview

#### Scenario: Recipient already downloaded the photo
- **WHEN** a recipient saved a full-size copy before the owner unshared the photo
- **THEN** the saved local copy remains on the recipient's device

### Requirement: Clear room photo data at teardown
When a room ends, expires, or the member's room session expires, the app SHALL clear that session's candidate records, share markers, room previews, and room-managed photo caches without deleting the owner's originals or recipient-saved copies.

#### Scenario: Room photo state is cleared
- **WHEN** a room or member session is deleted
- **THEN** its room-managed candidate and preview data are removed from that device

#### Scenario: Original photo remains intact
- **WHEN** room-managed photo data is cleared
- **THEN** the original remains in the owner's photo library
