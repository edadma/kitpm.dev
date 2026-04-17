package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class PlanOrderTests extends AnyFreeSpec with Matchers {

  val platform = "x86_64-linux-gnu"

  def pkg(
      name: String,
      hash: String,
      effects: Effects = Effects.empty,
      deps: List[DepRef] = Nil,
  ): Manifest =
    Manifest(name, "1.0", platform, ContentHash("sha256", hash), Scope.System, Nil, deps, effects)

  "type ordering: groups before users before directories before generators before services" in {
    val m = pkg(
      "all",
      "all-hash",
      Effects(
        groups = List(GroupEffect("grp", system = true)),
        users = List(UserEffect("usr", "grp", "/home/usr", "/bin/sh", system = true)),
        directories = List(DirectoryEffect("/var/lib/x", "usr", "grp", "0755")),
        capabilities = List(CapabilityEffect("bin/x", List("cap_net_bind+ep"))),
        services = List(ServiceEffect("svc", "bin/x", Nil, "usr", "grp", Nil, "on-failure", Map.empty)),
        generators = List(GenerateEffect("bin/gen", "/etc/x.conf", Nil)),
        firstRun = Some(FirstRunEffect("bin/init")),
      ),
    )
    val closure = Resolution.Closure(Map(m.contentHash -> m))
    val plan = PlanOrder.orderAdditions(closure)

    val types = plan.map(_.getClass.getSimpleName)
    types shouldBe List(
      "PlanGroup",
      "PlanUser",
      "PlanDirectory",
      "PlanGenerate",
      "PlanCapability",
      "PlanService",
      "PlanFirstRun",
    )
  }

  "dependency ordering within a type" in {
    val base = pkg(
      "base",
      "base-hash",
      Effects.empty.copy(groups = List(GroupEffect("base-grp", system = true))),
    )
    val mid = pkg(
      "mid",
      "mid-hash",
      Effects.empty.copy(groups = List(GroupEffect("mid-grp", system = true))),
      deps = List(DepRef("base", "1.0", base.contentHash)),
    )
    val top = pkg(
      "top",
      "top-hash",
      Effects.empty.copy(groups = List(GroupEffect("top-grp", system = true))),
      deps = List(DepRef("mid", "1.0", mid.contentHash)),
    )

    val closure = Resolution.Closure(
      Map(base.contentHash -> base, mid.contentHash -> mid, top.contentHash -> top),
    )
    val plan = PlanOrder.orderAdditions(closure)
    val groupNames = plan.collect { case PlanOrder.PlanGroup(g, _) => g.name }

    groupNames shouldBe List("base-grp", "mid-grp", "top-grp")
  }

  "empty closure produces empty plan" in {
    val m = pkg("empty", "empty-hash")
    val closure = Resolution.Closure(Map(m.contentHash -> m))
    PlanOrder.orderAdditions(closure) shouldBe empty
  }

  "multiple effects from same package stay grouped by type" in {
    val m = pkg(
      "multi",
      "multi-hash",
      Effects.empty.copy(
        directories = List(
          DirectoryEffect("/var/lib/a", "root", "root", "0755"),
          DirectoryEffect("/var/lib/b", "root", "root", "0755"),
        ),
        groups = List(GroupEffect("grp1", system = true)),
      ),
    )
    val closure = Resolution.Closure(Map(m.contentHash -> m))
    val plan = PlanOrder.orderAdditions(closure)

    // Group should come before both directories
    val types = plan.map(_.getClass.getSimpleName)
    types shouldBe List("PlanGroup", "PlanDirectory", "PlanDirectory")
  }
}
