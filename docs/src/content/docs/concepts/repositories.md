---
title: Repositories
description: How Kit packages are hosted, discovered, and managed.
---

A repository is a signed collection of packages served over HTTP. Users fetch packages from repositories; admins publish packages to them.

## Architecture

Each repository is a small HTTP server that stores `.kit` packages, manifests, and a signed index. It exposes two surfaces:

**Public (everyone):**
- `GET /index.toml` — the signed package listing
- `GET /manifests/<hash>.toml` — per-package manifests
- `GET /blobs/<hash>.kit` — package files

**Admin (authenticated):**
- `POST /add` — upload a `.kit` package
- `POST /sign` — regenerate and re-sign the index
- `GET /verify` — check that all blobs match their declared hashes

Admin operations require a bearer token. The server handles hashing and index management — admins don't need to hold a full copy of the repository locally.

## Index

The index is a signed TOML file listing every package in the repository:

```toml
repo-name = "kit-stable"
revision = "2026-04-18"
signed-by = "ed25519:..."

[[packages]]
name = "nginx"
version = "1.27.0"
content-hash = "sha256-..."
target = "x86_64-linux-gnu"
scope = "system"

[[packages]]
name = "hello"
version = "1.0.0"
content-hash = "sha256-..."
target = "x86_64-linux-gnu"
scope = "user"
```

Clients verify the index signature against trusted keys before using any content hash from it.

## Client commands

```sh
kit update                # fetch latest indexes from all configured repos
kit search <query>        # search across local indexes by name/description
```

`kit update` fetches the signed index from every configured repository. `kit search` filters the locally-cached indexes — no network request needed after the initial update.

## Admin commands

```sh
kit add <tarball>         # upload a package to the repo server
kit sign                  # tell the server to re-sign its index
kit verify                # check repo integrity (all blobs match hashes)
```

These commands authenticate to the repository server using a bearer token configured in `<root>/etc/kit/repos.toml` or via the `KIT_REPO_TOKEN` environment variable.

## Configuration

Repositories are configured in `<root>/etc/kit/repos.toml`:

```toml
[[repo]]
url = "https://repo.kitpm.dev/stable"
trusted-keys = ["ed25519:..."]
priority = 10
targets = ["x86_64-linux-gnu", "aarch64-linux-gnu"]

[[repo]]
url = "https://repo.kitpm.dev/macos"
trusted-keys = ["ed25519:..."]
priority = 10
targets = ["aarch64-apple-darwin"]
```

- **`url`** — the base URL of the repository server.
- **`trusted-keys`** — public keys used to verify the index signature.
- **`priority`** — higher priority wins when the same package name appears in multiple repos.
- **`targets`** — platform triples this repo serves. Kit only fetches indexes from repos that match the current platform, avoiding unnecessary downloads.

## Multiple repositories

You can configure as many repositories as you want. Lookup spans all of them in priority order. Different dependency closures can pull different versions from different repos — they all coexist in the store without conflict.

Common setups:

- **One repo per architecture:** `repo.kitpm.dev/x86_64-linux`, `repo.kitpm.dev/aarch64-macos`
- **Stable + testing:** a stable repo at priority 10 and a testing repo at priority 5
- **University internal:** a private repo for course-specific packages alongside the public repo

## Trust model

The client never trusts a content hash without first verifying the index signature. The flow:

1. `kit update` fetches `index.toml` from the server.
2. The client verifies the signature against the trusted keys in `repos.toml`.
3. If verification succeeds, the index is cached locally.
4. `kit install` uses content hashes from the verified index to fetch and verify blobs.

If you don't trust a repository's signing key, you don't configure it. There's no implicit trust.

## Publishing a package

The typical workflow for a package maintainer:

1. Build the package (or receive a pre-built `.kit` file from CI).
2. Run `kit add hello-1.0.0.kit` — this uploads the package to the repo server.
3. The server computes the content hash, extracts the manifest, stores both, and updates the index.
4. Run `kit sign` — the server re-signs the index with its signing key.
5. Users run `kit update` to see the new package.

The maintainer never touches the index directly. The server manages it.
