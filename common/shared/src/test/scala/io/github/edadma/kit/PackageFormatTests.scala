package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class PackageFormatTests extends AnyFreeSpec with Matchers {

  val sampleManifest =
    """name = "hello"
      |version = "1.0.0"
      |target = "x86_64-linux-gnu"
      |content-hash = "sha256-abc123"
      |scope = "user"
      |""".stripMargin

  val helloScript = "#!/bin/sh\necho \"hello world\"\n"
  val testScript  = "#!/bin/sh\noutput=$(bin/hello)\ntest \"$output\" = \"hello world\"\n"

  def helloPkg: PackageFormat.Package = PackageFormat.Package(
    manifest = sampleManifest,
    files = List(
      PackageFormat.FileEntry("bin/hello", 0x1ed, helloScript.getBytes("UTF-8")),      // 0755
      PackageFormat.FileEntry("bin/hello-test", 0x1ed, testScript.getBytes("UTF-8")),   // 0755
      PackageFormat.FileEntry("manifest.toml", 0x1a4, sampleManifest.getBytes("UTF-8")), // 0644
    ),
  )

  // --- Roundtrip ---

  "write and read roundtrip" in {
    val bytes = PackageFormat.writeBytes(helloPkg)
    val result = PackageFormat.readBytes(bytes)
    result.isRight shouldBe true
    val pkg = result.toOption.get

    pkg.manifest shouldBe sampleManifest
    pkg.files should have length 3
  }

  "file paths preserved" in {
    val pkg = PackageFormat.readBytes(PackageFormat.writeBytes(helloPkg)).toOption.get
    pkg.files.map(_.path) shouldBe List("bin/hello", "bin/hello-test", "manifest.toml")
  }

  "file modes preserved" in {
    val pkg = PackageFormat.readBytes(PackageFormat.writeBytes(helloPkg)).toOption.get
    pkg.files(0).mode shouldBe 0x1ed // 0755
    pkg.files(2).mode shouldBe 0x1a4 // 0644
  }

  "file data preserved" in {
    val pkg = PackageFormat.readBytes(PackageFormat.writeBytes(helloPkg)).toOption.get
    new String(pkg.files(0).data, "UTF-8") shouldBe helloScript
    new String(pkg.files(1).data, "UTF-8") shouldBe testScript
  }

  "manifest preserved exactly" in {
    val pkg = PackageFormat.readBytes(PackageFormat.writeBytes(helloPkg)).toOption.get
    pkg.manifest shouldBe sampleManifest
  }

  // --- Magic ---

  "starts with correct magic" in {
    val bytes = PackageFormat.writeBytes(helloPkg)
    new String(bytes.take(8), "US-ASCII") shouldBe "KITPKG01"
  }

  "reject wrong magic" in {
    val bytes = PackageFormat.writeBytes(helloPkg)
    bytes(0) = 'X'.toByte
    val result = PackageFormat.readBytes(bytes)
    result.isLeft shouldBe true
    result.left.toOption.get should include("invalid magic")
  }

  // --- Edge cases ---

  "empty file list" in {
    val pkg = PackageFormat.Package("name = \"empty\"", Nil)
    val bytes = PackageFormat.writeBytes(pkg)
    val result = PackageFormat.readBytes(bytes)
    result.isRight shouldBe true
    result.toOption.get.files shouldBe empty
  }

  "empty file data" in {
    val pkg = PackageFormat.Package("name = \"x\"", List(
      PackageFormat.FileEntry("empty.txt", 0x1a4, Array.empty[Byte]),
    ))
    val roundtripped = PackageFormat.readBytes(PackageFormat.writeBytes(pkg)).toOption.get
    roundtripped.files.head.data shouldBe empty
  }

  "large file" in {
    val bigData = new Array[Byte](1024 * 1024) // 1 MB
    java.util.Arrays.fill(bigData, 42.toByte)
    val pkg = PackageFormat.Package("name = \"big\"", List(
      PackageFormat.FileEntry("big.bin", 0x1a4, bigData),
    ))
    val roundtripped = PackageFormat.readBytes(PackageFormat.writeBytes(pkg)).toOption.get
    roundtripped.files.head.data.length shouldBe 1024 * 1024
    roundtripped.files.head.data(0) shouldBe 42.toByte
    roundtripped.files.head.data(1024 * 1024 - 1) shouldBe 42.toByte
  }

  "many files" in {
    val files = (1 to 100).map { i =>
      PackageFormat.FileEntry(s"file$i.txt", 0x1a4, s"content $i".getBytes("UTF-8"))
    }.toList
    val pkg = PackageFormat.Package("name = \"many\"", files)
    val roundtripped = PackageFormat.readBytes(PackageFormat.writeBytes(pkg)).toOption.get
    roundtripped.files should have length 100
    roundtripped.files.last.path shouldBe "file100.txt"
  }

  "binary file data" in {
    val binary = (0 until 256).map(_.toByte).toArray
    val pkg = PackageFormat.Package("name = \"bin\"", List(
      PackageFormat.FileEntry("all-bytes.bin", 0x1a4, binary),
    ))
    val roundtripped = PackageFormat.readBytes(PackageFormat.writeBytes(pkg)).toOption.get
    roundtripped.files.head.data should have length 256
    roundtripped.files.head.data(0) shouldBe 0.toByte
    roundtripped.files.head.data(127) shouldBe 127.toByte
    roundtripped.files.head.data(255) shouldBe -1.toByte // 0xFF as signed byte
  }

  "unicode file path" in {
    val pkg = PackageFormat.Package("name = \"uni\"", List(
      PackageFormat.FileEntry("share/locale/日本語.txt", 0x1a4, "hello".getBytes("UTF-8")),
    ))
    val roundtripped = PackageFormat.readBytes(PackageFormat.writeBytes(pkg)).toOption.get
    roundtripped.files.head.path shouldBe "share/locale/日本語.txt"
  }

  "unicode manifest" in {
    val manifest = "name = \"héllo\"\nversion = \"1.0.0\""
    val pkg = PackageFormat.Package(manifest, Nil)
    val roundtripped = PackageFormat.readBytes(PackageFormat.writeBytes(pkg)).toOption.get
    roundtripped.manifest shouldBe manifest
  }

  // --- Truncated data ---

  "reject truncated header" in {
    PackageFormat.readBytes(Array[Byte](0, 1, 2)).isLeft shouldBe true
  }

  "reject truncated manifest" in {
    val bytes = PackageFormat.writeBytes(helloPkg)
    val truncated = bytes.take(20) // cut in the middle of the manifest
    PackageFormat.readBytes(truncated).isLeft shouldBe true
  }

  "reject empty input" in {
    PackageFormat.readBytes(Array.empty[Byte]).isLeft shouldBe true
  }

  // --- Content hash ---

  "same package produces same bytes" in {
    val bytes1 = PackageFormat.writeBytes(helloPkg)
    val bytes2 = PackageFormat.writeBytes(helloPkg)
    bytes1 should have length bytes2.length
    bytes1.sameElements(bytes2) shouldBe true
  }

  "different package produces different bytes" in {
    val pkg2 = helloPkg.copy(manifest = helloPkg.manifest.replace("1.0.0", "2.0.0"))
    val bytes1 = PackageFormat.writeBytes(helloPkg)
    val bytes2 = PackageFormat.writeBytes(pkg2)
    bytes1.sameElements(bytes2) shouldBe false
  }

  "content hash is deterministic" in {
    val bytes = PackageFormat.writeBytes(helloPkg)
    val h1 = ContentHasher.sha256(bytes)
    val h2 = ContentHasher.sha256(bytes)
    h1 shouldBe h2
  }
}
