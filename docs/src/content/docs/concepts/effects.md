---
title: Effects
description: The closed effect schema for package system integration.
---

The effect schema is what makes Kit packages do more than just drop files on disk. When a package needs a system user, a running service, or file capabilities, it declares those needs as **effects** in its manifest. Kit's activation engine applies them.

The schema is **closed** — a package may only declare effects from this fixed set. The daemon rejects unknown effect types. Every effect has a defined inverse for rollback.

## Effect types

### group

Creates a system group.

```toml
[[effects.group]]
name = "nginx"
system = true
```

Applied via the [user-database adapter](/concepts/adapters/). Inverse: remove the group.

### user

Creates a system user.

```toml
[[effects.user]]
name = "nginx"
group = "nginx"
home = "/var/lib/nginx"
shell = "/sbin/nologin"
system = true
```

Depends on the referenced group existing first. Applied via the user-database adapter.

### directory

Creates a directory with specific ownership and permissions.

```toml
[[effects.directory]]
path = "/var/lib/nginx"
owner = "nginx"
group = "nginx"
mode = "0750"
```

Pure POSIX — no adapter required. **No inverse:** directories may contain user data, so Kit doesn't delete them on deactivation. Use `kit purge` for intentional removal.

### capability

Grants file capabilities to a binary.

```toml
[[effects.capability]]
binary = "sbin/nginx"
caps = ["cap_net_bind_service+ep"]
```

Applied via the [capabilities adapter](/concepts/adapters/). Only available on Linux and Slix.

### service

Registers a service with the host init system.

```toml
[[effects.service]]
name = "nginx"
binary = "sbin/nginx"
args = ["-g", "daemon off;"]
user = "nginx"
group = "nginx"
requires = ["network"]
restart = "on-failure"
```

Kit writes a portable service description; the [init adapter](/concepts/adapters/) translates it for systemd, launchd, rc.d, s6, or Slix's native service model.

### generate

Runs a sandboxed config generator.

```toml
[[effects.generate]]
generator = "bin/nginx-genconfig"
output = "/etc/nginx/nginx.conf"
inputs = ["/etc/kit/system.toml#nginx"]
```

The generator runs in a sandbox: unprivileged user, confined to declared inputs, output moved atomically on success. This is how packages template configuration from `system.toml`.

A generator's inputs may include another generator's output (generator chaining). The activation engine orders generators by the package dependency graph.

### first-run

Runs a one-time initialization binary (e.g., `postgres initdb`).

```toml
[effects.first-run]
binary = "bin/postgres-initdb"
```

Runs once on first activation, as the package's declared service user, sandboxed to the package's directories. Sets a completion flag. **No inverse** — first-run creates persistent state that isn't the package manager's to delete.

## What the schema does not include

- **Arbitrary shell snippets.** No "run this script on activation."
- **Cross-package coordination.** No "if package X is installed, do Y."
- **File modification outside declared outputs.** Generators write to their output paths; nothing else.
- **Interactive prompts.** Configuration is declarative via `system.toml`.

These are real capability losses compared to `.deb` or NixOS. They're the price of a closed schema, and the closed schema is the price of legibility and deterministic rollback.

## Activation engine

When a profile's generation changes, the activation engine:

1. Computes the effect diff between old and new generations.
2. Validates: no conflicts, required features present.
3. Orders by dependencies: groups before users before directories before generators before services.
4. Applies each effect, writing a journal entry with its inverse.
5. If any step fails, replays the journal in reverse to restore the previous state.
6. On success, flips `current` atomically.

Activation is atomic from the user's perspective: either the new generation is fully live, or the old one is.
