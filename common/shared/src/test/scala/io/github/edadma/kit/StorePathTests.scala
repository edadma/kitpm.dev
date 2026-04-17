package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class StorePathTests extends AnyFreeSpec with Matchers {

  "build store path with root /" in {
    val path = StorePath.build("/", ContentHash("sha256", "abc123"), "nginx", "1.27.0")
    path shouldBe "/kit/store/sha256-abc123-nginx-1.27.0"
  }

  "build store path with prefix root" in {
    val path = StorePath.build("/usr/local/kit", ContentHash("sha256", "abc"), "hello", "1.0")
    path shouldBe "/usr/local/kit/kit/store/sha256-abc-hello-1.0"
  }

  "build store path with home root" in {
    val path = StorePath.build("/home/alice/.kit", ContentHash("sha256", "def"), "tool", "2.0")
    path shouldBe "/home/alice/.kit/kit/store/sha256-def-tool-2.0"
  }

  "build store path with trailing slash root" in {
    val path = StorePath.build("/prefix/", ContentHash("sha256", "abc"), "pkg", "1.0")
    path shouldBe "/prefix/kit/store/sha256-abc-pkg-1.0"
  }

  "extract hash from valid store path" in {
    val hash = StorePath.extractHash("/kit/store/sha256-abc123-nginx-1.27.0")
    hash shouldBe Some(ContentHash("sha256", "abc123"))
  }

  "extract hash from prefixed store path" in {
    val hash = StorePath.extractHash("/usr/local/kit/kit/store/sha256-def456-tool-2.0")
    hash shouldBe Some(ContentHash("sha256", "def456"))
  }

  "extract hash returns None for non-store path" in {
    StorePath.extractHash("/usr/bin/nginx") shouldBe None
  }

  "extract hash returns None for malformed store path" in {
    StorePath.extractHash("/kit/store/nohash") shouldBe None
  }

  "build and extract roundtrip" in {
    val hash = ContentHash("sha256", "deadbeef")
    val path = StorePath.build("/", hash, "myapp", "3.1.4")
    StorePath.extractHash(path) shouldBe Some(hash)
  }
}
