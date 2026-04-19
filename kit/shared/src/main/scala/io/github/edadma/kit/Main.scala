package io.github.edadma.kit

import scopt.OParser
import io.github.edadma.kit.Protocol.*
import io.github.edadma.kit.ProtocolJson.{given, *}

sealed trait KitCommand
case class InstallCmd(name: String, version: Option[String] = None, system: Boolean = false)    extends KitCommand
case class RemoveCmd(name: String, system: Boolean = false)                                     extends KitCommand
case class ListCmd(system: Boolean = false)                                                     extends KitCommand
case class RollbackCmd(toGeneration: Option[Int] = None, system: Boolean = false)               extends KitCommand
case class GenerationsCmd(system: Boolean = false)                                              extends KitCommand
case class GCCmd(dryRun: Boolean = false)                                                       extends KitCommand
case object PingCmd                                                                             extends KitCommand
case object UpdateCmd                                                                           extends KitCommand
case class PackCmd(directory: String = "", output: String = "")                                  extends KitCommand
case class UnpackCmd(kitFile: String = "", output: String = "")                                  extends KitCommand
case class InspectCmd(kitFile: String = "")                                                      extends KitCommand

case class KitConfig(
    socket: String = "",
    command: KitCommand = PingCmd,
)

/**
 * kit — the Kit package manager CLI.
 * Sends requests to kitd over a Unix domain socket.
 */
