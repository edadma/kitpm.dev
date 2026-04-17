package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class RepoIndexTests extends AnyFreeSpec with Matchers {

  val validIndex: String =
    """
      |repo-name = "kit-stable"
      |revision = "2026-04-15"
      |signed-by = "ed25519:abc123"
      |
      |[[packages]]
      |name = "nginx"
      |version = "1.27.0"
      |content-hash = "sha256-aaa111"
      |target = "x86_64-linux-gnu"
      |scope = "system"
      |
      |[[packages]]
      |name = "hello"
      |version = "1.0.0"
      |content-hash = "sha256-bbb222"
      |target = "x86_64-linux-gnu"
      |scope = "user"
      |
      |[[packages]]
      |name = "hello"
      |version = "1.0.0"
      |content-hash = "sha256-ccc333"
      |target = "aarch64-apple-darwin"
      |scope = "user"
      |""".stripMargin

  // --- Parsing ---

  "parse a valid index" in {
    val result = RepoIndexParser.parse(validIndex)
    result shouldBe a[Right[?, ?]]
    val idx = result.toOption.get
    idx.repoName shouldBe "kit-stable"
    idx.revision shouldBe "2026-04-15"
    idx.signedBy shouldBe "ed25519:abc123"
    idx.packages should have length 3
  }

  "parse package entries" in {
    val idx = RepoIndexParser.parse(validIndex).toOption.get

    val nginx = idx.packages.head
    nginx.name shouldBe "nginx"
    nginx.version shouldBe "1.27.0"
    nginx.contentHash shouldBe ContentHash("sha256", "aaa111")
    nginx.target shouldBe "x86_64-linux-gnu"
    nginx.scope shouldBe Scope.System

    val hello = idx.packages(1)
    hello.scope shouldBe Scope.User
  }

  "parse empty package list" in {
    val input =
      """
        |repo-name = "empty"
        |revision = "2026-01-01"
        |signed-by = "ed25519:xyz"
        |""".stripMargin

    val idx = RepoIndexParser.parse(input).toOption.get
    idx.packages shouldBe empty
  }

  "reject missing repo-name" in {
    val input =
      """
        |revision = "2026-01-01"
        |signed-by = "ed25519:xyz"
        |""".stripMargin

    RepoIndexParser.parse(input) shouldBe Left("missing required field: 'repo-name'")
  }

  "reject missing signed-by" in {
    val input =
      """
        |repo-name = "test"
        |revision = "2026-01-01"
        |""".stripMargin

    RepoIndexParser.parse(input) shouldBe Left("missing required field: 'signed-by'")
  }

  "reject package entry with invalid scope" in {
    val input =
      """
        |repo-name = "test"
        |revision = "2026-01-01"
        |signed-by = "ed25519:xyz"
        |
        |[[packages]]
        |name = "bad"
        |version = "1.0"
        |content-hash = "sha256-abc"
        |target = "x86_64-linux-gnu"
        |scope = "global"
        |""".stripMargin

    val result = RepoIndexParser.parse(input)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("invalid scope")
  }

  "reject malformed TOML" in {
    RepoIndexParser.parse("not valid [toml") shouldBe a[Left[?, ?]]
  }

  // --- Lookup ---

  "lookup by exact name, version, and hash" in {
    val idx = RepoIndexParser.parse(validIndex).toOption.get
    val repos = List((idx, 10))

    val found = RepoLookup.lookup(repos, "nginx", "1.27.0", ContentHash("sha256", "aaa111"))
    found shouldBe defined
    found.get.name shouldBe "nginx"
  }

  "lookup returns None for missing package" in {
    val idx = RepoIndexParser.parse(validIndex).toOption.get
    val repos = List((idx, 10))

    RepoLookup.lookup(repos, "ghost", "1.0", ContentHash("sha256", "zzz")) shouldBe None
  }

  "lookup returns None for hash mismatch" in {
    val idx = RepoIndexParser.parse(validIndex).toOption.get
    val repos = List((idx, 10))

    RepoLookup.lookup(repos, "nginx", "1.27.0", ContentHash("sha256", "wrong")) shouldBe None
  }

  "lookupByName filters by target" in {
    val idx = RepoIndexParser.parse(validIndex).toOption.get
    val repos = List((idx, 10))

    val linux = RepoLookup.lookupByName(repos, "hello", "x86_64-linux-gnu")
    linux should have length 1
    linux.head.contentHash shouldBe ContentHash("sha256", "bbb222")

    val mac = RepoLookup.lookupByName(repos, "hello", "aarch64-apple-darwin")
    mac should have length 1
    mac.head.contentHash shouldBe ContentHash("sha256", "ccc333")
  }

  "higher priority repo wins in lookup order" in {
    val idx1Input =
      """
        |repo-name = "low-pri"
        |revision = "2026-01-01"
        |signed-by = "ed25519:key1"
        |
        |[[packages]]
        |name = "tool"
        |version = "1.0"
        |content-hash = "sha256-old"
        |target = "x86_64-linux-gnu"
        |scope = "user"
        |""".stripMargin

    val idx2Input =
      """
        |repo-name = "high-pri"
        |revision = "2026-04-01"
        |signed-by = "ed25519:key2"
        |
        |[[packages]]
        |name = "tool"
        |version = "1.0"
        |content-hash = "sha256-new"
        |target = "x86_64-linux-gnu"
        |scope = "user"
        |""".stripMargin

    val idx1 = RepoIndexParser.parse(idx1Input).toOption.get
    val idx2 = RepoIndexParser.parse(idx2Input).toOption.get
    val repos = List((idx1, 5), (idx2, 10))

    val results = RepoLookup.lookupByName(repos, "tool", "x86_64-linux-gnu")
    results.head.contentHash shouldBe ContentHash("sha256", "new")
  }
}
