# JianJi LAN sync v1 (app 2.0.0)

This file is the implementation contract shared by the Android and Electron clients. LAN only; desktop is the TLS server, Android the client. No cloud service and no Bluetooth in this version. Existing manuscripts must never be discarded during migration or conflict resolution.

## Records

Envelope: `{protocol:1, deviceId:string, records:Record[]}`. Each record has exactly `{kind,id,clock,deleted,data}`. `kind` is `folder`, `book`, or `note`; stable IDs are UUID strings or `conflict-` plus 64 lowercase hex characters. Never use local SQLite integer IDs across devices. `clock` is an object mapping stable device IDs to nonnegative safe integer counters. `deleted` is a hard deletion tombstone. Keep tombstones in the sync store even after local rows are deleted.

Folder data: `{name:string,color:string}`.

Book data: `{name:string,folderId:string}` (global folder ID).

Note data: `{body:string,notebookId:string|null,createdAt:number,updatedAt:number,trashedAt:number,favorite:boolean,pinned:boolean,images:string[],color:string,sticker:string,conflictOf:string}`. Times are epoch milliseconds, `trashedAt:0` means active. Images are SHA-256 hashes of original file bytes, not paths. `conflictOf` is empty for ordinary notes; a conflict copy points at the original global ID. Do not add a conflict heading to body text. Folder/book local name collisions may use a deterministic visible ` (short-id)` suffix without altering the wire record; remember the projected local snapshot so this does not create repeated edits.

The local sync metadata keeps wire records AND the last observed local projection. At capture/save, compare current local projection with its previous projection. Only a genuine local change increments the local device component of that record's clock. Missing previously tracked local rows produce tombstones. Import writes the merged record and its new observed local projection together, so import itself is not a new edit. Deleted parents never resurrect; live orphan notes appear in unfiled. Do not synchronize font size, selection, UI layout, or pairing credentials.

## Canonical serialization and merge

Canonical JSON sorts object keys by UTF-16 lexicographic order, preserves array order, and has no whitespace. Integers are decimal safe integers. Quote `"` and `\\` with a backslash. Encode EVERY character below U+0020 or above U+007E as lowercase `\\uXXXX` per UTF-16 code unit (including surrogate pairs). Leave other printable ASCII unchanged. Use canonical JSON UTF-8 for SHA-256 fingerprints.

Clock A dominates B when every counter in A is >= B and at least one is greater; absent counters are zero. A dominating record replaces B, an equal identical record is unchanged. Concurrent records (or equal clocks with different payloads) join their clocks by componentwise max. Select a deterministic winner: a hard tombstone wins over live; for notes, a trashed version wins over active; otherwise choose the lexicographically larger SHA-256 fingerprint of the ORIGINAL full record. The winning primary gets the joined clock.

For every concurrent NOTE pair with different data where the losing record is live, retain the loser as a visible conflict copy. Copy ID is `conflict-` + SHA256(originalId + `\n` + losingRecordFingerprint), UTF-8. Copy keeps the loser data except `conflictOf=originalId` and `trashedAt=0`; its clock is the joined clock, `deleted=false`. If an identical copy already exists, merge it normally; never duplicate it on retry. Do not emit copies for identical data and different clocks; just join. Folder/book conflicts choose the deterministic winner; keep pre-merge versions in local backups. Validate entire incoming envelopes before any destructive materialization.

## TLS pairing

Desktop generates an installation-specific self-signed certificate and key. QR payload is `jianji://pair?data=` followed by base64url UTF-8 JSON `{v:1,host,port,fingerprint,token,deviceId,name}`. Fingerprint is lowercase hex SHA-256 of the certificate DER. Token is at least 256 random bits, expires after 5 minutes and is one-use for a pairing request. Host must be an IPv4 private/link-local address (10/8,172.16/12,192.168/16,169.254/16); port 1..65535. Desktop UI lets the user choose a local network interface. Android pins the exact certificate before sending the token; never install a trust-all verifier. No cleartext transport or remote URL redirects.

`POST /v1/pair/request` JSON `{token,deviceId,name}` -> `{requestId,status:"pending"}`. Desktop shows the phone name and Approve/Reject. `GET /v1/pair/status?requestId=...` with `Authorization: Pair <token>` -> pending, rejected, or `{status:"approved",peerId,accessToken,deviceId,name}`. Access token is a new independent random 256-bit credential. Pending/approved status expires after 5 minutes. `POST /v1/pair/finish` with Bearer token consumes the request. Pairing must not automatically transfer notes. Persist credentials using OS secure storage where available; never log token/QR bodies or note content.

All remaining routes require `Authorization: Bearer <accessToken>` and are bound to the authenticated phone's device ID. Unknown or revoked credentials get 401. Local desktop IPC is separately allowlisted; no unauthenticated localhost admin HTTP endpoints, no wildcard CORS, no arbitrary file access.

## Sync and assets

