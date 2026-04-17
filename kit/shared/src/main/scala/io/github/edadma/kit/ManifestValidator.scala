package io.github.edadma.kit

/** Validates internal consistency of a manifest's effects. */
object ManifestValidator:

  /** Validate that effect references within a manifest are internally consistent. */
  def validate(m: Manifest): List[String] =
    val errors = List.newBuilder[String]

    val declaredGroups = m.effects.groups.map(_.name).toSet
    val declaredUsers  = m.effects.users.map(_.name).toSet

    // User effects must reference a declared group
    for u <- m.effects.users do
      if !declaredGroups.contains(u.group) then
        errors += s"user '${u.name}' references undeclared group '${u.group}'"

    // Directory effects must reference a declared owner and group
    for d <- m.effects.directories do
      if !declaredUsers.contains(d.owner) && d.owner != "root" then
        errors += s"directory '${d.path}' references undeclared owner '${d.owner}'"
      if !declaredGroups.contains(d.group) && d.group != "root" then
        errors += s"directory '${d.path}' references undeclared group '${d.group}'"

    // Service effects must reference a declared user and group
    for s <- m.effects.services do
      if !declaredUsers.contains(s.user) && s.user != "root" then
        errors += s"service '${s.name}' references undeclared user '${s.user}'"
      if !declaredGroups.contains(s.group) && s.group != "root" then
        errors += s"service '${s.name}' references undeclared group '${s.group}'"

    // Validate directory mode is valid octal
    for d <- m.effects.directories do
      if !isValidOctalMode(d.mode) then
        errors += s"directory '${d.path}' has invalid mode '${d.mode}'"

    // Generator output should not collide with a declared directory path
    val dirPaths = m.effects.directories.map(_.path).toSet
    for g <- m.effects.generators do
      if dirPaths.contains(g.output) then
        errors += s"generator output '${g.output}' conflicts with a declared directory path"

    // Capability binary must not be empty
    for c <- m.effects.capabilities do
      if c.binary.isEmpty then errors += "capability effect has empty binary path"
      if c.caps.isEmpty then errors += s"capability effect for '${c.binary}' has empty caps list"

    // First-run binary must not be empty
    m.effects.firstRun.foreach { fr =>
      if fr.binary.isEmpty then errors += "first-run effect has empty binary path"
    }

    // Scope constraints: user-scoped packages may not declare system effects
    if m.scope == Scope.User then
      if m.effects.groups.nonEmpty then
        errors += "user-scoped package may not declare group effects"
      if m.effects.users.exists(_.system) then
        errors += "user-scoped package may not declare system users"
      if m.effects.capabilities.nonEmpty then
        errors += "user-scoped package may not declare capability effects"

    errors.result()

  private def isValidOctalMode(mode: String): Boolean =
    mode.nonEmpty && mode.forall(c => c >= '0' && c <= '7') && mode.length <= 4
