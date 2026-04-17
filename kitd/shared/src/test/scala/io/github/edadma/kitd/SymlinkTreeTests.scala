package io.github.edadma.kitd

import io.github.edadma.kit.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class SymlinkTreeTests extends AnyFreeSpec with Matchers {

  val platform = "x86_64-linux-gnu"

  def pkg(name: String, hash: String, effects: Effects = Effects.empty, deps: List[DepRef] = Nil): Manifest =
    Manifest(name, "1.0", platform, ContentHash("sha256", hash), Scope.System, Nil, deps, effects, Nil)

  "plan symlinks from service effects" in {
    val m = pkg(
      "nginx",
      "abc",
      Effects.empty.copy(
        services = List(ServiceEffect("nginx", "sbin/nginx", Nil, "nginx", "nginx", Nil, "on-failure", Map.empty)),
      ),
    )
    val closure = Resolution.Closure(Map(m.contentHash -> m))

    val links = SymlinkTree.plan(closure, "/")
    links should have length 1
    links.head.relativePath shouldBe "sbin/nginx"
    links.head.storePath shouldBe "/kit/store/sha256-abc-nginx-1.0/sbin/nginx"
  }

  "plan symlinks from capability effects" in {
    val m = pkg(
      "tool",
      "def",
      Effects.empty.copy(
        capabilities = List(CapabilityEffect("bin/tool", List("cap_net_bind+ep"))),
      ),
    )
    val closure = Resolution.Closure(Map(m.contentHash -> m))

    val links = SymlinkTree.plan(closure, "/")
    links should have length 1
    links.head.relativePath shouldBe "bin/tool"
  }

  "plan symlinks from generator effects" in {
    val m = pkg(
      "gen",
      "ggg",
      Effects.empty.copy(
        generators = List(GenerateEffect("bin/genconfig", "/etc/app.conf", Nil)),
      ),
    )
    val closure = Resolution.Closure(Map(m.contentHash -> m))

    val links = SymlinkTree.plan(closure, "/")
    links should have length 1
    links.head.relativePath shouldBe "bin/genconfig"
  }

  "plan symlinks from first-run effect" in {
    val m = pkg(
      "db",
      "ddd",
      Effects.empty.copy(firstRun = Some(FirstRunEffect("bin/initdb"))),
    )
    val closure = Resolution.Closure(Map(m.contentHash -> m))

    val links = SymlinkTree.plan(closure, "/")
    links should have length 1
    links.head.relativePath shouldBe "bin/initdb"
  }

  "deduplicate same binary referenced by multiple effects" in {
    val m = pkg(
      "multi",
      "mmm",
      Effects.empty.copy(
        services = List(ServiceEffect("svc", "bin/app", Nil, "u", "g", Nil, "always", Map.empty)),
        capabilities = List(CapabilityEffect("bin/app", List("cap+ep"))),
      ),
    )
    val closure = Resolution.Closure(Map(m.contentHash -> m))

    val links = SymlinkTree.plan(closure, "/")
    links should have length 1
  }

  "plan from multiple packages" in {
    val a = pkg(
      "a",
      "aaa",
      Effects.empty.copy(
        services = List(ServiceEffect("svc-a", "bin/a", Nil, "u", "g", Nil, "always", Map.empty)),
      ),
    )
    val b = pkg(
      "b",
      "bbb",
      Effects.empty.copy(
        services = List(ServiceEffect("svc-b", "bin/b", Nil, "u", "g", Nil, "always", Map.empty)),
      ),
    )
    val closure = Resolution.Closure(Map(a.contentHash -> a, b.contentHash -> b))

    val links = SymlinkTree.plan(closure, "/")
    links should have length 2
    links.map(_.relativePath) shouldBe List("bin/a", "bin/b") // sorted
  }

  "empty effects produce no symlinks" in {
    val m = pkg("empty", "eee")
    val closure = Resolution.Closure(Map(m.contentHash -> m))
    SymlinkTree.plan(closure, "/") shouldBe empty
  }

  "respects root prefix" in {
    val m = pkg(
      "tool",
      "ttt",
      Effects.empty.copy(
        services = List(ServiceEffect("svc", "bin/tool", Nil, "u", "g", Nil, "always", Map.empty)),
      ),
    )
    val closure = Resolution.Closure(Map(m.contentHash -> m))

    val links = SymlinkTree.plan(closure, "/home/alice/.kit")
    links.head.storePath should startWith("/home/alice/.kit/kit/store/")
  }
}
