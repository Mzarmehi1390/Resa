# Resa — Technical Audit & Roadmap

**Audit type:** Static repository inspection  
**Audit date:** 2026-10-31  
**Upstream branch:** `main`  
**Audited upstream commit:** `1fc0e237f719514c7eb0920b503a4db103786479`  
**Target repository at start:** Empty; no branches or files existed.

> This is a preliminary engineering audit, not a security certification or a successful build report. Findings below are based on source files and Git metadata inspected so far. Runtime behavior, device compatibility, protocol interoperability, and build success still require execution and tests.

## 1. Repository state

### Upstream: `whisperbit-labs/zemzeme-android`

- Public Git repository, default branch `main`.
- Repository metadata reports Kotlin as its primary language.
- The uploaded source ZIP contains 1,844 archive entries including its root directory, matching the previously inspected upstream tree of 1,843 paths (including directories).
- Main Android source tree contains 248 Kotlin/Java source files.
- Android resources contain 1,392 files.
- One main Gradle application module: `:app`.
- A prebuilt `app/libs/golib.aar` is present (21,148,949 bytes).
- License file identifies the project as MIT; preserve copyright and license notices for upstream and inherited components.

### Target: `Mzarmehi1390/Resa`

- Was empty at audit start.
- Initial Resa README has now been added in commit `462e5506d1ab7de30e6a580c5c48ecc4e40f6010`.
- No upstream application source has yet been copied into this repository.

## 2. Confirmed technical baseline

| Area | Observed upstream configuration |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Android Gradle Plugin | 8.10.1 |
| Kotlin | 2.2.0 |
| Gradle wrapper | 8.13 |
| compileSdk | 35 |
| minSdk | 26 |
| targetSdk | 35 |
| Build JDK in upstream CI | 17 |
| Application namespace / ID | `com.roman.zemzeme` |
| Version | 1.0.2 (versionCode 3) |
| App module | `:app` |

The current application is not a multi-module Clean Architecture project. Its source is grouped largely by feature/responsibility packages under `com.roman.zemzeme`, with a large `MainActivity`, `ChatViewModel`, and Compose UI layer. Avoid introducing a large architecture migration before the existing dependency graph and lifecycle behavior are mapped.

## 3. Capabilities found in source

The following implementation areas are present in the source tree:

- BLE mesh: Bluetooth connection/GATT managers, peer management, packet processing, relay and store-forward components.
- Noise transport: Noise XX session classes, session manager, encryption service, key exchange and replay-window logic.
- Identity: static Noise keys, Ed25519 signing keys, peer fingerprints, QR verification and identity persistence.
- P2P: libp2p bridge through the bundled Go AAR, P2P transport, topic repositories and chunk assembly.
- Nostr: relay/client/subscription components, identity, event signing, direct-message handling and NIP-44-related crypto.
- Chat UI: home and chat screens, message components, image/file/audio UI, QR verification, settings sheets and connection indicators.
- Supporting features: geohash/location channels, notifications, favorites, onboarding, app lock, background mesh service and update handling.

Presence in source does not by itself prove a feature works end-to-end. Each transport and message type needs integration tests and device validation.

## 4. Priority findings

### P0 — Security behavior must be corrected or proven safe

1. **Legacy signature API is semantically unsafe and inconsistent, but the main BLE ingress has a separate mandatory Ed25519 check.**
   - `EncryptionService.sign(data)` returns an empty byte array; `EncryptionService.verify(signature, data, peerID)` returns only whether a Noise session exists.
   - `SecurityManager.verifySignature` delegates to that compatibility method, and `MessageHandler` can call it for a second check when a packet carries a signature.
   - However, the inspected `PacketProcessor` invokes `validatePacketSecurity` before routing packets, and `SecurityManager.validatePacket` independently requires Ed25519 verification for ANNOUNCE, MESSAGE and FILE_TRANSFER. This prevents describing the legacy check alone as a demonstrated bypass of the primary BLE ingress path.
   - The compatibility API remains a high-priority correctness hazard for alternate callers/future paths and should be removed or changed to fail closed. `SecurityManager.signPacket` currently delegates to the empty-signature method.
   - Required tests: forged signature, empty signature, altered canonical payload, unknown signing key, valid signature, and established-session-with-invalid-signature; also prove every receive route invokes the mandatory verifier.

2. **Cleartext traffic is globally permitted.**
   - `AndroidManifest.xml` sets `android:usesCleartextTraffic="true"`.
   - Audit all HTTP clients, endpoints, local-network exceptions and third-party SDKs. Remove the global allowance unless a documented, narrowly scoped requirement is proven.

3. **Cryptographic protocol documentation conflicts with implementation.**
   - README describes `Noise_NK`; `NoiseSession.kt` configures `Noise_XX_25519_ChaChaPoly_SHA256`.
   - Confirm protocol intent, peer compatibility and identity-authentication semantics before changing protocol strings or key formats.
   - Do not silently migrate keys or protocol versions.

4. **Secrets and diagnostic logging need a full pass.**
   - Audit every logger and debug export for message content, private keys, PIN material, tokens, file paths and sensitive peer metadata.
   - Ensure release builds do not expose verbose diagnostics and that sensitive byte arrays are cleared where practical.

### P1 — Product identity and architecture

1. App identity is still upstream: namespace/application ID `com.roman.zemzeme`, app label `Zemzeme`, old resource names and upstream package paths.
2. Manifest contains a large dynamic launcher-alias section (100 aliases). Review whether this feature is needed in Resa; remove only after mapping references and user-facing behavior.
3. MainActivity owns substantial startup, onboarding, lifecycle and screen-routing orchestration. Map responsibilities before extracting code.
4. `UnifiedMessageRouter.tryBleSend` currently returns a failure result with a comment that BLE sending is handled by the mesh service. Verify actual production call sites before claiming this router performs complete BLE fallback.
5. Nostr crypto is custom application code; require official Nostr/NIP test vectors and cross-client interoperability tests.

### P1 — Build, dependency and release engineering

