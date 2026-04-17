package io.github.edadma.kit

import io.github.edadma.toml.{TomlParser as Toml, TomlDocument, TomlValue}

import scala.collection.immutable.VectorMap

/** Parses and validates a TOML manifest string into a typed Manifest. */
object ManifestParser:

  def parse(input: String): Either[String, Manifest] =
    Toml.parse(input) match
      case Left(err) => Left(s"TOML parse error: $err")
      case Right(doc) =>
        for
          name    <- requireString(doc, "name")
          version <- requireString(doc, "version")
          target  <- requireString(doc, "target")
          hashStr <- requireString(doc, "content-hash")
          hash    <- ContentHash.parse(hashStr)
          scopeStr <- requireString(doc, "scope")
          scope    <- Scope.parse(scopeStr)
          features <- optionalStringList(doc, "requires-features")
          deps     <- parseDeps(doc)
          effects  <- parseEffects(doc)
        yield Manifest(name, version, target, hash, scope, features, deps, effects)

  // --- Top-level field helpers ---

  private def requireString(doc: TomlDocument, path: String): Either[String, String] =
    doc.getString(path).toRight(s"missing required field: '$path'")

  private def optionalStringList(doc: TomlDocument, path: String): Either[String, List[String]] =
    doc.getArr(path) match
      case None => Right(Nil)
      case Some(elems) =>
        val strs = elems.collect { case TomlValue.Str(s) => s }
        if strs.length != elems.length then Left(s"'$path' must be an array of strings")
        else Right(strs)

  // --- Dependency parsing ---

  private def parseDeps(doc: TomlDocument): Either[String, List[DepRef]] =
    doc.getArr("deps") match
      case None => Right(Nil)
      case Some(elems) =>
        val results = elems.zipWithIndex.map { (v, i) =>
          v match
            case TomlValue.Obj(fields) => parseDepRef(fields, i)
            case _                     => Left(s"deps[$i]: expected a table")
        }
        sequence(results)

  private def parseDepRef(fields: VectorMap[String, TomlValue], index: Int): Either[String, DepRef] =
    for
      name    <- fieldStr(fields, "name", s"deps[$index]")
      version <- fieldStr(fields, "version", s"deps[$index]")
      hashStr <- fieldStr(fields, "content-hash", s"deps[$index]")
      hash    <- ContentHash.parse(hashStr).left.map(e => s"deps[$index]: $e")
    yield DepRef(name, version, hash)

  // --- Effects parsing ---

  private def parseEffects(doc: TomlDocument): Either[String, Effects] =
    doc.getTable("effects") match
      case None => Right(Effects.empty)
      case Some(_) =>
        for
          groups       <- parseEffectList(doc, "effects.group", parseGroupEffect)
          users        <- parseEffectList(doc, "effects.user", parseUserEffect)
          directories  <- parseEffectList(doc, "effects.directory", parseDirectoryEffect)
          capabilities <- parseEffectList(doc, "effects.capability", parseCapabilityEffect)
          services     <- parseEffectList(doc, "effects.service", parseServiceEffect)
          generators   <- parseEffectList(doc, "effects.generate", parseGenerateEffect)
          firstRun     <- parseFirstRun(doc)
        yield Effects(groups, users, directories, capabilities, services, generators, firstRun)

  private def parseEffectList[A](
      doc: TomlDocument,
      path: String,
      parser: (VectorMap[String, TomlValue], Int) => Either[String, A],
  ): Either[String, List[A]] =
    doc.getArr(path) match
      case None => Right(Nil)
      case Some(elems) =>
        val results = elems.zipWithIndex.map { (v, i) =>
          v match
            case TomlValue.Obj(fields) => parser(fields, i)
            case _                     => Left(s"$path[$i]: expected a table")
        }
        sequence(results)

  private def parseGroupEffect(fields: VectorMap[String, TomlValue], i: Int): Either[String, GroupEffect] =
    for
      name   <- fieldStr(fields, "name", s"effects.group[$i]")
      system <- fieldBoolOpt(fields, "system").map(_.getOrElse(false))
    yield GroupEffect(name, system)

  private def parseUserEffect(fields: VectorMap[String, TomlValue], i: Int): Either[String, UserEffect] =
    for
      name   <- fieldStr(fields, "name", s"effects.user[$i]")
      group  <- fieldStr(fields, "group", s"effects.user[$i]")
      home   <- fieldStr(fields, "home", s"effects.user[$i]")
      shell  <- fieldStr(fields, "shell", s"effects.user[$i]")
      system <- fieldBoolOpt(fields, "system").map(_.getOrElse(false))
    yield UserEffect(name, group, home, shell, system)

  private def parseDirectoryEffect(fields: VectorMap[String, TomlValue], i: Int): Either[String, DirectoryEffect] =
    for
      path  <- fieldStr(fields, "path", s"effects.directory[$i]")
      owner <- fieldStr(fields, "owner", s"effects.directory[$i]")
      group <- fieldStr(fields, "group", s"effects.directory[$i]")
      mode  <- fieldStr(fields, "mode", s"effects.directory[$i]")
    yield DirectoryEffect(path, owner, group, mode)

  private def parseCapabilityEffect(fields: VectorMap[String, TomlValue], i: Int): Either[String, CapabilityEffect] =
    for
      binary <- fieldStr(fields, "binary", s"effects.capability[$i]")
      caps   <- fieldStrList(fields, "caps", s"effects.capability[$i]")
    yield CapabilityEffect(binary, caps)

  private def parseServiceEffect(fields: VectorMap[String, TomlValue], i: Int): Either[String, ServiceEffect] =
    for
      name    <- fieldStr(fields, "name", s"effects.service[$i]")
      binary  <- fieldStr(fields, "binary", s"effects.service[$i]")
      args    <- fieldStrListOpt(fields, "args").map(_.getOrElse(Nil))
      user    <- fieldStr(fields, "user", s"effects.service[$i]")
      group   <- fieldStr(fields, "group", s"effects.service[$i]")
      requires <- fieldStrListOpt(fields, "requires").map(_.getOrElse(Nil))
      restart <- fieldStrOpt(fields, "restart").map(_.getOrElse("on-failure"))
      env     <- fieldStringMap(fields, "environment", s"effects.service[$i]")
    yield ServiceEffect(name, binary, args, user, group, requires, restart, env)

  private def parseGenerateEffect(fields: VectorMap[String, TomlValue], i: Int): Either[String, GenerateEffect] =
    for
      generator <- fieldStr(fields, "generator", s"effects.generate[$i]")
      output    <- fieldStr(fields, "output", s"effects.generate[$i]")
      inputs    <- fieldStrList(fields, "inputs", s"effects.generate[$i]")
    yield GenerateEffect(generator, output, inputs)

  private def parseFirstRun(doc: TomlDocument): Either[String, Option[FirstRunEffect]] =
    doc.getString("effects.first-run.binary") match
      case None         => Right(None)
      case Some(binary) => Right(Some(FirstRunEffect(binary)))

  // --- Field extraction helpers ---

  private def fieldStr(fields: VectorMap[String, TomlValue], key: String, ctx: String): Either[String, String] =
    fields.get(key) match
      case Some(TomlValue.Str(s)) => Right(s)
      case Some(_)                => Left(s"$ctx.$key: expected a string")
      case None                   => Left(s"$ctx: missing required field '$key'")

  private def fieldStrOpt(fields: VectorMap[String, TomlValue], key: String): Either[String, Option[String]] =
    fields.get(key) match
      case Some(TomlValue.Str(s)) => Right(Some(s))
      case Some(_)                => Left(s"$key: expected a string")
      case None                   => Right(None)

  private def fieldBoolOpt(fields: VectorMap[String, TomlValue], key: String): Either[String, Option[Boolean]] =
    fields.get(key) match
      case Some(TomlValue.Bool(b)) => Right(Some(b))
      case Some(_)                 => Left(s"$key: expected a boolean")
      case None                    => Right(None)

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

  private def fieldStringMap(
      fields: VectorMap[String, TomlValue],
      key: String,
      ctx: String,
  ): Either[String, Map[String, String]] =
    fields.get(key) match
      case None => Right(Map.empty)
      case Some(TomlValue.Obj(entries)) =>
        val pairs = entries.toList.map { (k, v) =>
          v match
            case TomlValue.Str(s) => Right(k -> s)
            case _                => Left(s"$ctx.$key.$k: expected a string value")
        }
        sequence(pairs).map(_.toMap)
      case Some(_) => Left(s"$ctx.$key: expected a table")

  // --- Utilities ---

  private def sequence[A](results: List[Either[String, A]]): Either[String, List[A]] =
    results.foldRight(Right(Nil): Either[String, List[A]]) { (elem, acc) =>
      for
        a  <- elem
        as <- acc
      yield a :: as
    }
