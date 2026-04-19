package io.github.edadma.kit

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.file.{Files, Path, Paths}

/**
 * Client for fetching from Kit repository servers.
 */
object RepoClient:

  /** Fetch the index from a repo URL. */
  def fetchIndex(repoUrl: String): Either[String, String] =
    httpGet(s"$repoUrl/index.toml").map(new String(_, "UTF-8"))

  /** Fetch a package blob by content hash and save to a temp file. */
  def fetchBlob(repoUrl: String, contentHash: ContentHash): Either[String, Path] =
    httpGet(s"$repoUrl/blobs/$contentHash.kit").map { bytes =>
      val tmp = Files.createTempFile("kit-blob-", ".kit")
      Files.write(tmp, bytes)
      tmp
    }

  /** Upload a .kit file to a repo. */
  def uploadPackage(repoUrl: String, kitFile: Path, token: String): Either[String, String] =
    val bytes = Files.readAllBytes(kitFile)
    httpPost(s"$repoUrl/add", bytes, token)

  private def httpGet(url: String): Either[String, Array[Byte]] =
    try
      val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("GET")
      conn.setConnectTimeout(30000)
      conn.setReadTimeout(60000)

      val status = conn.getResponseCode
      if status != 200 then
        Left(s"HTTP $status from $url")
      else
        Right(readAll(conn.getInputStream))
    catch
      case e: Exception => Left(s"fetch failed: ${e.getMessage}")

  private def httpPost(url: String, body: Array[Byte], token: String): Either[String, String] =
    try
      val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setDoOutput(true)
      conn.setConnectTimeout(30000)
      conn.setReadTimeout(120000)
      conn.setRequestProperty("Content-Type", "application/octet-stream")
      if token.nonEmpty then
        conn.setRequestProperty("Authorization", s"Bearer $token")

      conn.getOutputStream.write(body)
      conn.getOutputStream.close()

      val status = conn.getResponseCode
      if status >= 200 && status < 300 then
        Right(new String(readAll(conn.getInputStream), "UTF-8"))
      else
        Left(s"HTTP $status: ${new String(readAll(conn.getErrorStream), "UTF-8")}")
    catch
      case e: Exception => Left(s"upload failed: ${e.getMessage}")

  private def readAll(in: InputStream): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var n = in.read(buf)
    while n >= 0 do
      out.write(buf, 0, n)
      n = in.read(buf)
    out.toByteArray