1. `app/build.gradle.kts` reads release signing properties from local `local.properties`.
2. Existing upstream Android CI builds Debug and Release APKs, but the workflow does not inject signing secrets. A CI Release APK therefore must not be assumed to be signed.
3. Existing workflow runs unit tests and lint, then uploads APK artifacts. It does not currently build an AAB.
4. The upstream tree includes a 21 MB native Go AAR. Verify ABI coverage, native symbol packaging, 16 KB page-size compatibility where applicable, licensing and reproducibility.
5. Repository size and resource count are significant; review the 100 icon aliases and resource duplication before optimizing. Do not delete assets solely to reduce APK size.

### P2 — Test coverage

- The source tree contains 12 test Kotlin files including a test Log shim; 11 are named test suites.
- No `app/src/androidTest` files were found in the inspected tree.
- Existing tests cover selected crypto, mesh, packet relay, file transfer, command processing, view model, notification and mesh graph behavior.
- No broad Compose UI/instrumentation suite was found.
- Add missing transport, Nostr interoperability, onboarding/navigation, storage migration, permission, lifecycle and release regression tests.

## 5. Upstream CI observations

The checked-in `.github/workflows/android-build.yml`:
- Runs on pushes and pull requests to `main` and `develop`, plus manual dispatch.
- Uses JDK 17.
- Runs `testDebugUnitTest` and `lintDebug`.
- Builds `assembleDebug` and `assembleRelease`.
- Uploads test/lint reports and APK artifacts.

The most recent workflow-run records visible during this audit were CodeQL and scheduled relay-data runs; they do not establish a successful Android build for the audited commit. The audited commit's combined status endpoint reported `pending` with zero status entries at inspection time. No build or test is marked successful by this audit.

## 6. Implementation roadmap

### Phase 1 — Complete source audit
- Map startup, lifecycle, screen state, message routing and persistence.
- Trace every transport send/receive path and all protocol packet types.
- Trace key generation, persistence, QR verification and trust establishment.
- Inventory all permissions, exported components, deep links and network endpoints.
- Review file/media handling, background service and update flow.

### Phase 2 — Safe source integration
- Import the upstream baseline into Resa without modifying the upstream repository.
- Preserve upstream MIT notices and third-party licenses.
- Establish a clean working branch and record the exact source commit.
- Confirm a baseline Debug build and existing tests before branding or UI refactoring.

### Phase 3 — Resa identity and design system
- Define package/application ID migration policy before changing it.
- Apply app label, icon, theme, strings, deep links and notification identity consistently.
- Create reusable Compose tokens/components for light/dark themes and RTL.
- Keep transport, storage and cryptographic behavior unchanged during UI work.

### Phase 4 — UI redesign
- Redesign Home, conversations, message composer, media flows, profile, settings, group/channel management and verification UI.
- Preserve existing functionality and add loading/empty/error states.
- Validate small/large screens, font scaling, accessibility and Persian text.

### Phase 5 — Feature and reliability work
- Close gaps found in the transport and messaging audit.
- Implement or repair real behavior before adding UI controls.
- Add offline/reconnect, duplicate/replay, file-transfer and state-restoration tests.

### Phase 6 — Security and privacy
- Unify signature verification and remove insecure compatibility behavior.
- Scope network security policy and remove unnecessary permissions.
- Validate Noise/Nostr implementations against official vectors and independent peers.
- Review app lock, secure storage, identity recovery and logging.

### Phase 7 — Performance, CI and release
- Measure startup, scrolling, recomposition, memory, battery and native-library footprint.
- Add UI/instrumentation tests and CI artifacts.
- Add secret-backed release signing and AAB generation if Play distribution is selected.
- Produce a release checklist, privacy disclosures, license notices and reproducible build notes.

### Phase 8 — Final audit
- Verify all user-visible actions work.
- Run unit, UI, integration, regression and security tests.
- Verify Debug and signed Release builds.
- Confirm GitHub Actions and document any remaining limitations.

## 7. Immediate blockers and execution constraints

- The user has now supplied the complete `zemzeme-android-main.zip` archive (36.1 MB compressed; 51.8 MB uncompressed). It was safely extracted to the local working directory and its archive path entries match the inspected upstream tree count.
- A local baseline test attempt (`./gradlew :app:testDebugUnitTest --no-daemon`) stopped before Gradle configuration because the environment cannot resolve `services.gradle.org` (`UnknownHostException`). The Gradle 8.13 distribution is not cached. No Android SDK was confirmed in this container.
- The Resa repository is writable through GitHub actions, but no bulk archive-import operation is exposed. The full 51.8 MB source tree, including binary resources and the 21 MB Go AAR, has not yet been committed to Resa. Do not confuse local extraction with a GitHub source import.
- Do not represent the project as buildable or claim completed app development until a complete source import and baseline build are verified.



### Additional navigation observation
- `androidx.navigation:navigation-compose` is declared in the version catalog and app dependencies, but no `NavHost`, `NavController` or `rememberNavController` usage was found in the inspected application source. The inspected `MainActivity` instead switches between HomeScreen and ChatScreen through ViewModel state. Confirm whether the dependency is unused and map all back-stack/sheet behavior before introducing a new navigation graph.



### Additional source-archive findings (2026-10-31)

