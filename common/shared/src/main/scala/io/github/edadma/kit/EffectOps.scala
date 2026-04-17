package io.github.edadma.kit

/** Pure operations on effects: merging, conflict detection, diffing, path substitution. */
object EffectOps:

  // --- Merging ---

  /** Merge effects from all manifests in a closure into a single Effects. */
  def merge(closure: Resolution.Closure): Effects =
    val all = closure.packages.values.toList
    Effects(
      groups = all.flatMap(_.effects.groups),
      users = all.flatMap(_.effects.users),
      directories = all.flatMap(_.effects.directories),
      capabilities = all.flatMap(_.effects.capabilities),
      services = all.flatMap(_.effects.services),
      generators = all.flatMap(_.effects.generators),
      firstRun = all.flatMap(_.effects.firstRun.toList).headOption,
    )

  // --- Conflict detection ---

  /** Detect conflicts in a merged effect set. Returns a list of conflict descriptions, empty if clean. */
  def detectConflicts(effects: Effects): List[String] =
    val conflicts = List.newBuilder[String]

    // Duplicate group names
    val groupDups = effects.groups.groupBy(_.name).filter(_._2.length > 1)
    groupDups.foreach { (name, _) =>
      conflicts += s"duplicate group effect: '$name'"
    }

    // Duplicate user names
    val userDups = effects.users.groupBy(_.name).filter(_._2.length > 1)
    userDups.foreach { (name, _) =>
      conflicts += s"duplicate user effect: '$name'"
    }

    // Duplicate service names
    val serviceDups = effects.services.groupBy(_.name).filter(_._2.length > 1)
    serviceDups.foreach { (name, _) =>
      conflicts += s"duplicate service effect: '$name'"
    }

    // Duplicate generator outputs
    val genDups = effects.generators.groupBy(_.output).filter(_._2.length > 1)
    genDups.foreach { (output, _) =>
      conflicts += s"duplicate generator output: '$output'"
    }

    conflicts.result()

  // --- Diffing ---

  /** The diff between two effect sets: what to add, remove, and update. */
  case class EffectDiff(
      addGroups: List[GroupEffect],
      removeGroups: List[GroupEffect],
      addUsers: List[UserEffect],
      removeUsers: List[UserEffect],
      addDirectories: List[DirectoryEffect],
      removeDirectories: List[DirectoryEffect],
      addCapabilities: List[CapabilityEffect],
      removeCapabilities: List[CapabilityEffect],
      addServices: List[ServiceEffect],
      removeServices: List[ServiceEffect],
      addGenerators: List[GenerateEffect],
      removeGenerators: List[GenerateEffect],
      addFirstRun: Option[FirstRunEffect],
      removeFirstRun: Option[FirstRunEffect],
  ):
    def isEmpty: Boolean =
      addGroups.isEmpty && removeGroups.isEmpty &&
        addUsers.isEmpty && removeUsers.isEmpty &&
        addDirectories.isEmpty && removeDirectories.isEmpty &&
        addCapabilities.isEmpty && removeCapabilities.isEmpty &&
        addServices.isEmpty && removeServices.isEmpty &&
        addGenerators.isEmpty && removeGenerators.isEmpty &&
        addFirstRun.isEmpty && removeFirstRun.isEmpty

  /** Compute the diff from `old` to `new` effect sets. */
  def diff(oldEffects: Effects, newEffects: Effects): EffectDiff =
    EffectDiff(
      addGroups = newEffects.groups.filterNot(oldEffects.groups.contains),
      removeGroups = oldEffects.groups.filterNot(newEffects.groups.contains),
      addUsers = newEffects.users.filterNot(oldEffects.users.contains),
      removeUsers = oldEffects.users.filterNot(newEffects.users.contains),
      addDirectories = newEffects.directories.filterNot(oldEffects.directories.contains),
      removeDirectories = oldEffects.directories.filterNot(newEffects.directories.contains),
      addCapabilities = newEffects.capabilities.filterNot(oldEffects.capabilities.contains),
      removeCapabilities = oldEffects.capabilities.filterNot(newEffects.capabilities.contains),
      addServices = newEffects.services.filterNot(oldEffects.services.contains),
      removeServices = oldEffects.services.filterNot(newEffects.services.contains),
      addGenerators = newEffects.generators.filterNot(oldEffects.generators.contains),
      removeGenerators = oldEffects.generators.filterNot(newEffects.generators.contains),
      addFirstRun = newEffects.firstRun.filterNot(fr => oldEffects.firstRun.contains(fr)),
      removeFirstRun = oldEffects.firstRun.filterNot(fr => newEffects.firstRun.contains(fr)),
    )

  // --- Path substitution ---

  /** Rewrite all effect paths relative to a root. */
  def substituteRoot(effects: Effects, root: String): Effects =
    val prefix = if root.endsWith("/") then root.dropRight(1) else root
    def sub(path: String): String =
      if prefix == "" || prefix == "/" then path
      else prefix + path

    effects.copy(
      users = effects.users.map(u => u.copy(home = sub(u.home))),
      directories = effects.directories.map(d => d.copy(path = sub(d.path))),
      generators = effects.generators.map(g =>
        g.copy(
          output = sub(g.output),
          inputs = g.inputs.map(sub),
        ),
      ),
    )
