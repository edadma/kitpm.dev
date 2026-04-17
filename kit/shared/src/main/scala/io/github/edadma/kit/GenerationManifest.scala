package io.github.edadma.kit

/**
 * A generation manifest: the closure of packages in a generation,
 * plus the merged and validated effects.
 */
case class GenerationManifest(
    generation: Int,
    profile: String,
    packages: List[GenerationPackageEntry],
    effects: Effects,
)

/** A package entry in a generation manifest. */
case class GenerationPackageEntry(
    name: String,
    version: String,
    contentHash: ContentHash,
    scope: Scope,
)

/** Constructs generation manifests from a resolved closure. */
object GenerationManifestBuilder:

  /**
   * Build a generation manifest from a closure.
   *
   * @param generation the generation number
   * @param profile    the profile name (e.g. "system", "users/alice")
   * @param closure    the resolved dependency closure
   * @return           the manifest or a list of conflict errors
   */
  def build(
      generation: Int,
      profile: String,
      closure: Resolution.Closure,
  ): Either[List[String], GenerationManifest] =
    val merged = EffectOps.merge(closure)
    val conflicts = EffectOps.detectConflicts(merged)

    if conflicts.nonEmpty then Left(conflicts)
    else
      val entries = closure.packages.values.toList
        .sortBy(_.name)
        .map(m => GenerationPackageEntry(m.name, m.version, m.contentHash, m.scope))

      Right(GenerationManifest(generation, profile, entries, merged))

  /**
   * Serialize a generation manifest to TOML.
   */
  def toToml(gm: GenerationManifest): String =
    val sb = new StringBuilder

    sb.append(s"generation = ${gm.generation}\n")
    sb.append(s"""profile = "${gm.profile}"\n""")
    sb.append("\n")

    for pkg <- gm.packages do
      sb.append("[[packages]]\n")
      sb.append(s"""name = "${pkg.name}"\n""")
      sb.append(s"""version = "${pkg.version}"\n""")
      sb.append(s"""content-hash = "${pkg.contentHash}"\n""")
      sb.append(s"""scope = "${if pkg.scope == Scope.System then "system" else "user"}"\n""")
      sb.append("\n")

    sb.result()

  /**
   * Parse a generation manifest from TOML.
   */
  def fromToml(input: String): Either[String, GenerationManifest] =
    import io.github.edadma.toml.{TomlParser as Toml, TomlValue}

    Toml.parse(input) match
      case Left(err) => Left(s"TOML parse error: $err")
      case Right(doc) =>
        for
          gen     <- doc.getLong("generation").toRight("missing required field: 'generation'")
          profile <- doc.getString("profile").toRight("missing required field: 'profile'")
          entries <- parseEntries(doc)
        yield GenerationManifest(gen.toInt, profile, entries, Effects.empty)

  private def parseEntries(
      doc: io.github.edadma.toml.TomlDocument,
  ): Either[String, List[GenerationPackageEntry]] =
    import io.github.edadma.toml.TomlValue

    doc.getArr("packages") match
      case None => Right(Nil)
      case Some(elems) =>
        val results = elems.zipWithIndex.map { (v, i) =>
          v match
            case TomlValue.Obj(fields) =>
              for
                name    <- fields.get("name").collect { case TomlValue.Str(s) => s }.toRight(s"packages[$i]: missing name")
                version <- fields.get("version").collect { case TomlValue.Str(s) => s }.toRight(s"packages[$i]: missing version")
                hashStr <- fields.get("content-hash").collect { case TomlValue.Str(s) => s }.toRight(s"packages[$i]: missing content-hash")
                hash    <- ContentHash.parse(hashStr).left.map(e => s"packages[$i]: $e")
                scopeStr <- fields.get("scope").collect { case TomlValue.Str(s) => s }.toRight(s"packages[$i]: missing scope")
                scope    <- Scope.parse(scopeStr).left.map(e => s"packages[$i]: $e")
              yield GenerationPackageEntry(name, version, hash, scope)
            case _ => Left(s"packages[$i]: expected a table")
        }
        results.foldRight(Right(Nil): Either[String, List[GenerationPackageEntry]]) { (elem, acc) =>
          for
            a  <- elem
            as <- acc
          yield a :: as
        }
