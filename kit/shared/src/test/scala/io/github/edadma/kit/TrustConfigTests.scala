package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class TrustConfigTests extends AnyFreeSpec with Matchers {

  val validConfig: String =
    """
      |[[repo]]
      |url = "https://repo.kitpm.dev/stable"
      |trusted-keys = ["ed25519:key1", "ed25519:key2"]
      |priority = 10
      |targets = ["x86_64-linux-gnu", "aarch64-linux-gnu"]
      |
      |[[repo]]
      |url = "https://repo.kitpm.dev/macos"
      |trusted-keys = ["ed25519:key3"]
      |priority = 10
      |targets = ["aarch64-apple-darwin"]
      |""".stripMargin

  "parse a valid repos.toml" in {
    val result = TrustConfigParser.parse(validConfig)
    result.isRight shouldBe true
    val tc = result.toOption.get
    tc.repos should have length 2
  }

  "parse repo fields" in {
    val tc = TrustConfigParser.parse(validConfig).toOption.get
    val stable = tc.repos.head

    stable.url shouldBe "https://repo.kitpm.dev/stable"
    stable.trustedKeys shouldBe List("ed25519:key1", "ed25519:key2")
    stable.priority shouldBe 10
    stable.targets shouldBe List("x86_64-linux-gnu", "aarch64-linux-gnu")
  }

  "parse config with no repos" in {
    val tc = TrustConfigParser.parse("").toOption.get
    tc.repos shouldBe empty
  }

  "parse repo without targets (optional)" in {
    val input =
      """
        |[[repo]]
        |url = "https://example.com/repo"
        |trusted-keys = ["ed25519:abc"]
        |priority = 5
        |""".stripMargin

    val tc = TrustConfigParser.parse(input).toOption.get
    tc.repos should have length 1
    tc.repos.head.targets shouldBe empty
  }

  "reject missing url" in {
    val input =
      """
        |[[repo]]
        |trusted-keys = ["ed25519:abc"]
        |priority = 5
        |""".stripMargin

    val result = TrustConfigParser.parse(input)
    result.isLeft shouldBe true
    result.left.toOption.get should include("missing required field 'url'")
  }

  "reject missing trusted-keys" in {
    val input =
      """
        |[[repo]]
        |url = "https://example.com"
        |priority = 5
        |""".stripMargin

    val result = TrustConfigParser.parse(input)
    result.isLeft shouldBe true
    result.left.toOption.get should include("missing required field 'trusted-keys'")
  }

  "reject missing priority" in {
    val input =
      """
        |[[repo]]
        |url = "https://example.com"
        |trusted-keys = ["ed25519:abc"]
        |""".stripMargin

    val result = TrustConfigParser.parse(input)
    result.isLeft shouldBe true
    result.left.toOption.get should include("missing required field 'priority'")
  }

  "reject malformed TOML" in {
    TrustConfigParser.parse("not [valid").isLeft shouldBe true
  }
}
