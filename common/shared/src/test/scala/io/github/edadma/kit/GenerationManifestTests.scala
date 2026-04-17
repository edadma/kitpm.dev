package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class GenerationManifestTests extends AnyFreeSpec with Matchers {

  val platform = "x86_64-linux-gnu"

  def pkg(name: String, hash: String, effects: Effects = Effects.empty, deps: List[DepRef] = Nil): Manifest =
    Manifest(name, "1.0", platform, ContentHash("sha256", hash), Scope.System, Nil, deps, effects, Nil)

  // --- Building ---

  "build a generation manifest from a closure" in {
    val a = pkg("alpha", "aaa")
    val b = pkg("beta", "bbb")
    val closure = Resolution.Closure(Map(a.contentHash -> a, b.contentHash -> b))

    val result = GenerationManifestBuilder.build(1, "system", closure)
    result.isRight shouldBe true
    val gm = result.toOption.get

    gm.generation shouldBe 1
    gm.profile shouldBe "system"
    gm.packages should have length 2
    gm.packages.map(_.name) shouldBe List("alpha", "beta") // sorted by name
  }

  "reject closure with conflicting effects" in {
    val a = pkg("a", "aaa", Effects.empty.copy(groups = List(GroupEffect("dup", system = true))))
    val b = pkg("b", "bbb", Effects.empty.copy(groups = List(GroupEffect("dup", system = false))))
    val closure = Resolution.Closure(Map(a.contentHash -> a, b.contentHash -> b))

    val result = GenerationManifestBuilder.build(1, "system", closure)
    result.isLeft shouldBe true
    result.left.toOption.get.head should include("duplicate group")
  }

  "build with empty closure" in {
    val m = pkg("solo", "solo-hash")
    val closure = Resolution.Closure(Map(m.contentHash -> m))

    val gm = GenerationManifestBuilder.build(5, "users/alice", closure).toOption.get
    gm.generation shouldBe 5
    gm.profile shouldBe "users/alice"
    gm.packages should have length 1
    gm.effects shouldBe Effects.empty
  }

  "merged effects are included in the manifest" in {
    val m = pkg(
      "nginx",
      "nginx-hash",
      Effects.empty.copy(
        groups = List(GroupEffect("nginx", system = true)),
        services = List(ServiceEffect("nginx", "sbin/nginx", Nil, "nginx", "nginx", Nil, "on-failure", Map.empty)),
      ),
    )
    val closure = Resolution.Closure(Map(m.contentHash -> m))

    val gm = GenerationManifestBuilder.build(1, "system", closure).toOption.get
    gm.effects.groups should have length 1
    gm.effects.services should have length 1
  }

  // --- TOML serialization roundtrip ---

  "serialize to TOML" in {
    val gm = GenerationManifest(
      3,
      "system",
      List(
        GenerationPackageEntry("libc", "0.3.1", ContentHash("sha256", "aaa"), Scope.System),
        GenerationPackageEntry("nginx", "1.27.0", ContentHash("sha256", "bbb"), Scope.System),
      ),
      Effects.empty,
    )

    val toml = GenerationManifestBuilder.toToml(gm)
    toml should include("generation = 3")
    toml should include("""profile = "system"""")
    toml should include("[[packages]]")
    toml should include("""name = "libc"""")
    toml should include("""name = "nginx"""")
  }

  "roundtrip TOML serialization" in {
    val original = GenerationManifest(
      7,
      "users/bob",
      List(
        GenerationPackageEntry("hello", "1.0.0", ContentHash("sha256", "abc123"), Scope.User),
        GenerationPackageEntry("tool", "2.1.0", ContentHash("sha256", "def456"), Scope.User),
      ),
      Effects.empty,
    )

    val toml = GenerationManifestBuilder.toToml(original)
    val parsed = GenerationManifestBuilder.fromToml(toml)

    parsed.isRight shouldBe true
    val roundtripped = parsed.toOption.get

    roundtripped.generation shouldBe original.generation
    roundtripped.profile shouldBe original.profile
    roundtripped.packages shouldBe original.packages
  }

  "parse invalid TOML" in {
    GenerationManifestBuilder.fromToml("not valid [toml").isLeft shouldBe true
  }

  "parse missing generation field" in {
    val input =
      """
        |profile = "system"
        |""".stripMargin
    GenerationManifestBuilder.fromToml(input) shouldBe Left("missing required field: 'generation'")
  }
}
