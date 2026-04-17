package io.github.edadma.kit

import scala.collection.mutable

/** Dependency resolution: transitive closure over exact-pinned dependencies. No solver. */
object Resolution:

  /** A resolved closure: the set of all packages reachable from the root package. */
  case class Closure(packages: Map[ContentHash, Manifest])

  /**
   * Resolve the transitive closure of a manifest's dependencies.
   *
   * @param root     the manifest to resolve
   * @param lookup   given a DepRef, return the manifest for that dependency
   * @param platform the target platform triple for this installation
   * @param scope    the target profile scope (system or user)
   * @param features the set of features available on this installation
   * @return         the closure or an error
   */
  def resolve(
      root: Manifest,
      lookup: DepRef => Either[String, Manifest],
      platform: String,
      scope: Scope,
      features: Set[String],
  ): Either[String, Closure] =
    val closed = mutable.Map.empty[ContentHash, Manifest]
    val queue  = mutable.Queue.empty[Manifest]

    def validate(m: Manifest, context: String): Either[String, Unit] =
      if m.target != platform then Left(s"$context: target mismatch: expected '$platform', got '${m.target}'")
      else if scope == Scope.User && m.scope == Scope.System then
        Left(s"$context: cannot install system-scoped package '${m.name}' into a user profile")
      else
        val missing = m.requiresFeatures.filterNot(features.contains)
        if missing.nonEmpty then Left(s"$context: missing required features: ${missing.mkString(", ")}")
        else Right(())

    validate(root, root.name) match
      case Left(err) => Left(err)
      case Right(_) =>
        closed(root.contentHash) = root
        queue.enqueue(root)

        var error: Option[String] = None

        while queue.nonEmpty && error.isEmpty do
          val current = queue.dequeue()
          for dep <- current.deps if error.isEmpty do
            if !closed.contains(dep.contentHash) then
              lookup(dep) match
                case Left(err) =>
                  error = Some(s"failed to resolve '${dep.name}@${dep.version}': $err")
                case Right(m) =>
                  if m.contentHash != dep.contentHash then
                    error = Some(
                      s"content hash mismatch for '${dep.name}@${dep.version}': " +
                        s"expected ${dep.contentHash}, got ${m.contentHash}",
                    )
                  else
                    validate(m, s"${dep.name}@${dep.version}") match
                      case Left(err) => error = Some(err)
                      case Right(_) =>
                        closed(m.contentHash) = m
                        queue.enqueue(m)

        error match
          case Some(err) => Left(err)
          case None      => Right(Closure(closed.toMap))
