package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class CLIFormatTests extends AnyFreeSpec with Matchers {

  "formatList" - {
    "format empty list" in {
      CLIFormat.formatList(Nil) shouldBe "No packages installed."
    }

    "format single package" in {
      val pkgs = List(Protocol.PackageListEntry("hello", "1.0.0", "sha256-abc"))
      val out = CLIFormat.formatList(pkgs)
      out should include("hello")
      out should include("1.0.0")
      out should include("sha256-abc")
    }

    "align columns" in {
      val pkgs = List(
        Protocol.PackageListEntry("nginx", "1.27.0", "sha256-aaa"),
        Protocol.PackageListEntry("hi", "2.0.0", "sha256-bbb"),
      )
      val lines = CLIFormat.formatList(pkgs).split("\n")
      // "nginx" and "hi" should be aligned — both names padded to same width
      lines(0).indexOf("1.27.0") shouldBe lines(1).indexOf("2.0.0")
    }
  }

  "formatSearch" - {
    "format empty results" in {
      CLIFormat.formatSearch(Nil) shouldBe "No packages found."
    }

    "format results with repo name" in {
      val results = List(
        Search.SearchResult(
          RepoPackageEntry("nginx", "1.27.0", ContentHash("sha256", "aaa"), "x86_64-linux-gnu", Scope.System),
          "stable",
          Search.MatchType.Exact,
        ),
      )
      val out = CLIFormat.formatSearch(results)
      out should include("nginx")
      out should include("1.27.0")
      out should include("system")
      out should include("[stable]")
    }

    "show scope" in {
      val results = List(
        Search.SearchResult(
          RepoPackageEntry("hello", "1.0.0", ContentHash("sha256", "aaa"), "x86_64-linux-gnu", Scope.User),
          "stable",
          Search.MatchType.Exact,
        ),
      )
      CLIFormat.formatSearch(results) should include("user")
    }
  }

  "formatGenerations" - {
    "format empty generations" in {
      CLIFormat.formatGenerations(Nil, 1) shouldBe "No generations."
    }

    "mark current generation" in {
      val gens = List(
        Protocol.GenerationEntry(1, 3),
        Protocol.GenerationEntry(2, 5),
        Protocol.GenerationEntry(3, 4),
      )
      val out = CLIFormat.formatGenerations(gens, 2)
      out should include(" * gen 2")
      out should not include " * gen 1"
      out should not include " * gen 3"
    }

    "show package count" in {
      val gens = List(Protocol.GenerationEntry(1, 7))
      CLIFormat.formatGenerations(gens, 1) should include("7 packages")
    }

    "sort by generation number" in {
      val gens = List(
        Protocol.GenerationEntry(3, 1),
        Protocol.GenerationEntry(1, 1),
        Protocol.GenerationEntry(2, 1),
      )
      val lines = CLIFormat.formatGenerations(gens, 3).split("\n")
      lines(0) should include("gen 1")
      lines(1) should include("gen 2")
      lines(2) should include("gen 3")
    }
  }

  "formatUpgrades" - {
    "format no upgrades" in {
      CLIFormat.formatUpgrades(Nil) shouldBe "All packages are up to date."
    }

    "format upgrade candidates" in {
      val candidates = List(
        UpgradeResolver.UpgradeCandidate(
          "nginx", "1.27.0", ContentHash("sha256", "old"),
          "1.28.0", ContentHash("sha256", "new"), "stable",
        ),
      )
      val out = CLIFormat.formatUpgrades(candidates)
      out should include("1 upgrade(s)")
      out should include("nginx")
      out should include("1.27.0 -> 1.28.0")
      out should include("[stable]")
    }
  }

  "formatEffects" - {
    "format empty effects" in {
      CLIFormat.formatEffects("hello", Effects.empty) shouldBe "hello: no effects declared."
    }

    "format all effect types" in {
      val effects = Effects(
        groups = List(GroupEffect("nginx", system = true)),
        users = List(UserEffect("nginx", "nginx", "/var/lib/nginx", "/sbin/nologin", system = true)),
        directories = List(DirectoryEffect("/var/lib/nginx", "nginx", "nginx", "0750")),
        capabilities = List(CapabilityEffect("sbin/nginx", List("cap_net_bind+ep"))),
        services = List(ServiceEffect("nginx", "sbin/nginx", Nil, "nginx", "nginx", Nil, "on-failure", Map.empty)),
        generators = List(GenerateEffect("bin/gen", "/etc/nginx.conf", Nil)),
        firstRun = Some(FirstRunEffect("bin/init")),
      )
      val out = CLIFormat.formatEffects("nginx", effects)
      out should include("nginx:")
      out should include("group: nginx (system)")
      out should include("user: nginx")
      out should include("directory: /var/lib/nginx")
      out should include("capability: sbin/nginx")
      out should include("service: nginx")
      out should include("generate: /etc/nginx.conf")
      out should include("first-run: bin/init")
    }
  }
}
