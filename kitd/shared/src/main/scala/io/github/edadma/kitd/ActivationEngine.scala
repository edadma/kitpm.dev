package io.github.edadma.kitd

import io.github.edadma.kit.*

import java.nio.file.{Files, Paths}

/**
 * The activation engine. Applies effect diffs by invoking platform adapters
 * and performing POSIX operations.
 */
class ActivationEngine(root: String, adaptersConfig: Map[String, String]):
  private val prefix = if root.endsWith("/") then root.dropRight(1) else root

  /**
   * Apply an effect diff (forward activation).
   * Returns Right(()) on success, Left(error) on failure.
   */
  def apply(diff: EffectOps.EffectDiff): Either[String, Unit] =
    import scala.util.boundary
    import scala.util.boundary.break

    boundary:
      // Removals first (reverse dependency order)
      for s <- diff.removeServices do stopService(s)
      for g <- diff.removeGenerators do removeGenerated(g)
      for c <- diff.removeCapabilities do revokeCapability(c)
      for u <- diff.removeUsers do removeUser(u)
      for g <- diff.removeGroups do removeGroup(g)

      // Additions in dependency order
      for g <- diff.addGroups do
        createGroup(g) match
          case Left(err) => break(Left(s"failed to create group '${g.name}': $err"))
          case Right(_)  => ()

      for u <- diff.addUsers do
        createUser(u) match
          case Left(err) => break(Left(s"failed to create user '${u.name}': $err"))
          case Right(_)  => ()

      for d <- diff.addDirectories do
        createDirectory(d) match
          case Left(err) => break(Left(s"failed to create directory '${d.path}': $err"))
          case Right(_)  => ()

      Right(())

  // --- Group operations ---

  private def createGroup(effect: GroupEffect): Either[String, Unit] =
    adaptersConfig.get("user-database") match
      case None => Left("no user-database adapter configured")
      case Some(adapterPath) =>
        AdapterClient.createGroup(adapterPath, effect).flatMap { resp =>
          if resp.ok then Right(())
          else Left(resp.message)
        }

  private def removeGroup(effect: GroupEffect): Unit =
    adaptersConfig.get("user-database").foreach { adapterPath =>
      AdapterClient.removeGroup(adapterPath, effect.name)
    }

  // --- User operations ---

  private def createUser(effect: UserEffect): Either[String, Unit] =
    adaptersConfig.get("user-database") match
      case None => Left("no user-database adapter configured")
      case Some(adapterPath) =>
        AdapterClient.createUser(adapterPath, effect).flatMap { resp =>
          if resp.ok then Right(())
          else Left(resp.message)
        }

  private def removeUser(effect: UserEffect): Unit =
    adaptersConfig.get("user-database").foreach { adapterPath =>
      AdapterClient.removeUser(adapterPath, effect.name)
    }

  // --- Directory operations (pure POSIX, no adapter needed) ---

  private def createDirectory(effect: DirectoryEffect): Either[String, Unit] =
    try
      val path = Paths.get(EffectOps.substituteRoot(
        Effects.empty.copy(directories = List(effect)), root,
      ).directories.head.path)
      Files.createDirectories(path)
      // chmod/chown would go here (requires adapter or native calls)
      Right(())
    catch
      case e: Exception => Left(e.getMessage)

  // --- Stubs for unimplemented operations ---

  private def stopService(effect: ServiceEffect): Unit = ()
  private def removeGenerated(effect: GenerateEffect): Unit = ()
  private def revokeCapability(effect: CapabilityEffect): Unit = ()
