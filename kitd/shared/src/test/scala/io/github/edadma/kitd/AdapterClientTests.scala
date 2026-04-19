package io.github.edadma.kitd

import io.github.edadma.kit.*
import io.github.edadma.kit.AdapterProtocol.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.nio.file.{Files, Path}

import scala.compiletime.uninitialized

/**
 * Tier 1 tests for adapter invocation using the stub adapter.
 */
class AdapterClientTests extends AnyFreeSpec with Matchers with BeforeAndAfterEach {

  var tmpDir: Path = uninitialized
  var logFile: Path = uninitialized
  var stubPath: String = uninitialized

  override def beforeEach(): Unit =
    tmpDir = Files.createTempDirectory("kit-adapter-test-")
    logFile = tmpDir.resolve("adapter.log")

    // Find the stub adapter relative to the project root
    val candidates = List(
      "adapters/kit-adapter-userdb-stub",
      "../adapters/kit-adapter-userdb-stub",
      "../../adapters/kit-adapter-userdb-stub",
    )
    stubPath = candidates.find(p => new java.io.File(p).exists()).getOrElse {
      // Create a local copy if not found
      val local = tmpDir.resolve("stub-adapter")
      Files.writeString(local,
        """#!/bin/sh
          |read -r INPUT
          |ACTION=$(echo "$INPUT" | sed 's/.*"action":"\([^"]*\)".*/\1/')
          |case "$ACTION" in
          |  create-group) echo '{"ok":true,"message":"stub: group created","gid":1000}' ;;
          |  remove-group) echo '{"ok":true,"message":"stub: group removed"}' ;;
          |  create-user) echo '{"ok":true,"message":"stub: user created","uid":1000}' ;;
          |  remove-user) echo '{"ok":true,"message":"stub: user removed"}' ;;
          |  *) echo '{"ok":true,"message":"stub: unknown"}' ;;
          |esac
          |""".stripMargin)
      local.toFile.setExecutable(true)
      local.toString
    }

  override def afterEach(): Unit =
    deleteRecursive(tmpDir)

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.forEach(deleteRecursive)
      finally stream.close()
    Files.deleteIfExists(path)

  // --- Tests ---

  "create group via stub adapter" in {
    val result = AdapterClient.createGroup(stubPath, GroupEffect("nginx", system = true))
    result.isRight shouldBe true
    result.toOption.get.ok shouldBe true
    result.toOption.get.gid shouldBe Some(1000)
  }

  "remove group via stub adapter" in {
    val result = AdapterClient.removeGroup(stubPath, "nginx")
    result.isRight shouldBe true
    result.toOption.get.ok shouldBe true
  }

  "create user via stub adapter" in {
    val result = AdapterClient.createUser(
      stubPath,
      UserEffect("nginx", "nginx", "/var/lib/nginx", "/sbin/nologin", system = true),
    )
    result.isRight shouldBe true
    result.toOption.get.ok shouldBe true
    result.toOption.get.uid shouldBe Some(1000)
  }

  "remove user via stub adapter" in {
    val result = AdapterClient.removeUser(stubPath, "nginx")
    result.isRight shouldBe true
    result.toOption.get.ok shouldBe true
  }

  "invoke nonexistent adapter fails" in {
    val result = AdapterClient.invoke("/nonexistent/adapter", "create-group", "{}")
    result.isLeft shouldBe true
    result.left.toOption.get should include("failed to invoke")
  }

  "activation engine with stub adapter" in {
    val engine = new ActivationEngine(tmpDir.toString, Map("user-database" -> stubPath))

    val diff = EffectOps.diff(
      Effects.empty,
      Effects.empty.copy(
        groups = List(GroupEffect("nginx", system = true)),
        users = List(UserEffect("nginx", "nginx", "/var/lib/nginx", "/sbin/nologin", system = true)),
        directories = List(DirectoryEffect("/var/lib/nginx", "nginx", "nginx", "0750")),
      ),
    )

    val result = engine.apply(diff)
    result.isRight shouldBe true

    // Verify the directory was created
    Files.exists(tmpDir.resolve("var/lib/nginx")) shouldBe true
  }

  "activation engine without adapter fails for user effects" in {
    val engine = new ActivationEngine(tmpDir.toString, Map.empty)

    val diff = EffectOps.diff(
      Effects.empty,
      Effects.empty.copy(groups = List(GroupEffect("nginx", system = true))),
    )

    val result = engine.apply(diff)
    result.isLeft shouldBe true
    result.left.toOption.get should include("no user-database adapter")
  }
}
