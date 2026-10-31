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
