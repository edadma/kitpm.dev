---
title: Package Format
description: The structure of a Kit package.
---

A Kit package is a compressed tarball (`.tar.zst`) paired with a TOML manifest.

## Manifest

The manifest describes the package, its dependencies, its effects, and its tests:

```toml
name = "nginx"
version = "1.27.0"
target = "x86_64-linux-gnu"
content-hash = "sha256-1a2b3c..."
scope = "system"

requires-features = ["capabilities", "service-registration"]

[[deps]]
name = "libc"
version = "0.3.1"
content-hash = "sha256-..."

[[deps]]
name = "openssl"
version = "3.2.1"
content-hash = "sha256-..."

# Effects (system integration)

[[effects.group]]
name = "nginx"
system = true

[[effects.user]]
name = "nginx"
group = "nginx"
home = "/var/lib/nginx"
shell = "/sbin/nologin"
system = true

[[effects.directory]]
path = "/var/lib/nginx"
owner = "nginx"
group = "nginx"
mode = "0750"

[[effects.capability]]
binary = "sbin/nginx"
caps = ["cap_net_bind_service+ep"]

[[effects.service]]
name = "nginx"
binary = "sbin/nginx"
args = ["-g", "daemon off;"]
user = "nginx"
group = "nginx"
requires = ["network"]
restart = "on-failure"

[[effects.generate]]
generator = "bin/nginx-genconfig"
output = "/etc/nginx/nginx.conf"
inputs = ["/etc/kit/system.toml#nginx"]

[effects.first-run]
binary = "bin/postgres-initdb"

# Tests (self-verification)

[[tests]]
name = "starts-and-listens"
binary = "bin/nginx-test-start"
requires-features = ["service-registration"]
```

### Fields

| Field | Required | Description |
|---|---|---|
| `name` | Yes | Human-facing name. Not used for identity. |
| `version` | Yes | Human-facing version. |
| `target` | Yes | Platform triple (e.g., `x86_64-linux-gnu`). |
| `content-hash` | Yes | `algorithm-digest`. The sole identity. |
| `scope` | Yes | `system` or `user`. |
| `requires-features` | No | Platform features the package needs. |
| `deps` | No | Exact-pinned dependency list. |
| `effects.*` | No | System integration effects. See [Effects](/concepts/effects/). |
| `tests` | No | Self-tests. See [Package Testing](/concepts/testing/). |

## Tarball layout

```
<package>.tar.zst
├── bin/
├── sbin/
├── lib/
├── share/
├── manifest.toml          # identical to the repo manifest
└── .kit-provenance        # origin metadata; excluded from content hash
```

The manifest is duplicated inside the tarball so a store entry is self-describing. The daemon cross-checks the two copies.

## Content hash canonicalization

The content hash is computed over the tarball after canonicalization:

- Deterministic entry ordering (lexicographic by path)
- Fixed mtime (epoch 0)
- Fixed uid/gid (0/0)
- Normalized permissions (0755/0644)
- `.kit-provenance` excluded from hash computation

Because `manifest.toml` is inside the tarball and contributes to the content hash, changes to effects, dependencies, or metadata produce a new content hash. An effect change is a new package, not a silent mutation.

## Provenance

```toml
origin = "fetch"
repo = "https://repo.kitpm.dev/stable"
fetched-at = "2026-04-17T14:23:00Z"
signature = "ed25519:..."
```

Provenance is metadata only. It does not affect identity, hash, GC, activation, or resolution.

## Scope

- **`system`**: may declare any effects. Only installable to the system profile.
- **`user`**: may not declare groups, system users, or capabilities. Installable to user profiles.
