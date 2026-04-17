package io.github.edadma.kitd

import io.github.edadma.kit.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class PackageTestOpsTests extends AnyFreeSpec with Matchers {

  val allFeatures = Set("user-database", "service-registration", "capabilities", "network", "sandbox")

  val testStart = PackageTest("starts", "bin/test-start", List("service-registration"))
  val testServe = PackageTest("serves", "bin/test-serve", List("network"))
  val testBasic = PackageTest("basic", "bin/test-basic", Nil)

  val hash = ContentHash("sha256", "abc123")

  // --- filterTests ---

  "run all tests when features satisfied" in {
    val results = PackageTestOps.filterTests(List(testStart, testServe, testBasic), allFeatures)
    results should have length 3
    results.foreach { d =>
      d shouldBe a[PackageTestOps.TestDisposition.Run]
    }
  }

  "skip tests with missing features" in {
    val results = PackageTestOps.filterTests(List(testStart, testServe, testBasic), Set.empty)
    val runs = results.collect { case PackageTestOps.TestDisposition.Run(_) => true }
    val skips = results.collect { case PackageTestOps.TestDisposition.Skip(_, _) => true }
    runs should have length 1 // testBasic has no feature requirements
    skips should have length 2
  }

  "skip reason includes missing features" in {
    val results = PackageTestOps.filterTests(List(testStart), Set.empty)
    val skip = results.head.asInstanceOf[PackageTestOps.TestDisposition.Skip]
    skip.reason should include("service-registration")
  }

  "tests with no requirements always run" in {
    val results = PackageTestOps.filterTests(List(testBasic), Set.empty)
    results.head shouldBe PackageTestOps.TestDisposition.Run(testBasic)
  }

  "empty test list produces empty result" in {
    PackageTestOps.filterTests(Nil, allFeatures) shouldBe empty
  }

  // --- summarize ---

  "summarize all passed" in {
    val results = List(
      PackageTestOps.TestResult(hash, "a", PackageTestOps.TestOutcome.Passed, 10, "", ""),
      PackageTestOps.TestResult(hash, "b", PackageTestOps.TestOutcome.Passed, 20, "", ""),
    )
    val s = PackageTestOps.summarize(results)
    s.total shouldBe 2
    s.passed shouldBe 2
    s.failed shouldBe 0
    s.skipped shouldBe 0
  }

  "summarize mixed outcomes" in {
    val results = List(
      PackageTestOps.TestResult(hash, "a", PackageTestOps.TestOutcome.Passed, 10, "", ""),
      PackageTestOps.TestResult(hash, "b", PackageTestOps.TestOutcome.Failed(1), 20, "", "error"),
      PackageTestOps.TestResult(hash, "c", PackageTestOps.TestOutcome.Skipped("no network"), 0, "", ""),
    )
    val s = PackageTestOps.summarize(results)
    s.total shouldBe 3
    s.passed shouldBe 1
    s.failed shouldBe 1
    s.skipped shouldBe 1
  }

  "summarize empty results" in {
    val s = PackageTestOps.summarize(Nil)
    s.total shouldBe 0
    s.passed shouldBe 0
  }

  // --- planTests ---

  "plan tests for a package" in {
    val m = Manifest(
      "nginx", "1.27.0", "x86_64-linux-gnu", hash, Scope.System, Nil, Nil, Effects.empty,
      List(testStart, testServe, testBasic),
    )
    val plan = PackageTestOps.planTests(m, allFeatures)
    plan.contentHash shouldBe hash
    plan.packageName shouldBe "nginx"
    plan.tests should have length 3
  }

  "plan skips tests when features missing" in {
    val m = Manifest(
      "nginx", "1.27.0", "x86_64-linux-gnu", hash, Scope.System, Nil, Nil, Effects.empty,
      List(testStart, testBasic),
    )
    val plan = PackageTestOps.planTests(m, Set.empty)
    val runs = plan.tests.collect { case PackageTestOps.TestDisposition.Run(_) => true }
    val skips = plan.tests.collect { case PackageTestOps.TestDisposition.Skip(_, _) => true }
    runs should have length 1
    skips should have length 1
  }

  "plan for package with no tests" in {
    val m = Manifest("empty", "1.0", "x86_64-linux-gnu", hash, Scope.User, Nil, Nil, Effects.empty, Nil)
    val plan = PackageTestOps.planTests(m, allFeatures)
    plan.tests shouldBe empty
  }

  // --- planAll ---

  "planAll filters by target and skips packages without tests" in {
    val hash1 = ContentHash("sha256", "h1")
    val hash2 = ContentHash("sha256", "h2")
    val hash3 = ContentHash("sha256", "h3")

    val index = RepoIndex("test", "2026-01-01", "ed25519:key", List(
      RepoPackageEntry("nginx", "1.27.0", hash1, "x86_64-linux-gnu", Scope.System),
      RepoPackageEntry("hello", "1.0.0", hash2, "x86_64-linux-gnu", Scope.User),
      RepoPackageEntry("mac-tool", "1.0.0", hash3, "aarch64-apple-darwin", Scope.User),
    ))

    val manifests = Map(
      hash1 -> Manifest("nginx", "1.27.0", "x86_64-linux-gnu", hash1, Scope.System, Nil, Nil, Effects.empty, List(testBasic)),
      hash2 -> Manifest("hello", "1.0.0", "x86_64-linux-gnu", hash2, Scope.User, Nil, Nil, Effects.empty, Nil),
      hash3 -> Manifest("mac-tool", "1.0.0", "aarch64-apple-darwin", hash3, Scope.User, Nil, Nil, Effects.empty, List(testBasic)),
    )

    val plans = PackageTestOps.planAll(index, "x86_64-linux-gnu", manifests, allFeatures)
    plans should have length 1 // only nginx has tests and matches target
    plans.head.packageName shouldBe "nginx"
  }

  // --- formatResult ---

  "format passed result" in {
    val r = PackageTestOps.TestResult(hash, "basic", PackageTestOps.TestOutcome.Passed, 42, "", "")
    PackageTestOps.formatResult(r) should include("PASS")
    PackageTestOps.formatResult(r) should include("basic")
    PackageTestOps.formatResult(r) should include("42ms")
  }

  "format failed result" in {
    val r = PackageTestOps.TestResult(hash, "serve", PackageTestOps.TestOutcome.Failed(1), 100, "", "err")
    PackageTestOps.formatResult(r) should include("FAIL")
    PackageTestOps.formatResult(r) should include("exit 1")
  }

  "format skipped result" in {
    val r = PackageTestOps.TestResult(hash, "caps", PackageTestOps.TestOutcome.Skipped("no caps"), 0, "", "")
    PackageTestOps.formatResult(r) should include("SKIP")
    PackageTestOps.formatResult(r) should include("no caps")
  }

  // --- formatReport ---

  "format package report" in {
    val results = List(
      PackageTestOps.TestResult(hash, "a", PackageTestOps.TestOutcome.Passed, 10, "", ""),
      PackageTestOps.TestResult(hash, "b", PackageTestOps.TestOutcome.Failed(2), 20, "", ""),
    )
    val report = PackageTestOps.formatReport("nginx", results)
    report should include("nginx:")
    report should include("1 passed")
    report should include("1 failed")
  }

  "format batch report" in {
    val r1 = List(PackageTestOps.TestResult(hash, "a", PackageTestOps.TestOutcome.Passed, 10, "", ""))
    val r2 = List(PackageTestOps.TestResult(hash, "b", PackageTestOps.TestOutcome.Failed(1), 20, "", ""))
    val report = PackageTestOps.formatBatchReport(List(("pkg1", r1), ("pkg2", r2)))
    report should include("Total:")
    report should include("1 passed")
    report should include("1 failed")
  }

  // --- hasPassingCache ---

  "cache hit when all tests passed for hash" in {
    val cached = List(
      PackageTestOps.TestResult(hash, "a", PackageTestOps.TestOutcome.Passed, 10, "", ""),
      PackageTestOps.TestResult(hash, "b", PackageTestOps.TestOutcome.Passed, 20, "", ""),
    )
    PackageTestOps.hasPassingCache(cached, hash) shouldBe true
  }

  "cache miss when a test failed" in {
    val cached = List(
      PackageTestOps.TestResult(hash, "a", PackageTestOps.TestOutcome.Passed, 10, "", ""),
      PackageTestOps.TestResult(hash, "b", PackageTestOps.TestOutcome.Failed(1), 20, "", ""),
    )
    PackageTestOps.hasPassingCache(cached, hash) shouldBe false
  }

  "cache miss when no results for hash" in {
    val other = ContentHash("sha256", "other")
    val cached = List(
      PackageTestOps.TestResult(other, "a", PackageTestOps.TestOutcome.Passed, 10, "", ""),
    )
    PackageTestOps.hasPassingCache(cached, hash) shouldBe false
  }

  "cache miss on empty cache" in {
    PackageTestOps.hasPassingCache(Nil, hash) shouldBe false
  }

  "cache miss when hash changed (different content)" in {
    val oldHash = ContentHash("sha256", "old")
    val cached = List(
      PackageTestOps.TestResult(oldHash, "a", PackageTestOps.TestOutcome.Passed, 10, "", ""),
    )
    PackageTestOps.hasPassingCache(cached, hash) shouldBe false
  }
}
