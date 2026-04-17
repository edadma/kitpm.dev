package io.github.edadma.kitd

import io.github.edadma.kit.*

/**
 * Activation plan ordering.
 *
 * Two levels of ordering:
 *   1. Effect types: groups → users → directories → generators → capabilities → services → first-run
 *   2. Within a type: by package dependency graph (if B depends on A, A's effects run first)
 */
object PlanOrder:

  /** A planned effect with its source package, ready for ordered execution. */
  sealed trait PlannedEffect:
    def sourceHash: ContentHash

  case class PlanGroup(effect: GroupEffect, sourceHash: ContentHash)           extends PlannedEffect
  case class PlanUser(effect: UserEffect, sourceHash: ContentHash)             extends PlannedEffect
  case class PlanDirectory(effect: DirectoryEffect, sourceHash: ContentHash)   extends PlannedEffect
  case class PlanGenerate(effect: GenerateEffect, sourceHash: ContentHash)     extends PlannedEffect
  case class PlanCapability(effect: CapabilityEffect, sourceHash: ContentHash) extends PlannedEffect
  case class PlanService(effect: ServiceEffect, sourceHash: ContentHash)       extends PlannedEffect
  case class PlanFirstRun(effect: FirstRunEffect, sourceHash: ContentHash)     extends PlannedEffect

  /**
   * Build an ordered activation plan from a closure.
   *
   * Effects are ordered by type (groups first, services last), and within each type
   * by the dependency graph: if package A depends on package B, B's effects come first.
   */
  def orderAdditions(closure: Resolution.Closure): List[PlannedEffect] =
    val depOrder = dependencyOrder(closure)

    def sorted[E](effects: List[(ContentHash, E)]): List[(ContentHash, E)] =
      effects.sortBy { (hash, _) => depOrder.getOrElse(hash, Int.MaxValue) }

    val groups = sorted(
      closure.packages.toList.flatMap { (hash, m) => m.effects.groups.map(hash -> _) },
    ).map((h, e) => PlanGroup(e, h))

    val users = sorted(
      closure.packages.toList.flatMap { (hash, m) => m.effects.users.map(hash -> _) },
    ).map((h, e) => PlanUser(e, h))

    val directories = sorted(
      closure.packages.toList.flatMap { (hash, m) => m.effects.directories.map(hash -> _) },
    ).map((h, e) => PlanDirectory(e, h))

    val generators = sorted(
      closure.packages.toList.flatMap { (hash, m) => m.effects.generators.map(hash -> _) },
    ).map((h, e) => PlanGenerate(e, h))

    val capabilities = sorted(
      closure.packages.toList.flatMap { (hash, m) => m.effects.capabilities.map(hash -> _) },
    ).map((h, e) => PlanCapability(e, h))

    val services = sorted(
      closure.packages.toList.flatMap { (hash, m) => m.effects.services.map(hash -> _) },
    ).map((h, e) => PlanService(e, h))

    val firstRuns = sorted(
      closure.packages.toList.flatMap { (hash, m) => m.effects.firstRun.map(hash -> _).toList },
    ).map((h, e) => PlanFirstRun(e, h))

    groups ++ users ++ directories ++ generators ++ capabilities ++ services ++ firstRuns

  /**
   * Compute a topological ordering index for packages by dependency depth.
   * Packages with no dependencies get index 0; packages that depend on them get higher indices.
   */
  private def dependencyOrder(closure: Resolution.Closure): Map[ContentHash, Int] =
    val depths = scala.collection.mutable.Map.empty[ContentHash, Int]

    def depth(hash: ContentHash): Int =
      depths.getOrElseUpdate(
        hash,
        closure.packages.get(hash) match
          case None => 0
          case Some(m) =>
            if m.deps.isEmpty then 0
            else m.deps.map(d => depth(d.contentHash)).max + 1,
      )

    closure.packages.keys.foreach(depth)
    depths.toMap
