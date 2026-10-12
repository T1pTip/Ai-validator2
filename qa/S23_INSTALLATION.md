# S23 Ultra installation investigation — 2026-10-13

## Evidence from run 38310829684

Commit: `1f2a873e35d5b9d847ebd337f3d35bf30dd594fd`.
Release assembly and lint succeeded. APK verification succeeded using v3 signing;
v1/v2 being false is not itself an error for this minSdk 28 application.
API 35 x86_64 emulator installation returned `Success`, and the app process
was alive eight seconds after launch. This does not test Samsung policies or a
pre-existing installation on a physical phone.

Downloaded APK SHA-256, independently matched to the published checksum:
`d1c278f496097a0e670a5718c061acc1d5e091c9db95b07365fb9756d09c9866`.
Signing certificate SHA-256 from the successful run:
`45dfa607f188a6d4e8e8facc24ff07e9dc27a759081e39adffd7042fa114299b`.
Package `com.aiorigin.detector`, versionCode 17, minSdk 28, targetSdk 35.

## Changes and limits

- New debug builds use `com.aiorigin.detector.debug`; release keeps its original
  package and existing signing secrets. This prevents future debug/release
  certificate conflicts. It does not repair a legacy debug installation with
  the release package name, and it does not authorize deleting that installation.
- Version 0.18 increments versionCode to 18 for the changed build.
- Signed release now requires installation and launch on API 35 and 36. The
  repeated install checks replacement with the same APK, not migration from
  every previous version. Both emulators remain x86_64, not Samsung ARM64 hardware.
- Artifacts include signer, APK manifest, checksum, commit/run and emulator
  reports. Failed emulator runs retain evidence without publishing a verified APK.

## Obtain the actual device failure safely

Extract the artifact ZIP and use the `.apk`, not the ZIP, checksum text or an
unsigned APK. Verify its hash against the checksum included in the same run.
Record the exact installer message, Android/One UI version and whether any
previous AI Origin Detector build is installed. Those facts are currently missing.

With an already authorized developer connection and Android SDK build-tools
35.0.0, the Windows helper collects limited diagnostics locally:

```powershell
./qa/device-install-diagnostics.ps1 -Apk ./ai-origin-release.apk -AndroidSdk C:/Android/Sdk -Serial DEVICE_SERIAL -OutputDirectory ./device-evidence
```

It is read-only by default. Optional `-Install` makes exactly one standard
`adb install -r` attempt and saves the error; it never uninstalls, downgrades,
grants permissions or changes protection settings. ADB and the phone's file
installer can follow different policy paths; success through ADB alone does
not establish success through Samsung's installer. Reports stay on the computer.

| Observed failure | Follow-up |
| --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Compare the installed and new signing certificates; build with the original trusted key. Do not remove app/data automatically. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Compare installed versionCode with the APK; produce a higher version using the same trusted signing key. Do not force downgrade. |
| Policy, Auto Blocker or Play Protect warning | Preserve the message. Use an approved store/testing distribution or administrator-approved route; do not disable or bypass protections. |
| Invalid/truncated APK or parse failure | Compare SHA-256 and signature report; obtain the intact APK from the same run. |
| Insufficient storage | Confirm available storage and let the owner manage it; do not delete data automatically. |

Samsung documents that Auto Blocker can reject installations from unauthorized
sources: https://docs.samsungknox.com/admin/fundamentals/whitepaper/samsung-knox-mobile-security/system-security/samsung-auto-blocker/
Android requires compatible signing certificates for updates:
https://developer.android.com/studio/publish/app-signing
Neither possibility has been established as this phone's root cause.

## Stage 2 scope is unresolved

The complete tracked source tree, README, QA documents, all 11 commits, the
single main branch, issues, pull requests and milestones were examined. Only
Stage 1 has a specification (`qa/STAGE1_BUILD_AUDIT.md`). No Stage 2 definition
was found. The imported conversation returns assistant content-reference
placeholders, not the original requirements. The listed absent AI classifier,
prompt reconstruction and provenance features do not define a Stage 2 contract.
No feature scope, model or accuracy claims are invented from that list.
The original Stage 2 requirements are needed to implement that phase faithfully.

Physical S23 Ultra installation, picker/share flows, thermal behavior, ARM64
execution and visual classifier accuracy remain unverified.
