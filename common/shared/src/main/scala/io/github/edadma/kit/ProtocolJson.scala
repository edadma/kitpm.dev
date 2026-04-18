package io.github.edadma.kit

import zio.json.*

/** JSON codecs for the IPC protocol. */
object ProtocolJson:

  // --- Request codecs ---

  given JsonEncoder[Protocol.InstallRequest] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.InstallRequest] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.RemoveRequest] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.RemoveRequest] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.ListRequest] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.ListRequest] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.RollbackRequest] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.RollbackRequest] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.GCRequest] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.GCRequest] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.TestRequest] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.TestRequest] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.GenerationsRequest] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.GenerationsRequest] = DeriveJsonDecoder.gen

  // Envelope for dispatching requests by "op" field
  case class RequestEnvelope(op: String, payload: String)
  given JsonEncoder[RequestEnvelope] = DeriveJsonEncoder.gen
  given JsonDecoder[RequestEnvelope] = DeriveJsonDecoder.gen

  def encodeRequest(req: Protocol.Request): String =
    req match
      case r: Protocol.InstallRequest     => s"""{"op":"install","payload":${r.toJson}}"""
      case r: Protocol.RemoveRequest      => s"""{"op":"remove","payload":${r.toJson}}"""
      case r: Protocol.ListRequest        => s"""{"op":"list","payload":${r.toJson}}"""
      case r: Protocol.RollbackRequest    => s"""{"op":"rollback","payload":${r.toJson}}"""
      case r: Protocol.GCRequest          => s"""{"op":"gc","payload":${r.toJson}}"""
      case r: Protocol.TestRequest        => s"""{"op":"test","payload":${r.toJson}}"""
      case r: Protocol.GenerationsRequest => s"""{"op":"generations","payload":${r.toJson}}"""
      case Protocol.PingRequest           => """{"op":"ping","payload":"{}"}"""

  // --- Response codecs ---

  given JsonEncoder[Protocol.PackageListEntry] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.PackageListEntry] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.TestResultEntry] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.TestResultEntry] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.GenerationEntry] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.GenerationEntry] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.InstalledData] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.InstalledData] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.RemovedData] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.RemovedData] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.PackageListData] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.PackageListData] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.RollbackData] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.RollbackData] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.GCData] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.GCData] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.TestResultsData] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.TestResultsData] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.GenerationsData] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.GenerationsData] = DeriveJsonDecoder.gen

  given JsonEncoder[Protocol.PongData] = DeriveJsonEncoder.gen
  given JsonDecoder[Protocol.PongData] = DeriveJsonDecoder.gen

  // Response envelope
  case class ResponseEnvelope(ok: Boolean, `type`: String, data: Option[String], message: Option[String])
  given JsonEncoder[ResponseEnvelope] = DeriveJsonEncoder.gen
  given JsonDecoder[ResponseEnvelope] = DeriveJsonDecoder.gen

  def encodeResponse(resp: Protocol.Response): String =
    resp match
      case Protocol.SuccessResponse(data) =>
        val (typeName, dataJson) = data match
          case d: Protocol.InstalledData    => ("installed", d.toJson)
          case d: Protocol.RemovedData      => ("removed", d.toJson)
          case d: Protocol.PackageListData  => ("package-list", d.toJson)
          case d: Protocol.RollbackData     => ("rollback", d.toJson)
          case d: Protocol.GCData           => ("gc", d.toJson)
          case d: Protocol.TestResultsData  => ("test-results", d.toJson)
          case d: Protocol.GenerationsData  => ("generations", d.toJson)
          case d: Protocol.PongData         => ("pong", d.toJson)
          case Protocol.AckData             => ("ack", "{}")
        ResponseEnvelope(ok = true, typeName, Some(dataJson), None).toJson
      case Protocol.ErrorResponse(message) =>
        ResponseEnvelope(ok = false, "error", None, Some(message)).toJson

  def decodeResponse(json: String): Either[String, Protocol.Response] =
    json.fromJson[ResponseEnvelope].left.map(e => s"JSON decode error: $e").flatMap { env =>
      if !env.ok then Right(Protocol.ErrorResponse(env.message.getOrElse("unknown error")))
      else
        val dataJson = env.data.getOrElse("{}")
        env.`type` match
          case "installed"    => dataJson.fromJson[Protocol.InstalledData].map(Protocol.SuccessResponse(_))
          case "removed"      => dataJson.fromJson[Protocol.RemovedData].map(Protocol.SuccessResponse(_))
          case "package-list" => dataJson.fromJson[Protocol.PackageListData].map(Protocol.SuccessResponse(_))
          case "rollback"     => dataJson.fromJson[Protocol.RollbackData].map(Protocol.SuccessResponse(_))
          case "gc"           => dataJson.fromJson[Protocol.GCData].map(Protocol.SuccessResponse(_))
          case "test-results" => dataJson.fromJson[Protocol.TestResultsData].map(Protocol.SuccessResponse(_))
          case "generations"  => dataJson.fromJson[Protocol.GenerationsData].map(Protocol.SuccessResponse(_))
          case "pong"         => dataJson.fromJson[Protocol.PongData].map(Protocol.SuccessResponse(_))
          case "ack"          => Right(Protocol.SuccessResponse(Protocol.AckData))
          case other          => Left(s"unknown response type: '$other'")
    }
