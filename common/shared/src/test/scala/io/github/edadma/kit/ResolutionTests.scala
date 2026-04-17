package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ResolutionTests extends AnyFreeSpec with Matchers {

  val platform = "x86_64-linux-gnu"
  val features = Set("user-database", "service-registration", "capabilities")

  def minimal(name: String, version: String, hash: String, deps: List[DepRef] = Nil): Manifest =
    Manifest(name, version, platform, ContentHash("sha256", hash), Scope.System, Nil, deps, Effects.empty)

  val libc: Manifest    = minimal("libc", "0.3.1", "libc-hash")
  val openssl: Manifest = minimal("openssl", "3.2.1", "openssl-hash", List(DepRef("libc", "0.3.1", libc.contentHash)))
  val nginx: Manifest = minimal(
    "nginx",
    "1.27.0",
    "nginx-hash",
    List(
      DepRef("libc", "0.3.1", libc.contentHash),
      DepRef("openssl", "3.2.1", openssl.contentHash),
    ),
  )

  val repo: Map[ContentHash, Manifest] =
    Map(libc.contentHash -> libc, openssl.contentHash -> openssl, nginx.contentHash -> nginx)

  def lookup(dep: DepRef): Either[String, Manifest] =
    repo.get(dep.contentHash).toRight(s"not found: ${dep.name}@${dep.version}")

  "resolve a package with no deps" in {
    val result = Resolution.resolve(libc, lookup, platform, Scope.System, features)
    result shouldBe a[Right[?, ?]]
    val closure = result.toOption.get
    closure.packages should have size 1
    closure.packages should contain key libc.contentHash
  }

  "resolve transitive dependencies" in {
    val result = Resolution.resolve(nginx, lookup, platform, Scope.System, features)
    result shouldBe a[Right[?, ?]]
    val closure = result.toOption.get
    closure.packages should have size 3
    closure.packages should contain key nginx.contentHash
    closure.packages should contain key openssl.contentHash
    closure.packages should contain key libc.contentHash
  }

  "deduplicate shared dependencies" in {
    // Both nginx and openssl depend on libc; libc should appear once
    val result = Resolution.resolve(nginx, lookup, platform, Scope.System, features)
    val closure = result.toOption.get
    closure.packages.values.count(_.name == "libc") shouldBe 1
  }

  "reject target mismatch" in {
    val arm = minimal("arm-pkg", "1.0", "arm-hash").copy(target = "aarch64-apple-darwin")
    val result = Resolution.resolve(arm, lookup, platform, Scope.System, features)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("target mismatch")
  }

  "reject system package in user profile" in {
    val result = Resolution.resolve(nginx, lookup, platform, Scope.User, features)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("cannot install system-scoped")
  }

  "reject missing required features" in {
    val pkg = minimal("secure", "1.0", "sec-hash").copy(requiresFeatures = List("sandbox"))
    val result = Resolution.resolve(pkg, lookup, platform, Scope.System, features)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("missing required features")
  }

  "report lookup failure" in {
    val missing = DepRef("ghost", "1.0", ContentHash("sha256", "ghost-hash"))
    val pkg = minimal("broken", "1.0", "broken-hash", List(missing))
    val result = Resolution.resolve(pkg, lookup, platform, Scope.System, features)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("failed to resolve")
  }

  "detect content hash mismatch" in {
    val wrongHash = DepRef("libc", "0.3.1", ContentHash("sha256", "wrong-hash"))
    val pkg = minimal("bad-ref", "1.0", "bad-ref-hash", List(wrongHash))
    val badLookup: DepRef => Either[String, Manifest] = dep =>
      if dep.name == "libc" then Right(libc) else Left("not found")
    val result = Resolution.resolve(pkg, badLookup, platform, Scope.System, features)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("content hash mismatch")
  }
}
