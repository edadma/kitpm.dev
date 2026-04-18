package io.github.edadma.kitd

import io.github.edadma.kit.*
import io.github.edadma.kit.Protocol.*
import io.github.edadma.kit.ProtocolJson.{given, *}

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.{ServerSocketChannel, SocketChannel, Channels}
import java.nio.file.{Files, Path, Paths}

import zio.json.*

/**
 * The kitd socket server. Listens on a Unix domain socket,
 * accepts connections, dispatches requests to the Daemon.
 */
class DaemonServer(daemon: Daemon):
  private val prefix = if daemon.root.endsWith("/") then daemon.root.dropRight(1) else daemon.root
  private val socketPath = Paths.get(s"$prefix/kit/var/kitd.sock")

  import scala.compiletime.uninitialized
  private var serverChannel: ServerSocketChannel = uninitialized
  @volatile private var running = false

  def start(): Unit =
    Files.deleteIfExists(socketPath)
    serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    serverChannel.bind(UnixDomainSocketAddress.of(socketPath))
    running = true

    val thread = new Thread(() => acceptLoop(), "kitd-accept")
    thread.setDaemon(true)
    thread.start()

  def stop(): Unit =
    running = false
    if serverChannel != null then
      serverChannel.close()
    Files.deleteIfExists(socketPath)

  def socketFile: Path = socketPath

  private def acceptLoop(): Unit =
    while running do
      try
        val client = serverChannel.accept()
        if client != null then
          val handler = new Thread(() => handleClient(client), "kitd-handler")
          handler.setDaemon(true)
          handler.start()
      catch
        case _: java.nio.channels.AsynchronousCloseException => () // server shutting down
        case e: Exception =>
          if running then System.err.println(s"kitd: accept error: ${e.getMessage}")

  private def handleClient(channel: SocketChannel): Unit =
    try
      val in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)))
      val out = new PrintWriter(new OutputStreamWriter(Channels.newOutputStream(channel)), true)

      val line = in.readLine()
      if line != null then
        val response = dispatch(line)
        out.println(response)

      channel.close()
    catch
      case e: Exception =>
        System.err.println(s"kitd: handler error: ${e.getMessage}")
        try channel.close() catch case _: Exception => ()

  private def dispatch(json: String): String =
    json.fromJson[RequestEnvelope] match
      case Left(err) =>
        encodeResponse(ErrorResponse(s"invalid request: $err"))
      case Right(envelope) =>
        val response = envelope.op match
          case "ping" =>
            SuccessResponse(PongData(daemon.root, "0.0.1"))

          case "install" =>
            envelope.payload.fromJson[InstallRequest] match
              case Left(err) => ErrorResponse(s"invalid install request: $err")
              case Right(req) =>
                // For now: install requires a pre-fetched blob path in the request
                // Full implementation will resolve + fetch from repos
                ErrorResponse("install via IPC not yet implemented (use daemon.install directly)")

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
                  GenerationEntry(n, 0) // TODO: read package count from manifest
                }
                SuccessResponse(GenerationsData(gens, current))

          case other =>
            ErrorResponse(s"unknown operation: '$other'")

        encodeResponse(response)
