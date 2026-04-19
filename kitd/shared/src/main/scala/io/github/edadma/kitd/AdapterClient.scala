package io.github.edadma.kitd

import io.github.edadma.kit.*
import io.github.edadma.kit.AdapterProtocol.*

import zio.json.*

/**
 * Invokes platform adapters as subprocesses.
 * Each adapter is an executable that reads JSON on stdin and writes JSON on stdout.
 */
object AdapterClient:

  /**
   * Invoke an adapter with a request and return the response.
   *
   * @param adapterPath path to the adapter executable
   * @param action      the action name (e.g., "create-group")
   * @param payload     JSON-encoded request payload
   * @return            the adapter response or an error
   */
  def invoke(adapterPath: String, action: String, payload: String): Either[String, AdapterResponse] =
    val envelope = AdapterEnvelope(action, payload).toJson

    try
      val pb = new ProcessBuilder(adapterPath)
      pb.redirectErrorStream(false)
      val process = pb.start()

      // Send request on stdin
      val out = process.getOutputStream
      out.write((envelope + "\n").getBytes("UTF-8"))
      out.flush()
      out.close()

      // Read response from stdout
      val in = process.getInputStream
      val response = new String(in.readAllBytes(), "UTF-8").trim
      val exitCode = process.waitFor()

      if exitCode != 0 then
        // Read stderr for error details
        val errBytes = process.getErrorStream.readAllBytes()
        val stderr = new String(errBytes, "UTF-8").trim
        Left(s"adapter exited with code $exitCode: $stderr")
      else if response.isEmpty then
        Left("adapter returned empty response")
      else
        response.fromJson[AdapterResponse].left.map(e => s"invalid adapter response: $e")
    catch
      case e: java.io.IOException =>
        Left(s"failed to invoke adapter '$adapterPath': ${e.getMessage}")
      case e: Exception =>
        Left(s"adapter error: ${e.getMessage}")

  // --- Convenience methods ---

  def createGroup(adapterPath: String, effect: GroupEffect): Either[String, AdapterResponse] =
    val payload = CreateGroupRequest(effect.name, effect.system).toJson
    invoke(adapterPath, "create-group", payload)

  def removeGroup(adapterPath: String, name: String): Either[String, AdapterResponse] =
    val payload = RemoveGroupRequest(name).toJson
    invoke(adapterPath, "remove-group", payload)

  def createUser(adapterPath: String, effect: UserEffect): Either[String, AdapterResponse] =
    val payload = CreateUserRequest(effect.name, effect.group, effect.home, effect.shell, effect.system).toJson
    invoke(adapterPath, "create-user", payload)

  def removeUser(adapterPath: String, name: String): Either[String, AdapterResponse] =
    val payload = RemoveUserRequest(name).toJson
    invoke(adapterPath, "remove-user", payload)
