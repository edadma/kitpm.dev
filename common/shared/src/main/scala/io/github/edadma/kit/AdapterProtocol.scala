package io.github.edadma.kit

import zio.json.*

/**
 * Protocol between kitd and platform adapters.
 * Adapters are separate processes invoked via stdin/stdout JSON.
 */
object AdapterProtocol:

  // --- Requests (kitd → adapter) ---

  sealed trait AdapterRequest

  case class CreateGroupRequest(name: String, system: Boolean) extends AdapterRequest derives JsonEncoder, JsonDecoder
  case class RemoveGroupRequest(name: String) extends AdapterRequest derives JsonEncoder, JsonDecoder

  case class CreateUserRequest(
      name: String,
      group: String,
      home: String,
      shell: String,
      system: Boolean,
  ) extends AdapterRequest derives JsonEncoder, JsonDecoder

  case class RemoveUserRequest(name: String) extends AdapterRequest derives JsonEncoder, JsonDecoder

  // --- Responses (adapter → kitd) ---

  case class AdapterResponse(
      ok: Boolean,
      message: String = "",
      uid: Option[Int] = None,
      gid: Option[Int] = None,
  ) derives JsonEncoder, JsonDecoder

  // --- Envelope for dispatch ---

  case class AdapterEnvelope(
      action: String,  // "create-group", "remove-group", "create-user", "remove-user"
      payload: String, // JSON-encoded request
  ) derives JsonEncoder, JsonDecoder
