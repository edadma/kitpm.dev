package io.github.edadma.kitd

import io.github.edadma.kit.*

/** Pure functions for package test planning, filtering, results, and reporting. */
object PackageTestOps:

  // --- Test filtering ---

  /** A test that has been evaluated for runnability. */
  enum TestDisposition:
    case Run(test: io.github.edadma.kit.PackageTest)
    case Skip(test: io.github.edadma.kit.PackageTest, reason: String)

  /**
   * Given a package's tests and the available features, determine which to run and which to skip.
   */
  def filterTests(
      tests: List[io.github.edadma.kit.PackageTest],
      availableFeatures: Set[String],
  ): List[TestDisposition] =
    tests.map { t =>
      val missing = t.requiresFeatures.filterNot(availableFeatures.contains)
      if missing.isEmpty then TestDisposition.Run(t)
      else TestDisposition.Skip(t, s"missing features: ${missing.mkString(", ")}")
    }

  // --- Test results ---

  /** The outcome of a single test execution. */
  enum TestOutcome:
    case Passed
    case Failed(exitCode: Int)
    case Skipped(reason: String)

  /** A recorded test result. */
  case class TestResult(
      contentHash: ContentHash,
      testName: String,
      outcome: TestOutcome,
      durationMs: Long,
      stdout: String,
      stderr: String,
  )

  /** Summary statistics for a batch of test results. */
  case class TestSummary(
      total: Int,
      passed: Int,
      failed: Int,
      skipped: Int,
  )

  def summarize(results: List[TestResult]): TestSummary =
    TestSummary(
      total = results.length,
      passed = results.count(_.outcome == TestOutcome.Passed),
      failed = results.count(_.outcome.isInstanceOf[TestOutcome.Failed]),
      skipped = results.count(_.outcome.isInstanceOf[TestOutcome.Skipped]),
    )

  // --- Test plan ---

  /** A planned test execution. */
  case class TestPlan(
      contentHash: ContentHash,
      packageName: String,
      tests: List[TestDisposition],
  )

  /**
   * Build a test plan for a package.
   */
  def planTests(
      manifest: Manifest,
      availableFeatures: Set[String],
  ): TestPlan =
    TestPlan(
      contentHash = manifest.contentHash,
      packageName = manifest.name,
      tests = filterTests(manifest.tests, availableFeatures),
    )

  /**
   * Build test plans for all packages in a repo index, filtered by target.
   */
  def planAll(
      index: RepoIndex,
      target: String,
      manifests: Map[ContentHash, Manifest],
      availableFeatures: Set[String],
  ): List[TestPlan] =
    index.packages
      .filter(_.target == target)
      .flatMap(entry => manifests.get(entry.contentHash))
      .filter(_.tests.nonEmpty)
      .map(m => planTests(m, availableFeatures))
      .sortBy(_.packageName)

  // --- Report formatting ---

  /** Format a single test result as a one-line summary. */
  def formatResult(r: TestResult): String =
    val status = r.outcome match
      case TestOutcome.Passed        => "PASS"
      case TestOutcome.Failed(code)  => s"FAIL (exit $code)"
      case TestOutcome.Skipped(reason) => s"SKIP ($reason)"
    val duration = s"${r.durationMs}ms"
    s"  $status  ${r.testName}  $duration"

  /** Format a complete test report for a package. */
  def formatReport(packageName: String, results: List[TestResult]): String =
    val header = s"$packageName:"
    val lines = results.map(formatResult)
    val summary = summarize(results)
    val footer = s"  ${summary.passed} passed, ${summary.failed} failed, ${summary.skipped} skipped"
    (header :: lines ::: List(footer)).mkString("\n")

  /** Format a batch report across multiple packages. */
  def formatBatchReport(reports: List[(String, List[TestResult])]): String =
    val packageReports = reports.map((name, results) => formatReport(name, results))
    val allResults = reports.flatMap(_._2)
    val total = summarize(allResults)
    val divider = s"\nTotal: ${total.passed} passed, ${total.failed} failed, ${total.skipped} skipped out of ${total.total} tests"
    (packageReports ::: List(divider)).mkString("\n\n")

  // --- Cache checking ---

  /**
   * Given cached test results and a content hash, determine if the package
   * needs retesting. Returns true if all tests passed for this exact hash.
   */
  def hasPassingCache(
      cachedResults: List[TestResult],
      contentHash: ContentHash,
  ): Boolean =
    val matching = cachedResults.filter(_.contentHash == contentHash)
    matching.nonEmpty && matching.forall(_.outcome == TestOutcome.Passed)
