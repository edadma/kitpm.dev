package io.github.edadma.kit

/**
 * Plans the symlink tree for a generation directory.
 * Each entry maps a relative path (e.g., "bin/nginx") to an absolute store path.
 */
object SymlinkTree:

  /** A planned symlink: relative path within the generation → absolute target in the store. */
  case class SymlinkEntry(relativePath: String, storePath: String)

  /**
   * The set of directory prefixes to expose from each store entry.
   * Only files under these directories get symlinked into the generation.
   */
  val exposedDirs: List[String] = List("bin", "sbin", "lib", "share")

  /**
   * Plan the symlink tree for a generation from a closure.
   *
   * For each package in the closure, for each exposed directory prefix,
   * create a symlink from the generation dir to the store path.
   *
   * @param closure the resolved dependency closure
   * @param root    the kit root path
   * @return list of symlink entries, sorted by relative path
   */
  def plan(closure: Resolution.Closure, root: String): List[SymlinkEntry] =
    val prefix = if root.endsWith("/") then root.dropRight(1) else root

    closure.packages.toList
      .sortBy(_._2.name)
      .flatMap { (hash, manifest) =>
        val storeDir = StorePath.build(root, hash, manifest.name, manifest.version)
        planForPackage(manifest, storeDir)
      }
      .sortBy(_.relativePath)

  /**
   * Plan symlinks for a single package.
   * Produces entries like "bin/nginx" → "/kit/store/sha256-abc-nginx-1.27.0/sbin/nginx"
   *
   * Since we don't have filesystem access at tier 0, this plans based on manifest metadata.
   * The actual file listing happens at tier 1 when the store entry exists on disk.
   *
   * For tier 0, we produce entries for each exposed binary/lib mentioned in effects.
   */
  def planFromEffects(manifest: Manifest, storeDir: String): List[SymlinkEntry] =
    val entries = List.newBuilder[SymlinkEntry]

    // Service binaries
    for s <- manifest.effects.services do
      entries += SymlinkEntry(s.binary, s"$storeDir/${s.binary}")

    // Capability binaries
    for c <- manifest.effects.capabilities do
      entries += SymlinkEntry(c.binary, s"$storeDir/${c.binary}")

    // Generator binaries
    for g <- manifest.effects.generators do
      entries += SymlinkEntry(g.generator, s"$storeDir/${g.generator}")

    // First-run binary
    manifest.effects.firstRun.foreach { fr =>
      entries += SymlinkEntry(fr.binary, s"$storeDir/${fr.binary}")
    }

    entries.result().distinctBy(_.relativePath)

  private def planForPackage(manifest: Manifest, storeDir: String): List[SymlinkEntry] =
    planFromEffects(manifest, storeDir)
