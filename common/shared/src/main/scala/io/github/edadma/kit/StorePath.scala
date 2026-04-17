package io.github.edadma.kit

/** Store path construction and parsing. */
object StorePath:

  /** Build the canonical store path for a package. */
  def build(root: String, hash: ContentHash, name: String, version: String): String =
    val prefix = if root.endsWith("/") then root.dropRight(1) else root
    s"$prefix/kit/store/$hash-$name-$version"

  /** Extract the content hash from a store path, if it matches the expected format. */
  def extractHash(path: String): Option[ContentHash] =
    val storeSuffix = "/kit/store/"
    val idx = path.indexOf(storeSuffix)
    if idx < 0 then None
    else
      val rest = path.substring(idx + storeSuffix.length)
      val dashIdx = rest.indexOf('-')
      if dashIdx < 0 then None
      else
        val hashStr = rest.substring(0, dashIdx)
        // The hash itself contains a dash (algo-digest), so we need the first two dash-separated parts
        ContentHash.parse(rest.take(rest.indexOf('-', dashIdx + 1))).toOption
