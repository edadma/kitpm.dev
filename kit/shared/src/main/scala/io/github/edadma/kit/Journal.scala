package io.github.edadma.kit

/**
 * Activation journal: a sequence of entries recording applied effects and their inverses.
 * Used for crash recovery (replay in reverse) and rollback planning.
 */
object Journal:

  /** A single journal entry: what was done, and how to undo it. */
  case class Entry(
      effectType: String,
      action: Action,
      key: String,
      detail: Map[String, String],
  )

  enum Action:
    case Apply, Inverse

  /** Plan the journal entries for a forward activation (generation N → N+1). */
  def planForward(diff: EffectOps.EffectDiff): List[Entry] =
    val entries = List.newBuilder[Entry]

    // Inverses first (remove old effects in reverse dependency order: services, generators, caps, dirs, users, groups)
    for s <- diff.removeServices do
      entries += Entry("service", Action.Inverse, s.name, Map("binary" -> s.binary))
    for g <- diff.removeGenerators do
      entries += Entry("generate", Action.Inverse, g.output, Map("generator" -> g.generator))
    for c <- diff.removeCapabilities do
      entries += Entry("capability", Action.Inverse, c.binary, Map("caps" -> c.caps.mkString(",")))
    for u <- diff.removeUsers do
      entries += Entry("user", Action.Inverse, u.name, Map("group" -> u.group))
    for g <- diff.removeGroups do
      entries += Entry("group", Action.Inverse, g.name, Map.empty)
    diff.removeFirstRun.foreach { fr =>
      entries += Entry("first-run", Action.Inverse, fr.binary, Map.empty)
    }

    // Additions in dependency order: groups, users, directories, generators, capabilities, services, first-run
    for g <- diff.addGroups do
      entries += Entry("group", Action.Apply, g.name, Map("system" -> g.system.toString))
    for u <- diff.addUsers do
      entries += Entry(
        "user",
        Action.Apply,
        u.name,
        Map("group" -> u.group, "home" -> u.home, "shell" -> u.shell, "system" -> u.system.toString),
      )
    for d <- diff.addDirectories do
      entries += Entry(
        "directory",
        Action.Apply,
        d.path,
        Map("owner" -> d.owner, "group" -> d.group, "mode" -> d.mode),
      )
    for g <- diff.addGenerators do
      entries += Entry(
        "generate",
        Action.Apply,
        g.output,
        Map("generator" -> g.generator, "inputs" -> g.inputs.mkString(",")),
      )
    for c <- diff.addCapabilities do
      entries += Entry("capability", Action.Apply, c.binary, Map("caps" -> c.caps.mkString(",")))
    for s <- diff.addServices do
      entries += Entry(
        "service",
        Action.Apply,
        s.name,
        Map("binary" -> s.binary, "user" -> s.user, "group" -> s.group, "restart" -> s.restart),
      )
    diff.addFirstRun.foreach { fr =>
      entries += Entry("first-run", Action.Apply, fr.binary, Map.empty)
    }

    entries.result()

  /** Plan the journal entries for a rollback (reverse the given forward plan). */
  def planRollback(forwardPlan: List[Entry]): List[Entry] =
    forwardPlan.reverse.map { entry =>
      entry.copy(action = entry.action match
        case Action.Apply   => Action.Inverse
        case Action.Inverse => Action.Apply,
      )
    }

  /** Serialize a journal to a stable text format. */
  def serialize(entries: List[Entry]): String =
    entries
      .map { e =>
        val action = e.action match
          case Action.Apply   => "APPLY"
          case Action.Inverse => "INVERSE"
        val detail = e.detail.toList.sorted.map((k, v) => s"$k=$v").mkString(" ")
        s"$action ${e.effectType} ${e.key} $detail".trim
      }
      .mkString("\n")

  /** Parse a serialized journal back into entries. */
  def deserialize(input: String): Either[String, List[Entry]] =
    if input.trim.isEmpty then Right(Nil)
    else
      val results = input.linesIterator.zipWithIndex.map { (line, i) =>
        parseLine(line.trim, i)
      }.toList
      results.foldRight(Right(Nil): Either[String, List[Entry]]) { (elem, acc) =>
        for
          a  <- elem
          as <- acc
        yield a :: as
      }

  private def parseLine(line: String, lineNum: Int): Either[String, Entry] =
    val parts = line.split(" ", 3)
    if parts.length < 2 then Left(s"line $lineNum: too few fields")
    else
      val action = parts(0) match
        case "APPLY"   => Right(Action.Apply)
        case "INVERSE" => Right(Action.Inverse)
        case other     => Left(s"line $lineNum: unknown action '$other'")

      action.map { a =>
        val rest = if parts.length > 2 then parts(2) else ""
        val spaceIdx = rest.indexOf(' ')
        val (key, detailStr) =
          if spaceIdx < 0 then (rest, "")
          else (rest.substring(0, spaceIdx), rest.substring(spaceIdx + 1))

        val detail =
          if detailStr.trim.isEmpty then Map.empty[String, String]
          else
            detailStr.trim
              .split(" ")
              .flatMap { kv =>
                val eqIdx = kv.indexOf('=')
                if eqIdx > 0 then Some(kv.substring(0, eqIdx) -> kv.substring(eqIdx + 1))
                else None
              }
              .toMap

        Entry(parts(1), a, key, detail)
      }
