package io.github.edadma.kit

/** Pure formatting functions for CLI output. */
object CLIFormat:

  /** Format `kit list` output. */
  def formatList(packages: List[Protocol.PackageListEntry]): String =
    if packages.isEmpty then "No packages installed."
    else
      val nameWidth = packages.map(_.name.length).max
      val verWidth  = packages.map(_.version.length).max
      packages
        .map(p => s"${p.name.padTo(nameWidth, ' ')}  ${p.version.padTo(verWidth, ' ')}  ${p.contentHash}")
        .mkString("\n")

  /** Format `kit search` output. */
  def formatSearch(results: List[Search.SearchResult]): String =
    if results.isEmpty then "No packages found."
    else
      val nameWidth = results.map(_.entry.name.length).max
      val verWidth  = results.map(_.entry.version.length).max
      results
        .map { r =>
          val scope = if r.entry.scope == Scope.System then "system" else "user"
          s"${r.entry.name.padTo(nameWidth, ' ')}  ${r.entry.version.padTo(verWidth, ' ')}  ${scope.padTo(6, ' ')}  [${r.repoName}]"
        }
        .mkString("\n")

  /** Format `kit generations` output. */
  def formatGenerations(gens: List[Protocol.GenerationEntry], current: Int): String =
    if gens.isEmpty then "No generations."
    else
      gens
        .sortBy(_.number)
        .map { g =>
          val marker = if g.number == current then " *" else "  "
          s"$marker gen ${g.number}  (${g.packageCount} packages)"
        }
        .mkString("\n")

  /** Format `kit upgrade` preview. */
  def formatUpgrades(candidates: List[UpgradeResolver.UpgradeCandidate]): String =
    if candidates.isEmpty then "All packages are up to date."
    else
      val nameWidth = candidates.map(_.name.length).max
      val header = s"${candidates.length} upgrade(s) available:\n"
      val lines = candidates.map { c =>
        s"  ${c.name.padTo(nameWidth, ' ')}  ${c.currentVersion} -> ${c.availableVersion}  [${c.repoName}]"
      }
      header + lines.mkString("\n")

  /** Format `kit effects` output. */
  def formatEffects(name: String, effects: Effects): String =
    if effects.isEmpty then s"$name: no effects declared."
    else
      val lines = List.newBuilder[String]
      lines += s"$name:"
      for g <- effects.groups do lines += s"  group: ${g.name}${if g.system then " (system)" else ""}"
      for u <- effects.users do lines += s"  user: ${u.name} (group=${u.group}, home=${u.home})"
      for d <- effects.directories do lines += s"  directory: ${d.path} (owner=${d.owner}, mode=${d.mode})"
      for c <- effects.capabilities do lines += s"  capability: ${c.binary} [${c.caps.mkString(", ")}]"
      for s <- effects.services do lines += s"  service: ${s.name} (binary=${s.binary}, user=${s.user})"
      for g <- effects.generators do lines += s"  generate: ${g.output} (from ${g.generator})"
      effects.firstRun.foreach(fr => lines += s"  first-run: ${fr.binary}")
      lines.result().mkString("\n")
