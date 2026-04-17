package io.github.edadma.kit

/** Serializes a Manifest back to TOML format. */
object ManifestSerializer:

  def toToml(m: Manifest): String =
    val sb = new StringBuilder

    sb.append(s"""name = "${m.name}"\n""")
    sb.append(s"""version = "${m.version}"\n""")
    sb.append(s"""target = "${m.target}"\n""")
    sb.append(s"""content-hash = "${m.contentHash}"\n""")
    sb.append(s"""scope = "${scopeStr(m.scope)}"\n""")

    if m.requiresFeatures.nonEmpty then
      val feats = m.requiresFeatures.map(f => s""""$f"""").mkString(", ")
      sb.append(s"\nrequires-features = [$feats]\n")

    for dep <- m.deps do
      sb.append("\n[[deps]]\n")
      sb.append(s"""name = "${dep.name}"\n""")
      sb.append(s"""version = "${dep.version}"\n""")
      sb.append(s"""content-hash = "${dep.contentHash}"\n""")

    if !m.effects.isEmpty then
      for g <- m.effects.groups do
        sb.append("\n[[effects.group]]\n")
        sb.append(s"""name = "${g.name}"\n""")
        sb.append(s"system = ${g.system}\n")

      for u <- m.effects.users do
        sb.append("\n[[effects.user]]\n")
        sb.append(s"""name = "${u.name}"\n""")
        sb.append(s"""group = "${u.group}"\n""")
        sb.append(s"""home = "${u.home}"\n""")
        sb.append(s"""shell = "${u.shell}"\n""")
        sb.append(s"system = ${u.system}\n")

      for d <- m.effects.directories do
        sb.append("\n[[effects.directory]]\n")
        sb.append(s"""path = "${d.path}"\n""")
        sb.append(s"""owner = "${d.owner}"\n""")
        sb.append(s"""group = "${d.group}"\n""")
        sb.append(s"""mode = "${d.mode}"\n""")

      for c <- m.effects.capabilities do
        sb.append("\n[[effects.capability]]\n")
        sb.append(s"""binary = "${c.binary}"\n""")
        val caps = c.caps.map(c => s""""$c"""").mkString(", ")
        sb.append(s"caps = [$caps]\n")

      for s <- m.effects.services do
        sb.append("\n[[effects.service]]\n")
        sb.append(s"""name = "${s.name}"\n""")
        sb.append(s"""binary = "${s.binary}"\n""")
        if s.args.nonEmpty then
          val args = s.args.map(a => s""""$a"""").mkString(", ")
          sb.append(s"args = [$args]\n")
        sb.append(s"""user = "${s.user}"\n""")
        sb.append(s"""group = "${s.group}"\n""")
        if s.requires.nonEmpty then
          val reqs = s.requires.map(r => s""""$r"""").mkString(", ")
          sb.append(s"requires = [$reqs]\n")
        sb.append(s"""restart = "${s.restart}"\n""")
        if s.environment.nonEmpty then
          sb.append("\n[effects.service.environment]\n")
          for (k, v) <- s.environment.toList.sorted do
            sb.append(s"""$k = "$v"\n""")

      for g <- m.effects.generators do
        sb.append("\n[[effects.generate]]\n")
        sb.append(s"""generator = "${g.generator}"\n""")
        sb.append(s"""output = "${g.output}"\n""")
        val inputs = g.inputs.map(i => s""""$i"""").mkString(", ")
        sb.append(s"inputs = [$inputs]\n")

      m.effects.firstRun.foreach { fr =>
        sb.append("\n[effects.first-run]\n")
        sb.append(s"""binary = "${fr.binary}"\n""")
      }

    sb.result()

  private def scopeStr(s: Scope): String = s match
    case Scope.System => "system"
    case Scope.User   => "user"
