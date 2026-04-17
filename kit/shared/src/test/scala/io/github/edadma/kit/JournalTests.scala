package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class JournalTests extends AnyFreeSpec with Matchers {

  // --- Forward planning ---

  "plan forward: add group and user" in {
    val diff = EffectOps.EffectDiff(
      addGroups = List(GroupEffect("nginx", system = true)),
      removeGroups = Nil,
      addUsers = List(UserEffect("nginx", "nginx", "/var/lib/nginx", "/sbin/nologin", system = true)),
      removeUsers = Nil,
      addDirectories = Nil,
      removeDirectories = Nil,
      addCapabilities = Nil,
      removeCapabilities = Nil,
      addServices = Nil,
      removeServices = Nil,
      addGenerators = Nil,
      removeGenerators = Nil,
      addFirstRun = None,
      removeFirstRun = None,
    )

    val plan = Journal.planForward(diff)
    plan should have length 2
    plan(0).effectType shouldBe "group"
    plan(0).action shouldBe Journal.Action.Apply
    plan(0).key shouldBe "nginx"
    plan(1).effectType shouldBe "user"
    plan(1).action shouldBe Journal.Action.Apply
    plan(1).key shouldBe "nginx"
  }

  "plan forward: removals come before additions" in {
    val diff = EffectOps.EffectDiff(
      addGroups = List(GroupEffect("new-grp", system = true)),
      removeGroups = List(GroupEffect("old-grp", system = true)),
      addUsers = Nil,
      removeUsers = Nil,
      addDirectories = Nil,
      removeDirectories = Nil,
      addCapabilities = Nil,
      removeCapabilities = Nil,
      addServices = List(ServiceEffect("new-svc", "bin/new", Nil, "a", "a", Nil, "always", Map.empty)),
      removeServices = List(ServiceEffect("old-svc", "bin/old", Nil, "a", "a", Nil, "always", Map.empty)),
      addGenerators = Nil,
      removeGenerators = Nil,
      addFirstRun = None,
      removeFirstRun = None,
    )

    val plan = Journal.planForward(diff)
    val inverses = plan.filter(_.action == Journal.Action.Inverse)
    val applies = plan.filter(_.action == Journal.Action.Apply)

    // All inverses should come before all applies
    val lastInverseIdx = plan.lastIndexWhere(_.action == Journal.Action.Inverse)
    val firstApplyIdx = plan.indexWhere(_.action == Journal.Action.Apply)
    lastInverseIdx should be < firstApplyIdx
  }

  "plan forward: removal order is reverse dependency (services before groups)" in {
    val diff = EffectOps.EffectDiff(
      addGroups = Nil,
      removeGroups = List(GroupEffect("grp", system = true)),
      addUsers = Nil,
      removeUsers = List(UserEffect("usr", "grp", "/home", "/bin/sh", system = true)),
      addDirectories = Nil,
      removeDirectories = Nil,
      addCapabilities = Nil,
      removeCapabilities = Nil,
      addServices = Nil,
      removeServices = List(ServiceEffect("svc", "bin/x", Nil, "usr", "grp", Nil, "always", Map.empty)),
      addGenerators = Nil,
      removeGenerators = Nil,
      addFirstRun = None,
      removeFirstRun = None,
    )

    val plan = Journal.planForward(diff)
    val types = plan.map(_.effectType)
    // Service removal before user removal before group removal
    types shouldBe List("service", "user", "group")
  }

  "plan forward: addition order follows dependency (groups before users before services)" in {
    val diff = EffectOps.EffectDiff(
      addGroups = List(GroupEffect("grp", system = true)),
      removeGroups = Nil,
      addUsers = List(UserEffect("usr", "grp", "/home", "/bin/sh", system = true)),
      removeUsers = Nil,
      addDirectories = List(DirectoryEffect("/var/lib/x", "usr", "grp", "0755")),
      removeDirectories = Nil,
      addCapabilities = Nil,
      removeCapabilities = Nil,
      addServices = List(ServiceEffect("svc", "bin/x", Nil, "usr", "grp", Nil, "always", Map.empty)),
      removeServices = Nil,
      addGenerators = List(GenerateEffect("bin/gen", "/etc/x.conf", Nil)),
      removeGenerators = Nil,
      addFirstRun = None,
      removeFirstRun = None,
    )

    val plan = Journal.planForward(diff)
    val types = plan.map(_.effectType)
    types shouldBe List("group", "user", "directory", "generate", "service")
  }

  "plan forward: empty diff produces empty plan" in {
    val diff = EffectOps.diff(Effects.empty, Effects.empty)
    Journal.planForward(diff) shouldBe empty
  }

  "plan forward: user detail fields are captured" in {
    val diff = EffectOps.EffectDiff(
      addGroups = Nil,
      removeGroups = Nil,
      addUsers = List(UserEffect("nginx", "nginx", "/var/lib/nginx", "/sbin/nologin", system = true)),
      removeUsers = Nil,
      addDirectories = Nil,
      removeDirectories = Nil,
      addCapabilities = Nil,
      removeCapabilities = Nil,
      addServices = Nil,
      removeServices = Nil,
      addGenerators = Nil,
      removeGenerators = Nil,
      addFirstRun = None,
      removeFirstRun = None,
    )

    val plan = Journal.planForward(diff)
    val entry = plan.head
    entry.detail("group") shouldBe "nginx"
    entry.detail("home") shouldBe "/var/lib/nginx"
    entry.detail("shell") shouldBe "/sbin/nologin"
    entry.detail("system") shouldBe "true"
  }

  // --- Rollback planning ---

  "rollback reverses the forward plan" in {
    val forward = List(
      Journal.Entry("group", Journal.Action.Apply, "nginx", Map("system" -> "true")),
      Journal.Entry("user", Journal.Action.Apply, "nginx", Map("group" -> "nginx")),
      Journal.Entry("service", Journal.Action.Apply, "nginx", Map("binary" -> "sbin/nginx")),
    )

    val rollback = Journal.planRollback(forward)
    rollback should have length 3
    rollback(0).effectType shouldBe "service"
    rollback(0).action shouldBe Journal.Action.Inverse
    rollback(1).effectType shouldBe "user"
    rollback(1).action shouldBe Journal.Action.Inverse
    rollback(2).effectType shouldBe "group"
    rollback(2).action shouldBe Journal.Action.Inverse
  }

  "rollback of inverse becomes apply" in {
    val forward = List(
      Journal.Entry("service", Journal.Action.Inverse, "old-svc", Map.empty),
      Journal.Entry("service", Journal.Action.Apply, "new-svc", Map.empty),
    )

    val rollback = Journal.planRollback(forward)
    rollback(0).key shouldBe "new-svc"
    rollback(0).action shouldBe Journal.Action.Inverse
    rollback(1).key shouldBe "old-svc"
    rollback(1).action shouldBe Journal.Action.Apply
  }

  "rollback of empty plan is empty" in {
    Journal.planRollback(Nil) shouldBe empty
  }

  // --- Serialization roundtrip ---

  "serialize and deserialize" in {
    val entries = List(
      Journal.Entry("group", Journal.Action.Apply, "nginx", Map("system" -> "true")),
      Journal.Entry("user", Journal.Action.Apply, "nginx", Map("group" -> "nginx", "home" -> "/var/lib/nginx")),
      Journal.Entry("service", Journal.Action.Inverse, "old-svc", Map("binary" -> "bin/old")),
    )

    val serialized = Journal.serialize(entries)
    val deserialized = Journal.deserialize(serialized)

    deserialized shouldBe a[Right[?, ?]]
    val parsed = deserialized.toOption.get
    parsed should have length 3

    parsed(0).effectType shouldBe "group"
    parsed(0).action shouldBe Journal.Action.Apply
    parsed(0).key shouldBe "nginx"
    parsed(0).detail("system") shouldBe "true"

    parsed(1).effectType shouldBe "user"
    parsed(1).detail("group") shouldBe "nginx"
    parsed(1).detail("home") shouldBe "/var/lib/nginx"

    parsed(2).effectType shouldBe "service"
    parsed(2).action shouldBe Journal.Action.Inverse
  }

  "deserialize empty string" in {
    Journal.deserialize("") shouldBe Right(Nil)
  }

  "serialize with no detail" in {
    val entries = List(Journal.Entry("group", Journal.Action.Inverse, "nginx", Map.empty))
    val serialized = Journal.serialize(entries)
    serialized shouldBe "INVERSE group nginx"
  }
}