1. **Channel password verification is a test stub.** `ChannelManager.verifyChannelPassword` unconditionally returns `true`, despite the nearby PBKDF2/AES-GCM channel-key code. The same file's `sendEncryptedChannelMessage` is an immediate no-op marked “REIMPLEMENT – REMOVED FOR NOW”. `ChatViewModel` calls it when a channel key exists, but the callback that would send the encrypted payload and the fallback callback are never invoked; the message is added locally before this call. When no key exists, the caller sends through the ordinary mesh path. Treat password-protected channel messaging as non-functional and potentially plaintext-routed until the full flow is repaired and covered by tests. Do not market encrypted channels as working based only on the presence of crypto helpers.
2. **Noise handshake initiation has an explicit TODO.** `NoiseEncryptionService.encrypt` returns null and invokes `onHandshakeRequired` when no session exists; the log explicitly says handshake initiation is not implemented there. Trace all callback owners and transport paths to verify that a session can actually be established in every intended case.
3. **Manifest exposure inventory:** XML parsing found 2 activities (one exported), 100 exported activity aliases, 1 non-exported service, 3 non-exported receivers and 1 non-exported provider. All 100 launcher aliases expose the `zemzeme://verify` deep-link intent filter. Review whether every alias needs that filter and exported status, and ensure the receiving activity validates all incoming URI data.
4. **Cleartext setting:** `usesCleartextTraffic=true` is a global application setting. A simple source search found no hard-coded `http://` or `ws://` service endpoint in the main application Java/Kotlin source; that is not a complete proof that dependencies or dynamically supplied URLs never use cleartext. Inventory actual network stacks and runtime endpoints before replacing this with a restrictive Network Security Config.
5. **Debug message content:** `DebugLogger` truncates content to 50 characters and is gated by `BuildConfig.DEBUG`, so this specific logger does not emit in Release. Debug builds can still place message previews and peer identifiers in Logcat; test-device privacy and log collection policies should account for that.
6. **Build verification blocked before project evaluation:** The local Gradle wrapper attempted to fetch Gradle 8.13 and failed DNS resolution for `services.gradle.org`. This is an environment/network blocker, not a Gradle compilation result. No test suite or APK build has completed.


### Channel encryption path trace — additional findings (2026-10-31)

The channel-encryption audit was extended across ChannelManager, ChatViewModel, BluetoothMeshService, MessageHandler, NoiseEncryptionService, NoiseChannelEncryption and DataManager.

1. **The ordinary BLE broadcast path only transmits UTF-8 plaintext.** BluetoothMeshService.sendMessage constructs a MESSAGE packet with content.toByteArray(UTF_8) as its payload. It does not accept a channel identifier, encrypted bytes, or an encrypted-message flag. MessageHandler.handleBroadcastMessage decodes incoming broadcast payloads as file data or plain UTF-8 text; no channel decryption is invoked in that path. The delegate exposes decryptChannelMessage, but the inspected broadcast handler does not call it. Therefore, the existing receive path is not wired to consume channel ciphertext.
2. **The encrypted-channel helper is disconnected from the actual transport.** NoiseChannelEncryption can derive an AES-GCM key and encrypt/decrypt bytes, and NoiseEncryptionService exposes wrappers for those methods. However, no call sites were found for createChannelKeyPacket, processChannelKeyPacket, calculateKeyCommitment or verifyKeyCommitment. The helper's key-packet JSON includes the channel password itself; it must not be wired into a transport without a carefully specified authenticated, encrypted key-distribution protocol and validation.
3. **Password-protected state persists, but the key/password does not.** DataManager.saveChannelData persists joined-channel and password-protected-channel names. ChannelManager keeps the password and derived AES key only in in-memory maps. On a process restart, the protected-channel marker can be restored without a corresponding key. The current unconditional verifyChannelPassword stub then reports success without deriving or validating a key, and the inspected join flow does not establish a replacement key on successful verification.
4. **Local optimistic UI can report a message that was never transmitted.** ChatViewModel adds a channel message to local state before calling the no-op encrypted send method. Since neither callback runs, the encrypted-channel branch does not dispatch a packet. Delivery state and failure feedback need to be designed so a locally inserted message cannot be mistaken for a transmitted/delivered message.
5. **Password is retained as a plaintext String in process memory.** Both ChannelManager and NoiseChannelEncryption retain channel passwords in ordinary in-memory maps. No call sites were found for the Noise helper's password-sharing packet methods. Avoid persisting or logging raw passwords; decide whether password retention is needed at all and minimize lifetime when implementing the replacement.

**Conclusion:** This is not a single missing callback. The current channel encryption feature lacks an end-to-end protocol: key distribution/verification, message envelope and transport encoding, receive-side decryption, restart behavior and reliable send status are not connected. Do not repair it by merely calling encryptChannelMessage and continuing to use the existing plaintext sendMessage; that would still leak plaintext. Design a versioned channel-message envelope and authenticated key-distribution flow, then test two independent app instances end to end before declaring the feature operational.


### Private-message handshake/retry trace — additional finding (2026-10-31)

6. **The direct BLE send APIs do not queue first attempts when the Noise session is absent, but the normal text-chat route has a higher-level outbox.** BluetoothMeshService.sendPrivateMessage itself initiates a Noise handshake and exits without retaining the message. However, the normal ChatViewModel text-message flow routes through MessageRouter.sendPrivate, which queues messages when a BLE link exists but the Noise session is not established, then initiates the handshake and schedules a bounded fallback. This means the direct API behavior must not be generalized to the ordinary text-chat path. The private-file send path still initiates a handshake without a corresponding file outbox in the inspected implementation. MessageRouter's outbox is in-memory, so process death can still discard queued text messages. Reliability work should unify send status and queue semantics across text and files, define persistence/expiry policy, and test handshake success, timeout, peer disconnect and process death.

## 8. Current phase

**Phase 1 — Repository Audit (in progress; source archive now available locally).**

The complete upstream archive is extracted locally. The next gate is to complete source-level review and establish a reproducible source import into Resa, then run a baseline build in an environment with Gradle/Android SDK dependencies available. No destructive changes have been made to the upstream project.


### Source import and baseline build pipeline (2026-10-31)

- The audited upstream source commit `1fc0e237f719514c7eb0920b503a4db103786479` has now been imported into `feat/resa-foundation` as commit `add38df920d6459185558657bbba1f20d834ac5c`. GitHub tree verification reports 1,831 paths, including 248 Kotlin/Java source files, the Gradle wrapper and the Go AAR. Resa audit documents and repository README were retained; upstream MIT license text was preserved under `licenses/`.
- A GitHub Actions baseline pipeline has been added. Initial attempts exposed runner setup issues (the legacy `tools` SDK package is unavailable; the wrapper executable bit was not preserved by the upstream checkout). These were corrected in the workflow. The latest run is proceeding through Android SDK setup and baseline Gradle tests; until it completes, no successful build or test result is claimed.
- The upstream source is now present in the Resa development branch. App behavior has not yet been changed from the imported baseline; functional fixes and Resa UI work begin after the baseline build gate is resolved.

