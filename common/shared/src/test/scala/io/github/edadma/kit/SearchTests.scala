package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class SearchTests extends AnyFreeSpec with Matchers {

  val target = "x86_64-linux-gnu"

  val index1 = RepoIndex("stable", "2026-04-18", "ed25519:key1", List(
    RepoPackageEntry("nginx", "1.27.0", ContentHash("sha256", "aaa"), target, Scope.System),
    RepoPackageEntry("nginx-extras", "1.27.0", ContentHash("sha256", "bbb"), target, Scope.System),
    RepoPackageEntry("hello", "1.0.0", ContentHash("sha256", "ccc"), target, Scope.User),
    RepoPackageEntry("hello", "2.0.0", ContentHash("sha256", "ddd"), target, Scope.User),
    RepoPackageEntry("python", "3.12.0", ContentHash("sha256", "eee"), target, Scope.User),
    RepoPackageEntry("python", "3.12.0", ContentHash("sha256", "fff"), "aarch64-apple-darwin", Scope.User),
  ))

  val index2 = RepoIndex("community", "2026-04-18", "ed25519:key2", List(
    RepoPackageEntry("nginx-module-geoip", "1.0", ContentHash("sha256", "ggg"), target, Scope.System),
    RepoPackageEntry("mycli", "0.5.0", ContentHash("sha256", "hhh"), target, Scope.User),
  ))

  val repos = List((index1, "stable"), (index2, "community"))

  "search" - {
    "exact match ranks first" in {
      val results = Search.search(repos, "nginx", target)
      results.head.entry.name shouldBe "nginx"
      results.head.matchType shouldBe Search.MatchType.Exact
    }

    "prefix matches rank second" in {
      val results = Search.search(repos, "nginx", target)
      results should have length 3 // nginx, nginx-extras, nginx-module-geoip
      results(0).matchType shouldBe Search.MatchType.Exact
      results(1).matchType shouldBe Search.MatchType.Prefix
    }

    "substring match" in {
      val results = Search.search(repos, "cli", target)
      results should have length 1
      results.head.entry.name shouldBe "mycli"
      results.head.matchType shouldBe Search.MatchType.Contains
    }

    "case insensitive" in {
      val results = Search.search(repos, "NGINX", target)
      results should not be empty
      results.head.entry.name shouldBe "nginx"
    }

    "no results for unmatched query" in {
      Search.search(repos, "nonexistent", target) shouldBe empty
    }

    "filters by target platform" in {
      val results = Search.search(repos, "python", target)
      results should have length 1
      results.head.entry.target shouldBe target
    }

    "deduplicates same name+version from different repos" in {
      // If same package appears in two repos, only show once
      val results = Search.search(repos, "hello", target)
      val versions = results.map(r => (r.entry.name, r.entry.version))
      versions.distinct.length shouldBe versions.length
    }

    "results include repo name" in {
      val results = Search.search(repos, "mycli", target)
      results.head.repoName shouldBe "community"
    }

    "empty query matches nothing" in {
      Search.search(repos, "", target) should have length 0
    }
  }

  "findByName" - {
    "finds all versions of a package" in {
      val results = Search.findByName(repos, "hello", target)
      results should have length 2
      results.map(_.entry.version).toSet shouldBe Set("1.0.0", "2.0.0")
    }

    "returns empty for unknown package" in {
      Search.findByName(repos, "ghost", target) shouldBe empty
    }

    "filters by target" in {
      val linux = Search.findByName(repos, "python", target)
      linux should have length 1
      val mac = Search.findByName(repos, "python", "aarch64-apple-darwin")
      mac should have length 1
      linux.head.entry.contentHash should not be mac.head.entry.contentHash
    }
  }

  "findLatest" - {
    "returns latest version" in {
      val result = Search.findLatest(repos, "hello", target)
      result shouldBe defined
      result.get.entry.version shouldBe "2.0.0"
    }

    "returns None for unknown package" in {
      Search.findLatest(repos, "ghost", target) shouldBe None
    }

    "returns single version when only one exists" in {
      val result = Search.findLatest(repos, "nginx", target)
      result shouldBe defined
      result.get.entry.version shouldBe "1.27.0"
    }
  }
}
