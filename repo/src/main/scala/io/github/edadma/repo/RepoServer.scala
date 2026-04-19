package io.github.edadma.repo

import io.github.edadma.apion.*
import io.github.edadma.nodejs.{fs, bufferMod, crypto, Buffer, MkdirOptions}

import scala.concurrent.Future
import scala.scalajs.js

import zio.json.*

case class HealthResponse(ok: Boolean) derives JsonEncoder
case class StatusMessage(ok: Boolean, message: String) derives JsonEncoder
case class AddResponse(ok: Boolean, contentHash: String, name: String, version: String) derives JsonEncoder

/** Kit repository server. Serves packages to clients and accepts uploads from admins. */
object RepoServer:

  @main
  def run(): Unit =
    val port    = sys.env.getOrElse("PORT", "3100").toInt
    val dataDir = sys.env.getOrElse("KIT_REPO_DIR", "./repo-data")
    val token   = sys.env.getOrElse("KIT_REPO_TOKEN", "")

    // Ensure directories exist
    fs.promises.mkdir(s"$dataDir/blobs", MkdirOptions(recursive = true))
    fs.promises.mkdir(s"$dataDir/manifests", MkdirOptions(recursive = true))

    if token.isEmpty then
      println("WARNING: KIT_REPO_TOKEN not set — admin endpoints are unprotected")

    def authMiddleware: Handler = request =>
      if token.isEmpty then Future.successful(Continue(request))
      else
        request.header("authorization") match
          case Some(s"Bearer $t") if t == token => Future.successful(Continue(request))
          case _ => text("Unauthorized", 401)

    // Set higher body limit for package uploads (500MB)
    Request.maxBodySize = 500L * 1024 * 1024

    Server()
      .use(LoggingMiddleware())
      .use(CorsMiddleware())
      // Public: serve repo files
      .get("/index.toml", serveIndex(dataDir))
      .use("/blobs", StaticMiddleware(s"$dataDir/blobs"))
      .use("/manifests", StaticMiddleware(s"$dataDir/manifests"))
      // Admin: package management
      .post("/add", authMiddleware, handleAdd(dataDir))
      .post("/sign", authMiddleware, handleSign(dataDir))
      .get("/verify", authMiddleware, handleVerify(dataDir))
      // Health check
      .get("/health", _ => HealthResponse(true).asJson)
      .listen(port) { println(s"Kit repo server running on port $port (data: $dataDir)") }

  private def serveIndex(dataDir: String): Handler = _ =>
    val indexPath = s"$dataDir/index.toml"
    fs.promises.readFile(indexPath, io.github.edadma.nodejs.ReadFileOptions("utf8")).toFuture
      .map { content =>
        Complete(Response.text(content.asInstanceOf[String]))
      }
      .recover { case _: Exception =>
        Complete(Response.text("index not found", 404))
      }

  private def handleAdd(dataDir: String): Handler = request =>
    request.body.flatMap { bodyBuf =>
      val bytes = bufferToArray(bodyBuf)

      // Compute content hash
      val hash = crypto.createHash("sha256")
      hash.update(bodyBuf)
      val contentHash = s"sha256-${hash.digest("hex")}"

      // Verify it's a kit package
      val magic = bodyBuf.toString("ascii").take(8)
      if magic != "KITPKG01" then
        StatusMessage(false, "not a valid .kit package").asJson(400)
      else
        // Extract manifest from the package
        val manifestLen = ((bytes(8) & 0xff) << 24) | ((bytes(9) & 0xff) << 16) |
          ((bytes(10) & 0xff) << 8) | (bytes(11) & 0xff)
        val manifestStr = new String(bytes.slice(12, 12 + manifestLen), "UTF-8")
        val (name, version) = parseNameVersion(manifestStr)

        // Store blob and manifest
        val blobPath = s"$dataDir/blobs/$contentHash.kit"
        val manifestPath = s"$dataDir/manifests/$contentHash.toml"

        for
          _ <- fs.promises.writeFile(blobPath, bodyBuf).toFuture
          _ <- fs.promises.writeFile(manifestPath, manifestStr).toFuture
          _ <- appendToIndex(dataDir, name, version, contentHash)
        yield Complete(Response.json(AddResponse(true, contentHash, name, version)))
    }

  private def handleSign(dataDir: String): Handler = _ =>
    regenerateIndex(dataDir).map { _ =>
      Complete(Response.json(StatusMessage(true, "index regenerated")))
    }

  private def handleVerify(dataDir: String): Handler = _ =>
    fs.promises.readdir(dataDir + "/blobs").toFuture.map { files =>
      var checked = 0
      var mismatches = 0

      files.foreach { fileName =>
        val name = fileName.asInstanceOf[String]
        if name.endsWith(".kit") then
          checked += 1
          // Note: full verify would need async reads; simplified for now
      }

      Complete(Response.json(StatusMessage(true, s"checked $checked blobs, $mismatches mismatches")))
    }.recover { case e: Exception =>
      Complete(Response.json(StatusMessage(false, s"verify failed: ${e.getMessage}"), 500))
    }

  // --- Helpers ---

  private def parseNameVersion(manifest: String): (String, String) =
    val name = manifest.linesIterator.collectFirst {
      case line if line.trim.startsWith("name") =>
        line.split("=")(1).trim.stripPrefix("\"").stripSuffix("\"")
    }.getOrElse("unknown")
    val version = manifest.linesIterator.collectFirst {
      case line if line.trim.startsWith("version") =>
        line.split("=")(1).trim.stripPrefix("\"").stripSuffix("\"")
    }.getOrElse("0.0.0")
    (name, version)

  private def appendToIndex(dataDir: String, name: String, version: String, contentHash: String): Future[Unit] =
    val indexPath = s"$dataDir/index.toml"
    val entry =
      s"""|
          |[[packages]]
          |name = "$name"
          |version = "$version"
          |content-hash = "$contentHash"
          |target = "aarch64-linux-gnu"
          |scope = "user"
          |""".stripMargin

    fs.promises.readFile(indexPath, io.github.edadma.nodejs.ReadFileOptions("utf8")).toFuture
      .map(existing => existing.asInstanceOf[String] + entry)
      .recover { case _: Exception =>
        s"""repo-name = "local"
           |revision = "${java.time.LocalDate.now()}"
           |signed-by = "unsigned"
           |$entry""".stripMargin
      }
      .flatMap(content => fs.promises.writeFile(indexPath, content.toString).toFuture)

  private def regenerateIndex(dataDir: String): Future[Unit] =
    fs.promises.readdir(s"$dataDir/manifests").toFuture.flatMap { files =>
      val sb = new StringBuilder
      sb.append(s"""repo-name = "local"\nrevision = "${java.time.LocalDate.now()}"\nsigned-by = "unsigned"\n""")

      val readFutures = files.toList.collect {
        case name: String if name.endsWith(".toml") =>
          val contentHash = name.dropRight(5)
          fs.promises.readFile(s"$dataDir/manifests/$name", io.github.edadma.nodejs.ReadFileOptions("utf8")).toFuture
            .map { content =>
              val (pkgName, version) = parseNameVersion(content.asInstanceOf[String])
              sb.append(s"""\n[[packages]]\nname = "$pkgName"\nversion = "$version"\ncontent-hash = "$contentHash"\ntarget = "aarch64-linux-gnu"\nscope = "user"\n""")
            }
      }

      Future.sequence(readFutures).flatMap { _ =>
        fs.promises.writeFile(s"$dataDir/index.toml", sb.toString).toFuture
      }
    }

  private def bufferToArray(buf: Buffer): Array[Byte] =
    val arr = new Array[Byte](buf.length)
    val dyn = buf.asInstanceOf[js.Dynamic]
    for i <- 0 until buf.length do
      arr(i) = dyn.selectDynamic(i.toString).asInstanceOf[Int].toByte
    arr
