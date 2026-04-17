package io.github.edadma.kit

/**
 * Garbage collection reachability analysis.
 * Determines which store paths are live (reachable from roots) and which are garbage.
 */
object GCReachability:

  /**
   * Compute the set of reachable content hashes from GC roots.
   *
   * Roots are:
   *   - current generation of each profile
   *   - last N generations of each profile (configurable)
   *   - pinned store paths
   *
   * @param generations all generation manifests that are GC roots
   * @param pinned      manually pinned content hashes
   * @return the set of reachable content hashes
   */
  def reachable(
      generations: List[GenerationManifest],
      pinned: Set[ContentHash],
  ): Set[ContentHash] =
    val fromGenerations = generations
      .flatMap(_.packages)
      .map(_.contentHash)
      .toSet

    fromGenerations ++ pinned

  /**
   * Given all store paths and the reachable set, compute garbage.
   *
   * @param allStoreHashes every content hash present in the store
   * @param reachableSet   the set from `reachable()`
   * @return content hashes that are garbage (unreachable)
   */
  def garbage(
      allStoreHashes: Set[ContentHash],
      reachableSet: Set[ContentHash],
  ): Set[ContentHash] =
    allStoreHashes -- reachableSet

  /**
   * Select which generations to keep as GC roots for a profile.
   *
   * @param generations all generations for a profile, sorted by generation number
   * @param current     the current generation number
   * @param keepCount   how many recent generations to keep (default 10)
   * @return the generation manifests that are GC roots
   */
  def selectRoots(
      generations: List[GenerationManifest],
      current: Int,
      keepCount: Int = 10,
  ): List[GenerationManifest] =
    val sorted = generations.sortBy(-_.generation)
    val currentGen = sorted.filter(_.generation == current)
    val recent = sorted.take(keepCount)
    (currentGen ++ recent).distinctBy(_.generation)
