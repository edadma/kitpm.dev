package io.github.edadma.kit

/** Validates that two copies of a manifest (repo + tarball) match. */
object ManifestCrossValidator:

  /** The fields that must match between repo manifest and tarball manifest. */
  def validate(repo: Manifest, tarball: Manifest): List[String] =
    val errors = List.newBuilder[String]

    if repo.name != tarball.name then
      errors += s"name mismatch: repo='${repo.name}', tarball='${tarball.name}'"
    if repo.version != tarball.version then
      errors += s"version mismatch: repo='${repo.version}', tarball='${tarball.version}'"
    if repo.target != tarball.target then
      errors += s"target mismatch: repo='${repo.target}', tarball='${tarball.target}'"
    if repo.contentHash != tarball.contentHash then
      errors += s"content-hash mismatch: repo='${repo.contentHash}', tarball='${tarball.contentHash}'"
    if repo.scope != tarball.scope then
      errors += s"scope mismatch: repo='${repo.scope}', tarball='${tarball.scope}'"
    if repo.deps != tarball.deps then
      errors += "deps mismatch between repo and tarball manifests"
    if repo.effects != tarball.effects then
      errors += "effects mismatch between repo and tarball manifests"
    if repo.tests != tarball.tests then
      errors += "tests mismatch between repo and tarball manifests"

    errors.result()
