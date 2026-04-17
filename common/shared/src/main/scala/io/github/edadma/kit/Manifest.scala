package io.github.edadma.kit

/** A parsed, validated package manifest. */
case class Manifest(
    name: String,
    version: String,
    target: String,
    contentHash: ContentHash,
    scope: Scope,
    requiresFeatures: List[String],
    deps: List[DepRef],
    effects: Effects,
    tests: List[PackageTest],
)

/** A declared self-test for the packaged software. */
case class PackageTest(
    name: String,
    binary: String,
    requiresFeatures: List[String],
)

/** Content-addressed identity of a package. */
case class ContentHash(algorithm: String, digest: String):
  override def toString: String = s"$algorithm-$digest"

object ContentHash:
  def parse(s: String): Either[String, ContentHash] =
    s.split("-", 2) match
      case Array(algo, digest) if algo.nonEmpty && digest.nonEmpty =>
        Right(ContentHash(algo, digest))
      case _ => Left(s"invalid content hash: '$s' (expected 'algorithm-digest')")

/** Package scope: system or user. */
enum Scope:
  case System, User

object Scope:
  def parse(s: String): Either[String, Scope] = s match
    case "system" => Right(Scope.System)
    case "user"   => Right(Scope.User)
    case other    => Left(s"invalid scope: '$other' (expected 'system' or 'user')")

/** An exact-pinned dependency reference. */
case class DepRef(
    name: String,
    version: String,
    contentHash: ContentHash,
)

/** The complete set of effects declared by a package. */
case class Effects(
    groups: List[GroupEffect],
    users: List[UserEffect],
    directories: List[DirectoryEffect],
    capabilities: List[CapabilityEffect],
    services: List[ServiceEffect],
    generators: List[GenerateEffect],
    firstRun: Option[FirstRunEffect],
):
  def isEmpty: Boolean =
    groups.isEmpty && users.isEmpty && directories.isEmpty &&
      capabilities.isEmpty && services.isEmpty && generators.isEmpty &&
      firstRun.isEmpty

object Effects:
  val empty: Effects = Effects(Nil, Nil, Nil, Nil, Nil, Nil, None)

case class GroupEffect(name: String, system: Boolean)

case class UserEffect(
    name: String,
    group: String,
    home: String,
    shell: String,
    system: Boolean,
)

case class DirectoryEffect(
    path: String,
    owner: String,
    group: String,
    mode: String,
)

case class CapabilityEffect(
    binary: String,
    caps: List[String],
)

case class ServiceEffect(
    name: String,
    binary: String,
    args: List[String],
    user: String,
    group: String,
    requires: List[String],
    restart: String,
    environment: Map[String, String],
)

case class GenerateEffect(
    generator: String,
    output: String,
    inputs: List[String],
)

case class FirstRunEffect(binary: String)
