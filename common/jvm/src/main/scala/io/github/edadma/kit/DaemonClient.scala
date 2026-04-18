package io.github.edadma.kit

import io.github.edadma.kit.Protocol.*
import io.github.edadma.kit.ProtocolJson.{given, *}

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.net.UnixDomainSocketAddress
import java.nio.channels.{SocketChannel, Channels}
import java.nio.file.Path

/**
 * Client for communicating with kitd over a Unix domain socket.
 */
object DaemonClient:

  /** Send a request to the daemon and return the response. */
  def send(socketPath: Path, request: Request): Either[String, Response] =
    try
      val address = UnixDomainSocketAddress.of(socketPath)
      val channel = SocketChannel.open(address)

      try
        val out = new PrintWriter(new OutputStreamWriter(Channels.newOutputStream(channel)), true)
        val in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)))

        out.println(encodeRequest(request))
        val line = in.readLine()

        if line == null then Left("no response from daemon")
        else decodeResponse(line)
      finally
        channel.close()
    catch
      case e: java.nio.file.NoSuchFileException =>
        Left("daemon not running (socket not found)")
      case e: java.net.ConnectException =>
        Left("cannot connect to daemon")
      case e: Exception =>
        Left(s"communication error: ${e.getMessage}")
