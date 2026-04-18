package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ContentHasherTests extends AnyFreeSpec with Matchers {

  "sha256" - {
    "hash of empty string" in {
      val hash = ContentHasher.sha256("")
      hash.algorithm shouldBe "sha256"
      // Known SHA-256 of empty string
      hash.digest shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    "hash of hello world" in {
      val hash = ContentHasher.sha256("hello world")
      hash.algorithm shouldBe "sha256"
      // Known SHA-256 of "hello world"
      hash.digest shouldBe "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
    }

    "hash of bytes" in {
      val hash = ContentHasher.sha256(Array[Byte](0, 1, 2, 3))
      hash.algorithm shouldBe "sha256"
      hash.digest.length shouldBe 64 // 32 bytes = 64 hex chars
    }

    "same input produces same hash" in {
      val h1 = ContentHasher.sha256("deterministic")
      val h2 = ContentHasher.sha256("deterministic")
      h1 shouldBe h2
    }

    "different input produces different hash" in {
      val h1 = ContentHasher.sha256("input A")
      val h2 = ContentHasher.sha256("input B")
      h1 should not be h2
    }

    "single byte difference changes hash" in {
      val h1 = ContentHasher.sha256(Array[Byte](0, 1, 2))
      val h2 = ContentHasher.sha256(Array[Byte](0, 1, 3))
      h1 should not be h2
    }

    "toString produces algorithm-digest format" in {
      val hash = ContentHasher.sha256("test")
      hash.toString should startWith("sha256-")
    }
  }

  "verify" - {
    "returns true for matching hash" in {
      val bytes = "test data".getBytes("UTF-8")
      val hash = ContentHasher.sha256(bytes)
      ContentHasher.verify(bytes, hash) shouldBe true
    }

    "returns false for non-matching hash" in {
      val bytes = "test data".getBytes("UTF-8")
      val wrongHash = ContentHash("sha256", "0000000000000000000000000000000000000000000000000000000000000000")
      ContentHasher.verify(bytes, wrongHash) shouldBe false
    }

    "returns false for unknown algorithm" in {
      val bytes = "test".getBytes("UTF-8")
      val hash = ContentHash("md5", "abc123")
      ContentHasher.verify(bytes, hash) shouldBe false
    }

    "verify roundtrip with ContentHash.parse" in {
      val bytes = "roundtrip test".getBytes("UTF-8")
      val hash = ContentHasher.sha256(bytes)
      val parsed = ContentHash.parse(hash.toString)
      parsed.isRight shouldBe true
      ContentHasher.verify(bytes, parsed.toOption.get) shouldBe true
    }
  }
}
