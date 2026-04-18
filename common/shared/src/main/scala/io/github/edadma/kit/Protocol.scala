package io.github.edadma.kit

/**
 * IPC protocol between kit (CLI) and kitd (daemon).
 * Wire format is JSON over a Unix domain socket, one JSON object per line.
 */
object Protocol:

  // --- Requests ---

  sealed trait Request:
    def op: String

  case class InstallRequest(
      name: String,
      version: Option[String],
      system: Boolean,
  ) extends Request:
    val op = "install"

  case class RemoveRequest(
      name: String,
      system: Boolean,
  ) extends Request:
    val op = "remove"

  case class ListRequest(
      system: Boolean,
      user: Option[String],
  ) extends Request:
    val op = "list"

  case class RollbackRequest(
      toGeneration: Option[Int],
      system: Boolean,
  ) extends Request:
    val op = "rollback"

  case class GCRequest(dryRun: Boolean) extends Request:
    val op = "gc"

  case class TestRequest(
      name: Option[String],
      force: Boolean,
  ) extends Request:
    val op = "test"

  case class GenerationsRequest(
      system: Boolean,
      user: Option[String],
  ) extends Request:
    val op = "generations"

  case object PingRequest extends Request:
    val op = "ping"

  // --- Responses ---

  sealed trait Response:
    def ok: Boolean

  case class SuccessResponse(
      data: ResponseData,
  ) extends Response:
    val ok = true

  case class ErrorResponse(
      message: String,
  ) extends Response:
    val ok = false

  // --- Response data variants ---

  sealed trait ResponseData

  case class InstalledData(
      name: String,
      version: String,
      contentHash: String,
      generation: Int,
  ) extends ResponseData

  case class RemovedData(
      name: String,
      generation: Int,
  ) extends ResponseData

  case class PackageListData(
      packages: List[PackageListEntry],
  ) extends ResponseData

  case class PackageListEntry(
      name: String,
      version: String,
      contentHash: String,
  )

  case class RollbackData(
      fromGeneration: Int,
      toGeneration: Int,
  ) extends ResponseData

  case class GCData(
      removedCount: Int,
      removedHashes: List[String],
  ) extends ResponseData

  case class TestResultsData(
      packageName: String,
      results: List[TestResultEntry],
      passed: Int,
      failed: Int,
      skipped: Int,
  ) extends ResponseData

  case class TestResultEntry(
      name: String,
      outcome: String,
      durationMs: Long,
  )

  case class GenerationsData(
      generations: List[GenerationEntry],
      current: Int,
  ) extends ResponseData

  case class GenerationEntry(
      number: Int,
      packageCount: Int,
  )

  case class PongData(
      root: String,
      version: String,
  ) extends ResponseData

  case object AckData extends ResponseData
