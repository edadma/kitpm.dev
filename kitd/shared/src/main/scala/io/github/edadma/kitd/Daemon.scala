package io.github.edadma.kitd

import io.github.edadma.kit.*

import java.nio.file.{Files, Path, Paths}

/**
 * The kitd daemon core. Coordinates store, profiles, activation, and GC.
 * This class contains no socket/IPC logic — it's the engine that request handlers call into.
 */
class Daemon(val root: String):
  private val prefix = if root.endsWith("/") then root.dropRight(1) else root

  val store          = new Store(root)
  val profiles       = new ProfileManager(root)

  /** Initialize the directory structure. */
  def init(): Unit =
    store.init()
    profiles.init()
    Files.createDirectories(Paths.get(s"$prefix/kit/var"))
    Files.createDirectories(Paths.get(s"$prefix/kit/var/activation-journal"))
    Files.createDirectories(Paths.get(s"$prefix/kit/var/services"))
    Files.createDirectories(Paths.get(s"$prefix/kit/var/state"))
    Files.createDirectories(Paths.get(s"$prefix/etc/kit"))

  // --- Install ---

  /**
   * Install a package into a profile.
   *
   * @param manifest the package manifest
   * @param blobPath path to the tarball (already fetched and verified)
   * @param system   install to system profile
   * @return the new generation number
   */
  def install(manifest: Manifest, blobPath: Path, system: Boolean): Either[String, Int] =
    val profile = profiles.profileDir(system)
    val genNum = profiles.nextGeneration(profile)

    // Extract blob to store
    val storePath = store.pathFor(manifest.contentHash, manifest.name, manifest.version)
    if !store.contains(manifest.contentHash) then
      extractToStore(blobPath, storePath)

    // Read current generation's packages (if any)
    val currentPkgs = readCurrentPackages(profile)

    // Check for duplicate
    if currentPkgs.exists(_.name == manifest.name) then
      return Left(s"package '${manifest.name}' is already installed")

    // Build new generation
    val newPkgs = currentPkgs :+ GenerationPackageEntry(
      manifest.name,
      manifest.version,
      manifest.contentHash,
      manifest.scope,
    )
    val genDir = profiles.createGeneration(profile, genNum)
    writeGenerationManifest(genDir, genNum, profileName(system), newPkgs)
    buildSymlinks(genDir, newPkgs)

    // Switch current
    profiles.switchCurrent(profile, genNum)
    Right(genNum)

  // --- Remove ---

  def remove(name: String, system: Boolean): Either[String, Int] =
    val profile = profiles.profileDir(system)
    val currentPkgs = readCurrentPackages(profile)

    if !currentPkgs.exists(_.name == name) then
      return Left(s"package '$name' is not installed")

    val genNum = profiles.nextGeneration(profile)
    val newPkgs = currentPkgs.filterNot(_.name == name)
    val genDir = profiles.createGeneration(profile, genNum)
    writeGenerationManifest(genDir, genNum, profileName(system), newPkgs)
    buildSymlinks(genDir, newPkgs)

    profiles.switchCurrent(profile, genNum)
    Right(genNum)

  // --- List ---

  def list(system: Boolean): List[GenerationPackageEntry] =
    val profile = profiles.profileDir(system)
    readCurrentPackages(profile)

  // --- GC ---

  def gc(): Set[ContentHash] =
    val allHashes = store.listHashes()
    // Collect reachable hashes from all profiles' current generations
    val reachable = collectReachableHashes()
    val garbage = GCReachability.garbage(allHashes, reachable)

    // Delete garbage
    for hash <- garbage do
      deleteStoreEntry(hash)

    garbage

  // --- Helpers ---

  private def profileName(system: Boolean): String =
    if system then "system" else s"users/${System.getProperty("user.name")}"

  private def readCurrentPackages(profile: Path): List[GenerationPackageEntry] =
    val current = profile.resolve("current")
    if !Files.exists(current) then Nil
    else
      val manifestPath = current.resolve("manifest.toml")
      if !Files.exists(manifestPath) then Nil
      else
        val toml = new String(Files.readAllBytes(manifestPath))
        GenerationManifestBuilder.fromToml(toml) match
          case Right(gm) => gm.packages
          case Left(_)   => Nil

  private def writeGenerationManifest(
      genDir: Path,
      genNum: Int,
      profile: String,
      packages: List[GenerationPackageEntry],
  ): Unit =
    val gm = GenerationManifest(genNum, profile, packages, Effects.empty)
    val toml = GenerationManifestBuilder.toToml(gm)
    Files.write(genDir.resolve("manifest.toml"), toml.getBytes)

  private def buildSymlinks(genDir: Path, packages: List[GenerationPackageEntry]): Unit =
    // Create bin/, lib/, sbin/, share/ directories
    for dir <- List("bin", "sbin", "lib", "share") do
      Files.createDirectories(genDir.resolve(dir))

    // For each package, symlink exposed files from the store
    for pkg <- packages do
      val storeEntry = store.pathFor(pkg.contentHash, pkg.name, pkg.version)
      if Files.exists(storeEntry) then
        for dir <- List("bin", "sbin", "lib", "share") do
          val srcDir = storeEntry.resolve(dir)
          if Files.exists(srcDir) then
            val stream = Files.list(srcDir)
            try
              stream.forEach { file =>
                val target = genDir.resolve(dir).resolve(file.getFileName)
                if !Files.exists(target) then
                  Files.createSymbolicLink(target, file)
              }
            finally stream.close()

  private def extractToStore(blobPath: Path, storePath: Path): Unit =
    // For now: copy the directory tree (real implementation would extract a tarball)
    Files.createDirectories(storePath)
    copyDirectory(blobPath, storePath)

  private def copyDirectory(src: Path, dst: Path): Unit =
    val stream = Files.walk(src)
    try
      stream.forEach { source =>
        val dest = dst.resolve(src.relativize(source))
        if Files.isDirectory(source) then Files.createDirectories(dest)
        else
          Files.createDirectories(dest.getParent)
          Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
    finally stream.close()

  private def collectReachableHashes(): Set[ContentHash] =
    val systemPkgs = readCurrentPackages(profiles.profileDir(true))
    val userPkgs = listUserProfiles().flatMap { userDir =>
      readCurrentPackages(userDir)
    }
    (systemPkgs ++ userPkgs).map(_.contentHash).toSet

  private def listUserProfiles(): List[Path] =
    val usersDir = profiles.profilesDir.resolve("users")
    if !Files.exists(usersDir) then Nil
    else
      val stream = Files.list(usersDir)
      try
        stream
          .iterator()
          .nn
          .asInstanceOf[java.util.Iterator[Path]]
          .asScala
          .filter(Files.isDirectory(_))
          .toList
      finally stream.close()

  private implicit class IteratorOps[A](it: java.util.Iterator[A]):
    def asScala: Iterator[A] = new Iterator[A]:
      def hasNext: Boolean = it.hasNext
      def next(): A = it.next()

  private def deleteStoreEntry(hash: ContentHash): Unit =
    // Find the directory matching this hash
    val stream = Files.list(store.storeDir)
    try
      stream.forEach { entry =>
        if entry.getFileName.toString.startsWith(hash.toString) then
          deleteRecursive(entry)
      }
    finally stream.close()

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.forEach(deleteRecursive)
      finally stream.close()
    Files.deleteIfExists(path)
