package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class SystemConfigTests extends AnyFreeSpec with Matchers {

  val sampleConfig: String =
    """
      |[nginx]
      |worker_processes = 4
      |listen_port = 443
      |server_name = "kit.example.edu"
      |
      |[postgres]
      |max_connections = 100
      |shared_buffers = "256MB"
      |
      |[networking]
      |hostname = "kit-lab-03"
      |""".stripMargin

  // --- parseInputRef ---

  "parse input ref with section" in {
    SystemConfig.parseInputRef("/etc/kit/system.toml#nginx") shouldBe Some("nginx")
  }

  "parse input ref with nested path" in {
    SystemConfig.parseInputRef("/usr/local/kit/etc/kit/system.toml#postgres") shouldBe Some("postgres")
  }

  "parse input ref without section returns None" in {
    SystemConfig.parseInputRef("/etc/kit/system.toml") shouldBe None
  }

  "parse input ref for non-system.toml returns None" in {
    SystemConfig.parseInputRef("/etc/kit/repos.toml#something") shouldBe None
  }

  // --- parse and extractSection ---

  "extract nginx section" in {
    val doc = SystemConfig.parse(sampleConfig).toOption.get
    val section = SystemConfig.extractSection(doc, "nginx")
    section shouldBe defined
    val values = section.get
    values("worker_processes") shouldBe "4"
    values("listen_port") shouldBe "443"
    values("server_name") shouldBe "kit.example.edu"
  }

  "extract postgres section" in {
    val doc = SystemConfig.parse(sampleConfig).toOption.get
    val section = SystemConfig.extractSection(doc, "postgres")
    section shouldBe defined
    section.get("max_connections") shouldBe "100"
    section.get("shared_buffers") shouldBe "256MB"
  }

  "extract missing section returns None" in {
    val doc = SystemConfig.parse(sampleConfig).toOption.get
    SystemConfig.extractSection(doc, "redis") shouldBe None
  }

  // --- extractInputs ---

  "extract inputs for generator" in {
    val doc = SystemConfig.parse(sampleConfig).toOption.get
    val inputs = List("/etc/kit/system.toml#nginx", "/etc/kit/system.toml#networking")
    val result = SystemConfig.extractInputs(doc, inputs)

    result should have size 2
    result("nginx")("worker_processes") shouldBe "4"
    result("networking")("hostname") shouldBe "kit-lab-03"
  }

  "extract inputs skips non-system.toml refs" in {
    val doc = SystemConfig.parse(sampleConfig).toOption.get
    val inputs = List("/etc/kit/system.toml#nginx", "/some/other/file.conf")
    val result = SystemConfig.extractInputs(doc, inputs)
    result should have size 1
  }

  "extract inputs with missing section omits it" in {
    val doc = SystemConfig.parse(sampleConfig).toOption.get
    val inputs = List("/etc/kit/system.toml#redis")
    SystemConfig.extractInputs(doc, inputs) shouldBe empty
  }

  "extract inputs deduplicates section refs" in {
    val doc = SystemConfig.parse(sampleConfig).toOption.get
    val inputs = List("/etc/kit/system.toml#nginx", "/etc/kit/system.toml#nginx")
    val result = SystemConfig.extractInputs(doc, inputs)
    result should have size 1
  }

  // --- sectionFingerprint ---

  "fingerprint is stable and sorted" in {
    val a = SystemConfig.sectionFingerprint(Map("z" -> "1", "a" -> "2", "m" -> "3"))
    val b = SystemConfig.sectionFingerprint(Map("m" -> "3", "z" -> "1", "a" -> "2"))
    a shouldBe b
    a shouldBe "a=2\nm=3\nz=1"
  }

  "fingerprint of empty map" in {
    SystemConfig.sectionFingerprint(Map.empty) shouldBe ""
  }

  // --- parse errors ---

  "reject malformed TOML" in {
    SystemConfig.parse("not [valid").isLeft shouldBe true
  }

  // --- boolean values ---

  "extract boolean values as strings" in {
    val input =
      """
        |[feature]
        |enabled = true
        |debug = false
        |""".stripMargin

    val doc = SystemConfig.parse(input).toOption.get
    val section = SystemConfig.extractSection(doc, "feature").get
    section("enabled") shouldBe "true"
    section("debug") shouldBe "false"
  }
}
