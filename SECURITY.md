# Safety and privacy

This unofficial tool edits local EXML files. It does not call a launcher provider,
modify launcher databases, obtain root, or perform device restoration.

- Keep a fresh, unmodified native backup and a recovery plan.
- Treat input EXML, saved plans, installed-app inventories and device-test reports
  as private. Keep them outside the public repository.
- Do not file public issues containing real backups, shortcut targets/icons,
  app inventories, device addresses, account details, or proprietary APKs.
- Use synthetic reproductions. The `examples/` input is deliberately fictional.
- Do not restore the fictional fixture or IO probe sample to a device.
- Version-specific successful testing is not a universal compatibility guarantee.

## Shizuku organizer preview

- Requires explicit Shizuku approval and supports non-root shell UID 2000 only.
  Shizuku does not grant Samsung signature or launcher privileges.
- File service paths are fixed to Home Up's native backup/preview directories.
  No arbitrary shell command, generic path access or launcher API is exposed.
- Native EXML names must be exactly 13 decimal digits plus `.exml`.
- Rejects path traversal, symlink files, existing output names, source-hash changes,
  payload-hash changes, invalid EXML and any change outside `appOrder`.
- New native files use exclusive creation and are reread before success. Existing
  companion files are copied to new names and reread. Failed writes trigger
  cleanup of files created by that operation only; originals are not rewritten.
- The APK requests only the Shizuku API permission; no internet or broad storage
  permission. XML conversion and Android app-category suggestions stay local.
- This is a **debuggable, debug-signed preview**, not a hardened production app.
  Authorized local ADB can use `run-as` to inspect its private files for tests.
  Do not leave USB/wireless debugging authorized to an untrusted computer.
- Source EXML, plan and diagnostics remain in app-internal storage. Uninstalling
  the editor removes them. Export a private copy first when needed.
- Disconnecting its service does not stop the Shizuku server or other clients.

The separate `device-tests` APK is never bundled with the editor or published as
an end-user download. It must match the local debug signature and run explicitly
with `allowStage=yes`. It uses an already imported source and approved Shizuku
permission, temporarily tests local moves, reverts them and adds one new backup
file. It never restores the launcher. CI builds it but does not execute it.

## Other paths and common limits

The old permission-free `android/io-probe` APK is a separate IO diagnostic tool.
It cannot directly read Home Up's `Android/data` backup path. PC ADB is an
alternative only after the user explicitly authorizes their computer/device.
Download-folder copies used by that alternative are sensitive; remove the exact
temporary copies when finished, not the original rollback backup.

The offline converters reject DTD/entities, unsupported source structures, hidden
Apps entries, malformed grids, and inexact component coverage. Input is limited
to 8 MiB. Apps-only byte preservation does **not** make Home Up restoration an
apps-only operation: native restore is still a full-layout action by the user.

The repository contains no vulnerability disclosure service. Avoid putting
sensitive proof into public discussions; minimize and synthesize a reproduction.