1. Android captures its current manifest and local signature. It requests `POST /v1/blobs/check` with `{hashes:[sha256...]}` -> `{missing:[sha256...]}`. Only hashes referenced by live outgoing records need uploading.
2. Upload missing assets using `PUT /v1/blobs/<sha256>` (raw bytes). Maximum 30 MiB each. Server validates hash and byte limit, writes a temporary file, then atomically renames. GET on the same path downloads immutable bytes. Invalid hash syntax never reaches a filesystem path. Store blobs outside the UI/public directory.
3. `POST /v1/sync` JSON envelope. Desktop asks the renderer to flush and briefly lock edits, merges into its latest records, verifies every live note image exists, atomically persists a pre-sync backup and merged state, then returns `{protocol:1,deviceId,records,summary:{received,conflicts},syncId}`. Local edits and incoming sync are serialized. Renderer unlocks on success AND failure; time out a nonresponsive renderer instead of overwriting a draft.
4. Android downloads missing images from returned live records and verifies hashes before importing. It rechecks its local signature inside the import transaction; changed local data aborts rather than being overwritten. Files can stage before the transaction; no note points at an incomplete file. Apply folders, books, then notes, with tombstones after reference resolution. Retain pre-sync snapshots and immutable assets for recovery.
5. `POST /v1/sync/ack` JSON `{syncId}` records last successful sync. A failed/interrupted attempt can safely repeat the protocol; do not acknowledge success until local import has committed.

`GET /v1/status` -> `{protocol:1,deviceId,name,lastSyncAt,syncRequested}`; `syncRequested` is an integer generation. Desktop's Sync button increments it for a chosen phone. While Android is foreground and paired it polls status (about 3 seconds); a new generation initiates the same bidirectional flow, with draft flush and a blocking progress dialog. The phone's own Sync button starts immediately. No background synchronization promise. Desktop explains that the phone app must be open. Acknowledge the generation on `/v1/sync/ack` as `{syncId,syncRequested}`. Failed attempts must not spin/retry continuously.

`DELETE /v1/peers/self` revokes current pairing. Desktop can revoke a peer through local IPC. Pair again after computer IP changes; do not silently trust a different certificate.

JSON body limit 32 MiB; bounded record/hash counts; network timeouts and cancellation; all validation failures leave existing data untouched. Synchronization failure must not be reported as success. Never send device-local paths in records.

## Shared implementation interfaces

Android `SyncStore(Context, NoteStore)` exposes:

* `String deviceId()`
* `Snapshot capture()` where Snapshot has `JSONObject manifest`, `String signature`, and `Map<String,File> blobs`.
* `File blobFile(String hash)` -> available immutable blob or null.
* `boolean hasBlob(String hash)`.
* `void receiveBlob(String hash, InputStream stream)` -> bounded/hash-checked atomic staging/cache write.
* `JSONObject apply(JSONArray records, String expectedSignature)` -> summary, atomic and refuses changed local state.

Android connection/UI implementation owns `SyncActivity`, QR scanner, encrypted peer preferences and pinned TLS networking. `SyncActivity` is launched from MainActivity's “电脑同步” menu. It exposes static `onForeground(Activity, NoteStore, SyncHost)` / `onBackground(Activity)` for the active MainActivity, where `SyncHost` defines `boolean prepareSync()` (flush editor, false if image import in progress), `void syncFinished()` (refresh/reopen view as needed). Desktop-initiated requests run only with an active host. MainActivity integration is owned by root; coordinate exact signatures before changing them.

Desktop bridge `window.jianjiDesktop` (contextIsolation; no Node in renderer):

* `loadState()` synchronous -> `{state:{notes,folders,books,font,expanded},error?:string}`.
* `saveState(state)` synchronous -> `{ok:true}` or `{ok:false,error}`; writes local file data and updates record clocks. UI IDs are wire global IDs, dates are ISO strings, images are hashes; optional `conflictOf` preserved. Detect missing IDs as hard tombstones. Font/expanded stay device-local.
* `putImage(arrayBuffer)` async -> hash; `getImage(hash)` async -> arrayBuffer|null; `removeImage(hash)` async (only when not referenced by records/backups).
* `syncInfo()` async -> `{deviceId,name,addresses:[{name,address}],port,peers:[{id,name,lastSyncAt}],pending:[{id,name}],status}` (no secrets).
* `startPairing(host)` async -> `{qrDataUrl,payload,expiresAt}`; `approvePair(requestId,approve)`; `requestSync(peerId)`; `revokePeer(peerId)`.
* `onSyncInfo(callback)` returns unsubscribe.
* `onPrepareSync(callback)` returns unsubscribe; callback returns boolean (or Promise<boolean>) after flushing and locking UI. Preload answers main's request automatically.
* `onSyncResult(callback)` returns unsubscribe; event `{ok,state?,summary?,error?}`. Renderer applies state and releases its lock even when `ok:false`.
* `openDataFolder()` reveals local data folder; no arbitrary path opening.

Backend uses Electron userData directory, durable atomic JSON + immutable image files, sync backups, and secure peer credentials. Desktop UI starts empty (no demo manuscripts); reuse existing browser preview storage only in browser mode. Build macOS arm64 and Windows x64 runnable distributions. App version 2.0.0, author @yue. No test execution without the user's explicit request; compile/package the requested artifacts and report unverified cross-device behavior honestly.
