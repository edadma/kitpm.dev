package io.github.edadma.kit

import io.github.edadma.crypto.Crypto

/** Computes content hashes over byte arrays. */
object ContentHasher:

  /** Compute the SHA-256 content hash of a byte array. */
  def sha256(bytes: Array[Byte]): ContentHash =
    ContentHash("sha256", Crypto.toHex(Crypto.sha256(bytes)))

  /** Compute the SHA-256 content hash of a string (UTF-8 encoded). */
  def sha256(s: String): ContentHash =
    sha256(s.getBytes("UTF-8"))

  /** Verify that bytes match an expected content hash. */
  def verify(bytes: Array[Byte], expected: ContentHash): Boolean =
    expected.algorithm match
      case "sha256" => sha256(bytes) == expected
      case _        => false
