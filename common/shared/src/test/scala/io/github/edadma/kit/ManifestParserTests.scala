package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ManifestParserTests extends AnyFreeSpec with Matchers {

  val fullManifest: String =
    """
      |name = "nginx"
      |version = "1.27.0"
      |target = "x86_64-linux-gnu"
      |content-hash = "sha256-1a2b3c4d5e6f"
      |scope = "system"
      |
      |requires-features = ["capabilities", "service-registration"]
      |
      |[[deps]]
      |name = "libc"
      |version = "0.3.1"
      |content-hash = "sha256-aaa111"
      |
      |[[deps]]
      |name = "openssl"
      |version = "3.2.1"
      |content-hash = "sha256-bbb222"
      |
      |[[effects.group]]
      |name = "nginx"
      |system = true
      |
      |[[effects.user]]
      |name = "nginx"
      |group = "nginx"
      |home = "/var/lib/nginx"
      |shell = "/sbin/nologin"
      |system = true
      |
      |[[effects.directory]]
      |path = "/var/lib/nginx"
      |owner = "nginx"
      |group = "nginx"
      |mode = "0750"
      |
      |[[effects.directory]]
      |path = "/var/log/nginx"
      |owner = "nginx"
      |group = "nginx"
      |mode = "0755"
      |
      |[[effects.capability]]
      |binary = "sbin/nginx"
      |caps = ["cap_net_bind_service+ep"]
      |
      |[[effects.service]]
      |name = "nginx"
      |binary = "sbin/nginx"
      |args = ["-g", "daemon off;"]
      |user = "nginx"
      |group = "nginx"
      |requires = ["network"]
      |restart = "on-failure"
      |
      |[effects.service.environment]
      |NGINX_CONF = "/etc/nginx/nginx.conf"
      |
      |[[effects.generate]]
      |generator = "bin/nginx-genconfig"
      |output = "/etc/nginx/nginx.conf"
      |inputs = ["/etc/kit/system.toml#nginx"]
      |
      |[effects.first-run]
      |binary = "bin/nginx-initdb"
      |""".stripMargin

  "parse a full manifest" in {
    val result = ManifestParser.parse(fullManifest)
    result shouldBe a[Right[?, ?]]
    val m = result.toOption.get

    m.name shouldBe "nginx"
    m.version shouldBe "1.27.0"
    m.target shouldBe "x86_64-linux-gnu"
    m.contentHash shouldBe ContentHash("sha256", "1a2b3c4d5e6f")
    m.scope shouldBe Scope.System
    m.requiresFeatures shouldBe List("capabilities", "service-registration")
  }

  "parse dependencies" in {
    val m = ManifestParser.parse(fullManifest).toOption.get

    m.deps should have length 2
    m.deps.head shouldBe DepRef("libc", "0.3.1", ContentHash("sha256", "aaa111"))
    m.deps(1) shouldBe DepRef("openssl", "3.2.1", ContentHash("sha256", "bbb222"))
  }

  "parse group effects" in {
    val m = ManifestParser.parse(fullManifest).toOption.get

    m.effects.groups should have length 1
    m.effects.groups.head shouldBe GroupEffect("nginx", system = true)
  }

  "parse user effects" in {
    val m = ManifestParser.parse(fullManifest).toOption.get

    m.effects.users should have length 1
    val u = m.effects.users.head
    u.name shouldBe "nginx"
    u.group shouldBe "nginx"
    u.home shouldBe "/var/lib/nginx"
    u.shell shouldBe "/sbin/nologin"
    u.system shouldBe true
  }

  "parse directory effects" in {
    val m = ManifestParser.parse(fullManifest).toOption.get

    m.effects.directories should have length 2
    m.effects.directories.head shouldBe DirectoryEffect("/var/lib/nginx", "nginx", "nginx", "0750")
    m.effects.directories(1) shouldBe DirectoryEffect("/var/log/nginx", "nginx", "nginx", "0755")
  }

  "parse capability effects" in {
    val m = ManifestParser.parse(fullManifest).toOption.get

    m.effects.capabilities should have length 1
    m.effects.capabilities.head shouldBe CapabilityEffect("sbin/nginx", List("cap_net_bind_service+ep"))
  }

  "parse service effects" in {
    val m = ManifestParser.parse(fullManifest).toOption.get

    m.effects.services should have length 1
    val s = m.effects.services.head
    s.name shouldBe "nginx"
    s.binary shouldBe "sbin/nginx"
    s.args shouldBe List("-g", "daemon off;")
    s.user shouldBe "nginx"
    s.group shouldBe "nginx"
    s.requires shouldBe List("network")
    s.restart shouldBe "on-failure"
    s.environment shouldBe Map("NGINX_CONF" -> "/etc/nginx/nginx.conf")
  }

  "parse generate effects" in {
    val m = ManifestParser.parse(fullManifest).toOption.get

    m.effects.generators should have length 1
    val g = m.effects.generators.head
    g.generator shouldBe "bin/nginx-genconfig"
    g.output shouldBe "/etc/nginx/nginx.conf"
    g.inputs shouldBe List("/etc/kit/system.toml#nginx")
  }

  "parse first-run effect" in {
    val m = ManifestParser.parse(fullManifest).toOption.get

    m.effects.firstRun shouldBe Some(FirstRunEffect("bin/nginx-initdb"))
  }

  // --- Minimal manifest ---

  "parse a minimal manifest (no effects, no deps)" in {
    val input =
      """
        |name = "hello"
        |version = "1.0.0"
        |target = "aarch64-apple-darwin"
        |content-hash = "sha256-deadbeef"
        |scope = "user"
        |""".stripMargin

    val m = ManifestParser.parse(input).toOption.get

    m.name shouldBe "hello"
    m.scope shouldBe Scope.User
    m.deps shouldBe empty
    m.effects shouldBe Effects.empty
    m.requiresFeatures shouldBe empty
  }

  // --- Error cases ---

  "reject missing name" in {
    val input =
      """
        |version = "1.0.0"
        |target = "x86_64-linux-gnu"
        |content-hash = "sha256-abc"
        |scope = "system"
        |""".stripMargin

    ManifestParser.parse(input) shouldBe Left("missing required field: 'name'")
  }

  "reject invalid scope" in {
    val input =
      """
        |name = "bad"
        |version = "1.0.0"
        |target = "x86_64-linux-gnu"
        |content-hash = "sha256-abc"
        |scope = "global"
        |""".stripMargin

    ManifestParser.parse(input) shouldBe Left("invalid scope: 'global' (expected 'system' or 'user')")
  }

  "reject invalid content hash format" in {
    val input =
      """
        |name = "bad"
        |version = "1.0.0"
        |target = "x86_64-linux-gnu"
        |content-hash = "nohyphen"
        |scope = "system"
        |""".stripMargin

    val result = ManifestParser.parse(input)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("invalid content hash")
  }

  "reject malformed TOML" in {
    ManifestParser.parse("not = [valid toml") shouldBe a[Left[?, ?]]
  }

  // --- ContentHash ---

  "ContentHash.parse valid" in {
    ContentHash.parse("sha256-abc123") shouldBe Right(ContentHash("sha256", "abc123"))
  }

  "ContentHash.parse invalid" in {
    ContentHash.parse("nohyphen") shouldBe a[Left[?, ?]]
    ContentHash.parse("-digest") shouldBe a[Left[?, ?]]
    ContentHash.parse("algo-") shouldBe a[Left[?, ?]]
  }

  "ContentHash.toString roundtrip" in {
    val h = ContentHash("sha256", "abc123")
    h.toString shouldBe "sha256-abc123"
  }

  // --- Scope ---

  "Scope.parse valid" in {
    Scope.parse("system") shouldBe Right(Scope.System)
    Scope.parse("user") shouldBe Right(Scope.User)
  }

  "Scope.parse invalid" in {
    Scope.parse("root") shouldBe a[Left[?, ?]]
  }
}
