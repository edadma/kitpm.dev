package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class EnvComposeTests extends AnyFreeSpec with Matchers {

  val alice  = EnvCompose.ProfilePath("users/alice", "alice")
  val system = EnvCompose.ProfilePath("system", "system")

  "compose PATH with user before system" in {
    val path = EnvCompose.composePath("/", List(alice, system))
    path shouldBe "/kit/profiles/users/alice/current/bin:/kit/profiles/system/current/bin"
  }

  "compose PATH with prefix root" in {
    val path = EnvCompose.composePath("/usr/local/kit", List(system))
    path shouldBe "/usr/local/kit/kit/profiles/system/current/bin"
  }

  "compose lib path" in {
    val path = EnvCompose.composeLibPath("/", List(alice, system))
    path shouldBe "/kit/profiles/users/alice/current/lib:/kit/profiles/system/current/lib"
  }

  "compose full env" in {
    val env = EnvCompose.composeEnv("/home/alice/.kit", List(alice))
    env("KIT_ROOT") shouldBe "/home/alice/.kit"
    env("PATH") shouldBe "/home/alice/.kit/kit/profiles/users/alice/current/bin"
    env("LD_LIBRARY_PATH") shouldBe "/home/alice/.kit/kit/profiles/users/alice/current/lib"
  }

  "empty profiles produce empty paths" in {
    EnvCompose.composePath("/", Nil) shouldBe ""
  }

  "trailing slash on root is normalized" in {
    val path = EnvCompose.composePath("/prefix/", List(system))
    path shouldBe "/prefix/kit/profiles/system/current/bin"
  }
}
