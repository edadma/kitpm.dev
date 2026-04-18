package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ManifestCrossValidatorTests extends AnyFreeSpec with Matchers {

  val base = Manifest(
    "hello", "1.0.0", "x86_64-linux-gnu",
    ContentHash("sha256", "abc123"), Scope.User, Nil,
    List(DepRef("libc", "0.3", ContentHash("sha256", "libc-hash"))),
    Effects.empty.copy(groups = List(GroupEffect("grp", system = true))),
    List(PackageTest("basic", "bin/test", Nil)),
  )

  "identical manifests produce no errors" in {
    ManifestCrossValidator.validate(base, base) shouldBe empty
  }

  "detect name mismatch" in {
    val tarball = base.copy(name = "wrong")
    val errors = ManifestCrossValidator.validate(base, tarball)
    errors should have length 1
    errors.head should include("name mismatch")
  }

  "detect version mismatch" in {
    val tarball = base.copy(version = "2.0.0")
    val errors = ManifestCrossValidator.validate(base, tarball)
    errors.head should include("version mismatch")
  }

  "detect target mismatch" in {
    val tarball = base.copy(target = "aarch64-apple-darwin")
    val errors = ManifestCrossValidator.validate(base, tarball)
    errors.head should include("target mismatch")
  }

  "detect content-hash mismatch" in {
    val tarball = base.copy(contentHash = ContentHash("sha256", "different"))
    val errors = ManifestCrossValidator.validate(base, tarball)
    errors.head should include("content-hash mismatch")
  }

  "detect scope mismatch" in {
    val tarball = base.copy(scope = Scope.System)
    val errors = ManifestCrossValidator.validate(base, tarball)
    errors.head should include("scope mismatch")
  }

  "detect deps mismatch" in {
    val tarball = base.copy(deps = Nil)
    val errors = ManifestCrossValidator.validate(base, tarball)
    errors.head should include("deps mismatch")
  }

  "detect effects mismatch" in {
    val tarball = base.copy(effects = Effects.empty)
    val errors = ManifestCrossValidator.validate(base, tarball)
    errors.head should include("effects mismatch")
  }

  "detect tests mismatch" in {
    val tarball = base.copy(tests = Nil)
    val errors = ManifestCrossValidator.validate(base, tarball)
    errors.head should include("tests mismatch")
  }

  "report multiple mismatches at once" in {
    val tarball = base.copy(name = "wrong", version = "9.9.9", scope = Scope.System)
    val errors = ManifestCrossValidator.validate(base, tarball)
    errors should have length 3
  }
}
