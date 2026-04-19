package io.github.edadma.kitd

import io.github.edadma.kit.*

import java.nio.file.{Files, Path, Paths}

/**
 * The kitd daemon core. Coordinates store, profiles, activation, and GC.
 */
class Daemon(val root: String):
  private val prefix = if root.endsWith("/") then root.dropRight(1) else root

  val store    = new Store(root)
  val profiles = new ProfileManager(root)

  /** Adapter config: feature name → adapter executable path. */
  var adapters: Map[String, String] = Map.empty

  private lazy val activation = new ActivationEngine(root, adapters)

  /** Initialize the directory structure. */
  def init(): Unit =
    store.init()
    profiles.init()
    Files.createDirectories(Paths.get(s"$prefix/kit/var"))
    Files.createDirectories(Paths.get(s"$prefix/kit/var/activation-journal"))
    Files.createDirectories(Paths.get(s"$prefix/kit/var/services"))
    Files.createDirectories(Paths.get(s"$prefix/kit/var/state"))
    Files.createDirectories(Paths.get(s"$prefix/etc/kit"))

  /** Load adapter configuration from <root>/etc/kit/adapters.toml */
  def loadAdapters(): Unit =
    val configPath = Paths.get(s"$prefix/etc/kit/adapters.toml")
    if Files.exists(configPath) then
      val toml = new String(Files.readAllBytes(configPath))
      FeatureCheck.availableFrom(toml) match
        case Right(features) =>
          // Map feature names to adapter paths from the TOML
          import io.github.edadma.toml.{TomlParser, TomlValue}
          TomlParser.parse(toml) match
            case Right(doc) =>
              val map = scala.collection.mutable.Map.empty[String, String]
              if doc.getString("user-database.adapter").isDefined then
                map("user-database") = s"$prefix/kit/adapters/kit-adapter-userdb-${doc.getString("user-database.adapter").get}"
              if doc.getString("init.adapter").isDefined then
                map("service-registration") = s"$prefix/kit/adapters/kit-adapter-init-${doc.getString("init.adapter").get}"
              if doc.getString("capabilities.adapter").isDefined then
                map("capabilities") = s"$prefix/kit/adapters/kit-adapter-caps-${doc.getString("capabilities.adapter").get}"
              adapters = map.toMap
            case Left(_) => ()
        case Left(_) => ()

  // --- Install from .kit file ---

  /**
   * Install a package from a .kit file. Reads the manifest from the package,
   * extracts to store, builds generation, runs activation.
   */
  def installFromFile(kitFile: Path, system: Boolean): Either[String, (Manifest, Int)] =
    val bytes = Files.readAllBytes(kitFile)
    PackageFormat.readBytes(bytes) match
      case Left(err) => Left(s"invalid package: $err")
      case Right(pkg) =>
        ManifestParser.parse(pkg.manifest) match
          case Left(err) => Left(s"invalid manifest: $err")
          case Right(manifest) =>
            install(manifest, kitFile, system).map(gen => (manifest, gen))

  // --- Install ---

  def install(manifest: Manifest, blobPath: Path, system: Boolean): Either[String, Int] =
    val profile = profiles.profileDir(system)
    val genNum = profiles.nextGeneration(profile)

    // Extract blob to store
    val storePath = store.pathFor(manifest.contentHash, manifest.name, manifest.version)
    if !store.contains(manifest.contentHash) then
      extractToStore(blobPath, storePath)

    // Read current generation's packages
    val currentPkgs = readCurrentPackages(profile)

    if currentPkgs.exists(_.name == manifest.name) then
      return Left(s"package '${manifest.name}' is already installed")

    // Compute effect diff and run activation
    val oldEffects = Effects.empty // TODO: merge effects from current packages' manifests
    val newEffects = manifest.effects
    if !newEffects.isEmpty then
      val diff = EffectOps.diff(oldEffects, newEffects)
      activation.apply(diff) match
        case Left(err) => return Left(s"activation failed: $err")
        case Right(_)  => ()

    // Build new generation
    val newPkgs = currentPkgs :+ GenerationPackageEntry(
      manifest.name, manifest.version, manifest.contentHash, manifest.scope,
    )
    val genDir = profiles.createGeneration(profile, genNum)
    writeGenerationManifest(genDir, genNum, profileName(system), newPkgs)
    buildSymlinks(genDir, newPkgs)

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

  // --- Rollback ---

  def rollback(system: Boolean, toGeneration: Option[Int] = None): Either[String, Int] =
    val profile = profiles.profileDir(system)
    val current = profiles.currentGeneration(profile)
    if current == 0 then return Left("no generation to roll back to")

    val target = toGeneration.getOrElse(current - 1)
    if target < 0 then return Left(s"invalid generation: $target")
    if target == current then return Left("already at that generation")

    val genDir = profile.resolve(s"generations/$target")
    if !Files.exists(genDir) then return Left(s"generation $target does not exist")

    profiles.switchCurrent(profile, target)
    Right(target)

  // --- List ---

  def list(system: Boolean): List[GenerationPackageEntry] =
    val profile = profiles.profileDir(system)
    readCurrentPackages(profile)

  // --- GC ---

  def gc(): Set[ContentHash] =
    val allHashes = store.listHashes()
    val reachable = collectReachableHashes()
    val garbage = GCReachability.garbage(allHashes, reachable)
    for hash <- garbage do deleteStoreEntry(hash)
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
      genDir: Path, genNum: Int, profile: String, packages: List[GenerationPackageEntry],
  ): Unit =
    val gm = GenerationManifest(genNum, profile, packages, Effects.empty)
    Files.write(genDir.resolve("manifest.toml"), GenerationManifestBuilder.toToml(gm).getBytes)

  private def buildSymlinks(genDir: Path, packages: List[GenerationPackageEntry]): Unit =
    for dir <- List("bin", "sbin", "lib", "share") do
      Files.createDirectories(genDir.resolve(dir))
    for pkg <- packages do
      val storeEntry = store.pathFor(pkg.contentHash, pkg.name, pkg.version)
      if Files.exists(storeEntry) then
        for dir <- List("bin", "sbin", "lib", "share") do
          val srcDir = storeEntry.resolve(dir)
          if Files.exists(srcDir) then
            val stream = Files.list(srcDir)
            try stream.forEach { file =>
              val target = genDir.resolve(dir).resolve(file.getFileName)
              if !Files.exists(target) then Files.createSymbolicLink(target, file)
            }
            finally stream.close()

  private def extractToStore(blobPath: Path, storePath: Path): Unit =
    Files.createDirectories(storePath)
    if Files.isDirectory(blobPath) then
      copyDirectory(blobPath, storePath)
    else
      val bytes = Files.readAllBytes(blobPath)
      PackageFormat.readBytes(bytes) match
        case Left(err) => throw new RuntimeException(s"Failed to read package $blobPath: $err")
        case Right(pkg) =>
          for f <- pkg.files do
            val dest = storePath.resolve(f.path)
            Files.createDirectories(dest.getParent)
            Files.write(dest, f.data)
            if (f.mode & 0x49) != 0 then dest.toFile.setExecutable(true)

  private def copyDirectory(src: Path, dst: Path): Unit =
    val stream = Files.walk(src)
    try stream.forEach { source =>
      val dest = dst.resolve(src.relativize(source))
      if Files.isDirectory(source) then Files.createDirectories(dest)
      else
        Files.createDirectories(dest.getParent)
        Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    finally stream.close()

  private def collectReachableHashes(): Set[ContentHash] =
    val systemPkgs = readCurrentPackages(profiles.profileDir(true))
    val userPkgs = listUserProfiles().flatMap(readCurrentPackages)
    (systemPkgs ++ userPkgs).map(_.contentHash).toSet

  private def listUserProfiles(): List[Path] =
    val usersDir = profiles.profilesDir.resolve("users")
    if !Files.exists(usersDir) then Nil
    else
      val stream = Files.list(usersDir)
      try stream.iterator().nn.asInstanceOf[java.util.Iterator[Path]].asScala.filter(Files.isDirectory(_)).toList
      finally stream.close()

  private implicit class IteratorOps[A](it: java.util.Iterator[A]):
    def asScala: Iterator[A] = new Iterator[A]:
      def hasNext: Boolean = it.hasNext
      def next(): A = it.next()

  private def deleteStoreEntry(hash: ContentHash): Unit =
    val stream = Files.list(store.storeDir)
    try stream.forEach { entry =>
      if entry.getFileName.toString.startsWith(hash.toString) then deleteRecursive(entry)
    }
    finally stream.close()

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.forEach(deleteRecursive)
      finally stream.close()
    Files.deleteIfExists(path)
