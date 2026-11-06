# Safety and privacy

This unofficial tool edits local EXML files. It does not call a launcher provider,
modify launcher databases, obtain root, or perform device restoration.

- Keep a fresh, unmodified native backup and a recovery plan.
- Treat input EXML and installed-app inventories as private.
- Do not file public issues containing real backups, shortcut targets/icons,
  app inventories, device addresses, account details, or proprietary APKs.
- Use synthetic reproductions. The `examples/` input is deliberately fictional.
- Do not restore the fictional fixture or IO probe sample to a device.
- Version-specific successful testing is not a universal compatibility guarantee.
- The Android subproject is an IO diagnostic probe, not a folder-editing app.
- Its debug-signed APK has no requested permissions or network capability.
- It keeps selected data and diagnostic URI metadata in app-internal storage;
  uninstalling the probe removes this data. Do not upload those internal reports.

The offline converter rejects DTD/entities, unsupported source structures, hidden
Apps entries, malformed grids, and inexact component coverage. It limits inputs
to 8 MiB and refuses existing source/output/report paths. Inputs and output should
still be reviewed before a full-layout Home Up restoration.

The repository contains no vulnerability disclosure service. Avoid putting
sensitive proof into public discussions; minimize and synthesize a reproduction.
