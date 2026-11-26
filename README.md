# Fortress Reader — dual-level secure storage

[![Build APK](https://github.com/mister-X165/Fortress-Reader/actions/workflows/build-apk.yml/badge.svg)](https://github.com/mister-X165/Fortress-Reader/actions/workflows/build-apk.yml)

Offline Android reader and encrypted vault with **two security levels**,
styled with the **Swiss Industrial** design system (Inter + Roboto Mono,
monochrome palette, restrained red accent).

## Level 1 — Library (ordinary reader)

- Scans the device (MediaStore) for PDF / DOCX / FB2 files — similar to ReadEra.
- Opens files without importing them into the vault (temporary working copy, wiped on close).
- App-level lock: biometrics or device credential (`BIOMETRIC_WEAK | DEVICE_CREDENTIAL`).
- `FLAG_SECURE`, no screenshots, lock on background, working files zero-overwritten.
- On Android 13+ MediaStore exposes almost no third-party documents; use “Open file…” (SAF).

## Level 2 — Secure Vault

- Separate encrypted storage for **documents**, **photos** and **notes**.
- Own PIN (minimum **6 characters**; alphanumeric passphrases allowed) required after the app unlock.
- **PIN throttling**: 30 s after the 3rd failed entry, doubling per failure, capped at 24 h.
  The backoff is checked before any PBKDF2 work.
- **Duress (coercion) PIN**: when entered, every document, photo and note in the vault is
  crypto-erased (DB rows + ciphertext files zero-wiped). The UI then shows an empty vault —
  indistinguishable from a real unlock of an empty vault.
- Per-item AES-256-GCM encryption; DEKs wrapped by a non-exportable Android Keystore key.
- Names/titles are also Keystore-wrapped so the on-disk DB does not leak metadata.
- Photos are verified by magic bytes (JPEG / PNG / WEBP / HEIF / GIF), not by picker MIME.

### Vault setup

1. Unlock the app (biometric / device lock).
2. Open the **VAULT** tab.
3. On first use set a **Vault PIN** and a different **Duress PIN** (min. 6 characters each).
4. Later: **PINS** inside the vault to change both.

There is **no recovery** for a forgotten Vault PIN. The duress PIN exists only to destroy data under coercion.

## Security Dashboard

The SECURITY tab shows only **verifiable, live** device states, each with an explanation:

| Check | Source |
|-------|--------|
| KEY PROTECTION | AndroidKeyStore `KeyInfo` (TEE / StrongBox vs software) |
| VAULT ENCRYPTION | Per-item AES-256-GCM by construction |
| BACKUP EXCLUSION | `ApplicationInfo.FLAG_ALLOW_BACKUP` |
| NETWORK ACCESS | Runtime `INTERNET` permission check |
| SCREEN PROTECTION | `FLAG_SECURE` on the current window |
| SESSION LOCK | Current session state |

States: `PROTECTED`, `LIMITED`, `NOT VERIFIED`, `NON-COMPLIANT`. Nothing is fabricated: until the
master key is created, key protection reads `NOT VERIFIED`.

## Import flow

Every picked file passes through an **Import Document** screen that shows name, format, size and
the selected storage mode before anything happens:

- **IMPORT TO VAULT** — stream-encrypt on arrival (progress shown, no plaintext copy written).
- **TEMPORARY VIEW** — short-lived working copy in `noBackupFilesDir`, zero-wiped on close.

## Design system

Swiss International Typographic Style: modular grid (4/8/12/16/24/32 dp), rectangular components
(radius ≤ 4 dp), editorial typography (Inter, bundled OFL) and Roboto Mono for metadata/states,
light + dark themes following the system. Security state is never communicated by color alone —
every status carries a glyph and a text label. The design adds no network dependency; fonts ship in `res/font`.

## Architecture

| Module | Responsibility |
|--------|----------------|
| `:security` | AES-256-GCM streaming cipher, Keystore wrapper, PBKDF2 PIN verifiers, secure temp files |
| `:document` | Format sniffing + PDF / DOCX / FB2 renderers |
| `:vault` | Room DB (documents, photos, notes, safe_auth), repositories, library scanner, duress wipe |
| `:app` | Hilt, ViewModel, biometric lock, Compose UI (design system + Library / Vault / Security) |

## Security measures

- No `INTERNET` permission (stripped from merged manifest)
- Backups / device-transfer disabled
- `FLAG_SECURE` on the window
- Lock on `onStop` (except while the system file picker is open)
- Working files in `noBackupFilesDir`, zero-overwritten then deleted on close / lock / process start
- GCM + last-chunk AAD → detects truncation / reordering
- Size caps (100 MB docs, 50 MB photos)
- DOCX zip-bomb limits
- Generic error messages (no paths / names in logs)
- PIN comparison uses constant-time `MessageDigest.isEqual`
- Explicit Room migrations only — no destructive fallback; schema JSONs exported to `vault/schemas`

## Build

### Download a ready APK (no build needed)

Every push to `main` is built by GitHub Actions. The finished APKs are published to
[Releases](https://github.com/mister-X165/Fortress-Reader/releases) (and are also available
as artifacts under the **Actions** tab for every run). Open the latest release in the phone
browser and download `FortressReader-debug.apk` — it installs directly on Android 10+.

### Build from source (fresh clone)

Binary assets are not committed to keep the repository text-only; fetch them once:

```bash
bash scripts/fetch-fonts.sh      # Inter + Roboto Mono into res/font (SHA-256 verified)
bash scripts/restore-wrapper.sh  # gradle/wrapper/gradle-wrapper.jar for ./gradlew
chmod +x gradlew                 # one-time: the API upload does not preserve the exec bit

./gradlew :app:assembleDebug
```

CI does not need the wrapper jar (it runs Gradle 8.10.2 directly).

Open in Android Studio (Ladybug / API 35 toolchain recommended).

## Known limitations

- PDF still needs a short-lived plaintext working file (`PdfRenderer` requires a seekable fd);
  the UI states this explicitly in the Temporary View banner.
- Library scan depends on MediaStore indexing and storage permission; use “Open file…” as fallback.
- Password-protected PDFs and complex DOCX layouts are unsupported.
- Keystore master key is not yet bound to user authentication (`setUserAuthenticationRequired`
  with `BiometricPrompt.CryptoObject` is the next hardening step; it requires a DEK re-wrap
  migration strategy because changing the device credential invalidates auth-bound keys).
- Photo grid shows metadata cards, not decrypted thumbnails (deliberate: keeps the vault sealed
  until an item is explicitly opened).
