---
title: Store
description: The content-addressed package store.
---

The store is the heart of Kit. It's a flat directory of every package version ever installed, keyed by content hash.

```
<root>/kit/store/
  sha256-1a2b3c-nginx-1.27.0/
  sha256-4d5e6f-nginx-1.26.0/
  sha256-7a8b9c-openssl-3.2.1/
  sha256-deadbe-libc-0.3.1/
```

## Content addressing

Each store path is identified by the SHA-256 hash of the `.kit` package file contents. The `name-version` suffix is human affordance only — the hash is the sole identity. No code parses the name or version out of a store path.

Two packages with identical contents always produce the same hash, regardless of where they came from. Two packages with different contents — even a single byte difference — produce different hashes.

## Immutability

Store paths are never mutated after creation. The store is append-only (except during garbage collection). This means:

- You can always trust that a store path contains exactly what its hash says.
- Multiple versions of the same package coexist without conflict.
- Rollback doesn't need to reconstruct anything — the old version is still there.

## Dependency references

Binaries in the store reference their dependencies by absolute store path:

- **Linux/BSDs:** RPATH points into the store
- **macOS:** `@rpath` with absolute install names
- **Slix:** direct paths

This means runtime resolution is unambiguous. It doesn't matter which profile is active or what's on PATH — every binary loads the exact libraries it was built against.

## Garbage collection

Store entries are garbage-collected when they're no longer reachable from any generation root. See [CLI reference](/reference/cli/) for `kit gc`.
