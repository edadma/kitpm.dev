package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class EffectOpsTests extends AnyFreeSpec with Matchers {

  val platform = "x86_64-linux-gnu"

  def pkg(name: String, hash: String, effects: Effects = Effects.empty, deps: List[DepRef] = Nil): Manifest =
    Manifest(name, "1.0", platform, ContentHash("sha256", hash), Scope.System, Nil, deps, effects, Nil)

  // --- Merging ---

  "merge effects from a closure" in {
    val a = pkg(
      "a",
      "a-hash",
      Effects.empty.copy(groups = List(GroupEffect("grpA", system = true))),
    )
    val b = pkg(
      "b",
      "b-hash",
      Effects.empty.copy(groups = List(GroupEffect("grpB", system = false))),
    )
    val closure = Resolution.Closure(Map(a.contentHash -> a, b.contentHash -> b))

    val merged = EffectOps.merge(closure)
    merged.groups should have length 2
    merged.groups.map(_.name).toSet shouldBe Set("grpA", "grpB")
  }

  "merge with empty effects" in {
    val a = pkg("a", "a-hash")
    val closure = Resolution.Closure(Map(a.contentHash -> a))
    EffectOps.merge(closure) shouldBe Effects.empty
  }

  // --- Conflict detection ---

  "no conflicts in clean effects" in {
    val effects = Effects(
      groups = List(GroupEffect("nginx", system = true)),
      users = List(UserEffect("nginx", "nginx", "/var/lib/nginx", "/sbin/nologin", system = true)),
      directories = List(DirectoryEffect("/var/lib/nginx", "nginx", "nginx", "0750")),
      capabilities = Nil,
      services = List(ServiceEffect("nginx", "sbin/nginx", Nil, "nginx", "nginx", Nil, "on-failure", Map.empty)),
      generators = List(GenerateEffect("bin/genconfig", "/etc/nginx/nginx.conf", Nil)),
      firstRun = None,
    )
    EffectOps.detectConflicts(effects) shouldBe empty
  }

  "detect duplicate group names" in {
    val effects = Effects.empty.copy(groups =
      List(GroupEffect("nginx", system = true), GroupEffect("nginx", system = false)),
    )
    val conflicts = EffectOps.detectConflicts(effects)
    conflicts should have length 1
    conflicts.head should include("duplicate group")
  }

  "detect duplicate user names" in {
    val effects = Effects.empty.copy(users = List(
      UserEffect("nginx", "nginx", "/a", "/bin/sh", system = true),
      UserEffect("nginx", "www", "/b", "/bin/sh", system = false),
    ))
    val conflicts = EffectOps.detectConflicts(effects)
    conflicts should have length 1
    conflicts.head should include("duplicate user")
  }

  "detect duplicate service names" in {
    val effects = Effects.empty.copy(services = List(
      ServiceEffect("web", "bin/a", Nil, "a", "a", Nil, "always", Map.empty),
      ServiceEffect("web", "bin/b", Nil, "b", "b", Nil, "always", Map.empty),
    ))
    val conflicts = EffectOps.detectConflicts(effects)
    conflicts should have length 1
    conflicts.head should include("duplicate service")
  }

  "detect duplicate generator outputs" in {
    val effects = Effects.empty.copy(generators = List(
      GenerateEffect("bin/gen1", "/etc/app.conf", Nil),
      GenerateEffect("bin/gen2", "/etc/app.conf", Nil),
    ))
    val conflicts = EffectOps.detectConflicts(effects)
    conflicts should have length 1
    conflicts.head should include("duplicate generator output")
  }

  "detect multiple conflicts at once" in {
    val effects = Effects.empty.copy(
      groups = List(GroupEffect("dup", system = true), GroupEffect("dup", system = true)),
      services = List(
        ServiceEffect("svc", "bin/a", Nil, "a", "a", Nil, "always", Map.empty),
        ServiceEffect("svc", "bin/b", Nil, "b", "b", Nil, "always", Map.empty),
      ),
    )
    EffectOps.detectConflicts(effects) should have length 2
  }

  // --- Diffing ---

  "diff: adding effects from empty" in {
    val newEffects = Effects.empty.copy(
      groups = List(GroupEffect("nginx", system = true)),
      users = List(UserEffect("nginx", "nginx", "/var/lib/nginx", "/sbin/nologin", system = true)),
    )
    val d = EffectOps.diff(Effects.empty, newEffects)
    d.addGroups shouldBe List(GroupEffect("nginx", system = true))
    d.addUsers should have length 1
    d.removeGroups shouldBe empty
    d.removeUsers shouldBe empty
  }

  "diff: removing effects to empty" in {
    val oldEffects = Effects.empty.copy(
      services = List(ServiceEffect("nginx", "sbin/nginx", Nil, "nginx", "nginx", Nil, "on-failure", Map.empty)),
    )
    val d = EffectOps.diff(oldEffects, Effects.empty)
    d.removeServices should have length 1
    d.addServices shouldBe empty
  }

  "diff: no change" in {
    val effects = Effects.empty.copy(groups = List(GroupEffect("nginx", system = true)))
    val d = EffectOps.diff(effects, effects)
    d.isEmpty shouldBe true
  }

  "diff: swap service" in {
    val old = Effects.empty.copy(
      services = List(ServiceEffect("web", "bin/old", Nil, "a", "a", Nil, "always", Map.empty)),
    )
    val nw = Effects.empty.copy(
      services = List(ServiceEffect("web", "bin/new", Nil, "a", "a", Nil, "always", Map.empty)),
    )
    val d = EffectOps.diff(old, nw)
    d.removeServices should have length 1
    d.addServices should have length 1
    d.removeServices.head.binary shouldBe "bin/old"
    d.addServices.head.binary shouldBe "bin/new"
  }

  // --- Path substitution ---

  "substitute root on effects" in {
    val effects = Effects.empty.copy(
      users = List(UserEffect("nginx", "nginx", "/var/lib/nginx", "/sbin/nologin", system = true)),
      directories = List(DirectoryEffect("/var/lib/nginx", "nginx", "nginx", "0750")),
      generators = List(GenerateEffect("bin/gen", "/etc/nginx/nginx.conf", List("/etc/kit/system.toml#nginx"))),
    )

    val subbed = EffectOps.substituteRoot(effects, "/usr/local/kit")
    subbed.users.head.home shouldBe "/usr/local/kit/var/lib/nginx"
    subbed.directories.head.path shouldBe "/usr/local/kit/var/lib/nginx"
    subbed.generators.head.output shouldBe "/usr/local/kit/etc/nginx/nginx.conf"
    subbed.generators.head.inputs.head shouldBe "/usr/local/kit/etc/kit/system.toml#nginx"
  }

  "substitute root=/ is identity" in {
    val effects = Effects.empty.copy(
      directories = List(DirectoryEffect("/var/lib/nginx", "nginx", "nginx", "0750")),
    )
    val subbed = EffectOps.substituteRoot(effects, "/")
    subbed.directories.head.path shouldBe "/var/lib/nginx"
  }

  "substitute root with trailing slash" in {
    val effects = Effects.empty.copy(
      directories = List(DirectoryEffect("/var/log", "root", "root", "0755")),
    )
    val subbed = EffectOps.substituteRoot(effects, "/prefix/")
    subbed.directories.head.path shouldBe "/prefix/var/log"
  }
}
