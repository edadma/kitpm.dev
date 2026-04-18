package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class UpgradeResolverTests extends AnyFreeSpec with Matchers {

  val target = "x86_64-linux-gnu"

  val repoIndex = RepoIndex("stable", "2026-04-18", "ed25519:key", List(
    RepoPackageEntry("nginx", "1.28.0", ContentHash("sha256", "nginx-new"), target, Scope.System),
    RepoPackageEntry("hello", "2.0.0", ContentHash("sha256", "hello-new"), target, Scope.User),
    RepoPackageEntry("python", "3.12.0", ContentHash("sha256", "python-same"), target, Scope.User),
    RepoPackageEntry("tool", "1.0.0", ContentHash("sha256", "tool-v1"), target, Scope.User),
  ))

  val repos = List((repoIndex, "stable"))

  val installed = List(
    GenerationPackageEntry("nginx", "1.27.0", ContentHash("sha256", "nginx-old"), Scope.System),
    GenerationPackageEntry("hello", "1.0.0", ContentHash("sha256", "hello-old"), Scope.User),
    GenerationPackageEntry("python", "3.12.0", ContentHash("sha256", "python-same"), Scope.User),
  )

  "findUpgrades" - {
    "detects packages with newer versions" in {
      val upgrades = UpgradeResolver.findUpgrades(installed, repos, target)
      upgrades should have length 2
      upgrades.map(_.name).toSet shouldBe Set("nginx", "hello")
    }

    "includes current and available versions" in {
      val upgrades = UpgradeResolver.findUpgrades(installed, repos, target)
      val nginx = upgrades.find(_.name == "nginx").get
      nginx.currentVersion shouldBe "1.27.0"
      nginx.availableVersion shouldBe "1.28.0"
      nginx.currentHash shouldBe ContentHash("sha256", "nginx-old")
      nginx.availableHash shouldBe ContentHash("sha256", "nginx-new")
    }

    "skips packages with same content hash" in {
      val upgrades = UpgradeResolver.findUpgrades(installed, repos, target)
      upgrades.map(_.name) should not contain "python"
    }

    "skips packages not in any repo" in {
      val extra = installed :+ GenerationPackageEntry("local-only", "1.0", ContentHash("sha256", "xxx"), Scope.User)
      val upgrades = UpgradeResolver.findUpgrades(extra, repos, target)
      upgrades.map(_.name) should not contain "local-only"
    }

    "empty installed list produces no upgrades" in {
      UpgradeResolver.findUpgrades(Nil, repos, target) shouldBe empty
    }

    "empty repos produces no upgrades" in {
      UpgradeResolver.findUpgrades(installed, Nil, target) shouldBe empty
    }

    "includes repo name" in {
      val upgrades = UpgradeResolver.findUpgrades(installed, repos, target)
      upgrades.head.repoName shouldBe "stable"
    }
  }

  "findUpgrade" - {
    "finds upgrade for specific package" in {
      val result = UpgradeResolver.findUpgrade("nginx", installed, repos, target)
      result shouldBe defined
      result.get.availableVersion shouldBe "1.28.0"
    }

    "returns None for up-to-date package" in {
      UpgradeResolver.findUpgrade("python", installed, repos, target) shouldBe None
    }

    "returns None for uninstalled package" in {
      UpgradeResolver.findUpgrade("ghost", installed, repos, target) shouldBe None
    }
  }

  "hasUpgrades" - {
    "returns true when upgrades available" in {
      UpgradeResolver.hasUpgrades(installed, repos, target) shouldBe true
    }

    "returns false when everything is current" in {
      val current = List(
        GenerationPackageEntry("python", "3.12.0", ContentHash("sha256", "python-same"), Scope.User),
      )
      UpgradeResolver.hasUpgrades(current, repos, target) shouldBe false
    }

    "returns false for empty installed list" in {
      UpgradeResolver.hasUpgrades(Nil, repos, target) shouldBe false
    }
  }
}