## 9. Verified Resa repository status — 2026-10-31 (supersedes earlier point-in-time notes)

The sections above preserve findings as they were recorded during the initial audit. The following is the current verified state and takes precedence wherever earlier notes describe the repository as empty, the source as not imported, or CI as not yet successful.

- The upstream application source is imported into `feat/resa-foundation`; the import is pinned to `1fc0e237f719514c7eb0920b503a4db103786479`.
- GitHub Actions run [41219038644](https://github.com/Mzarmehi1390/Resa/actions/runs/41219038644) completed successfully. Its unit-test, lint and Debug APK assembly steps all passed.
- The stale `BATTERY_OPTIMIZATION_CHECK` test enum reference was corrected to match the current `OnboardingState`; `APP_LOCK_SETUP` is now included in the test cases.
- The source-import workflow now checks for `UPSTREAM_BASELINE.md` before copying upstream files. After the initial import it preserves Resa source changes instead of overwriting them.
- A separate `.github/workflows/android-ci.yml` workflow now runs unit tests, lint and Debug APK assembly for app/build changes on `feat/resa-foundation`.

### Channel password verification — initial fail-closed correction

The unconditional `return true` test stub in `ChannelManager.verifyChannelPassword` has been removed.

- A new `ChannelKeyCommitment` helper creates SHA-256 commitments from derived key bytes and compares them with `MessageDigest.isEqual` (constant-time comparison for equal-length values).
- `ChannelManager.setChannelPassword` now stores a local commitment alongside the derived key.
- Password verification fails closed if there is no expected commitment, if the commitment is malformed, or if the candidate password derives a different key. On a match, the derived key is made available in memory for the current process.
- Unit tests cover matching keys, incorrect keys, missing/malformed commitments and uppercase hexadecimal commitments.

**Important limitation:** this is only a local fail-closed correction, not a completed multi-device channel security protocol. The commitment is currently held in memory and is not distributed through an authenticated peer protocol or persisted across process restarts. Consequently, a device that has no trusted commitment cannot join a protected channel through password verification; it will now be rejected rather than accepted without proof. Channel message encryption is still not connected end-to-end to the BLE/P2P transport or receive-side decryption. Do not describe protected-channel messaging as secure or operational until authenticated commitment distribution, versioned encrypted message envelopes, receive-side decryption, persistence/recovery policy and two-device integration tests are implemented.

### Current CI run for the channel correction

The dedicated Android CI run for the current channel-verification changes is queued/running at the time of this update: [41219606306](https://github.com/Mzarmehi1390/Resa/actions/runs/41219606306). The earlier successful baseline run does not substitute for this new run. Do not mark the new code as test-passing until this run completes successfully.


### Versioned channel envelope groundwork — 2026-10-31

- Added `ChannelMessageEnvelope` in the protocol package. Version 1 uses a strict binary frame with a fixed magic value, version byte, bounded channel/message identifiers, explicit ciphertext length and opaque ciphertext bytes.
- Decoder rejects unknown versions, invalid or oversized lengths, truncated frames, trailing bytes, invalid UTF-8 channel identifiers and malformed message identifiers. The payload must be large enough to contain a 12-byte AES-GCM nonce and 16-byte authentication tag.
- Added unit tests for round-trip encoding, malformed framing, unsupported versions, invalid identifiers and undersized ciphertext.
- **Scope limitation:** this is framing/validation only. It does not encrypt data, authenticate senders, distribute keys, prevent replay, persist messages or connect to BLE/P2P. No transport call sites have been changed. Do not treat the presence of this envelope as end-to-end channel encryption.
- The dedicated CI run triggered by these commits must pass before this change is considered verified.


### Authenticated channel-envelope cryptography — 2026-10-31

- Added `ChannelMessageCrypto`, an isolated AES-GCM helper for the versioned channel envelope.
- Encryption stores a fresh 12-byte GCM nonce followed by authenticated ciphertext and tag in the envelope's opaque ciphertext field.
- The channel identifier, message identifier, protocol version and a domain-separation label are supplied as GCM additional authenticated data (AAD). Changing either identifier or the ciphertext causes decryption to fail.
- Added unit tests for UTF-8 round-trip, wrong keys, altered channel/message identifiers, modified ciphertext and empty plaintext.
- **Security boundary:** this helper requires the caller to supply a trusted AES key. It does not establish peer identity, distribute or persist keys, provide replay protection, or connect to BLE/P2P. It is not yet used by the live channel send/receive path. Existing channel messaging must not be represented as end-to-end encrypted.
- CI for these commits is required before this isolated crypto layer is considered verified.

### Packet replay-cache poisoning correction — 2026-10-31

- SecurityManager.validatePacket previously inserted a packet ID into the replay/duplicate cache before verifying the packet signature. An invalid or unsigned packet with the same generated ID could therefore reserve that ID and cause a later correctly signed copy to be rejected.
- Signature verification now completes before the packet ID is recorded. The final duplicate check and cache insertion are synchronized so concurrent receive callbacks cannot both accept the same ordinary packet.
- The existing direct-neighbor ANNOUNCE duplicate exception is preserved; it is still subject to signature verification.
- Added regression tests proving that both unsigned and invalidly signed packets do not poison the cache and that a later valid signature for the same packet is accepted.
- This cache remains in-memory and time-bounded; it is not durable replay protection across process restarts. The change does not replace message-level idempotency or authenticated transport/session sequencing.

### Identity-announcement TLV parser hardening — 2026-10-31

- IdentityAnnouncement.encode now requires a non-empty nickname and exactly 32-byte Curve25519 and Ed25519 public keys.
- IdentityAnnouncement.decode now rejects incomplete TLV headers, truncated values, duplicate known fields, malformed UTF-8 nicknames, empty nicknames and public keys with incorrect lengths.
- Unknown TLV types remain skippable for forward compatibility. The parser still requires all three known identity fields.
- Added unit tests for valid round trips, invalid key lengths, empty nicknames, duplicate fields, truncated headers, malformed UTF-8 and unknown-field compatibility.
- This validates the structure of the announced identity data; it does not by itself prove that a peer ID is cryptographically bound to the Noise public key or provide out-of-band identity verification. That binding remains a separate item to verify against the transport's peer-ID derivation and rotation rules before changing behavior.

### Peer-ID to Noise-key binding — 2026-10-31

- Source inspection confirmed the local peer-ID derivation: the app uses the first 16 hexadecimal characters of SHA-256 over the 32-byte Noise static public key. RoutedPacket.peerID is parsed from the packet sender ID.
- MessageHandler.handleAnnounce now checks that the announced Noise static key derives the incoming packet peer ID before trusting the self-announced Ed25519 signing key or storing the peer identity.
- Added PeerIdentityBinding and unit tests for valid derivation, uppercase hex, mismatched IDs, malformed IDs and incorrect key lengths.
- This closes the specific first-announcement spoofing gap where an attacker could previously sign an announcement with their own signing key while claiming another sender ID. It does not provide out-of-band human identity verification or prevent a legitimate identity owner from changing their signing key without a separate key-continuity policy.

### Verified peer signing-key continuity — 2026-10-31

- PeerManager now pins both the Noise static public key and Ed25519 signing public key after a peer has been verified. An announcement that attempts to replace either key under the same peer ID is rejected without changing the stored nickname or key material.
- The check and update are synchronized so concurrent first announcements cannot race to overwrite the first accepted identity.
- MessageHandler confirms that the peer manager retained the exact keys from the accepted announcement before updating derived peer-ID/fingerprint state.
- Added regression tests for rejecting signing-key replacement, rejecting Noise-key replacement and allowing nickname refreshes when both keys remain unchanged.
- **Scope limitation:** this pinning currently lives in the in-memory PeerManager. It does not survive process restarts and is not a persistent trust store. There is no authenticated key-rotation/recovery protocol; legitimate key changes are intentionally rejected until such a protocol is designed. Out-of-band human identity verification is still not provided.
- The CI run for the final commit containing this change must pass before it is considered verified.

### Persistent verified-peer trust records — 2026-10-31

- Added a device-local trust database encrypted with AES-256-GCM using a non-exportable Android Keystore key. The encrypted snapshot is stored in app-private SharedPreferences; the peer public keys are not secret, but authenticated encryption prevents silent local record modification and protects stored metadata.
- The binary record codec is versioned and validates peer IDs, UTF-8 nicknames, exact 32-byte Noise/Ed25519 keys, record counts, duplicate IDs, truncation and trailing data.
- BluetoothMeshService now supplies the application context to PeerManager. Verified identities are persisted before being accepted into the in-memory peer map; a persistence failure or key mismatch rejects the update.
- On process restart, stored signing keys remain available to SecurityManager through PeerManager.getPeerInfo, including when the peer is not currently connected. Ordinary stale-peer removal does not erase trust records; explicit clear-all attempts to clear the persistent trust database.
- If encrypted data cannot be decrypted/authenticated (including a missing or invalidated Keystore key), the store enters an unavailable state and refuses trust updates rather than silently starting a new trust database.
- Added JVM tests for codec round trips, duplicate IDs, malformed IDs, invalid key lengths, truncation, trailing bytes and unsupported versions.
- **Limitations:** Android Keystore-backed storage is device-local and does not sync across devices or provide human/out-of-band identity verification. This change does not implement authenticated key rotation, trust export/recovery, hardware-backed-key attestation, or protection against a fully compromised/rooted running device. Android Keystore integration itself still requires device-level testing; JVM CI only validates the pure record codec.
- Trust state is monotonic for a pinned identity: a later update marked unverified cannot downgrade a peer whose identity is already pinned.
- When a legacy in-memory peer entry lacks verified keys, PeerManager prefers the authenticated persisted record for signature-verification lookups.
- The codec additionally bounds stored database size and rejects blank nicknames and unexpected GCM nonce sizes.
- Unverified legacy peer-cache entries cannot override the nickname or public keys from a persisted verified identity during signature-verification lookups.


### Private message handler signature enforcement — 2026-10-31

- The public SecurityManager.verifySignature API now uses the same strict Ed25519 packet-signature verification policy as the primary ingress validator; it no longer treats a missing signature as valid.
- MessageHandler.handlePrivateMessage now independently requires a present signature and a successful verification result before decoding or saving private MESSAGE/FILE_TRANSFER payloads.
- This is defense in depth: the normal PacketProcessor ingress already validates these packet types, but internal call paths must not weaken the requirement by treating a missing signature or absent delegate result as success.
- The change does not alter NOISE_ENCRYPTED transport packets, whose confidentiality/authentication is provided by the Noise session layer rather than this plain-packet handler.
- CI run `41225500295` passed unit tests, lint and debug APK assembly for the handler-level enforcement commit.

## Noise handshake peer-ID binding — 2026-10-31

A completed Noise XX handshake proves possession of the remote static Noise key, but the handshake completion callback previously recorded the key fingerprint without independently checking that the key matched the packet's claimed mesh peer ID. The callback now validates the protocol binding (peer ID = first 8 bytes of SHA-256 of the 32-byte Noise static public key) before storing the fingerprint or notifying authenticated-peer listeners. A mismatch causes the session to be removed and prevents it from being treated as authenticated.

This complements the signed ANNOUNCE binding check and the persistent verified-peer key pins. It does not provide out-of-band human identity verification, and the peer ID remains a truncated 64-bit identifier.

## Noise session lifecycle concurrency — 2026-10-31

The session manager previously used a separate map lookup, destroy, and removal sequence. A concurrent replacement could therefore be removed accidentally, and replacing a session through `addSession` did not destroy the previous session. Lifecycle mutations are now serialized at the manager boundary; replacement destroys the displaced session, and removal uses an atomic map removal before teardown. Handshake failure paths use the same teardown path.

Session teardown now coordinates with the transport cipher lock, preventing a concurrent encrypt/decrypt operation from using a `CipherState` while it is being destroyed. This is a local concurrency hardening change; it does not solve multi-device key synchronization or message delivery reliability.



## Noise handshake ingress and replay handling — 2026-10-31

- `SecurityManager` now requires a valid Ed25519 signature on `NOISE_HANDSHAKE` packets, using the signing key pinned for the already-announced peer. Previously this packet type bypassed the packet-signature allowlist, even though the sender signed outgoing handshake packets.
- Handshake replay tracking now hashes the complete payload with SHA-256 rather than a short prefix/content-hash value, avoiding easy hash collisions between different handshake payloads.
- `HandshakeReplayGuard` separates in-flight exchanges from completed exchanges. Concurrent duplicates are rejected; a failed Noise processing attempt releases its reservation so a legitimate retry is not permanently blocked; a successfully processed exchange is committed before response callbacks run.
- A duplicate exchange is rejected before any existing session is replaced. A fresh, signed handshake may still replace an established session to support re-handshake.
- `NoiseEncryptionService.processHandshakeMessage` now propagates protocol failures to its caller instead of converting them to `null`. `null` remains the valid result for a handshake step that requires no outgoing response. This distinction is necessary for retry-safe replay tracking.
- Added unit tests for in-flight duplicate rejection, retry after abort, completed-exchange rejection, bounded cleanup, reset behavior, and ingress rejection/acceptance for unsigned/signed Noise handshakes.
- **Verification status:** CI run `41229649447` passed unit tests, lint and debug APK assembly for the final handshake-ingress changes.
- **Scope limitation:** these changes protect handshake packet authenticity and local replay bookkeeping. They do not establish a human identity beyond the existing pinned signing key, nor do they replace the need for real two-device BLE/mesh handshake tests.


## Legacy Ed25519 API hardening — 2026-10-31

- Replaced the legacy `EncryptionService.sign` empty-byte placeholder with a real Ed25519 signature using the existing local signing identity.
- The legacy `EncryptionService.verify` API now fails closed. It cannot authenticate a peer because this service does not own the peer's pinned signing key; callers must use `SecurityManager.verifySignature` with the trusted peer identity.
- `SecurityManager.signPacket` now calls the explicit Ed25519 `signData` API instead of the legacy wrapper.
- Added regression tests proving the legacy signer produces a verifiable non-empty signature, modified data fails verification, and legacy peer verification does not accept a peer without a pinned key.
- **Verification:** CI run `41230492968` passed unit tests, lint and debug APK assembly.
- **Compatibility note:** the old `verify` behavior (treating an established Noise session as proof of an Ed25519 packet signature) was intentionally removed because those are different authentication mechanisms.

## Fragment reassembly resource and isolation hardening — 2026-10-31

- Fragment reassembly state is now scoped by sender ID, recipient ID and fragment ID. Identical 8-byte fragment IDs from different senders/recipients can no longer be mixed into one reconstructed packet.
- Added bounds for active incomplete sets (64), aggregate buffered fragment data (2 MiB), reconstructed packet size (512 KiB), fragment count (1,024) and individual fragment data (469 bytes).
- Incomplete sets are evicted by age when the active-set cap is reached, and aggregate memory accounting is updated on completion, timeout, rejection and explicit clear.
- Reassembly now rejects metadata changes within a set and conflicting duplicate fragment indexes; identical duplicate fragments are ignored.
- Fragment IDs are generated with `SecureRandom`; the wire format remains the existing 8-byte ID and 13-byte fragment header.
- Added regression coverage for sender isolation and rejection of excessive fragment counts, oversized fragment data and nested fragment types.
- **Verification status:** pending CI for the fragment hardening commits. Real interoperability with the iOS implementation and multi-device transfer testing remains required.

## P2P/Tor lifecycle and packet-ingress audit — 2026-11-01

This section records a follow-up inspection of the current `feat/resa-foundation` source. It supersedes any earlier assumption that the only Tor/P2P issue is a missing SOCKS proxy option.

### Confirmed P2P/Tor lifecycle findings

1. **Enabling P2P can overwrite the user's persisted Tor preference.** In `BluetoothMeshService.applyTransportSettingsLocked`, when the target P2P toggle is enabled and `TorPreferenceManager.get(context)` is `ON`, the code writes `OFF` to `TorPreferenceManager` before starting P2P. The P2P settings sheet also performs this change and displays a toast explaining that Tor was disabled. The service-level write is still an implicit side effect for non-UI callers and startup reconciliation, and the setting is not preserved as a separate user preference.
2. **Changing Tor to ON does not itself stop an already-running libp2p node.** `ArtiTorManager.applyMode(ON)` starts Arti and resets OkHttp/Nostr connections; `resetNetworkConnections()` does not stop or reconfigure libp2p. The mesh service does not currently collect `TorPreferenceManager.modeFlow` to reconcile P2P runtime state. Therefore the P2P-start guard in `applyTransportSettingsLocked` is not sufficient to prevent overlap when Tor is enabled independently while P2P is already running.
3. **The Go libp2p node creation API currently receives no Tor/SOCKS configuration.** Both profile-based creation and fallback creation call the bundled Go binding without a proxy argument. Do not claim libp2p traffic is routed through Tor.
4. **Topic restoration has a useful existing mechanism.** `P2PTopicsRepository` persists its own topic list and observes `P2PLibraryRepository.nodeStatus`; on transition to RUNNING it re-subscribes saved topics and restarts discovery/refresh. However, `P2PLibraryRepository.stopNode()` clears its in-memory subscribed-topic set. Any coordinated Tor/P2P stop-and-restart must rely on the topics repository's persisted list and verify duplicate refresh-job behavior, not the cleared repository set.
5. **Application initialization order matters.** `ZemzemeApplication.onCreate()` initializes `ArtiTorManager` before proactively starting `MeshForegroundService`. Tor preferences are loaded during Arti initialization, and the default saved mode is ON. A fix must handle both this startup order and later user-driven mode changes.
6. **The current P2P stop path cannot provide a fail-closed guarantee to its caller.** `P2PLibraryRepository.stopNode()` returns `Result<Unit>`, but `BluetoothMeshService.stopP2PTransportLocked()` wraps `p2pTransport.stop()` in `runCatching` and only logs failures; it returns no success/failure signal. A Tor coordinator cannot safely infer that P2P is stopped from completion of this service method.
7. **P2P can be started outside the mesh transport mutation lock.** In `P2PSettingsSheet`, when `MeshServiceHolder` has no attached service, the fallback calls `P2PTransport.start()` or `stop()` directly. Those calls bypass `BluetoothMeshService.transportMutationLock`; any future coordinator that only serializes mesh-service operations would leave this path outside its ordering guarantees.
8. **Tor mode application also hides failure from its caller.** `ArtiTorManager.applyMode()` catches exceptions internally and returns `Unit`; startup failures may be reflected in `statusFlow`, but the caller receives no operation result. A fail-closed transition needs an explicit result/state contract and must account for asynchronous Arti retries/bootstrap, not merely successful return from `applyMode()`.
9. **The P2P settings UI changes Tor before asking the service to enable P2P.** The UI writes `TorMode.OFF` and shows a toast before calling `setP2PEnabled(true)`; this creates a second orchestration path in addition to the service-level preference mutation. The UI and service should eventually delegate to one serialized policy entry point rather than each making transport decisions.

**Required design before changing runtime behavior:** make Tor/P2P mutual exclusion explicit and fail closed. Enabling Tor must coordinate a successful P2P stop before Arti begins routing; if P2P cannot be stopped, do not claim Tor protection is active. Enabling P2P while Tor is ON must leave the user's Tor preference unchanged and keep P2P stopped, with a clear runtime reason. When Tor is turned OFF, P2P may restart only if the user's P2P and auto-start preferences still permit it. Preserve saved topic subscriptions and test OFF→ON→OFF, P2P enable/disable, startup, shutdown, stop failure and rapid-toggle races. A real SOCKS integration should only be attempted if the bundled Go binding is rebuilt with a documented, tested proxy configuration; do not simulate it in Kotlin.

### Confirmed PacketProcessor findings

1. `PacketProcessor.actors` is a plain mutable map accessed by the non-suspending `processPacket()` entry point. If receive callbacks can invoke it concurrently, actor lookup/creation and debug/shutdown iteration are not thread-safe.
2. The per-peer actor uses `Channel.UNLIMITED`, so pending packets have no per-peer memory bound.
3. Actors are retained until global shutdown; processing a `LEAVE` packet does not evict the actor. The separate `peerActors` map of `CompletableDeferred<Unit>` is declared but unused.
4. When `actor.send()` fails, the current catch block processes the packet directly. This fallback can bypass per-peer serialization precisely when the actor is closing or cancelled.

**Required design before changing queue capacity:** trace receive callback threading, BLE write/notification acknowledgement semantics, packet retransmission, fragmentation and handshake ordering. A bounded queue must apply backpressure or an explicit protocol-safe overflow response; it must not silently drop handshake, encrypted-session or fragment packets. Actor eviction must preserve FIFO processing for packets already accepted and must never fall back to concurrent direct handling. Add stress tests for concurrent producers, bounded memory, LEAVE ordering, shutdown races and retry/overflow semantics.

### Verification boundary

These are static-source findings, not device-level reproduction. No runtime Tor/P2P isolation, libp2p proxy support, BLE backpressure behavior or multi-device interoperability has been demonstrated by the Android CI workflow. The workflow currently verifies unit tests, lint and Debug APK assembly only.

### BLE ingress and outbound queue semantics — 2026-11-01

The receive-path trace adds an important constraint to the PacketProcessor redesign:

1. `BluetoothGattServerManager.onCharacteristicWriteRequest` parses the packet and invokes the delegate synchronously, then responds with `BluetoothGatt.GATT_SUCCESS`. The delegate ultimately calls `PacketProcessor.processPacket()`, which only schedules a coroutine to enqueue the packet for processing. Therefore the GATT success response confirms receipt at the GATT callback, not successful validation or application processing. It cannot be treated as an application-level retry/ACK if a future bounded actor rejects a packet.
2. The GATT client notification callback also dispatches directly to the delegate without an application-level acknowledgement or backpressure contract.
3. `PacketProcessor.processPacket()` creates a new coroutine for each packet before calling `actor.send()`. Merely changing the actor channel from unlimited to bounded would not bound all pending work: producer coroutines can accumulate while suspended on a full channel.
4. `BluetoothPacketBroadcaster` has a second independent `Channel.UNLIMITED` actor. Its public broadcast entry point also launches one coroutine per request before calling `broadcasterActor.send()`; on send failure it falls back to direct broadcast processing, which can bypass serialization. This queue needs a separate outbound-flow audit and must not be conflated with the inbound PacketProcessor queue.
5. The client-side GATT sender deliberately uses `WRITE_TYPE_NO_RESPONSE` to allow multiple in-flight writes. Consequently the current transport does not provide a reliable per-packet application delivery acknowledgement through that write path.

**Implication:** no queue-capacity change should be made until the BLE protocol defines explicit overload behavior and reliable retransmission/acknowledgement above GATT. The inbound processor and outbound broadcaster need separate bounded-flow designs, with no coroutine-per-item unbounded staging and no direct-processing fallback that breaks ordering. Preserve fragment ordering and test notification/write pressure on actual Android devices.

### Tor/P2P exclusion implementation — 2026-11-01

A first runtime implementation was added on `feat/resa-foundation` after the static audit:

1. `P2PTransportPolicy` centralizes the current rule: P2P may run only when the user's P2P and auto-start preferences are enabled and the Tor preference is OFF. The policy does not rewrite either preference.
2. `P2PLibraryRepository.startNode()` checks the Tor preference while holding its node lifecycle mutex and rejects a new start while Tor is ON. This also protects the settings-sheet fallback that can call `P2PTransport.start()` without a mesh-service instance.
3. `BluetoothMeshService` observes the Tor preference together with Arti's runtime state. It stops P2P when Tor is requested and only resumes P2P after Tor reaches OFF, provided P2P and auto-start remain enabled. Its normal transport reconciliation no longer silently turns Tor OFF.
4. `ArtiTorManager.applyMode(ON)` explicitly stops the shared P2P repository and verifies the node reached STOPPED before starting Arti. If stop fails or the node is not STOPPED, Arti startup is refused and Tor status is set to ERROR.
5. The P2P settings sheet preserves the Tor preference and displays a localized explanation while P2P is paused by Tor (English and Persian resources).
6. A unit test covers the pure P2P transport eligibility policy.

**Important verification limits and follow-up:**

- The current implementation has unit coverage for the policy predicate, but not yet deterministic coroutine tests for the complete Tor/P2P lifecycle, stop-failure behavior, rapid toggles, or process startup/shutdown races.
- Android CI for the current code head must complete before treating the change as build-verified. A successful CI run still does not prove device-level Tor isolation.
- No SOCKS proxy configuration was added to the Go libp2p binding. P2P remains paused while Tor is enabled; this change does not route libp2p through Tor.
- Multi-device BLE/P2P interoperability and real Arti startup/stop failure scenarios remain untested on physical devices.
- The service and repository lifecycle mutexes serialize node operations, but a dedicated injectable transport coordinator with deterministic transition tests remains a desirable hardening step before release.

### Tor stop confirmation fail-closed guard — 2026-11-01

A follow-up hardening change closes a lifecycle gap found during review:

1. Switching Tor OFF no longer publishes runtime state OFF when Arti does not confirm its stopped transition before the configured timeout. The manager leaves the runtime state in ERROR and records that P2P remains paused.
2. Direct P2P node startup now requires both the persisted Tor preference and Arti's observed runtime state to be OFF. This protects callers that bypass the mesh-service reconciliation path.
3. Unit coverage now checks that P2P startup is rejected for Tor STARTING, BOOTSTRAPPING, RUNNING, STOPPING, and ERROR states, as well as when the saved preference is ON.

This is fail-closed state gating, not proof of device-level isolation. Runtime Arti stop-failure injection and physical-device verification remain outstanding.

### PacketProcessor ingress serialization hardening — 2026-11-01

1. Actor registry lookup/creation and shutdown are serialized under one lock, preventing concurrent GATT callbacks from creating multiple actors for one peer or racing actor creation against shutdown.
2. The synchronous receive entry point now uses trySend directly instead of launching one coroutine per packet. This removes unbounded staging of suspended producer coroutines while preserving per-peer FIFO enqueue order.
3. Handler exceptions are isolated per packet. A failed packet is logged and discarded; later packets continue through the same actor. The direct-processing fallback is removed because it could bypass per-peer serialization.
4. Per-peer channels remain unlimited intentionally. BLE currently lacks reliable application-level acceptance ACK and retry semantics, so bounded overflow could silently lose handshakes, encrypted packets or file fragments. This change does not claim bounded queue memory.
5. Unit tests cover concurrent actor creation for one peer and continued actor operation after a handler exception.

The independent outbound broadcaster queue is unchanged and still requires its own protocol-aware redesign.


### BLE outbound broadcaster audit — 2026-11-01

A focused review of `BluetoothPacketBroadcaster` and the GATT send callbacks confirms that the outbound path has additional serialization and delivery-accounting gaps. This is a static-source audit; no BLE device behavior is claimed.

1. **The actor is not the single serialization boundary.** `broadcastSinglePacket` submits work through the actor, but the public `sendPacketToPeer` and `sendToPeer` methods perform GATT notification/write operations directly. They can therefore overlap actor-driven broadcasts and mutate the same characteristic without sharing its ordering boundary.
2. **The actor queue and its producer staging are both unbounded.** The actor uses `Channel.UNLIMITED`, while each `broadcastSinglePacket` call launches a new coroutine before sending to the actor. A slow consumer can therefore accumulate queued requests and producer coroutines. Replacing only the channel capacity would move the unbounded staging into suspended producer coroutines.
3. **The actor failure fallback breaks ordering.** If actor submission throws, the public method calls `broadcastSinglePacketInternal` directly from the producer coroutine. That bypasses the actor and can overlap other broadcasts.
4. **GATT success values are transport-admission signals, not application delivery acknowledgements.** Server-side `notifyCharacteristicChanged` returns a Boolean for whether the local stack accepted the notification request. Client-side writes use `WRITE_TYPE_NO_RESPONSE`; the returned Boolean does not confirm remote packet processing. Neither path supplies a per-packet application ACK.
5. **Transfer progress can get ahead of transport work.** `broadcastSinglePacket` returns `Unit` after launching asynchronous actor submission. Its callers update fragment/single-packet progress immediately after that call, not after a confirmed send or peer acknowledgement. The displayed progress therefore cannot be treated as remote delivery progress.
6. **Shutdown has no explicit drain/rejection result for callers.** Closing/cancelling the actor scope can terminate pending submissions, but the current public API does not tell the originating caller which requests were admitted, dropped during shutdown, or actually attempted.

**Required design before implementation:** define separate request-admission, local-GATT-accepted, and remote-application-acknowledged states. Route every BLE outbound path through one serialized dispatcher per shared GATT characteristic/connection; remove direct-send bypasses and direct-processing fallback. Bound admission only after the caller can observe rejection and the protocol has a retry/ACK contract for messages, handshakes and ordered fragments. Tie transfer progress to the intended stage rather than to coroutine launch. Add deterministic tests for concurrent direct/broadcast submissions, per-connection ordering, fragment ordering, actor shutdown with pending work, duplicate transfer IDs, admission rejection/retry and callback-driven completion. Validate notification pacing and write pressure on physical Android devices before selecting queue limits.

### CI follow-up — PacketProcessor concurrency test — 2026-11-01

- Workflow run `41312406427` checked out `f402a0ddb2c7dac085034e7e5ec0dfe5339d7298`. The application sources compiled, but `compileDebugUnitTestKotlin` failed because the new test passed a hexadecimal peer-ID `String` to `ZemzemePacket.senderID`, which requires a `ByteArray`.
- The test helper now decodes each hexadecimal byte into a byte array in commit `b7a80dc25050a0d27f2c0353b929988067e30e67`.
- A new CI result for that correction is not yet available. Do not treat the test suite or Debug APK as verified until the new workflow completes.
