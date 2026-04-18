---
title: Invariants
description: The seven load-bearing invariants that every design decision serves.
---

Every design decision in Kit is in service of these seven invariants. Features that would erode them are rejected.

## 1. Immutability

Store paths are content-addressed and never mutated after creation.

Once a package is extracted into the store, its bytes are fixed. No upgrade, no activation, no configuration change modifies a store entry. This is what makes rollback trustworthy — the old version is exactly as it was.

## 2. Exact pinning

Dependency references name exact versions and exact content hashes. No version ranges, no constraint solving, ever.

Resolution is a simple transitive closure walk. There's no SAT solver, no backtracking, no "compatible version" heuristics. If a dependency isn't available at the exact pinned version and hash, installation fails with a clear error.

## 3. Separation of concerns

The package manager fetches, verifies, registers, composes, and activates. It does not build.

Building and installing are different problems with different complexity budgets. Fusing them (as Nix does) creates a system where understanding installation requires understanding the build language. Kit keeps them separate.

## 4. Profile composition

Each principal (user, system) has its own profile with its own generation history. Environments are composed from profiles, not conflicts resolved between them.

Two profiles can contain different versions of the same package. There's no merge conflict because there's no merge — PATH ordering determines which binary wins, and each binary brings its own dependency closure.

## 5. Identity is content

A store path is identified by the content hash of its bytes. The origin — fetched, built, copied — is metadata, not identity.

This means a locally-built package that produces identical bytes to a repo-fetched package is literally the same package. Provenance is recorded but does not affect behavior.

## 6. Bounded effects

Every system state change a package can request is drawn from a fixed, enumerable schema of effect types, each with a defined inverse.

No package can declare an effect outside the schema. No package-provided code runs during activation except through narrowly sandboxed generators and first-run hooks. A student can read the schema and know the complete set of things Kit can do to their system.

## 7. POSIX portability

The daemon core uses only POSIX APIs. Platform-specific behavior is isolated behind adapter interfaces.

The same source compiles and runs identically on every supported platform. Differences in capability are expressed as the presence or absence of adapters, not as code branches.
