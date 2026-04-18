package io.github.edadma.kitd

import io.github.edadma.kit.*

import java.nio.file.{Files, Path, Paths}

/** Manages profiles and generations at <root>/kit/profiles/. */
class ProfileManager(root: String):
  private val prefix = if root.endsWith("/") then root.dropRight(1) else root
  val profilesDir: Path = Paths.get(s"$prefix/kit/profiles")

  def init(): Unit =
    Files.createDirectories(profilesDir.resolve("system/generations"))
    Files.createDirectories(profilesDir.resolve("users"))

  /** Get the path to a profile directory. */
  def profileDir(system: Boolean, user: Option[String] = None): Path =
    if system then profilesDir.resolve("system")
    else
      val u = user.getOrElse(System.getProperty("user.name"))
      val userDir = profilesDir.resolve(s"users/$u")
      Files.createDirectories(userDir.resolve("generations"))
      userDir

  /** Get the current generation number for a profile, or 0 if none. */
  def currentGeneration(profile: Path): Int =
    val current = profile.resolve("current")
    if !Files.exists(current) then 0
    else
      val target = Files.readSymbolicLink(current)
      target.getFileName.toString.toIntOption.getOrElse(0)

  /** Get the next generation number. */
  def nextGeneration(profile: Path): Int =
    currentGeneration(profile) + 1

  /** Create a new generation directory and return its path. */
  def createGeneration(profile: Path, genNum: Int): Path =
    val genDir = profile.resolve(s"generations/$genNum")
    Files.createDirectories(genDir)
    genDir

  /** Atomically switch the current symlink to point to a new generation. */
  def switchCurrent(profile: Path, genNum: Int): Unit =
    val current = profile.resolve("current")
    val target = Paths.get(s"generations/$genNum")
    val tmp = profile.resolve(".current-tmp")

    // Create new symlink at temp location, then atomic rename
    Files.deleteIfExists(tmp)
    Files.createSymbolicLink(tmp, target)
    Files.move(tmp, current, java.nio.file.StandardCopyOption.ATOMIC_MOVE)

  /** List all generation numbers for a profile. */
  def listGenerations(profile: Path): List[Int] =
    val gensDir = profile.resolve("generations")
    if !Files.exists(gensDir) then Nil
    else
      val stream = Files.list(gensDir)
      try
        stream
          .iterator()
          .nn
          .asInstanceOf[java.util.Iterator[Path]]
          .asScala
          .flatMap(p => p.getFileName.toString.toIntOption)
          .toList
          .sorted
      finally stream.close()

  private implicit class IteratorOps[A](it: java.util.Iterator[A]):
    def asScala: Iterator[A] = new Iterator[A]:
      def hasNext: Boolean = it.hasNext
      def next(): A = it.next()
