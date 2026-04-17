package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ManifestValidatorTests extends AnyFreeSpec with Matchers {

  val platform = "x86_64-linux-gnu"

  def pkg(effects: Effects, scope: Scope = Scope.System): Manifest =
    Manifest("test", "1.0", platform, ContentHash("sha256", "abc"), scope, Nil, Nil, effects, Nil)

  // --- Valid manifests ---

  "accept a well-formed manifest" in {
    val m = pkg(Effects(
      groups = List(GroupEffect("nginx", system = true)),
      users = List(UserEffect("nginx", "nginx", "/var/lib/nginx", "/sbin/nologin", system = true)),
      directories = List(DirectoryEffect("/var/lib/nginx", "nginx", "nginx", "0750")),
      capabilities = List(CapabilityEffect("sbin/nginx", List("cap_net_bind+ep"))),
      services = List(ServiceEffect("nginx", "sbin/nginx", Nil, "nginx", "nginx", Nil, "on-failure", Map.empty)),
      generators = List(GenerateEffect("bin/gen", "/etc/nginx/nginx.conf", Nil)),
      firstRun = None,
    ))
    ManifestValidator.validate(m) shouldBe empty
  }

  "accept root as owner/group without declaration" in {
    val m = pkg(Effects.empty.copy(
      directories = List(DirectoryEffect("/var/log", "root", "root", "0755")),
    ))
    ManifestValidator.validate(m) shouldBe empty
  }

  "accept empty effects" in {
    ManifestValidator.validate(pkg(Effects.empty)) shouldBe empty
  }

  // --- User references undeclared group ---

  "reject user referencing undeclared group" in {
    val m = pkg(Effects.empty.copy(
      users = List(UserEffect("nginx", "missing-group", "/var/lib/nginx", "/sbin/nologin", system = true)),
    ))
    val errors = ManifestValidator.validate(m)
    errors should have length 1
    errors.head should include("undeclared group 'missing-group'")
  }

  // --- Directory references ---

  "reject directory with undeclared owner" in {
    val m = pkg(Effects.empty.copy(
      groups = List(GroupEffect("grp", system = true)),
      directories = List(DirectoryEffect("/var/lib/x", "ghost", "grp", "0755")),
    ))
    val errors = ManifestValidator.validate(m)
    errors should have length 1
    errors.head should include("undeclared owner 'ghost'")
  }

  "reject directory with undeclared group" in {
    val m = pkg(Effects.empty.copy(
      users = List(UserEffect("usr", "root", "/home/usr", "/bin/sh", system = true)),
      directories = List(DirectoryEffect("/var/lib/x", "usr", "ghost", "0755")),
    ))
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("undeclared group 'ghost'")) shouldBe true
  }

  // --- Service references ---

  "reject service with undeclared user" in {
    val m = pkg(Effects.empty.copy(
      groups = List(GroupEffect("grp", system = true)),
      services = List(ServiceEffect("svc", "bin/x", Nil, "ghost", "grp", Nil, "always", Map.empty)),
    ))
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("undeclared user 'ghost'")) shouldBe true
  }

  "reject service with undeclared group" in {
    val m = pkg(Effects.empty.copy(
      users = List(UserEffect("usr", "root", "/home", "/bin/sh", system = true)),
      services = List(ServiceEffect("svc", "bin/x", Nil, "usr", "ghost", Nil, "always", Map.empty)),
    ))
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("undeclared group 'ghost'")) shouldBe true
  }

  // --- Mode validation ---

  "reject invalid octal mode" in {
    val m = pkg(Effects.empty.copy(
      directories = List(DirectoryEffect("/var/lib/x", "root", "root", "999")),
    ))
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("invalid mode")) shouldBe true
  }

  "reject empty mode" in {
    val m = pkg(Effects.empty.copy(
      directories = List(DirectoryEffect("/var/lib/x", "root", "root", "")),
    ))
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("invalid mode")) shouldBe true
  }

  "accept valid octal modes" in {
    for mode <- List("0755", "0644", "777", "0700") do
      val m = pkg(Effects.empty.copy(
        directories = List(DirectoryEffect("/var/lib/x", "root", "root", mode)),
      ))
      ManifestValidator.validate(m) shouldBe empty

  }

  // --- Generator/directory collision ---

  "reject generator output colliding with directory path" in {
    val m = pkg(Effects.empty.copy(
      directories = List(DirectoryEffect("/etc/nginx", "root", "root", "0755")),
      generators = List(GenerateEffect("bin/gen", "/etc/nginx", Nil)),
    ))
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("conflicts with a declared directory")) shouldBe true
  }

  // --- Capability validation ---

  "reject capability with empty binary" in {
    val m = pkg(Effects.empty.copy(
      capabilities = List(CapabilityEffect("", List("cap_net_bind+ep"))),
    ))
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("empty binary")) shouldBe true
  }

  "reject capability with empty caps list" in {
    val m = pkg(Effects.empty.copy(
      capabilities = List(CapabilityEffect("bin/x", Nil)),
    ))
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("empty caps list")) shouldBe true
  }

  // --- First-run validation ---

  "reject first-run with empty binary" in {
    val m = pkg(Effects.empty.copy(firstRun = Some(FirstRunEffect(""))))
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("empty binary")) shouldBe true
  }

  // --- Scope constraints ---

  "reject group effects in user-scoped package" in {
    val m = pkg(
      Effects.empty.copy(groups = List(GroupEffect("grp", system = true))),
      scope = Scope.User,
    )
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("user-scoped package may not declare group")) shouldBe true
  }

  "reject system users in user-scoped package" in {
    val m = pkg(
      Effects.empty.copy(users = List(UserEffect("usr", "root", "/home", "/bin/sh", system = true))),
      scope = Scope.User,
    )
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("may not declare system users")) shouldBe true
  }

  "reject capabilities in user-scoped package" in {
    val m = pkg(
      Effects.empty.copy(capabilities = List(CapabilityEffect("bin/x", List("cap_net_bind+ep")))),
      scope = Scope.User,
    )
    val errors = ManifestValidator.validate(m)
    errors.exists(_.contains("may not declare capability")) shouldBe true
  }

  // --- Multiple errors ---

  "report multiple errors at once" in {
    val m = pkg(Effects.empty.copy(
      users = List(UserEffect("usr", "missing", "/home", "/bin/sh", system = true)),
      directories = List(DirectoryEffect("/var", "ghost", "root", "999")),
    ))
    val errors = ManifestValidator.validate(m)
    errors.length should be >= 3 // undeclared group, undeclared owner, invalid mode
  }
}
