package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.nio.file.{Files, Path}

import scala.compiletime.uninitialized

/**
 * Tier 1 tests for kit pack and kit unpack via the Main object's internal methods.
 */
class PackUnpackTests extends AnyFreeSpec with Matchers with BeforeAndAfterEach {

  var tmpDir: Path = uninitialized

  override def beforeEach(): Unit =
    tmpDir = Files.createTempDirectory("kit-packunpack-")

  override def afterEach(): Unit =
    deleteRecursive(tmpDir)

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.forEach(deleteRecursive)
      finally stream.close()
    Files.deleteIfExists(path)

  private val manifestToml =
    """name = "hello"
      |version = "1.0.0"
      |target = "x86_64-linux-gnu"
      |content-hash = "sha256-abc123"
      |scope = "user"
      |""".stripMargin

  private val helloScript = "#!/bin/sh\necho \"hello world\"\n"

  private def createSrcDir(): Path =
    val srcDir = tmpDir.resolve("src")
    Files.createDirectories(srcDir.resolve("bin"))
    Files.writeString(srcDir.resolve("manifest.toml"), manifestToml)
    Files.writeString(srcDir.resolve("bin/hello"), helloScript)
    srcDir.resolve("bin/hello").toFile.setExecutable(true)
    srcDir

  // --- Pack tests ---

  "pack creates a valid .kit file" in {
    val srcDir = createSrcDir()
    val kitFile = tmpDir.resolve("hello.kit")

    Main.doPack(srcDir.toString, kitFile.toString)

    Files.exists(kitFile) shouldBe true
    val bytes = Files.readAllBytes(kitFile)
    new String(bytes.take(8), "US-ASCII") shouldBe "KITPKG01"
  }

  "pack output is readable by PackageFormat" in {
    val srcDir = createSrcDir()
    val kitFile = tmpDir.resolve("hello.kit")

    Main.doPack(srcDir.toString, kitFile.toString)

    val bytes = Files.readAllBytes(kitFile)
    val result = PackageFormat.readBytes(bytes)
    result.isRight shouldBe true
    val pkg = result.toOption.get
    pkg.manifest shouldBe manifestToml
    pkg.files.map(_.path).toSet should contain("bin/hello")
  }

  "pack preserves executable permission in mode" in {
    val srcDir = createSrcDir()
    val kitFile = tmpDir.resolve("hello.kit")

    Main.doPack(srcDir.toString, kitFile.toString)

    val pkg = PackageFormat.readBytes(Files.readAllBytes(kitFile)).toOption.get
    val hello = pkg.files.find(_.path == "bin/hello").get
    (hello.mode & 0x49) should not be 0 // some execute bit
  }

  "pack sorts files by path" in {
    val srcDir = createSrcDir()
    Files.writeString(srcDir.resolve("aaa.txt"), "first")
    Files.createDirectories(srcDir.resolve("zzz"))
    Files.writeString(srcDir.resolve("zzz/last.txt"), "last")
    val kitFile = tmpDir.resolve("hello.kit")

    Main.doPack(srcDir.toString, kitFile.toString)

    val pkg = PackageFormat.readBytes(Files.readAllBytes(kitFile)).toOption.get
    val paths = pkg.files.map(_.path)
    paths shouldBe paths.sorted
  }

  // --- Unpack tests ---

  "unpack extracts files correctly" in {
    val srcDir = createSrcDir()
    val kitFile = tmpDir.resolve("hello.kit")
    Main.doPack(srcDir.toString, kitFile.toString)

    val dstDir = tmpDir.resolve("dst")
    Main.doUnpack(kitFile.toString, dstDir.toString)

    Files.exists(dstDir.resolve("bin/hello")) shouldBe true
    Files.exists(dstDir.resolve("manifest.toml")) shouldBe true
  }

  "unpack preserves file contents" in {
    val srcDir = createSrcDir()
    val kitFile = tmpDir.resolve("hello.kit")
    Main.doPack(srcDir.toString, kitFile.toString)

    val dstDir = tmpDir.resolve("dst")
    Main.doUnpack(kitFile.toString, dstDir.toString)

    new String(Files.readAllBytes(dstDir.resolve("bin/hello"))) shouldBe helloScript
    new String(Files.readAllBytes(dstDir.resolve("manifest.toml"))) shouldBe manifestToml
  }

  "unpack sets executable permission" in {
    val srcDir = createSrcDir()
    val kitFile = tmpDir.resolve("hello.kit")
    Main.doPack(srcDir.toString, kitFile.toString)

    val dstDir = tmpDir.resolve("dst")
    Main.doUnpack(kitFile.toString, dstDir.toString)

    dstDir.resolve("bin/hello").toFile.canExecute shouldBe true
  }

  // --- Roundtrip ---

  "pack then unpack roundtrip preserves all files" in {
    val srcDir = createSrcDir()
    Files.writeString(srcDir.resolve("README"), "A readme file")
    Files.createDirectories(srcDir.resolve("lib"))
    Files.writeString(srcDir.resolve("lib/data.txt"), "some data")

    val kitFile = tmpDir.resolve("hello.kit")
    Main.doPack(srcDir.toString, kitFile.toString)

    val dstDir = tmpDir.resolve("dst")
    Main.doUnpack(kitFile.toString, dstDir.toString)

    new String(Files.readAllBytes(dstDir.resolve("README"))) shouldBe "A readme file"
    new String(Files.readAllBytes(dstDir.resolve("lib/data.txt"))) shouldBe "some data"
    new String(Files.readAllBytes(dstDir.resolve("bin/hello"))) shouldBe helloScript
  }

  // --- Content hash ---

  "pack produces deterministic content hash" in {
    val srcDir = createSrcDir()

    val kitFile1 = tmpDir.resolve("hello1.kit")
    Main.doPack(srcDir.toString, kitFile1.toString)

    val kitFile2 = tmpDir.resolve("hello2.kit")
    Main.doPack(srcDir.toString, kitFile2.toString)

    val hash1 = ContentHasher.sha256(Files.readAllBytes(kitFile1))
    val hash2 = ContentHasher.sha256(Files.readAllBytes(kitFile2))
    hash1 shouldBe hash2
  }
}
