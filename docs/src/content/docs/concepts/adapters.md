---
title: Adapters
description: How Kit handles platform differences.
---

Adapters are the portability mechanism. Each adapter is a small, separately-distributed program that implements a well-defined interface between `kitd` and a platform facility.

## How they work

Adapters are out-of-process helpers. The daemon sends a JSON request on stdin, the adapter does its work, and writes a JSON response on stdout.

```
kitd  ---request--->  kit-adapter-init-systemd
     <--response---
```

This design gives three properties:

- **Privilege minimization.** An adapter for user creation can run setuid; the daemon doesn't need those privileges.
- **Testability.** Stub adapters that log calls and return success make the system testable without touching real platform facilities.
- **Pluggability.** Don't want Kit managing users on macOS? Don't install the user-database adapter. Packages that require it fail cleanly; packages that don't work fine.

## Adapter types

| Type | Feature name | What it does |
|---|---|---|
| **user-database** | `user-database` | Creates/removes users and groups |
| **init** | `service-registration` | Registers/controls services |
| **capabilities** | `capabilities` | Grants/revokes file capabilities |
| **sandbox** | `sandbox` | Strengthens generator/test sandboxing |

### User database adapters

- `kit-adapter-userdb-useradd` — wraps `useradd`/`groupadd` (Linux)
- `kit-adapter-userdb-dscl` — wraps `dscl` (macOS)
- `kit-adapter-userdb-pw` — wraps `pw` (FreeBSD)
- `kit-adapter-userdb-passwd` — edits `/etc/passwd` directly (POSIX baseline)
- `kit-adapter-userdb-slix` — native Slix user database

### Init adapters

- `kit-adapter-init-systemd` (Linux)
- `kit-adapter-init-launchd` (macOS)
- `kit-adapter-init-rcd` (BSDs)
- `kit-adapter-init-openrc`, `kit-adapter-init-s6`, `kit-adapter-init-runit` (alternatives)
- `kit-adapter-init-slix` (Slix)

### Capabilities adapters

- `kit-adapter-caps-linux` (Linux)
- `kit-adapter-caps-slix` (Slix)
- Absent on macOS and BSDs; packages fall back to SUID or refuse to install

### Sandbox adapters

- `kit-adapter-sandbox-linux` (namespaces + seccomp)
- `kit-adapter-sandbox-macos` (sandbox-exec)
- `kit-adapter-sandbox-freebsd` (capsicum + jails)
- Absent: portable minimum (chroot + unprivileged user + rlimits) is used

## Configuration

Adapters are configured in `<root>/etc/kit/adapters.toml`:

```toml
[user-database]
adapter = "useradd"

[init]
adapter = "systemd"

[capabilities]
adapter = "linux"

[sandbox]
adapter = "linux"
```

Absent entries mean the feature is unavailable. Packages that require a missing feature fail to install with a clear diagnostic. Packages that don't need the feature proceed normally.
