package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ManifestSerializerTests extends AnyFreeSpec with Matchers {

  val fullManifest: Manifest = Manifest(
    name = "nginx",
    version = "1.27.0",
    target = "x86_64-linux-gnu",
    contentHash = ContentHash("sha256", "1a2b3c4d5e6f"),
    scope = Scope.System,
    requiresFeatures = List("capabilities", "service-registration"),
    deps = List(
      DepRef("libc", "0.3.1", ContentHash("sha256", "aaa111")),
      DepRef("openssl", "3.2.1", ContentHash("sha256", "bbb222")),
    ),
    effects = Effects(
      groups = List(GroupEffect("nginx", system = true)),
      users = List(UserEffect("nginx", "nginx", "/var/lib/nginx", "/sbin/nologin", system = true)),
      directories = List(
        DirectoryEffect("/var/lib/nginx", "nginx", "nginx", "0750"),
        DirectoryEffect("/var/log/nginx", "nginx", "nginx", "0755"),
      ),
      capabilities = List(CapabilityEffect("sbin/nginx", List("cap_net_bind_service+ep"))),
      services = List(ServiceEffect(
        "nginx",
        "sbin/nginx",
        List("-g", "daemon off;"),
        "nginx",
        "nginx",
        List("network"),
        "on-failure",
        Map("NGINX_CONF" -> "/etc/nginx/nginx.conf"),
      )),
      generators = List(GenerateEffect("bin/nginx-genconfig", "/etc/nginx/nginx.conf", List("/etc/kit/system.toml#nginx"))),
      firstRun = Some(FirstRunEffect("bin/nginx-initdb")),
    ),
  )

  "roundtrip: serialize then parse" in {
    val toml = ManifestSerializer.toToml(fullManifest)
    val parsed = ManifestParser.parse(toml)

    parsed.isRight shouldBe true
    val m = parsed.toOption.get

    m.name shouldBe fullManifest.name
    m.version shouldBe fullManifest.version
    m.target shouldBe fullManifest.target
    m.contentHash shouldBe fullManifest.contentHash
    m.scope shouldBe fullManifest.scope
    m.requiresFeatures shouldBe fullManifest.requiresFeatures
    m.deps shouldBe fullManifest.deps
  }

  "roundtrip effects: groups" in {
    val toml = ManifestSerializer.toToml(fullManifest)
    val m = ManifestParser.parse(toml).toOption.get
    m.effects.groups shouldBe fullManifest.effects.groups
  }

  "roundtrip effects: users" in {
    val toml = ManifestSerializer.toToml(fullManifest)
    val m = ManifestParser.parse(toml).toOption.get
    m.effects.users shouldBe fullManifest.effects.users
  }

  "roundtrip effects: directories" in {
    val toml = ManifestSerializer.toToml(fullManifest)
    val m = ManifestParser.parse(toml).toOption.get
    m.effects.directories shouldBe fullManifest.effects.directories
  }

  "roundtrip effects: capabilities" in {
    val toml = ManifestSerializer.toToml(fullManifest)
    val m = ManifestParser.parse(toml).toOption.get
    m.effects.capabilities shouldBe fullManifest.effects.capabilities
  }

  "roundtrip effects: services" in {
    val toml = ManifestSerializer.toToml(fullManifest)
    val m = ManifestParser.parse(toml).toOption.get
    m.effects.services shouldBe fullManifest.effects.services
  }

  "roundtrip effects: generators" in {
    val toml = ManifestSerializer.toToml(fullManifest)
    val m = ManifestParser.parse(toml).toOption.get
    m.effects.generators shouldBe fullManifest.effects.generators
  }

  "roundtrip effects: first-run" in {
    val toml = ManifestSerializer.toToml(fullManifest)
    val m = ManifestParser.parse(toml).toOption.get
    m.effects.firstRun shouldBe fullManifest.effects.firstRun
  }

  "minimal manifest roundtrip" in {
    val minimal = Manifest(
      "hello",
      "1.0.0",
      "aarch64-apple-darwin",
      ContentHash("sha256", "deadbeef"),
      Scope.User,
      Nil,
      Nil,
      Effects.empty,
    )

    val toml = ManifestSerializer.toToml(minimal)
    val m = ManifestParser.parse(toml).toOption.get

    m.name shouldBe "hello"
    m.scope shouldBe Scope.User
    m.deps shouldBe empty
    m.effects shouldBe Effects.empty
  }

  "serialized output includes key fields" in {
    val toml = ManifestSerializer.toToml(fullManifest)
    toml should include("""name = "nginx"""")
    toml should include("""version = "1.27.0"""")
    toml should include("""target = "x86_64-linux-gnu"""")
    toml should include("""content-hash = "sha256-1a2b3c4d5e6f"""")
    toml should include("""scope = "system"""")
    toml should include("[[deps]]")
    toml should include("[[effects.group]]")
    toml should include("[[effects.user]]")
    toml should include("[[effects.directory]]")
    toml should include("[[effects.capability]]")
    toml should include("[[effects.service]]")
    toml should include("[[effects.generate]]")
    toml should include("[effects.first-run]")
  }
}
