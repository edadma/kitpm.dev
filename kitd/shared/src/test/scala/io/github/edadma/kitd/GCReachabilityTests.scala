package io.github.edadma.kitd

import io.github.edadma.kit.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class GCReachabilityTests extends AnyFreeSpec with Matchers {

  def entry(name: String, hash: String): GenerationPackageEntry =
    GenerationPackageEntry(name, "1.0", ContentHash("sha256", hash), Scope.System)

  def gen(n: Int, packages: List[GenerationPackageEntry]): GenerationManifest =
    GenerationManifest(n, "system", packages, Effects.empty)

  val hashA = ContentHash("sha256", "aaa")
  val hashB = ContentHash("sha256", "bbb")
  val hashC = ContentHash("sha256", "ccc")
  val hashD = ContentHash("sha256", "ddd")
  val hashE = ContentHash("sha256", "eee")

  // --- reachable ---

  "reachable from single generation" in {
    val g1 = gen(1, List(entry("a", "aaa"), entry("b", "bbb")))
    val reach = GCReachability.reachable(List(g1), Set.empty)
    reach shouldBe Set(hashA, hashB)
  }

  "reachable from multiple generations" in {
    val g1 = gen(1, List(entry("a", "aaa")))
    val g2 = gen(2, List(entry("b", "bbb")))
    val reach = GCReachability.reachable(List(g1, g2), Set.empty)
    reach shouldBe Set(hashA, hashB)
  }

  "pinned paths are reachable" in {
    val g1 = gen(1, List(entry("a", "aaa")))
    val reach = GCReachability.reachable(List(g1), Set(hashC))
    reach shouldBe Set(hashA, hashC)
  }

  "empty generations with pins" in {
    val reach = GCReachability.reachable(Nil, Set(hashA))
    reach shouldBe Set(hashA)
  }

  "deduplicate across generations" in {
    val g1 = gen(1, List(entry("a", "aaa")))
    val g2 = gen(2, List(entry("a", "aaa"), entry("b", "bbb")))
    val reach = GCReachability.reachable(List(g1, g2), Set.empty)
    reach shouldBe Set(hashA, hashB)
  }

  // --- garbage ---

  "identify garbage" in {
    val all = Set(hashA, hashB, hashC, hashD)
    val reach = Set(hashA, hashB)
    GCReachability.garbage(all, reach) shouldBe Set(hashC, hashD)
  }

  "no garbage when all reachable" in {
    val all = Set(hashA, hashB)
    GCReachability.garbage(all, all) shouldBe empty
  }

  "all garbage when none reachable" in {
    val all = Set(hashA, hashB)
    GCReachability.garbage(all, Set.empty) shouldBe all
  }

  "empty store has no garbage" in {
    GCReachability.garbage(Set.empty, Set(hashA)) shouldBe empty
  }

  // --- selectRoots ---

  "select current generation as root" in {
    val g1 = gen(1, List(entry("a", "aaa")))
    val g2 = gen(2, List(entry("b", "bbb")))
    val g3 = gen(3, List(entry("c", "ccc")))
    val roots = GCReachability.selectRoots(List(g1, g2, g3), current = 3, keepCount = 2)
    roots.map(_.generation).toSet shouldBe Set(2, 3) // current + most recent 2
  }

  "current is always included even if outside keepCount window" in {
    val gens = (1 to 20).map(i => gen(i, List(entry(s"p$i", s"h$i")))).toList
    val roots = GCReachability.selectRoots(gens, current = 5, keepCount = 3)
    roots.map(_.generation) should contain(5)
    roots.map(_.generation) should contain(20) // most recent
  }

  "keepCount larger than generation count returns all" in {
    val g1 = gen(1, List(entry("a", "aaa")))
    val g2 = gen(2, List(entry("b", "bbb")))
    val roots = GCReachability.selectRoots(List(g1, g2), current = 2, keepCount = 10)
    roots should have length 2
  }

  "empty generations list" in {
    GCReachability.selectRoots(Nil, current = 1, keepCount = 10) shouldBe empty
  }
}
