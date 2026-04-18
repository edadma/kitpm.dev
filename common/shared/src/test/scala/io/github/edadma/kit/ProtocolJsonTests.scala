package io.github.edadma.kit

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import zio.json.*

class ProtocolJsonTests extends AnyFreeSpec with Matchers {

  import Protocol.*
  import ProtocolJson.{given, *}

  // --- Request encoding ---

  "request encoding" - {
    "encode install request" in {
      val json = encodeRequest(InstallRequest("nginx", Some("1.27.0"), system = true))
      json should include("\"op\":\"install\"")
      json should include("nginx")
      json should include("1.27.0")
    }

    "encode remove request" in {
      val json = encodeRequest(RemoveRequest("nginx", system = false))
      json should include("\"op\":\"remove\"")
      json should include("nginx")
    }

    "encode list request" in {
      val json = encodeRequest(ListRequest(system = true, user = None))
      json should include("\"op\":\"list\"")
    }

    "encode rollback request" in {
      val json = encodeRequest(RollbackRequest(Some(5), system = true))
      json should include("\"op\":\"rollback\"")
    }

    "encode gc request" in {
      val json = encodeRequest(GCRequest(dryRun = true))
      json should include("\"op\":\"gc\"")
    }

    "encode test request" in {
      val json = encodeRequest(TestRequest(Some("nginx"), force = false))
      json should include("\"op\":\"test\"")
    }

    "encode generations request" in {
      val json = encodeRequest(GenerationsRequest(system = false, user = Some("alice")))
      json should include("\"op\":\"generations\"")
    }

    "encode ping request" in {
      val json = encodeRequest(PingRequest)
      json should include("\"op\":\"ping\"")
    }
  }

  // --- Response encoding/decoding roundtrip ---

  "response roundtrip" - {
    "installed data" in {
      val resp = SuccessResponse(InstalledData("nginx", "1.27.0", "sha256-abc", 3))
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.isRight shouldBe true
      decoded.toOption.get match
        case SuccessResponse(InstalledData(name, version, hash, gen)) =>
          name shouldBe "nginx"
          version shouldBe "1.27.0"
          hash shouldBe "sha256-abc"
          gen shouldBe 3
        case other => fail(s"Expected InstalledData, got $other")
    }

    "removed data" in {
      val resp = SuccessResponse(RemovedData("nginx", 4))
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.isRight shouldBe true
      decoded.toOption.get match
        case SuccessResponse(RemovedData(name, gen)) =>
          name shouldBe "nginx"
          gen shouldBe 4
        case other => fail(s"Expected RemovedData, got $other")
    }

    "package list data" in {
      val resp = SuccessResponse(PackageListData(List(
        PackageListEntry("nginx", "1.27.0", "sha256-aaa"),
        PackageListEntry("hello", "1.0.0", "sha256-bbb"),
      )))
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.isRight shouldBe true
      decoded.toOption.get match
        case SuccessResponse(PackageListData(pkgs)) =>
          pkgs should have length 2
          pkgs.head.name shouldBe "nginx"
          pkgs(1).name shouldBe "hello"
        case other => fail(s"Expected PackageListData, got $other")
    }

    "empty package list" in {
      val resp = SuccessResponse(PackageListData(Nil))
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.toOption.get match
        case SuccessResponse(PackageListData(pkgs)) => pkgs shouldBe empty
        case other => fail(s"Expected empty PackageListData, got $other")
    }

    "rollback data" in {
      val resp = SuccessResponse(RollbackData(5, 3))
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.toOption.get match
        case SuccessResponse(RollbackData(from, to)) =>
          from shouldBe 5
          to shouldBe 3
        case other => fail(s"Expected RollbackData, got $other")
    }

    "gc data" in {
      val resp = SuccessResponse(GCData(2, List("sha256-aaa", "sha256-bbb")))
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.toOption.get match
        case SuccessResponse(GCData(count, hashes)) =>
          count shouldBe 2
          hashes shouldBe List("sha256-aaa", "sha256-bbb")
        case other => fail(s"Expected GCData, got $other")
    }

    "test results data" in {
      val resp = SuccessResponse(TestResultsData(
        "nginx",
        List(
          TestResultEntry("starts", "PASS", 142),
          TestResultEntry("serves", "FAIL (exit 1)", 200),
        ),
        passed = 1,
        failed = 1,
        skipped = 0,
      ))
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.toOption.get match
        case SuccessResponse(TestResultsData(name, results, p, f, s)) =>
          name shouldBe "nginx"
          results should have length 2
          p shouldBe 1
          f shouldBe 1
        case other => fail(s"Expected TestResultsData, got $other")
    }

    "generations data" in {
      val resp = SuccessResponse(GenerationsData(
        List(GenerationEntry(1, 3), GenerationEntry(2, 5)),
        current = 2,
      ))
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.toOption.get match
        case SuccessResponse(GenerationsData(gens, current)) =>
          gens should have length 2
          current shouldBe 2
        case other => fail(s"Expected GenerationsData, got $other")
    }

    "pong data" in {
      val resp = SuccessResponse(PongData("/home/alice/.kit", "0.0.1"))
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.toOption.get match
        case SuccessResponse(PongData(root, version)) =>
          root shouldBe "/home/alice/.kit"
          version shouldBe "0.0.1"
        case other => fail(s"Expected PongData, got $other")
    }

    "ack data" in {
      val resp = SuccessResponse(AckData)
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.toOption.get match
        case SuccessResponse(AckData) => succeed
        case other => fail(s"Expected AckData, got $other")
    }

    "error response" in {
      val resp = ErrorResponse("package not found")
      val json = encodeResponse(resp)
      val decoded = decodeResponse(json)
      decoded.toOption.get match
        case ErrorResponse(msg) => msg shouldBe "package not found"
        case other => fail(s"Expected ErrorResponse, got $other")
    }
  }

  // --- Decode error handling ---

  "decode errors" - {
    "reject invalid JSON" in {
      decodeResponse("not json").isLeft shouldBe true
    }

    "reject unknown response type" in {
      val json = """{"ok":true,"type":"unknown","data":"{}"}"""
      decodeResponse(json).isLeft shouldBe true
    }
  }
}
