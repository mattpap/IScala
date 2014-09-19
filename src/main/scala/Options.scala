package org.refptr.iscala

import java.io.File
import scopt.OptionParser

class Options(args: Array[String]) {
    case class Config(
        profile: Option[File] = None,
        parent: Boolean = false,
        verbose: Boolean = false,
        args: List[String] = Nil)

    val config: Config = {
        val parser = new scopt.OptionParser[Config]("IScala") {
            opt[File]('P', "profile")
                .action { (profile, config) => config.copy(profile=Some(profile)) }
                .text("path to IPython's connection file")

            opt[Unit]('p', "parent")
                .action { (_, config) => config.copy(parent=true) }
                .text("indicate that IPython started this engine")

            opt[Unit]('v', "verbose")
                .action { (_, config) => config.copy(verbose=true) }
                .text("print debug messages to the terminal")

            // arg[String]("<arg>...")
            //     .unbounded()
            //     .optional()
            //     .action { (arg, config) => config.copy(args=config.args :+ arg) }
            //     .text("arguments to pass directly to Scala compiler")

            help("help") text("prints this usage text")
        }

        // parser.parse(args, Config()) getOrElse { sys.exit(1) }

        val (iscala_args, scala_args) = args.span(_ != "--")

        parser.parse(iscala_args, Config()) map {
            _.copy(args=scala_args.drop(1).toList)
        } getOrElse {
            sys.exit(1)
        }
    }
}
