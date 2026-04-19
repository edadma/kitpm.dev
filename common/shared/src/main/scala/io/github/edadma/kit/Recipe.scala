package io.github.edadma.kit

import io.github.edadma.toml.{TomlParser as Toml, TomlValue}

/**
 * A build recipe — describes how to build a package from source.
 *
 * Example recipe.toml:
 * {{{
 * name = "hello"
 * version = "2.12"
 * target = "aarch64-linux-gnu"
 * scope = "user"
 *
 * [source]
 * url = "https://ftp.gnu.org/gnu/hello/hello-2.12.tar.gz"
 * hash = "sha256-..."
 *
 * [build]
 * steps = [
 *   "./configure --prefix=$KIT_PREFIX",
 *   "make -j$KIT_JOBS",
 *   "make install DESTDIR=$KIT_OUT",
 * ]
 *
 * [build.env]
 * CFLAGS = "-O2"
 * }}}
 */
case class Recipe(
    name: String,
    version: String,
    target: String,
    scope: Scope,
    sourceUrl: Option[String],
    sourceHash: Option[String],
    buildSteps: List[String],
    buildEnv: Map[String, String],
    deps: List[String],
)

object RecipeParser:

  def parse(input: String): Either[String, Recipe] =
    Toml.parse(input) match
      case Left(err) => Left(s"TOML parse error: $err")
      case Right(doc) =>
        for
          name    <- doc.getString("name").toRight("missing 'name'")
          version <- doc.getString("version").toRight("missing 'version'")
          target  <- doc.getString("target").toRight("missing 'target'")
          scopeStr <- doc.getString("scope").toRight("missing 'scope'")
          scope   <- Scope.parse(scopeStr)
        yield
          val sourceUrl  = doc.getString("source.url")
          val sourceHash = doc.getString("source.hash")

          val buildSteps = doc.getArr("build.steps") match
            case None => Nil
            case Some(elems) => elems.collect { case TomlValue.Str(s) => s }

          val buildEnv = doc.getTable("build.env") match
            case None => Map.empty[String, String]
            case Some(table) =>
              table.toList.flatMap { (k, v) =>
                v match
                  case TomlValue.Str(s) => Some(k -> s)
                  case _                => None
              }.toMap

          val deps = doc.getArr("deps") match
            case None => Nil
            case Some(elems) => elems.collect { case TomlValue.Str(s) => s }

          Recipe(name, version, target, scope, sourceUrl, sourceHash, buildSteps, buildEnv, deps)
