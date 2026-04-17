package io.github.edadma.kit

import io.github.edadma.toml.{TomlParser as Toml, TomlDocument, TomlValue}

import scala.collection.immutable.VectorMap

/** A parsed repository index. */
case class RepoIndex(
    repoName: String,
    revision: String,
    signedBy: String,
    packages: List[RepoPackageEntry],
)

/** A single package entry in the repository index. */
case class RepoPackageEntry(
    name: String,
    version: String,
    contentHash: ContentHash,
    target: String,
    scope: Scope,
)

/** Parses and validates repository index TOML. */
object RepoIndexParser:

  def parse(input: String): Either[String, RepoIndex] =
    Toml.parse(input) match
      case Left(err) => Left(s"TOML parse error: $err")
      case Right(doc) =>
        for
          repoName <- requireString(doc, "repo-name")
          revision <- requireString(doc, "revision")
          signedBy <- requireString(doc, "signed-by")
          packages <- parsePackages(doc)
        yield RepoIndex(repoName, revision, signedBy, packages)

  private def requireString(doc: TomlDocument, path: String): Either[String, String] =
    doc.getString(path).toRight(s"missing required field: '$path'")

  private def parsePackages(doc: TomlDocument): Either[String, List[RepoPackageEntry]] =
    doc.getArr("packages") match
      case None => Right(Nil)
      case Some(elems) =>
        val results = elems.zipWithIndex.map { (v, i) =>
          v match
            case TomlValue.Obj(fields) => parseEntry(fields, i)
            case _                     => Left(s"packages[$i]: expected a table")
        }
        sequence(results)

  private def parseEntry(fields: VectorMap[String, TomlValue], i: Int): Either[String, RepoPackageEntry] =
    for
      name    <- fieldStr(fields, "name", s"packages[$i]")
      version <- fieldStr(fields, "version", s"packages[$i]")
      hashStr <- fieldStr(fields, "content-hash", s"packages[$i]")
      hash    <- ContentHash.parse(hashStr).left.map(e => s"packages[$i]: $e")
      target  <- fieldStr(fields, "target", s"packages[$i]")
      scopeStr <- fieldStr(fields, "scope", s"packages[$i]")
      scope    <- Scope.parse(scopeStr).left.map(e => s"packages[$i]: $e")
    yield RepoPackageEntry(name, version, hash, target, scope)

  private def fieldStr(fields: VectorMap[String, TomlValue], key: String, ctx: String): Either[String, String] =
    fields.get(key) match
      case Some(TomlValue.Str(s)) => Right(s)
      case Some(_)                => Left(s"$ctx.$key: expected a string")
      case None                   => Left(s"$ctx: missing required field '$key'")

  private def sequence[A](results: List[Either[String, A]]): Either[String, List[A]] =
    results.foldRight(Right(Nil): Either[String, List[A]]) { (elem, acc) =>
      for
        a  <- elem
        as <- acc
      yield a :: as
    }

/** Lookup operations on a set of repository indexes. */
object RepoLookup:

  /**
   * Look up a package across multiple repositories, ordered by priority.
   * Returns the first match by name, version, and content hash.
   */
  def lookup(
      repos: List[(RepoIndex, Int)],
      name: String,
      version: String,
      contentHash: ContentHash,
  ): Option[RepoPackageEntry] =
    repos
      .sortBy(-_._2)
      .flatMap(_._1.packages)
      .find(e => e.name == name && e.version == version && e.contentHash == contentHash)

  /**
   * Look up the latest version of a package by name across repositories.
   * Filters by target platform. Returns all matching entries sorted by priority.
   */
  def lookupByName(
      repos: List[(RepoIndex, Int)],
      name: String,
      target: String,
  ): List[RepoPackageEntry] =
    repos
      .sortBy(-_._2)
      .flatMap(_._1.packages)
      .filter(e => e.name == name && e.target == target)
