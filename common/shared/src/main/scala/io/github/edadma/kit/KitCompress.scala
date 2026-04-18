package io.github.edadma.kit

/**
 * Simple LZ4-style compression for Kit packages.
 * Pure Scala, zero dependencies. Operates on byte arrays.
 *
 * Format: sequence of blocks, each block is:
 *   [token]  1 byte: high nibble = literal length (0-14, 15 = extended)
 *                    low nibble  = match length - 4 (0-14, 15 = extended)
 *   [extra literal length] variable: if high nibble == 15, read bytes adding 255 until < 255
 *   [literals] N bytes of literal data
 *   [offset] 2 bytes little-endian: match offset (how far back to copy)
 *   [extra match length] variable: if low nibble == 15, read bytes adding 255 until < 255
 *
 * The last block has no match (offset/match length omitted) — signaled by reaching end of input.
 */
object KitCompress:

  private val HashLog    = 16
  private val HashSize   = 1 << HashLog
  private val MinMatch   = 4
  private val MaxOffset  = 65535

  // --- Compression ---

  def compress(input: Array[Byte]): Array[Byte] =
    if input.isEmpty then return Array.empty

    val out = new java.io.ByteArrayOutputStream(input.length)
    val hashTable = new Array[Int](HashSize)
    java.util.Arrays.fill(hashTable, -1)

    var anchor = 0   // start of literals
    var pos    = 0   // current scan position
    val limit  = input.length - MinMatch

    while pos < limit do
      val h = hash4(input, pos)
      val ref = hashTable(h)
      hashTable(h) = pos

      if ref >= 0 && pos - ref <= MaxOffset && matchAt(input, ref, pos) then
        // Found a match — emit literals + match
        val litLen = pos - anchor
        val matchLen = countMatch(input, ref + MinMatch, pos + MinMatch, input.length) + MinMatch
        val offset = pos - ref

        emitToken(out, litLen, matchLen - MinMatch, input, anchor, offset)

        // Advance past the match
        pos += matchLen
        anchor = pos
      else
        pos += 1

    // Emit final literals (no match)
    val remaining = input.length - anchor
    if remaining > 0 then
      emitFinalLiterals(out, remaining, input, anchor)

    out.toByteArray

  private def hash4(data: Array[Byte], pos: Int): Int =
    val v = ((data(pos) & 0xff) |
      ((data(pos + 1) & 0xff) << 8) |
      ((data(pos + 2) & 0xff) << 16) |
      ((data(pos + 3) & 0xff) << 24))
    ((v * 2654435761L) >>> (32 - HashLog)).toInt & (HashSize - 1)

  private def matchAt(data: Array[Byte], ref: Int, pos: Int): Boolean =
    data(ref) == data(pos) &&
      data(ref + 1) == data(pos + 1) &&
      data(ref + 2) == data(pos + 2) &&
      data(ref + 3) == data(pos + 3)

  private def countMatch(data: Array[Byte], a: Int, b: Int, limit: Int): Int =
    var i = 0
    while a + i < limit && b + i < limit && data(a + i) == data(b + i) do i += 1
    i

  private def emitToken(
      out: java.io.ByteArrayOutputStream,
      litLen: Int,
      matchLenMinusMin: Int,
      input: Array[Byte],
      litStart: Int,
      offset: Int,
  ): Unit =
    val litNibble   = if litLen >= 15 then 15 else litLen
    val matchNibble = if matchLenMinusMin >= 15 then 15 else matchLenMinusMin
    out.write((litNibble << 4) | matchNibble)

    // Extended literal length
    if litLen >= 15 then writeExtended(out, litLen - 15)

    // Literal bytes
    out.write(input, litStart, litLen)

    // Match offset (little-endian)
    out.write(offset & 0xff)
    out.write((offset >> 8) & 0xff)

    // Extended match length
    if matchLenMinusMin >= 15 then writeExtended(out, matchLenMinusMin - 15)

  private def emitFinalLiterals(
      out: java.io.ByteArrayOutputStream,
      litLen: Int,
      input: Array[Byte],
      litStart: Int,
  ): Unit =
    val litNibble = if litLen >= 15 then 15 else litLen
    out.write(litNibble << 4) // match nibble = 0, no match follows

    if litLen >= 15 then writeExtended(out, litLen - 15)

    out.write(input, litStart, litLen)

  private def writeExtended(out: java.io.ByteArrayOutputStream, value: Int): Unit =
    var v = value
    while v >= 255 do
      out.write(255)
      v -= 255
    out.write(v)

  // --- Decompression ---

  def decompress(input: Array[Byte], originalSize: Int): Array[Byte] =
    if input.isEmpty then return Array.empty

    val output = new Array[Byte](originalSize)
    var ip     = 0 // input position
    var op     = 0 // output position

    while ip < input.length && op < originalSize do
      val token    = input(ip) & 0xff
      ip += 1
      var litLen   = token >>> 4
      val matchNib = token & 0x0f

      // Extended literal length
      if litLen == 15 then
        var b = 255
        while b == 255 do
          b = input(ip) & 0xff
          ip += 1
          litLen += b

      // Copy literals
      System.arraycopy(input, ip, output, op, litLen)
      ip += litLen
      op += litLen

      // Last block: no match data follows
      if ip < input.length && op < originalSize then
        // Match offset (little-endian)
        val offset = (input(ip) & 0xff) | ((input(ip + 1) & 0xff) << 8)
        ip += 2

        // Match length
        var ml = matchNib + MinMatch
        if matchNib == 15 then
          var b = 255
          while b == 255 do
            b = input(ip) & 0xff
            ip += 1
            ml += b

        // Copy match (byte by byte for overlapping matches)
        val matchStart = op - offset
        var i = 0
        while i < ml do
          output(op + i) = output(matchStart + i)
          i += 1
        op += ml

    output
