package io.github.edadma.kit

import io.github.edadma.kit.Protocol.*
import io.github.edadma.kit.ProtocolJson.{given, *}
import io.github.edadma.cross_platform.connectSocket

/**
 * Client for communicating with kitd over a Unix domain socket.
 */
object DaemonClient:

  /** Send a request to the daemon and return the response. */
  def send(socketPath: String, request: Request): Either[String, Response] =
    try
      val conn = connectSocket(socketPath)
      try
        conn.writeLine(encodeRequest(request))
        conn.readLine() match
          case None       => Left("no response from daemon")
          case Some(line) => decodeResponse(line)
      finally
        conn.close()
    catch
      case e: java.io.IOException =>
        Left(s"cannot connect to daemon: ${e.getMessage}")
      case e: Exception =>
        Left(s"communication error: ${e.getMessage}")
