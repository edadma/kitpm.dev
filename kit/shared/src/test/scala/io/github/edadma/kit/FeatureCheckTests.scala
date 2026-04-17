package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class FeatureCheckTests extends AnyFreeSpec with Matchers {

  val allFeatures = Set("user-database", "service-registration", "capabilities", "sandbox")

  // --- inferFeatures ---

  "infer no features from empty effects" in {
    FeatureCheck.inferFeatures(Effects.empty) shouldBe empty
  }

  "infer user-database from group effects" in {
    val effects = Effects.empty.copy(groups = List(GroupEffect("grp", system = true)))
    FeatureCheck.inferFeatures(effects) should contain("user-database")
  }

  "infer user-database from user effects" in {
    val effects = Effects.empty.copy(
      users = List(UserEffect("usr", "grp", "/home", "/bin/sh", system = true)),
    )
    FeatureCheck.inferFeatures(effects) should contain("user-database")
  }

  "infer capabilities from capability effects" in {
    val effects = Effects.empty.copy(
      capabilities = List(CapabilityEffect("bin/x", List("cap_net_bind+ep"))),
    )
    FeatureCheck.inferFeatures(effects) should contain("capabilities")
  }

  "infer service-registration from service effects" in {
    val effects = Effects.empty.copy(
      services = List(ServiceEffect("svc", "bin/x", Nil, "u", "g", Nil, "always", Map.empty)),
    )
    FeatureCheck.inferFeatures(effects) should contain("service-registration")
  }

  "infer multiple features" in {
    val effects = Effects(
      groups = List(GroupEffect("grp", system = true)),
      users = List(UserEffect("usr", "grp", "/home", "/bin/sh", system = true)),
      directories = Nil,
      capabilities = List(CapabilityEffect("bin/x", List("cap+ep"))),
      services = List(ServiceEffect("svc", "bin/x", Nil, "usr", "grp", Nil, "always", Map.empty)),
      generators = Nil,
      firstRun = None,
    )
    val inferred = FeatureCheck.inferFeatures(effects)
    inferred shouldBe Set("user-database", "capabilities", "service-registration")
  }

  "directories and generators infer no features" in {
    val effects = Effects.empty.copy(
      directories = List(DirectoryEffect("/var/lib/x", "root", "root", "0755")),
      generators = List(GenerateEffect("bin/gen", "/etc/x.conf", Nil)),
      firstRun = Some(FirstRunEffect("bin/init")),
    )
    FeatureCheck.inferFeatures(effects) shouldBe empty
  }

  // --- check ---

  "no gaps when all features available" in {
    val effects = Effects.empty.copy(
      groups = List(GroupEffect("grp", system = true)),
      services = List(ServiceEffect("svc", "bin/x", Nil, "u", "g", Nil, "always", Map.empty)),
    )
    val gaps = FeatureCheck.check(effects, allFeatures, List("user-database", "service-registration"))
    gaps shouldBe empty
  }

  "detect missing explicitly required feature" in {
    val gaps = FeatureCheck.check(Effects.empty, Set.empty, List("capabilities"))
    gaps should have length 1
    gaps.head.feature shouldBe "capabilities"
    gaps.head.required shouldBe true
  }

  "detect missing implied feature not in requires-features" in {
    val effects = Effects.empty.copy(
      capabilities = List(CapabilityEffect("bin/x", List("cap+ep"))),
    )
    // Package has capability effects but didn't declare capabilities as required
    val gaps = FeatureCheck.check(effects, Set.empty, Nil)
    gaps should have length 1
    gaps.head.feature shouldBe "capabilities"
    gaps.head.required shouldBe false
  }

  "no duplicate gap for feature that is both required and implied" in {
    val effects = Effects.empty.copy(
      groups = List(GroupEffect("grp", system = true)),
    )
    // user-database is both in requires-features and implied by effects
    val gaps = FeatureCheck.check(effects, Set.empty, List("user-database"))
    gaps should have length 1
    gaps.head.feature shouldBe "user-database"
    gaps.head.required shouldBe true
  }

  "no gaps for effects-only packages with no adapters needed" in {
    val effects = Effects.empty.copy(
      directories = List(DirectoryEffect("/var/lib/x", "root", "root", "0755")),
      generators = List(GenerateEffect("bin/gen", "/etc/x.conf", Nil)),
    )
    FeatureCheck.check(effects, Set.empty, Nil) shouldBe empty
  }

  "detect multiple missing features" in {
    val effects = Effects(
      groups = List(GroupEffect("grp", system = true)),
      users = Nil,
      directories = Nil,
      capabilities = List(CapabilityEffect("bin/x", List("cap+ep"))),
      services = List(ServiceEffect("svc", "bin/x", Nil, "u", "g", Nil, "always", Map.empty)),
      generators = Nil,
      firstRun = None,
    )
    val gaps = FeatureCheck.check(effects, Set.empty, List("user-database", "capabilities", "service-registration"))
    gaps should have length 3
    gaps.map(_.feature).toSet shouldBe Set("user-database", "capabilities", "service-registration")
  }

  // --- availableFrom ---

  "parse full adapters.toml" in {
    val input =
      """
        |[user-database]
        |adapter = "useradd"
        |
        |[init]
        |adapter = "systemd"
        |
        |[capabilities]
        |adapter = "linux"
        |
        |[sandbox]
        |adapter = "linux"
        |""".stripMargin

    val result = FeatureCheck.availableFrom(input)
    result.isRight shouldBe true
    result.toOption.get shouldBe Set("user-database", "service-registration", "capabilities", "sandbox")
  }

  "parse partial adapters.toml" in {
    val input =
      """
        |[user-database]
        |adapter = "dscl"
        |""".stripMargin

    val result = FeatureCheck.availableFrom(input)
    result.toOption.get shouldBe Set("user-database")
  }

  "parse empty adapters.toml" in {
    val result = FeatureCheck.availableFrom("")
    result.toOption.get shouldBe empty
  }

  "reject malformed adapters.toml" in {
    FeatureCheck.availableFrom("not [valid").isLeft shouldBe true
  }
}
