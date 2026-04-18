package io.github.edadma.kitd

import io.github.edadma.kit.*

import java.nio.file.{Files, Path, Paths}

/** Manages the content-addressed store at <root>/kit/store/. */
class Store(root: String):
  private val prefix = if root.endsWith("/") then root.dropRight(1) else root
  val storeDir: Path = Paths.get(s"$prefix/kit/store")

  def init(): Unit =
    Files.createDirectories(storeDir)

  /** Check if a package is already in the store. */
  def contains(hash: ContentHash): Boolean =
    Files.exists(storeDir.resolve(hash.toString))

  /** Get the store path for a content hash (may not exist yet). */
  def pathFor(hash: ContentHash, name: String, version: String): Path =
    storeDir.resolve(s"$hash-$name-$version")

  /** List all content hashes present in the store. */
  def listHashes(): Set[ContentHash] =
    if !Files.exists(storeDir) then Set.empty
    else
      val stream = Files.list(storeDir)
      try
        stream
          .iterator()
          .nn
          .asInstanceOf[java.util.Iterator[Path]]
          .asScala
          .flatMap { p =>
            val name = p.getFileName.toString
            val dashIdx = name.indexOf('-', name.indexOf('-') + 1)
            if dashIdx > 0 then ContentHash.parse(name.substring(0, dashIdx)).toOption
            else None
          }
          .toSet
      finally stream.close()

  private implicit class IteratorOps[A](it: java.util.Iterator[A]):
    def asScala: Iterator[A] = new Iterator[A]:
      def hasNext: Boolean = it.hasNext
      def next(): A = it.next()
