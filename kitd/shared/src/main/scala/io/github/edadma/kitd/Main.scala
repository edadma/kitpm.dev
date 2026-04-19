package io.github.edadma.kitd

import scopt.OParser

case class KitdConfig(
    root: String = "",
)

/**
 * kitd — the Kit package manager daemon.
 * Initializes the directory structure, then listens for requests from the kit CLI.
 */
object Main:
  private val builder = OParser.builder[KitdConfig]

  private val parser =
    import builder.*
    OParser.sequence(
      programName("kitd"),
      head("kitd", "0.0.1"),
      opt[String]("root")
        .required()
        .valueName("<path>")
        .action((v, c) => c.copy(root = v))
        .text("root directory for the Kit installation (required)"),
    )

  def main(args: Array[String]): Unit =
    OParser.parse(parser, args, KitdConfig()) match
      case None => sys.exit(1)
      case Some(config) =>
        val daemon = new Daemon(config.root)
        daemon.init()

        val server = new DaemonServer(daemon)
        server.start()

        println(s"kitd: listening on ${server.socketPath}")
        println(s"kitd: root=${config.root}")
        println("kitd: press Ctrl+C to stop")

        Runtime.getRuntime.addShutdownHook(new Thread(() => {
          println("\nkitd: shutting down")
          server.stop()
        }))

        // Block forever (daemon threads do the work)
        Thread.currentThread().synchronized {
          Thread.currentThread().wait()
        }
