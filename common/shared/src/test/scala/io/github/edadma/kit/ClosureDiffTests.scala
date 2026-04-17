package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ClosureDiffTests extends AnyFreeSpec with Matchers {

  def entry(name: String, version: String, hash: String): GenerationPackageEntry =
    GenerationPackageEntry(name, version, ContentHash("sha256", hash), Scope.System)

  def gen(n: Int, packages: List[GenerationPackageEntry]): GenerationManifest =
    GenerationManifest(n, "system", packages, Effects.empty)

  "detect added packages" in {
    val old = gen(1, List(entry("libc", "0.3", "aaa")))
    val nw = gen(2, List(entry("libc", "0.3", "aaa"), entry("nginx", "1.27", "bbb")))

    val d = ClosureDiff.diff(old, nw)
    d.added should have length 1
    d.added.head.name shouldBe "nginx"
    d.removed shouldBe empty
    d.changed shouldBe empty
    d.unchanged should have length 1
  }

  "detect removed packages" in {
    val old = gen(1, List(entry("libc", "0.3", "aaa"), entry("nginx", "1.27", "bbb")))
    val nw = gen(2, List(entry("libc", "0.3", "aaa")))

    val d = ClosureDiff.diff(old, nw)
    d.removed should have length 1
    d.removed.head.name shouldBe "nginx"
    d.added shouldBe empty
  }

  "detect changed packages (same name, different hash)" in {
    val old = gen(1, List(entry("nginx", "1.26", "old-hash")))
    val nw = gen(2, List(entry("nginx", "1.27", "new-hash")))

    val d = ClosureDiff.diff(old, nw)
    d.changed should have length 1
    d.changed.head._1.version shouldBe "1.26"
    d.changed.head._2.version shouldBe "1.27"
    d.added shouldBe empty
    d.removed shouldBe empty
  }

  "detect unchanged packages" in {
    val old = gen(1, List(entry("libc", "0.3", "aaa")))
    val nw = gen(2, List(entry("libc", "0.3", "aaa")))

    val d = ClosureDiff.diff(old, nw)
    d.unchanged should have length 1
    d.added shouldBe empty
    d.removed shouldBe empty
    d.changed shouldBe empty
  }

  "empty to empty" in {
    val d = ClosureDiff.diff(gen(1, Nil), gen(2, Nil))
    d.added shouldBe empty
    d.removed shouldBe empty
    d.changed shouldBe empty
    d.unchanged shouldBe empty
  }

  "empty to populated" in {
    val nw = gen(1, List(entry("a", "1.0", "aaa"), entry("b", "1.0", "bbb")))
    val d = ClosureDiff.diff(gen(0, Nil), nw)
    d.added should have length 2
    d.removed shouldBe empty
  }

  "populated to empty" in {
    val old = gen(1, List(entry("a", "1.0", "aaa"), entry("b", "1.0", "bbb")))
    val d = ClosureDiff.diff(old, gen(2, Nil))
    d.removed should have length 2
    d.added shouldBe empty
  }

  "mixed add, remove, change, unchanged" in {
    val old = gen(1, List(
      entry("keep", "1.0", "keep-hash"),
      entry("upgrade", "1.0", "old-hash"),
      entry("drop", "1.0", "drop-hash"),
    ))
    val nw = gen(2, List(
      entry("keep", "1.0", "keep-hash"),
      entry("upgrade", "2.0", "new-hash"),
      entry("fresh", "1.0", "fresh-hash"),
    ))

    val d = ClosureDiff.diff(old, nw)
    d.unchanged.map(_.name) shouldBe List("keep")
    d.changed.map(_._2.name) shouldBe List("upgrade")
    d.removed.map(_.name) shouldBe List("drop")
    d.added.map(_.name) shouldBe List("fresh")
  }
}
