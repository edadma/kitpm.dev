package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class KitCompressTests extends AnyFreeSpec with Matchers {

  private def roundtrip(data: Array[Byte]): Unit =
    val compressed = KitCompress.compress(data)
    val decompressed = KitCompress.decompress(compressed, data.length)
    decompressed should have length data.length
    decompressed.sameElements(data) shouldBe true

  "compress and decompress" - {
    "empty input" in {
      roundtrip(Array.empty[Byte])
    }

    "single byte" in {
      roundtrip(Array[Byte](42))
    }

    "short string" in {
      roundtrip("hello world".getBytes("UTF-8"))
    }

    "repeated data compresses well" in {
      val data = "abcdefgh" * 1000
      val bytes = data.getBytes("UTF-8")
      val compressed = KitCompress.compress(bytes)
      compressed.length should be < bytes.length
      roundtrip(bytes)
    }

    "random data roundtrips" in {
      val rng = new java.util.Random(12345)
      val data = new Array[Byte](10000)
      rng.nextBytes(data)
      roundtrip(data)
    }

    "all zeros" in {
      val data = new Array[Byte](10000)
      val compressed = KitCompress.compress(data)
      compressed.length should be < 200 // should compress very well
      roundtrip(data)
    }

    "all same byte" in {
      val data = Array.fill[Byte](5000)(0x7f)
      val compressed = KitCompress.compress(data)
      compressed.length should be < 200
      roundtrip(data)
    }

    "alternating pattern" in {
      val data = Array.tabulate[Byte](10000)(i => if i % 2 == 0 then 0xaa.toByte else 0x55.toByte)
      roundtrip(data)
    }

    "binary data with some repetition" in {
      // Simulates an ELF binary with repeated sequences
      val data = new Array[Byte](50000)
      val rng = new java.util.Random(99)
      rng.nextBytes(data)
      // Add some repeated blocks
      System.arraycopy(data, 0, data, 10000, 5000)
      System.arraycopy(data, 0, data, 30000, 5000)
      val compressed = KitCompress.compress(data)
      compressed.length should be < data.length
      roundtrip(data)
    }

    "large input" in {
      val data = new Array[Byte](1024 * 1024) // 1 MB
      val rng = new java.util.Random(42)
      rng.nextBytes(data)
      roundtrip(data)
    }

    "data shorter than minimum match" in {
      roundtrip(Array[Byte](1, 2, 3))
    }

    "exactly minimum match length" in {
      roundtrip(Array[Byte](1, 2, 3, 4))
    }

    "data with long literal runs" in {
      // 300 unique bytes followed by repetition
      val data = new Array[Byte](600)
      for i <- 0 until 300 do data(i) = (i % 256).toByte
      for i <- 300 until 600 do data(i) = ((i - 300) % 256).toByte
      roundtrip(data)
    }

    "all 256 byte values" in {
      val data = Array.tabulate[Byte](256)(_.toByte)
      roundtrip(data)
    }
  }

  "compression ratio" - {
    "compresses repetitive text well" in {
      val text = "the quick brown fox jumps over the lazy dog\n" * 100
      val data = text.getBytes("UTF-8")
      val compressed = KitCompress.compress(data)
      val ratio = compressed.length.toDouble / data.length
      ratio should be < 0.3 // at least 70% compression
    }

    "handles incompressible data" in {
      val rng = new java.util.Random(777)
      val data = new Array[Byte](10000)
      rng.nextBytes(data)
      val compressed = KitCompress.compress(data)
      // Should not expand by more than ~1%
      compressed.length should be < (data.length * 1.02).toInt
    }
  }
}
