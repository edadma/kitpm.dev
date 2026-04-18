---
title: On-Disk Layout
description: Where Kit puts everything.
---

All paths are relative to the root (`--root` flag to `kitd`).

```
<root>/
  kit/
    store/
      <content-hash>-<name>-<version>/
        bin/, sbin/, lib/, share/
        manifest.toml
        .kit-provenance

    profiles/
      system/
        generations/1/, 2/, ... N/
          bin/, sbin/, lib/, share/         # symlinks into store
          manifest.toml
          effects.toml
          activated-state.toml
        current -> generations/N

      users/
        alice/
          generations/1/, 2/, ...
          current -> generations/N

    var/
      cache/                                # blobs pending verification
      kitd.sock                             # daemon socket
      activation-journal/                   # mid-activation journals
      services/                             # portable service descriptions
      state/
        users.db                            # kit-assigned UIDs/GIDs
        caps.db                             # capability grants
        generated-files.db                  # generator output ownership
        first-run.db                        # completion flags
        test-results.db                     # package test results
      log/

    bin/kit                                 # unprivileged client
    sbin/kitd                               # privileged daemon

  etc/kit/
    repos.toml                              # repository configuration
    trusted-keys/                           # signing keys
    system.toml                             # system configuration
    adapters.toml                           # adapter configuration
```

## Root flexibility

The root can be anything:

| Root | Use case |
|---|---|
| `/` | Slix or Linux system-wide |
| `/usr/local/kit` | Linux or macOS local install |
| `~/.kit` | Per-user personal prefix |
| `/tmp/test-abc` | Test environment |

The structure is identical regardless of root. A test root contains the same directories as a production install.

## Key directories

**`kit/store/`** — The content-addressed package store. Globally readable, writable only by `kitd`. Append-only except during GC.

**`kit/profiles/`** — One directory per principal. Each contains numbered generation directories and a `current` symlink.

**`kit/var/`** — Daemon runtime state. The Unix socket, activation journals, service descriptions, and PetraDB state databases.

**`etc/kit/`** — Configuration. Repository URLs and keys, system-wide package configuration, adapter selection.
