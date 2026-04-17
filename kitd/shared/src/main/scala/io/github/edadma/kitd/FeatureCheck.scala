package io.github.edadma.kitd

import io.github.edadma.kit.*

/**
 * Maps effects to the platform features they require, and checks
 * whether an installation's adapter set can satisfy them.
 */
object FeatureCheck:

  /** A diagnostic about an unsatisfied feature requirement. */
  case class FeatureGap(
      feature: String,
      reason: String,
      required: Boolean,
  )

  /** The features implied by each effect type (from §7.8 of the design). */
  val effectFeatureMap: Map[String, String] = Map(
    "group"      -> "user-database",
    "user"       -> "user-database",
    "capability" -> "capabilities",
    "service"    -> "service-registration",
  )
  // directory, generate, first-run require no adapter — they use POSIX / portable sandbox

  /**
   * Given a manifest's effects and the set of features available on this installation,
   * compute which features are needed but missing.
   *
   * @param effects           the effects declared by the package
   * @param availableFeatures features provided by installed adapters
   * @param requiredFeatures  features the package explicitly requires (from requires-features)
   * @return list of gaps, empty if all satisfied
   */
  def check(
      effects: Effects,
      availableFeatures: Set[String],
      requiredFeatures: List[String],
  ): List[FeatureGap] =
    val gaps = List.newBuilder[FeatureGap]

    // Check explicitly required features
    for f <- requiredFeatures do
      if !availableFeatures.contains(f) then
        gaps += FeatureGap(f, s"package declares '$f' as required but no adapter is installed", required = true)

    // Check features implied by effects
    val impliedFeatures = inferFeatures(effects)
    for f <- impliedFeatures do
      if !availableFeatures.contains(f) && !requiredFeatures.contains(f) then
        gaps += FeatureGap(
          f,
          s"effects require '$f' adapter but it is not installed and not in requires-features",
          required = false,
        )

    gaps.result()

  /**
   * Infer which features are needed based on the effects present.
   */
  def inferFeatures(effects: Effects): Set[String] =
    val features = Set.newBuilder[String]

    if effects.groups.nonEmpty || effects.users.nonEmpty then
      features += "user-database"
    if effects.capabilities.nonEmpty then
      features += "capabilities"
    if effects.services.nonEmpty then
      features += "service-registration"

    features.result()

  /**
   * Given an adapters.toml config, derive the set of available features.
   */
  def availableFrom(adaptersToml: String): Either[String, Set[String]] =
    import io.github.edadma.toml.{TomlParser as Toml, TomlValue}

    Toml.parse(adaptersToml) match
      case Left(err) => Left(s"TOML parse error: $err")
      case Right(doc) =>
        val features = Set.newBuilder[String]
        val table = doc.root

        if table.contains("user-database") then features += "user-database"
        if table.contains("init") then features += "service-registration"
        if table.contains("capabilities") then features += "capabilities"
        if table.contains("sandbox") then features += "sandbox"

        Right(features.result())
