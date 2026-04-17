---
title: Design Overview
description: A summary of Kit's design and a pointer to the full design document.
---

The full design document lives in [`design.md`](https://github.com/edadma/kit/blob/main/design.md) at the root of the Kit repository. What follows is a brief summary.

## Summary

Kit is built around a content-addressed store where every package is identified by the hash of its build inputs and outputs. Installations are organized into generations — immutable snapshots of the set of active packages — so that any change can be rolled back atomically. Package definitions use a closed effect schema: each package declares exactly which files it installs, which environment variables it sets, and which services it provides, and the package manager enforces that nothing outside the schema is touched. Platform adapters sit between Kit's core and the host operating system, translating abstract operations (like "install this service file") into platform-specific actions, which is what makes the same package definitions portable across Linux, macOS, and BSD.
