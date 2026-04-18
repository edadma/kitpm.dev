package io.github.edadma.kitd

import io.github.edadma.kit.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.nio.file.{Files, Path, Paths}

/**
 * Tier 1 tests: full daemon against a real filesystem in a temp directory.
 * No network, no real adapters, no system modifications.
 */
class DaemonTests extends AnyFreeSpec with Matchers with BeforeAndAfterEach {

  import scala.compiletime.uninitialized
  var testRoot: Path = uninitialized
  var daemon: Daemon = uninitialized

  override def beforeEach(): Unit =
    testRoot = Files.createTempDirectory("kit-test-")
    daemon = new Daemon(testRoot.toString)
    daemon.init()

  override def afterEach(): Unit =
    deleteRecursive(testRoot)

  // --- Helpers ---

  /** Create a fake "hello" package in a temp directory (simulating an extracted blob). */
  private def createHelloBlob(): Path =
    val blobDir = Files.createTempDirectory("kit-blob-")
    val binDir = blobDir.resolve("bin")
    Files.createDirectories(binDir)

    // hello script
    val hello = binDir.resolve("hello")
    Files.writeString(hello, "#!/bin/sh\necho \"hello world\"\n")
    hello.toFile.setExecutable(true)

    // hello-test script
    val test = binDir.resolve("hello-test")
    Files.writeString(test, "#!/bin/sh\noutput=$(\"$0/../hello\")\ntest \"$output\" = \"hello world\"\n")
    test.toFile.setExecutable(true)

    // manifest
    val manifest =
      """name = "hello"
        |version = "1.0.0"
        |target = "x86_64-linux-gnu"
        |content-hash = "sha256-hellohash123"
        |scope = "user"
        |
        |[[tests]]
        |name = "prints-hello"
        |binary = "bin/hello-test"
        |""".stripMargin
    Files.writeString(blobDir.resolve("manifest.toml"), manifest)

    blobDir

  private val helloManifest = Manifest(
    "hello",
    "1.0.0",
    "x86_64-linux-gnu",
    ContentHash("sha256", "hellohash123"),
    Scope.User,
    Nil,
    Nil,
    Effects.empty,
    List(PackageTest("prints-hello", "bin/hello-test", Nil)),
  )

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.forEach(deleteRecursive)
      finally stream.close()
    Files.deleteIfExists(path)

  // --- Tests ---

  "init creates directory structure" in {
    Files.exists(testRoot.resolve("kit/store")) shouldBe true
    Files.exists(testRoot.resolve("kit/profiles/system/generations")) shouldBe true
    Files.exists(testRoot.resolve("kit/profiles/users")) shouldBe true
    Files.exists(testRoot.resolve("kit/var")) shouldBe true
    Files.exists(testRoot.resolve("etc/kit")) shouldBe true
  }

  "install hello package" in {
    val blob = createHelloBlob()
    val result = daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    result.isRight shouldBe true
    result.toOption.get shouldBe 1 // first generation
  }

