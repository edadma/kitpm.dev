package io.github.edadma.kit

/** Diff between two generation manifests at the package level. */
object ClosureDiff:

  case class PackageDiff(
      added: List[GenerationPackageEntry],
      removed: List[GenerationPackageEntry],
      changed: List[(GenerationPackageEntry, GenerationPackageEntry)],
      unchanged: List[GenerationPackageEntry],
  )

  /** Compute the package-level diff between two generation manifests. */
  def diff(oldGen: GenerationManifest, newGen: GenerationManifest): PackageDiff =
    val oldByName = oldGen.packages.map(p => p.name -> p).toMap
    val newByName = newGen.packages.map(p => p.name -> p).toMap

    val allNames = (oldByName.keySet ++ newByName.keySet).toList.sorted

    val added     = List.newBuilder[GenerationPackageEntry]
    val removed   = List.newBuilder[GenerationPackageEntry]
    val changed   = List.newBuilder[(GenerationPackageEntry, GenerationPackageEntry)]
    val unchanged = List.newBuilder[GenerationPackageEntry]

    for name <- allNames do
      (oldByName.get(name), newByName.get(name)) match
        case (None, Some(n))    => added += n
        case (Some(o), None)    => removed += o
        case (Some(o), Some(n)) =>
          if o.contentHash == n.contentHash then unchanged += n
          else changed += ((o, n))
        case (None, None) => // impossible

    PackageDiff(added.result(), removed.result(), changed.result(), unchanged.result())
