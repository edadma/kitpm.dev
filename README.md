# Kit

A portable POSIX package manager. Designed as the native package manager for [Slix](https://github.com/edadma/slix) and usable on Linux, macOS, and the BSDs as a personal-prefix package manager with real rollback, multi-version coexistence, and deterministic installs.

**Website:** [kitpm.dev](https://kitpm.dev)

## Key Ideas

- **Content-addressed store** — packages identified by SHA-256 hash. Multiple versions coexist.
- **Generations with rollback** — every operation is atomic and reversible.
- **Closed effect schema** — packages declare exactly what they do. No arbitrary shell scripts.
- **Platform adapters** — portable core, platform differences handled by small adapter programs.
- **Own package format** — KITPKG01 with kitlz compression. Zero external dependencies.

## Project Structure

```
kitpm.dev/
  common/    — shared types, parsing, resolution, effects, IPC
  kit/       — CLI client (kit install, kit list, kit pack, etc.)
  kitd/      — daemon (store, profiles, activation engine, adapters)
  repo/      — repository server (Apion/Scala.js on Node.js)
  adapters/  — platform adapter scripts (useradd, stub)
  docker/    — Dockerfile for tier 2 testing
  docs/      — Starlight documentation site
  design.md  — full design specification
```

## Building

```sh
# Compile (JVM)
sbt compile

# Run tests (JVM — tier 0 + tier 1)
sbt test

# Run tests (Native — tier 0 + tier 1)
sbt "commonNative/test" "kitdNative/test" "kitNative/test"

# Run tier 2 tests in Docker (real useradd/groupadd)
./docker/run-tier2.sh
```

## Testing

390 tests across four tiers:

| Tier | Environment | Tests | What |
|------|-------------|-------|------|
| 0 | Any | 222 | Pure functions: parsing, resolution, effects, compression, hashing |
| 1 | Any | 157 | Filesystem: daemon, IPC, .kit packages, rollback, stub adapters |
| 2 | Docker | 4 | Real adapters: useradd, groupadd, activation engine |
| 3 | VM | — | Full OS integration (planned) |

## Dependencies

All authored by the same developer:

- [toml](https://github.com/edadma/toml) — TOML parser
- [petradb-engine](https://github.com/edadma/petradb) — embedded SQL database
- [cross_platform](https://github.com/edadma/cross_platform) — cross-platform utilities (filesystem, sockets)
- [crypto](https://github.com/edadma/crypto) — cross-platform cryptography (SHA-256)
- [apion](https://github.com/edadma/apion) — HTTP server framework for Scala.js (repo server)

Third-party:
- [zio-json](https://github.com/zio/zio-json) — JSON serialization
- [scopt](https://github.com/scopt/scopt) — CLI argument parsing
- [scalatest](https://www.scalatest.org/) — testing

## License

ISC License — see [LICENSE](LICENSE) for details.