  "list shows installed package" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    val pkgs = daemon.list(system = false)
    pkgs should have length 1
    pkgs.head.name shouldBe "hello"
    pkgs.head.version shouldBe "1.0.0"
  }

  "install creates store entry" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    val storePath = daemon.store.pathFor(helloManifest.contentHash, "hello", "1.0.0")
    Files.exists(storePath) shouldBe true
    Files.exists(storePath.resolve("bin/hello")) shouldBe true
  }

  "install creates generation with symlinks" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    val profile = daemon.profiles.profileDir(system = false)
    val current = profile.resolve("current")
    Files.exists(current) shouldBe true
    Files.isSymbolicLink(current) shouldBe true

    // Symlink to hello binary should exist in generation
    val helloBin = current.resolve("bin/hello")
    Files.exists(helloBin) shouldBe true
    Files.isSymbolicLink(helloBin) shouldBe true
  }

  "install writes generation manifest" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    val profile = daemon.profiles.profileDir(system = false)
    val manifestPath = profile.resolve("current/manifest.toml")
    Files.exists(manifestPath) shouldBe true

    val toml = new String(Files.readAllBytes(manifestPath))
    toml should include("hello")
    toml should include("1.0.0")
  }

  "reject duplicate install" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)

    val blob2 = createHelloBlob()
    val result = daemon.install(helloManifest, blob2, system = false)
    deleteRecursive(blob)
    deleteRecursive(blob2)

    result shouldBe Left("package 'hello' is already installed")
  }

  "remove hello package" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    val result = daemon.remove("hello", system = false)
    result.isRight shouldBe true
    result.toOption.get shouldBe 2 // second generation

    daemon.list(system = false) shouldBe empty
  }

  "remove nonexistent package fails" in {
    daemon.remove("ghost", system = false) shouldBe Left("package 'ghost' is not installed")
  }

  "gc removes unreachable store entries" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    // Remove the package (store entry still exists)
    daemon.remove("hello", system = false)

    val storePath = daemon.store.pathFor(helloManifest.contentHash, "hello", "1.0.0")
    Files.exists(storePath) shouldBe true

    // GC should clean it up
    val removed = daemon.gc()
    removed should contain(helloManifest.contentHash)
    Files.exists(storePath) shouldBe false
  }

  "gc preserves reachable store entries" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    // Package is still installed — GC should not touch it
    val removed = daemon.gc()
    removed shouldBe empty

    val storePath = daemon.store.pathFor(helloManifest.contentHash, "hello", "1.0.0")
    Files.exists(storePath) shouldBe true
  }

  "full lifecycle: install, list, remove, gc" in {
    val blob = createHelloBlob()

    // Install
    daemon.install(helloManifest, blob, system = false).isRight shouldBe true
    deleteRecursive(blob)

    // List
    daemon.list(system = false).map(_.name) shouldBe List("hello")

    // Remove
    daemon.remove("hello", system = false).isRight shouldBe true
    daemon.list(system = false) shouldBe empty

    // GC
    val removed = daemon.gc()
    removed should have size 1

    // Store is clean
    daemon.store.listHashes() shouldBe empty
  }

  "multiple packages coexist" in {
    val blob1 = createHelloBlob()
    daemon.install(helloManifest, blob1, system = false)
    deleteRecursive(blob1)

    // Create a second package
    val blob2 = Files.createTempDirectory("kit-blob-")
    val binDir2 = blob2.resolve("bin")
    Files.createDirectories(binDir2)
    Files.writeString(binDir2.resolve("greet"), "#!/bin/sh\necho \"greetings\"\n")
    binDir2.resolve("greet").toFile.setExecutable(true)
    Files.writeString(blob2.resolve("manifest.toml"),
      """name = "greet"
        |version = "2.0.0"
        |target = "x86_64-linux-gnu"
        |content-hash = "sha256-greethash456"
        |scope = "user"
        |""".stripMargin)

    val greetManifest = Manifest(
      "greet", "2.0.0", "x86_64-linux-gnu",
      ContentHash("sha256", "greethash456"), Scope.User, Nil, Nil, Effects.empty, Nil,
    )
    daemon.install(greetManifest, blob2, system = false)
    deleteRecursive(blob2)

    val pkgs = daemon.list(system = false)
    pkgs should have length 2
    pkgs.map(_.name).toSet shouldBe Set("hello", "greet")

    // Both binaries should be symlinked
    val profile = daemon.profiles.profileDir(system = false)
    Files.exists(profile.resolve("current/bin/hello")) shouldBe true
    Files.exists(profile.resolve("current/bin/greet")) shouldBe true
  }

  // --- .kit package format tests ---

  private val helloManifestToml =
    """name = "hello"
      |version = "1.0.0"
      |target = "x86_64-linux-gnu"
      |content-hash = "sha256-hellohash123"
      |scope = "user"
      |
      |[[tests]]
      |name = "prints-hello"
      |binary = "bin/hello-test"
      |""".stripMargin

  private val helloScript     = "#!/bin/sh\necho \"hello world\"\n"
  private val helloTestScript = "#!/bin/sh\noutput=$(\"$(dirname \"$0\")/hello\")\ntest \"$output\" = \"hello world\"\n"

  private def createHelloKitFile(): Path =
    val pkg = PackageFormat.Package(
      manifest = helloManifestToml,
      files = List(
        PackageFormat.FileEntry("bin/hello", 0x1ed, helloScript.getBytes("UTF-8")),
        PackageFormat.FileEntry("bin/hello-test", 0x1ed, helloTestScript.getBytes("UTF-8")),
        PackageFormat.FileEntry("manifest.toml", 0x1a4, helloManifestToml.getBytes("UTF-8")),
      ),
    )
    val bytes = PackageFormat.writeBytes(pkg)
    val kitFile = Files.createTempFile("hello-1.0.0-", ".kit")
    Files.write(kitFile, bytes)
    kitFile

  "install from .kit package file" in {
    val kitFile = createHelloKitFile()
    val result = daemon.install(helloManifest, kitFile, system = false)
    Files.deleteIfExists(kitFile)

    result.isRight shouldBe true
    result.toOption.get shouldBe 1
  }

  "kit package extracts files correctly" in {
    val kitFile = createHelloKitFile()
    daemon.install(helloManifest, kitFile, system = false)
    Files.deleteIfExists(kitFile)

    val storePath = daemon.store.pathFor(helloManifest.contentHash, "hello", "1.0.0")
    Files.exists(storePath.resolve("bin/hello")) shouldBe true
    Files.exists(storePath.resolve("bin/hello-test")) shouldBe true
    Files.exists(storePath.resolve("manifest.toml")) shouldBe true
  }

  "kit package preserves file contents" in {
    val kitFile = createHelloKitFile()
    daemon.install(helloManifest, kitFile, system = false)
    Files.deleteIfExists(kitFile)

    val storePath = daemon.store.pathFor(helloManifest.contentHash, "hello", "1.0.0")
    val content = new String(Files.readAllBytes(storePath.resolve("bin/hello")))
    content shouldBe helloScript
  }

  "kit package sets executable permission" in {
    val kitFile = createHelloKitFile()
    daemon.install(helloManifest, kitFile, system = false)
    Files.deleteIfExists(kitFile)

    val storePath = daemon.store.pathFor(helloManifest.contentHash, "hello", "1.0.0")
    storePath.resolve("bin/hello").toFile.canExecute shouldBe true
  }

  "installed hello script runs correctly" in {
    val kitFile = createHelloKitFile()
    daemon.install(helloManifest, kitFile, system = false)
    Files.deleteIfExists(kitFile)

    val storePath = daemon.store.pathFor(helloManifest.contentHash, "hello", "1.0.0")
    val hello = storePath.resolve("bin/hello").toString

    val pb = new ProcessBuilder("sh", hello)
    pb.redirectErrorStream(true)
    val process = pb.start()
    val output = new String(process.getInputStream.readAllBytes()).trim
    val exitCode = process.waitFor()

    exitCode shouldBe 0
    output shouldBe "hello world"
  }

  // --- Rollback tests ---

  "rollback to previous generation" in {
    val blob1 = createHelloBlob()
    daemon.install(helloManifest, blob1, system = false) // gen 1
    deleteRecursive(blob1)

    val blob2 = Files.createTempDirectory("kit-blob-")
    Files.createDirectories(blob2.resolve("bin"))
    Files.writeString(blob2.resolve("bin/greet"), "#!/bin/sh\necho greet\n")
    Files.writeString(blob2.resolve("manifest.toml"), "name = \"greet\"")
    val greetManifest = Manifest(
      "greet", "1.0", "x86_64-linux-gnu",
      ContentHash("sha256", "greet-hash"), Scope.User, Nil, Nil, Effects.empty, Nil,
    )
    daemon.install(greetManifest, blob2, system = false) // gen 2
    deleteRecursive(blob2)

    daemon.list(system = false) should have length 2

    val result = daemon.rollback(system = false)
    result.isRight shouldBe true
    result.toOption.get shouldBe 1

    daemon.list(system = false) should have length 1
    daemon.list(system = false).head.name shouldBe "hello"
  }

  "rollback to specific generation" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false) // gen 1
    deleteRecursive(blob)

    daemon.remove("hello", system = false) // gen 2

    daemon.list(system = false) shouldBe empty

    val result = daemon.rollback(system = false, toGeneration = Some(1))
    result.isRight shouldBe true

    daemon.list(system = false) should have length 1
    daemon.list(system = false).head.name shouldBe "hello"
  }

  "rollback with no generations fails" in {
    daemon.rollback(system = false) shouldBe Left("no generation to roll back to")
  }

  "rollback to nonexistent generation fails" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    daemon.rollback(system = false, toGeneration = Some(99)) shouldBe Left("generation 99 does not exist")
  }

  "rollback to current generation fails" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    daemon.rollback(system = false, toGeneration = Some(1)) shouldBe Left("already at that generation")
  }

  // --- Multiple generations ---

  "list generations" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false) // gen 1
    deleteRecursive(blob)

    daemon.remove("hello", system = false) // gen 2

    val profile = daemon.profiles.profileDir(system = false)
    val gens = daemon.profiles.listGenerations(profile)
    gens shouldBe List(1, 2)
    daemon.profiles.currentGeneration(profile) shouldBe 2
  }

  // --- Content hash verification ---

  "content hash of .kit file is deterministic" in {
    val bytes1 = PackageFormat.writeBytes(PackageFormat.Package(
      manifest = helloManifestToml,
      files = List(
        PackageFormat.FileEntry("bin/hello", 0x1ed, helloScript.getBytes("UTF-8")),
      ),
    ))
    val bytes2 = PackageFormat.writeBytes(PackageFormat.Package(
      manifest = helloManifestToml,
      files = List(
        PackageFormat.FileEntry("bin/hello", 0x1ed, helloScript.getBytes("UTF-8")),
      ),
    ))
    ContentHasher.sha256(bytes1) shouldBe ContentHasher.sha256(bytes2)
  }

  "different .kit files produce different hashes" in {
    val bytes1 = PackageFormat.writeBytes(PackageFormat.Package(
      manifest = helloManifestToml,
      files = List(PackageFormat.FileEntry("bin/hello", 0x1ed, "v1".getBytes("UTF-8"))),
    ))
    val bytes2 = PackageFormat.writeBytes(PackageFormat.Package(
      manifest = helloManifestToml,
      files = List(PackageFormat.FileEntry("bin/hello", 0x1ed, "v2".getBytes("UTF-8"))),
    ))
    ContentHasher.sha256(bytes1) should not be ContentHasher.sha256(bytes2)
  }

  // --- Pack/unpack roundtrip ---

  "pack and unpack roundtrip preserves files" in {
    // Create a source directory
    val srcDir = Files.createTempDirectory("kit-pack-src-")
    val binDir = srcDir.resolve("bin")
    Files.createDirectories(binDir)
    Files.writeString(binDir.resolve("hello"), helloScript)
    binDir.resolve("hello").toFile.setExecutable(true)
    Files.writeString(srcDir.resolve("manifest.toml"), helloManifestToml)

    // Pack it
    val files = List.newBuilder[PackageFormat.FileEntry]
    val walk = Files.walk(srcDir)
    try
      walk.forEach { path =>
        if Files.isRegularFile(path) then
          val rel = srcDir.relativize(path).toString
          val mode = if path.toFile.canExecute then 0x1ed else 0x1a4
          files += PackageFormat.FileEntry(rel, mode, Files.readAllBytes(path))
      }
    finally walk.close()

    val pkg = PackageFormat.Package(helloManifestToml, files.result().sortBy(_.path))
    val kitBytes = PackageFormat.writeBytes(pkg)

    // Unpack it
    val dstDir = Files.createTempDirectory("kit-pack-dst-")
    val readPkg = PackageFormat.readBytes(kitBytes).toOption.get
    for f <- readPkg.files do
      val dest = dstDir.resolve(f.path)
      Files.createDirectories(dest.getParent)
      Files.write(dest, f.data)
      if (f.mode & 0x49) != 0 then dest.toFile.setExecutable(true)

    // Verify
    val helloContent = new String(Files.readAllBytes(dstDir.resolve("bin/hello")))
    helloContent shouldBe helloScript
    dstDir.resolve("bin/hello").toFile.canExecute shouldBe true

    val manifestContent = new String(Files.readAllBytes(dstDir.resolve("manifest.toml")))
    manifestContent shouldBe helloManifestToml

    deleteRecursive(srcDir)
    deleteRecursive(dstDir)
  }

  // --- Store deduplication ---

  "store entry reused when same hash installed to different profile" in {
    // Install to user profile
    val blob1 = createHelloBlob()
    daemon.install(helloManifest, blob1, system = false)
    deleteRecursive(blob1)

    // Install same package to system profile
    val blob2 = createHelloBlob()
    val systemManifest = helloManifest.copy(scope = Scope.System)
    daemon.install(systemManifest, blob2, system = true)
    deleteRecursive(blob2)

    // Only one store entry should exist for this hash
    val storeEntries = daemon.store.listHashes()
    storeEntries.count(_ == helloManifest.contentHash) shouldBe 1
  }

  // --- .kit file lifecycle ---

  "full lifecycle with .kit file" in {
    val kitFile = createHelloKitFile()

    // Install from .kit file
    daemon.install(helloManifest, kitFile, system = false).isRight shouldBe true
    Files.deleteIfExists(kitFile)

    // Verify installed
    daemon.list(system = false).map(_.name) shouldBe List("hello")

    // Verify binary runs
    val storePath = daemon.store.pathFor(helloManifest.contentHash, "hello", "1.0.0")
    val pb = new ProcessBuilder("sh", storePath.resolve("bin/hello").toString)
    pb.redirectErrorStream(true)
    val output = new String(pb.start().getInputStream.readAllBytes()).trim
    output shouldBe "hello world"

    // Remove and GC
    daemon.remove("hello", system = false).isRight shouldBe true
    daemon.gc() should have size 1
    daemon.store.listHashes() shouldBe empty
  }
}
