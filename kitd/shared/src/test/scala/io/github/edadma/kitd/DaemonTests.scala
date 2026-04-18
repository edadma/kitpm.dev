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
}
