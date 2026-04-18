package io.github.edadma.kitd

import io.github.edadma.kit.*
import io.github.edadma.kit.Protocol.*
import io.github.edadma.kit.ProtocolJson.{given, *}
import io.github.edadma.cross_platform.{createSocketServer, SocketServer, SocketConnection}

import zio.json.*

/**
 * The kitd socket server. Listens on a Unix domain socket,
 * accepts connections, dispatches requests to the Daemon.
 */
class DaemonServer(daemon: Daemon):
  private val prefix = if daemon.root.endsWith("/") then daemon.root.dropRight(1) else daemon.root
  val socketPath: String = s"$prefix/kit/var/kitd.sock"

  private var server: SocketServer = scala.compiletime.uninitialized
  @volatile private var running = false

  def start(): Unit =
    server = createSocketServer(socketPath)
    running = true

    val thread = new Thread(() => acceptLoop(), "kitd-accept")
    thread.setDaemon(true)
    thread.start()

  def stop(): Unit =
    running = false
    if server != null then server.close()

  private def acceptLoop(): Unit =
    while running do
      try
        val conn = server.accept()
        val handler = new Thread(() => handleClient(conn), "kitd-handler")
        handler.setDaemon(true)
        handler.start()
      catch
        case _: java.io.IOException if !running => () // server shutting down
        case e: Exception =>
          if running then System.err.println(s"kitd: accept error: ${e.getMessage}")

  private def handleClient(conn: SocketConnection): Unit =
    try
      conn.readLine() match
        case None => ()
        case Some(line) =>
          val response = dispatch(line)
          conn.writeLine(response)
      conn.close()
    catch
      case e: Exception =>
        System.err.println(s"kitd: handler error: ${e.getMessage}")
        try conn.close() catch case _: Exception => ()

  private def dispatch(json: String): String =
    json.fromJson[RequestEnvelope] match
      case Left(err) =>
        encodeResponse(ErrorResponse(s"invalid request: $err"))
      case Right(envelope) =>
        val response = envelope.op match
          case "ping" =>
            SuccessResponse(PongData(daemon.root, "0.0.1"))

          case "remove" =>
            envelope.payload.fromJson[RemoveRequest] match
              case Left(err) => ErrorResponse(s"invalid remove request: $err")
              case Right(req) =>
                daemon.remove(req.name, req.system) match
                  case Right(gen) => SuccessResponse(RemovedData(req.name, gen))
                  case Left(err)  => ErrorResponse(err)

          case "list" =>
            envelope.payload.fromJson[ListRequest] match
              case Left(err) => ErrorResponse(s"invalid list request: $err")
              case Right(req) =>
                val pkgs = daemon.list(req.system)
                val entries = pkgs.map(p => PackageListEntry(p.name, p.version, p.contentHash.toString))
                SuccessResponse(PackageListData(entries))

          case "gc" =>
            envelope.payload.fromJson[GCRequest] match
              case Left(err) => ErrorResponse(s"invalid gc request: $err")
              case Right(req) =>
                val removed = daemon.gc()
                SuccessResponse(GCData(removed.size, removed.map(_.toString).toList))

          case "generations" =>
            envelope.payload.fromJson[GenerationsRequest] match
              case Left(err) => ErrorResponse(s"invalid generations request: $err")
              case Right(req) =>
                val profile = daemon.profiles.profileDir(req.system)
                val current = daemon.profiles.currentGeneration(profile)
                val gens = daemon.profiles.listGenerations(profile).map { n =>
                  GenerationEntry(n, 0)
                }
                SuccessResponse(GenerationsData(gens, current))

          case other =>
            ErrorResponse(s"unknown operation: '$other'")

        encodeResponse(response)