object Main:
  private val builder = OParser.builder[KitConfig]

  private val parser =
    import builder.*
    OParser.sequence(
      programName("kit"),
      head("kit", "0.0.1"),
      opt[String]('s', "socket")
        .valueName("<path>")
        .action((v, c) => c.copy(socket = v))
        .text("path to kitd socket (default: from KIT_ROOT env)"),

      cmd("install")
        .action((_, c) => c.copy(command = InstallCmd("")))
        .text("install a package")
        .children(
          arg[String]("<name>")
            .action((v, c) => c.copy(command = c.command.asInstanceOf[InstallCmd].copy(name = v))),
          opt[Unit]("system")
            .action((_, c) => c.copy(command = c.command.asInstanceOf[InstallCmd].copy(system = true))),
        ),

      cmd("remove")
        .action((_, c) => c.copy(command = RemoveCmd("")))
        .text("remove a package")
        .children(
          arg[String]("<name>")
            .action((v, c) => c.copy(command = c.command.asInstanceOf[RemoveCmd].copy(name = v))),
          opt[Unit]("system")
            .action((_, c) => c.copy(command = c.command.asInstanceOf[RemoveCmd].copy(system = true))),
        ),

      cmd("list")
        .action((_, c) => c.copy(command = ListCmd()))
        .text("list installed packages")
        .children(
          opt[Unit]("system")
            .action((_, c) => c.copy(command = c.command.asInstanceOf[ListCmd].copy(system = true))),
        ),

      cmd("rollback")
        .action((_, c) => c.copy(command = RollbackCmd()))
        .text("rollback to a previous generation")
        .children(
          opt[Int]("to")
            .action((v, c) => c.copy(command = c.command.asInstanceOf[RollbackCmd].copy(toGeneration = Some(v)))),
          opt[Unit]("system")
            .action((_, c) => c.copy(command = c.command.asInstanceOf[RollbackCmd].copy(system = true))),
        ),

      cmd("generations")
        .action((_, c) => c.copy(command = GenerationsCmd()))
        .text("list generations")
        .children(
          opt[Unit]("system")
            .action((_, c) => c.copy(command = c.command.asInstanceOf[GenerationsCmd].copy(system = true))),
        ),

      cmd("gc")
        .action((_, c) => c.copy(command = GCCmd()))
        .text("garbage collect unreachable store entries")
        .children(
          opt[Unit]("dry-run")
            .action((_, c) => c.copy(command = c.command.asInstanceOf[GCCmd].copy(dryRun = true))),
        ),

      cmd("ping")
        .action((_, c) => c.copy(command = PingCmd))
        .text("check if daemon is running"),

      cmd("update")
        .action((_, c) => c.copy(command = UpdateCmd))
        .text("fetch latest package indexes from repos"),

      cmd("pack")
        .action((_, c) => c.copy(command = PackCmd()))
        .text("create a .kit package from a directory")
        .children(
          arg[String]("<directory>")
            .action((v, c) => c.copy(command = c.command.asInstanceOf[PackCmd].copy(directory = v))),
          opt[String]('o', "output")
            .required()
            .action((v, c) => c.copy(command = c.command.asInstanceOf[PackCmd].copy(output = v))),
        ),

      cmd("unpack")
        .action((_, c) => c.copy(command = UnpackCmd()))
        .text("extract a .kit package to a directory")
        .children(
          arg[String]("<file>")
            .action((v, c) => c.copy(command = c.command.asInstanceOf[UnpackCmd].copy(kitFile = v))),
          opt[String]('o', "output")
            .required()
            .action((v, c) => c.copy(command = c.command.asInstanceOf[UnpackCmd].copy(output = v))),
        ),

      cmd("inspect")
        .action((_, c) => c.copy(command = InspectCmd()))
        .text("show contents of a .kit package")
        .children(
          arg[String]("<file>")
            .action((v, c) => c.copy(command = c.command.asInstanceOf[InspectCmd].copy(kitFile = v))),
        ),
    )

  def main(args: Array[String]): Unit =
    OParser.parse(parser, args, KitConfig()) match
      case None => sys.exit(1)
      case Some(config) =>
        config.command match
          case PackCmd(dir, out)    => doPack(dir, out)
          case UnpackCmd(file, out) => doUnpack(file, out)
          case InspectCmd(file)     => doInspect(file)
          case cmd                  => doRemoteCommand(config.socket, cmd)

  private def resolveSocket(socket: String): String =
    if socket.nonEmpty then socket
    else
      val root = sys.env.getOrElse("KIT_ROOT", {
        System.err.println("Error: no socket path. Set KIT_ROOT or use --socket")
        sys.exit(1)
      })
      val prefix = if root.endsWith("/") then root.dropRight(1) else root
      s"$prefix/kit/var/kitd.sock"

  private def doRemoteCommand(socket: String, cmd: KitCommand): Unit =
    val socketPath = resolveSocket(socket)
    val request: Request = cmd match
      case InstallCmd(name, version, system)    => InstallRequest(name, version, system)
      case RemoveCmd(name, system)              => RemoveRequest(name, system)
      case ListCmd(system)                      => ListRequest(system, None)
      case RollbackCmd(to, system)              => RollbackRequest(to, system)
      case GenerationsCmd(system)               => GenerationsRequest(system, None)
      case GCCmd(dryRun)                        => GCRequest(dryRun)
      case PingCmd                              => Protocol.PingRequest
      case UpdateCmd                            => Protocol.UpdateRequest
      case _                                    => Protocol.PingRequest

    DaemonClient.send(socketPath, request) match
      case Left(err) =>
        System.err.println(s"Error: $err")
        sys.exit(1)
      case Right(ErrorResponse(msg)) =>
        System.err.println(s"Error: $msg")
        sys.exit(1)
      case Right(SuccessResponse(data)) =>
        data match
          case PackageListData(pkgs) =>
            println(CLIFormat.formatList(pkgs))
          case RemovedData(name, gen) =>
            println(s"Removed $name (generation $gen)")
          case GCData(count, hashes) =>
            println(s"Removed $count unreachable store entries")
          case GenerationsData(gens, current) =>
            println(CLIFormat.formatGenerations(gens, current))
          case PongData(root, version) =>
            println(s"kitd $version (root=$root)")
          case InstalledData(name, version, hash, gen) =>
            println(s"Installed $name $version (generation $gen)")
          case AckData =>
            println("OK")
          case other =>
            println(other)

  private[kit] def doPack(directory: String, output: String): Unit =
    import java.nio.file.{Files, Paths}

    val srcDir = Paths.get(directory)
    if !Files.isDirectory(srcDir) then
      System.err.println(s"Error: $directory is not a directory")
      sys.exit(1)

    val manifestPath = srcDir.resolve("manifest.toml")
    if !Files.exists(manifestPath) then
      System.err.println(s"Error: $directory does not contain manifest.toml")
      sys.exit(1)

    val manifest = new String(Files.readAllBytes(manifestPath))
    val files = List.newBuilder[PackageFormat.FileEntry]

    val walk = Files.walk(srcDir)
    try
      walk.forEach { path =>
        if Files.isRegularFile(path) then
          val rel = srcDir.relativize(path).toString.replace('\\', '/')
          val mode = if path.toFile.canExecute then 0x1ed else 0x1a4
          files += PackageFormat.FileEntry(rel, mode, Files.readAllBytes(path))
      }
    finally walk.close()

    val pkg = PackageFormat.Package(manifest, files.result().sortBy(_.path))
    val bytes = PackageFormat.writeBytes(pkg)
    Files.write(Paths.get(output), bytes)

    val hash = ContentHasher.sha256(bytes)
    println(s"Packed ${files.result().length} files into $output")
    println(s"Content hash: $hash")

  private[kit] def doUnpack(kitFile: String, output: String): Unit =
    import java.nio.file.{Files, Paths}

    val bytes = Files.readAllBytes(Paths.get(kitFile))
    PackageFormat.readBytes(bytes) match
      case Left(err) =>
        System.err.println(s"Error: $err")
        sys.exit(1)
      case Right(pkg) =>
        val outDir = Paths.get(output)
        Files.createDirectories(outDir)

        for f <- pkg.files do
          val dest = outDir.resolve(f.path)
          Files.createDirectories(dest.getParent)
          Files.write(dest, f.data)
          if (f.mode & 0x49) != 0 then dest.toFile.setExecutable(true)

        println(s"Unpacked ${pkg.files.length} files to $output")

  private[kit] def doInspect(kitFile: String): Unit =
    import java.nio.file.{Files, Paths}

    val bytes = Files.readAllBytes(Paths.get(kitFile))
    val hash = ContentHasher.sha256(bytes)

    PackageFormat.readBytes(bytes) match
      case Left(err) =>
        System.err.println(s"Error: $err")
        sys.exit(1)
      case Right(pkg) =>
        // Parse manifest for display
        val manifestInfo = ManifestParser.parse(pkg.manifest) match
          case Right(m) => s"${m.name} ${m.version} (${m.scope}, ${m.target})"
          case Left(_)  => "(unparseable manifest)"

        println(s"Package: $manifestInfo")
        println(s"Content hash: $hash")
        println(s"Files: ${pkg.files.length}")
        println()

        val totalUncompressed = pkg.files.map(_.data.length.toLong).sum
        println(s"Total uncompressed: ${formatSize(totalUncompressed)}")
        println(s"Package file size:  ${formatSize(bytes.length.toLong)}")
        println()

        for f <- pkg.files.sortBy(_.path) do
          val mode = if (f.mode & 0x49) != 0 then "x" else " "
          val size = f.data.length
          println(f"  $mode ${size}%8d  ${f.path}")

  private def formatSize(bytes: Long): String =
    if bytes < 1024 then s"$bytes B"
    else if bytes < 1024 * 1024 then f"${bytes / 1024.0}%.1f KB"
    else f"${bytes / (1024.0 * 1024.0)}%.1f MB"
