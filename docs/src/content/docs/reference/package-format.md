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

## Package file format

Kit uses its own binary package format (`.kit` files) rather than tarballs. The format is simple enough to implement in any language with zero external dependencies — critical for bootstrapping on Slix.

```
KITPKG01                           # 8-byte magic + version
<manifest-length: u32>             # big-endian
<manifest TOML: UTF-8 bytes>
<file-count: u32>                  # big-endian
for each file:
  <path-length: u16>              # big-endian
  <path: UTF-8 bytes>             # relative, forward slashes
  <mode: u16>                     # big-endian (e.g., 0755 = 0x01ED)
  <original-size: u32>            # big-endian, uncompressed size
  <compressed-size: u32>          # big-endian
  <compressed-data: bytes>        # LZ4-style compressed
```

Each file's data is individually compressed using a built-in LZ4-style compressor — pure Scala, zero external dependencies. Typical compression ratios: 70%+ for text/scripts, 40-60% for binaries, near-zero expansion for incompressible data.

The manifest is embedded in the package so each store entry is self-describing. `kit pack` creates `.kit` files; `kit unpack` extracts them.

## Content hash

The content hash is computed over the entire `.kit` file bytes (SHA-256).

The `kit pack` command produces deterministic output — same directory contents always produce the same `.kit` file bytes. Files are ordered lexicographically by path. The manifest is embedded and contributes to the hash, so changes to effects, dependencies, or metadata produce a new content hash.

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
