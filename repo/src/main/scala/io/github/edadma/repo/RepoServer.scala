package io.github.edadma.repo

import io.github.edadma.apion.*

import scala.concurrent.Future

import zio.json.*

case class HealthResponse(ok: Boolean) derives JsonEncoder
case class StatusMessage(ok: Boolean, message: String) derives JsonEncoder

/** Kit repository server. Serves packages to clients and accepts uploads from admins. */
object RepoServer:

  @main
  def run(): Unit =
    val port    = sys.env.getOrElse("PORT", "3100").toInt
    val dataDir = sys.env.getOrElse("KIT_REPO_DIR", "./repo-data")
    val token   = sys.env.getOrElse("KIT_REPO_TOKEN", "")

    if token.isEmpty then
      println("WARNING: KIT_REPO_TOKEN not set — admin endpoints are unprotected")

    def authMiddleware: Handler = request =>
      if token.isEmpty then Future.successful(Continue(request))
      else
        request.header("authorization") match
          case Some(s"Bearer $t") if t == token => Future.successful(Continue(request))
          case _ => text("Unauthorized", 401)

    Server()
      .use(LoggingMiddleware())
      .use(CorsMiddleware())
      // Public: serve repo files as static content
      .use("/blobs", StaticMiddleware(s"$dataDir/blobs"))
      .use("/manifests", StaticMiddleware(s"$dataDir/manifests"))
      .get("/index.toml", _ => text("index not found", 404)) // TODO: serve actual index
      // Admin: package management
      .post("/add", authMiddleware, handleAdd(dataDir))
      .post("/sign", authMiddleware, handleSign(dataDir))
      .get("/verify", authMiddleware, handleVerify(dataDir))
      // Health check
      .get("/health", _ => HealthResponse(true).asJson)
      .listen(port) { println(s"Kit repo server running on port $port (data: $dataDir)") }

  private def handleAdd(dataDir: String): Handler = _ =>
    StatusMessage(false, "add not yet implemented").asJson(501)

  private def handleSign(dataDir: String): Handler = _ =>
    StatusMessage(false, "sign not yet implemented").asJson(501)

  private def handleVerify(dataDir: String): Handler = _ =>
    StatusMessage(false, "verify not yet implemented").asJson(501)
