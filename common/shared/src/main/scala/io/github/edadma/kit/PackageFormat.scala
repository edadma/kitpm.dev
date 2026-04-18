package io.github.edadma.kit

import java.io.{ByteArrayOutputStream, DataInputStream, DataOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets

/**
 * Kit package format — a simple binary container for a manifest + file tree.
 * No external dependencies. Pure byte manipulation.
 *
 * Format v1 (magic "KITPKG01"):
 * {{{
 * [8 bytes]  magic "KITPKG01"
 * [4 bytes]  manifest length (big-endian u32)
 * [N bytes]  manifest TOML (UTF-8)
 * [4 bytes]  file count (big-endian u32)
 * for each file:
 *   [2 bytes]  path length (big-endian u16)
 *   [N bytes]  relative path (UTF-8, forward slashes)
 *   [2 bytes]  mode (big-endian u16, e.g., 0755 = 493)
 *   [4 bytes]  data length (big-endian u32)
 *   [N bytes]  file data (raw bytes)
 * }}}
 */
object PackageFormat:

  val Magic = "KITPKG01"

  /** A file entry in a package. */
  case class FileEntry(
      path: String,
      mode: Int,
      data: Array[Byte],
  )

  /** A complete package ready for writing or just read. */
  case class Package(
      manifest: String,
      files: List[FileEntry],
  )

  // --- Writing ---

  /** Write a package to an output stream. */
  def write(pkg: Package, out: OutputStream): Unit =
    val dos = new DataOutputStream(out)

    // Magic
    dos.write(Magic.getBytes(StandardCharsets.US_ASCII))

    // Manifest
    val manifestBytes = pkg.manifest.getBytes(StandardCharsets.UTF_8)
    dos.writeInt(manifestBytes.length)
    dos.write(manifestBytes)

    // Files
    dos.writeInt(pkg.files.length)
    for f <- pkg.files do
      val pathBytes = f.path.getBytes(StandardCharsets.UTF_8)
      dos.writeShort(pathBytes.length)
      dos.write(pathBytes)
      dos.writeShort(f.mode)
      dos.writeInt(f.data.length)
      dos.write(f.data)

    dos.flush()

  /** Write a package to a byte array. */
  def writeBytes(pkg: Package): Array[Byte] =
    val baos = new ByteArrayOutputStream()
    write(pkg, baos)
    baos.toByteArray

  // --- Reading ---

  /** Read a package from an input stream. */
  def read(in: InputStream): Either[String, Package] =
    import scala.util.boundary
    import scala.util.boundary.break

    val dis = new DataInputStream(in)
    try
      boundary:
        // Magic
        val magicBuf = new Array[Byte](8)
        dis.readFully(magicBuf)
        val magic = new String(magicBuf, StandardCharsets.US_ASCII)
        if magic != Magic then
          break(Left(s"invalid magic: expected '$Magic', got '$magic'"))

        // Manifest
        val manifestLen = dis.readInt()
        if manifestLen < 0 || manifestLen > 10 * 1024 * 1024 then
          break(Left(s"invalid manifest length: $manifestLen"))
        val manifestBuf = new Array[Byte](manifestLen)
        dis.readFully(manifestBuf)
        val manifest = new String(manifestBuf, StandardCharsets.UTF_8)

        // Files
        val fileCount = dis.readInt()
        if fileCount < 0 || fileCount > 100000 then
          break(Left(s"invalid file count: $fileCount"))

        val files = List.newBuilder[FileEntry]
        for _ <- 0 until fileCount do
          val pathLen = dis.readUnsignedShort()
          val pathBuf = new Array[Byte](pathLen)
          dis.readFully(pathBuf)
          val path = new String(pathBuf, StandardCharsets.UTF_8)

          val mode = dis.readUnsignedShort()

          val dataLen = dis.readInt()
          if dataLen < 0 || dataLen > 500 * 1024 * 1024 then
            break(Left(s"invalid data length for '$path': $dataLen"))
          val dataBuf = new Array[Byte](dataLen)
          dis.readFully(dataBuf)

          files += FileEntry(path, mode, dataBuf)

        Right(Package(manifest, files.result()))
    catch
      case e: java.io.EOFException => Left("unexpected end of package data")
      case e: Exception            => Left(s"error reading package: ${e.getMessage}")

  /** Read a package from a byte array. */
  def readBytes(bytes: Array[Byte]): Either[String, Package] =
    read(new java.io.ByteArrayInputStream(bytes))
