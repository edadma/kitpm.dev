package io.github.edadma.kitd

import io.github.edadma.kit.*
import io.github.edadma.kit.AdapterProtocol.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Tag, BeforeAndAfterEach}

import java.nio.file.{Files, Path}

import scala.compiletime.uninitialized

/**
 * Tier 2 tests — run inside a Docker container with real adapters.
 * These tests create real system users and groups.
 * Tagged "Tier2" so they're excluded from normal test runs.
 *
 * Run with: sbt "kitdJVM/testOnly io.github.edadma.kitd.Tier2Tests"
 */
object Tier2 extends Tag("Tier2")

class Tier2Tests extends AnyFreeSpec with Matchers with BeforeAndAfterEach {

  var testRoot: Path = uninitialized
  val adapterPath = "adapters/kit-adapter-userdb-useradd"

  override def beforeEach(): Unit =
    testRoot = Files.createTempDirectory("kit-tier2-")

  override def afterEach(): Unit =
    deleteRecursive(testRoot)

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.forEach(deleteRecursive)
      finally stream.close()
    Files.deleteIfExists(path)

  private def adapterExists: Boolean =
    new java.io.File(adapterPath).canExecute

  private def isLinux: Boolean =
    System.getProperty("os.name").toLowerCase.contains("linux")

  private def canCreateUsers: Boolean =
    isLinux && adapterExists && {
      // Check if we're root (needed for useradd)
      try
        val pb = new ProcessBuilder("id", "-u")
        val p = pb.start()
        val uid = new String(p.getInputStream.readAllBytes()).trim
        p.waitFor()
        uid == "0"
      catch
        case _: Exception => false
    }

  // --- Tests ---

  "create group via real adapter" taggedAs Tier2 in {
    if !canCreateUsers then cancel("requires Linux with root access and adapter")

    val result = AdapterClient.createGroup(adapterPath, GroupEffect("kit-test-grp", system = true))
    result.isRight shouldBe true
    result.toOption.get.ok shouldBe true
    result.toOption.get.gid shouldBe defined

    // Verify group actually exists
    val pb = new ProcessBuilder("getent", "group", "kit-test-grp")
    val p = pb.start()
    val output = new String(p.getInputStream.readAllBytes()).trim
    p.waitFor() shouldBe 0
    output should include("kit-test-grp")

    // Cleanup
    AdapterClient.removeGroup(adapterPath, "kit-test-grp")
  }

  "create user via real adapter" taggedAs Tier2 in {
    if !canCreateUsers then cancel("requires Linux with root access and adapter")

    // Create group first
    AdapterClient.createGroup(adapterPath, GroupEffect("kit-test-grp2", system = true))

    val result = AdapterClient.createUser(
      adapterPath,
      UserEffect("kit-test-usr", "kit-test-grp2", "/var/lib/kit-test", "/sbin/nologin", system = true),
    )
    result.isRight shouldBe true
    result.toOption.get.ok shouldBe true
    result.toOption.get.uid shouldBe defined

    // Verify user actually exists
    val pb = new ProcessBuilder("getent", "passwd", "kit-test-usr")
    val p = pb.start()
    val output = new String(p.getInputStream.readAllBytes()).trim
    p.waitFor() shouldBe 0
    output should include("kit-test-usr")

    // Cleanup
    AdapterClient.removeUser(adapterPath, "kit-test-usr")
    AdapterClient.removeGroup(adapterPath, "kit-test-grp2")
  }

  "remove user via real adapter" taggedAs Tier2 in {
    if !canCreateUsers then cancel("requires Linux with root access and adapter")

    // Create then remove
    AdapterClient.createGroup(adapterPath, GroupEffect("kit-test-grp3", system = true))
    AdapterClient.createUser(
      adapterPath,
      UserEffect("kit-test-usr2", "kit-test-grp3", "/var/lib/kit-test2", "/sbin/nologin", system = true),
    )

    val result = AdapterClient.removeUser(adapterPath, "kit-test-usr2")
    result.isRight shouldBe true
    result.toOption.get.ok shouldBe true

    // Verify user is gone
    val pb = new ProcessBuilder("getent", "passwd", "kit-test-usr2")
    val p = pb.start()
    p.waitFor() should not be 0

    // Cleanup
    AdapterClient.removeGroup(adapterPath, "kit-test-grp3")
  }

  "full activation with real adapter" taggedAs Tier2 in {
    if !canCreateUsers then cancel("requires Linux with root access and adapter")

    val engine = new ActivationEngine(testRoot.toString, Map("user-database" -> adapterPath))

    val effects = Effects(
      groups = List(GroupEffect("kit-act-grp", system = true)),
      users = List(UserEffect("kit-act-usr", "kit-act-grp", "/var/lib/kit-act", "/sbin/nologin", system = true)),
      directories = List(DirectoryEffect("/var/lib/kit-act", "kit-act-usr", "kit-act-grp", "0750")),
      capabilities = Nil,
      services = Nil,
      generators = Nil,
      firstRun = None,
    )

    val diff = EffectOps.diff(Effects.empty, effects)
    val result = engine.apply(diff)
    result.isRight shouldBe true

    // Verify everything was created
    val pb1 = new ProcessBuilder("getent", "group", "kit-act-grp")
    pb1.start().waitFor() shouldBe 0

    val pb2 = new ProcessBuilder("getent", "passwd", "kit-act-usr")
    pb2.start().waitFor() shouldBe 0

    Files.exists(testRoot.resolve("var/lib/kit-act")) shouldBe true

    // Cleanup
    AdapterClient.removeUser(adapterPath, "kit-act-usr")
    AdapterClient.removeGroup(adapterPath, "kit-act-grp")
  }
}
