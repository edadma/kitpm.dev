package io.github.edadma.kitd

import io.github.edadma.kit.*
import io.github.edadma.kit.Protocol.*
import io.github.edadma.kit.ProtocolJson.{given, *}

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.nio.file.{Files, Path}

import scala.compiletime.uninitialized

/**
 * Tier 1 tests for the kitd socket server + kit client IPC.
 */
class DaemonServerTests extends AnyFreeSpec with Matchers with BeforeAndAfterEach {

  var testRoot: Path = uninitialized
  var daemon: Daemon = uninitialized
  var server: DaemonServer = uninitialized

  override def beforeEach(): Unit =
    testRoot = Files.createTempDirectory("kit-ipc-test-")
    daemon = new Daemon(testRoot.toString)
    daemon.init()
    server = new DaemonServer(daemon)
    server.start()
    Thread.sleep(50) // give server thread time to bind

  override def afterEach(): Unit =
    server.stop()
    deleteRecursive(testRoot)

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.forEach(deleteRecursive)
      finally stream.close()
    Files.deleteIfExists(path)

  private def send(request: Request): Either[String, Response] =
    DaemonClient.send(server.socketFile, request)

  // --- Tests ---

  "ping" in {
    val result = send(PingRequest)
    result.isRight shouldBe true
    result.toOption.get match
      case SuccessResponse(PongData(root, version)) =>
        root shouldBe testRoot.toString
        version shouldBe "0.0.1"
      case other => fail(s"Expected PongData, got $other")
  }

  "list empty" in {
    val result = send(ListRequest(system = false, user = None))
    result.isRight shouldBe true
    result.toOption.get match
      case SuccessResponse(PackageListData(pkgs)) =>
        pkgs shouldBe empty
      case other => fail(s"Expected PackageListData, got $other")
  }

  "list after install" in {
    // Install directly via daemon (IPC install not yet implemented)
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    val result = send(ListRequest(system = false, user = None))
    result.isRight shouldBe true
    result.toOption.get match
      case SuccessResponse(PackageListData(pkgs)) =>
        pkgs should have length 1
        pkgs.head.name shouldBe "hello"
      case other => fail(s"Expected PackageListData, got $other")
  }

  "remove via IPC" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    val result = send(RemoveRequest("hello", system = false))
    result.isRight shouldBe true
    result.toOption.get match
      case SuccessResponse(RemovedData(name, gen)) =>
        name shouldBe "hello"
        gen shouldBe 2
      case other => fail(s"Expected RemovedData, got $other")

    // Verify actually removed
    daemon.list(system = false) shouldBe empty
  }

  "remove nonexistent via IPC" in {
    val result = send(RemoveRequest("ghost", system = false))
    result.isRight shouldBe true
    result.toOption.get match
      case ErrorResponse(msg) =>
        msg should include("not installed")
      case other => fail(s"Expected ErrorResponse, got $other")
  }

  "gc via IPC" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)
    daemon.remove("hello", system = false)

    val result = send(GCRequest(dryRun = false))
    result.isRight shouldBe true
    result.toOption.get match
      case SuccessResponse(GCData(count, hashes)) =>
        count shouldBe 1
      case other => fail(s"Expected GCData, got $other")
  }

  "generations via IPC" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    val result = send(GenerationsRequest(system = false, user = None))
    result.isRight shouldBe true
    result.toOption.get match
      case SuccessResponse(GenerationsData(gens, current)) =>
        current shouldBe 1
        gens should have length 1
      case other => fail(s"Expected GenerationsData, got $other")
  }

  "multiple sequential requests on separate connections" in {
    val blob = createHelloBlob()
    daemon.install(helloManifest, blob, system = false)
    deleteRecursive(blob)

    // First request: list
    val r1 = send(ListRequest(system = false, user = None))
    r1.isRight shouldBe true

    // Second request: ping
    val r2 = send(PingRequest)
    r2.isRight shouldBe true
    r2.toOption.get match
      case SuccessResponse(PongData(_, _)) => succeed
      case other => fail(s"Expected PongData, got $other")
  }

  // --- Helpers ---

  private val helloManifest = Manifest(
    "hello", "1.0.0", "x86_64-linux-gnu",
    ContentHash("sha256", "hellohash123"), Scope.User, Nil, Nil, Effects.empty,
    List(PackageTest("prints-hello", "bin/hello-test", Nil)),
  )

  private def createHelloBlob(): Path =
    val blobDir = Files.createTempDirectory("kit-blob-")
    val binDir = blobDir.resolve("bin")
    Files.createDirectories(binDir)
    Files.writeString(binDir.resolve("hello"), "#!/bin/sh\necho \"hello world\"\n")
    binDir.resolve("hello").toFile.setExecutable(true)
    Files.writeString(blobDir.resolve("manifest.toml"), "name = \"hello\"")
    blobDir
}
