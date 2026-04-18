package io.github.edadma.kit

/**
 * Determines which installed packages have newer versions available in repos.
 */
object UpgradeResolver:

  /** A package that can be upgraded. */
  case class UpgradeCandidate(
      name: String,
      currentVersion: String,
      currentHash: ContentHash,
      availableVersion: String,
      availableHash: ContentHash,
      repoName: String,
  )

  /**
   * Given the current generation's packages and repo indexes,
   * find packages that have newer versions available.
   *
   * @param installed current generation's package entries
   * @param repos     repository indexes with names
   * @param target    platform triple
   * @return          list of upgrade candidates
   */
  def findUpgrades(
      installed: List[GenerationPackageEntry],
      repos: List[(RepoIndex, String)],
      target: String,
  ): List[UpgradeCandidate] =
    installed.flatMap { pkg =>
      Search.findLatest(repos, pkg.name, target).flatMap { latest =>
        if latest.entry.contentHash != pkg.contentHash then
          Some(UpgradeCandidate(
            name = pkg.name,
            currentVersion = pkg.version,
            currentHash = pkg.contentHash,
            availableVersion = latest.entry.version,
            availableHash = latest.entry.contentHash,
            repoName = latest.repoName,
          ))
        else None
      }
    }

  /**
   * Find upgrades for a single named package.
   */
  def findUpgrade(
      name: String,
      installed: List[GenerationPackageEntry],
      repos: List[(RepoIndex, String)],
      target: String,
  ): Option[UpgradeCandidate] =
    installed.find(_.name == name).flatMap { pkg =>
      findUpgrades(List(pkg), repos, target).headOption
    }

  /**
   * Check if any upgrades are available (quick check for `kit upgrade` with no args).
   */
  def hasUpgrades(
      installed: List[GenerationPackageEntry],
      repos: List[(RepoIndex, String)],
      target: String,
  ): Boolean =
    findUpgrades(installed, repos, target).nonEmpty
