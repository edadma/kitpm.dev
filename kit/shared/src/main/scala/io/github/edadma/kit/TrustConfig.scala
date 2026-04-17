package io.github.edadma.kit

import io.github.edadma.toml.{TomlParser as Toml, TomlValue}

import scala.collection.immutable.VectorMap

/** Parsed trust configuration from repos.toml. */
case class TrustConfig(repos: List[RepoConfig])

case class RepoConfig(
    url: String,
    trustedKeys: List[String],
    priority: Int,
    targets: List[String],
)

/** Parses repos.toml into typed config. */
object TrustConfigParser:

  def parse(input: String): Either[String, TrustConfig] =
    Toml.parse(input) match
      case Left(err) => Left(s"TOML parse error: $err")
      case Right(doc) =>
        doc.getArr("repo") match
          case None => Right(TrustConfig(Nil))
          case Some(elems) =>
            val results = elems.zipWithIndex.map { (v, i) =>
              v match
                case TomlValue.Obj(fields) => parseRepo(fields, i)
                case _                     => Left(s"repo[$i]: expected a table")
            }
            sequence(results).map(TrustConfig(_))

  private def parseRepo(fields: VectorMap[String, TomlValue], i: Int): Either[String, RepoConfig] =
    for
      url     <- fieldStr(fields, "url", s"repo[$i]")
      keys    <- fieldStrList(fields, "trusted-keys", s"repo[$i]")
      priority <- fieldLong(fields, "priority", s"repo[$i]")
      targets <- fieldStrListOpt(fields, "targets").map(_.getOrElse(Nil))
    yield RepoConfig(url, keys, priority.toInt, targets)

  private def fieldStr(fields: VectorMap[String, TomlValue], key: String, ctx: String): Either[String, String] =
    fields.get(key) match
      case Some(TomlValue.Str(s)) => Right(s)
      case Some(_)                => Left(s"$ctx.$key: expected a string")
      case None                   => Left(s"$ctx: missing required field '$key'")

  private def fieldLong(fields: VectorMap[String, TomlValue], key: String, ctx: String): Either[String, Long] =
    fields.get(key) match
      case Some(TomlValue.Num(n)) => Right(n)
      case Some(_)                => Left(s"$ctx.$key: expected an integer")
      case None                   => Left(s"$ctx: missing required field '$key'")

  private def fieldStrList(
      fields: VectorMap[String, TomlValue],
      key: String,
      ctx: String,
  ): Either[String, List[String]] =
    fields.get(key) match
      case Some(TomlValue.Arr(elems)) =>
        val strs = elems.collect { case TomlValue.Str(s) => s }
        if strs.length != elems.length then Left(s"$ctx.$key: all elements must be strings")
        else Right(strs)
      case Some(_) => Left(s"$ctx.$key: expected an array")
      case None    => Left(s"$ctx: missing required field '$key'")

  private def fieldStrListOpt(
      fields: VectorMap[String, TomlValue],
      key: String,
  ): Either[String, Option[List[String]]] =
    fields.get(key) match
      case Some(TomlValue.Arr(elems)) =>
        val strs = elems.collect { case TomlValue.Str(s) => s }
        if strs.length != elems.length then Left(s"$key: all elements must be strings")
        else Right(Some(strs))
      case Some(_) => Left(s"$key: expected an array")
      case None    => Right(None)

  private def sequence[A](results: List[Either[String, A]]): Either[String, List[A]] =
    results.foldRight(Right(Nil): Either[String, List[A]]) { (elem, acc) =>
      for
        a  <- elem
        as <- acc
      yield a :: as
    }
