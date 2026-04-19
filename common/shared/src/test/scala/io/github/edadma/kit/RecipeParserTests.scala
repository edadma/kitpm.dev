package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class RecipeParserTests extends AnyFreeSpec with Matchers {

  val fullRecipe: String =
    """
      |name = "hello"
      |version = "2.12"
      |target = "aarch64-linux-gnu"
      |scope = "user"
      |
      |deps = ["gcc", "make", "glibc"]
      |
      |[source]
      |url = "https://ftp.gnu.org/gnu/hello/hello-2.12.tar.gz"
      |hash = "sha256-abc123"
      |
      |[build]
      |steps = [
      |  "./configure --prefix=$KIT_PREFIX",
      |  "make -j$KIT_JOBS",
      |  "make install DESTDIR=$KIT_OUT",
      |]
      |
      |[build.env]
      |CFLAGS = "-O2"
      |LDFLAGS = "-s"
      |""".stripMargin

  "parse a full recipe" in {
    val result = RecipeParser.parse(fullRecipe)
    result.isRight shouldBe true
    val r = result.toOption.get
    r.name shouldBe "hello"
    r.version shouldBe "2.12"
    r.target shouldBe "aarch64-linux-gnu"
    r.scope shouldBe Scope.User
  }

  "parse source section" in {
    val r = RecipeParser.parse(fullRecipe).toOption.get
    r.sourceUrl shouldBe Some("https://ftp.gnu.org/gnu/hello/hello-2.12.tar.gz")
    r.sourceHash shouldBe Some("sha256-abc123")
  }

  "parse build steps" in {
    val r = RecipeParser.parse(fullRecipe).toOption.get
    r.buildSteps should have length 3
    r.buildSteps.head shouldBe "./configure --prefix=$KIT_PREFIX"
    r.buildSteps.last shouldBe "make install DESTDIR=$KIT_OUT"
  }

  "parse build environment" in {
    val r = RecipeParser.parse(fullRecipe).toOption.get
    r.buildEnv("CFLAGS") shouldBe "-O2"
    r.buildEnv("LDFLAGS") shouldBe "-s"
  }

  "parse dependencies" in {
    val r = RecipeParser.parse(fullRecipe).toOption.get
    r.deps shouldBe List("gcc", "make", "glibc")
  }

  "parse minimal recipe" in {
    val input =
      """
        |name = "simple"
        |version = "1.0"
        |target = "x86_64-linux-gnu"
        |scope = "user"
        |""".stripMargin

    val r = RecipeParser.parse(input).toOption.get
    r.name shouldBe "simple"
    r.sourceUrl shouldBe None
    r.buildSteps shouldBe empty
    r.buildEnv shouldBe empty
    r.deps shouldBe empty
  }

  "reject missing name" in {
    val input =
      """
        |version = "1.0"
        |target = "x86_64-linux-gnu"
        |scope = "user"
        |""".stripMargin
    RecipeParser.parse(input) shouldBe Left("missing 'name'")
  }

  "reject missing version" in {
    val input =
      """
        |name = "hello"
        |target = "x86_64-linux-gnu"
        |scope = "user"
        |""".stripMargin
    RecipeParser.parse(input) shouldBe Left("missing 'version'")
  }

  "reject invalid scope" in {
    val input =
      """
        |name = "hello"
        |version = "1.0"
        |target = "x86_64-linux-gnu"
        |scope = "global"
        |""".stripMargin
    val result = RecipeParser.parse(input)
    result.isLeft shouldBe true
    result.left.toOption.get should include("invalid scope")
  }

  "reject malformed TOML" in {
    RecipeParser.parse("not [valid toml").isLeft shouldBe true
  }

  "recipe with system scope" in {
    val input =
      """
        |name = "nginx"
        |version = "1.27.0"
        |target = "x86_64-linux-gnu"
        |scope = "system"
        |
        |[build]
        |steps = ["make", "make install DESTDIR=$KIT_OUT"]
        |""".stripMargin

    val r = RecipeParser.parse(input).toOption.get
    r.scope shouldBe Scope.System
    r.buildSteps should have length 2
  }
}
