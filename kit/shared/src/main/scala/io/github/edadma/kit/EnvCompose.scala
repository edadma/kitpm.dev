package io.github.edadma.kit

/** Environment composition: build PATH and other env vars from profiles. */
object EnvCompose:

  /** A profile with its current generation path. */
  case class ProfilePath(profileDir: String, name: String)

  /**
   * Compose PATH from a list of profiles, ordered by priority (first wins).
   * Typically: user profile first, then system profile.
   */
  def composePath(root: String, profiles: List[ProfilePath]): String =
    val prefix = if root.endsWith("/") then root.dropRight(1) else root
    profiles
      .map(p => s"$prefix/kit/profiles/${p.profileDir}/current/bin")
      .mkString(":")

  /**
   * Compose LD_LIBRARY_PATH (or equivalent) from profiles.
   */
  def composeLibPath(root: String, profiles: List[ProfilePath]): String =
    val prefix = if root.endsWith("/") then root.dropRight(1) else root
    profiles
      .map(p => s"$prefix/kit/profiles/${p.profileDir}/current/lib")
      .mkString(":")

  /**
   * Compose the full set of environment variables for shell init.
   */
  def composeEnv(root: String, profiles: List[ProfilePath]): Map[String, String] =
    Map(
      "KIT_ROOT" -> root,
      "PATH"     -> composePath(root, profiles),
      "LD_LIBRARY_PATH" -> composeLibPath(root, profiles),
    )
