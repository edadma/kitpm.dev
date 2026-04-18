package io.github.edadma.kit

/** Package search across repository indexes. */
object Search:

  /** A search result with match context. */
  case class SearchResult(
      entry: RepoPackageEntry,
      repoName: String,
      matchType: MatchType,
  )

  enum MatchType:
    case Exact      // name matches query exactly
    case Prefix     // name starts with query
    case Contains   // name contains query as substring
    case NoMatch

  /**
   * Search for packages across multiple repository indexes.
   *
   * @param repos   repository indexes with their names
   * @param query   the search string (case-insensitive)
   * @param target  the platform triple to filter by
   * @return        matching entries, sorted by match quality then name
   */
  def search(
      repos: List[(RepoIndex, String)],
      query: String,
      target: String,
  ): List[SearchResult] =
    if query.isEmpty then Nil
    else
      val q = query.toLowerCase
      repos
        .flatMap { (index, repoName) =>
          index.packages
            .filter(_.target == target)
            .map { entry =>
              val matchType = classifyMatch(entry.name.toLowerCase, q)
              SearchResult(entry, repoName, matchType)
            }
            .filter(_.matchType != MatchType.NoMatch)
        }
        .sortBy(r => (matchOrder(r.matchType), r.entry.name))
        .distinctBy(r => (r.entry.name, r.entry.version))

  /**
   * Search by name only (exact match on package name).
   */
  def findByName(
      repos: List[(RepoIndex, String)],
      name: String,
      target: String,
  ): List[SearchResult] =
    repos.flatMap { (index, repoName) =>
      index.packages
        .filter(e => e.name == name && e.target == target)
        .map(e => SearchResult(e, repoName, MatchType.Exact))
    }

  /**
   * Find the latest version of a package by name.
   */
  def findLatest(
      repos: List[(RepoIndex, String)],
      name: String,
      target: String,
  ): Option[SearchResult] =
    findByName(repos, name, target)
      .sortBy(_.entry.version)
      .lastOption

  private def classifyMatch(name: String, query: String): MatchType =
    if name == query then MatchType.Exact
    else if name.startsWith(query) then MatchType.Prefix
    else if name.contains(query) then MatchType.Contains
    else MatchType.NoMatch

  private def matchOrder(mt: MatchType): Int = mt match
    case MatchType.Exact    => 0
    case MatchType.Prefix   => 1
    case MatchType.Contains => 2
    case MatchType.NoMatch  => 3
