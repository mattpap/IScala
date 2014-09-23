package org.refptr.iscala

import java.io.File

import scopt.{OptionParser,Read}
import sbt.{ModuleID,CrossVersion,Resolver,MavenRepository}

private object CustomReads {
    implicit val modulesReads: Read[List[ModuleID]] = Read.reads { string =>
        Nil
    }

    implicit val resolversReads: Read[List[Resolver]] = Read.reads { string =>
        Nil
    }
}

class Options(args: Array[String]) {
    case class Config(
        profile: Option[File] = None,
        parent: Boolean = false,
        verbose: Boolean = false,
        javacp: Boolean = true,
        modules: List[ModuleID] = Nil,
        resolvers: List[Resolver] = Nil,
        args: List[String] = Nil)

    val config: Config = {
        import CustomReads._

        val parser = new scopt.OptionParser[Config]("IScala") {
            opt[File]('P', "profile")
                .action { (profile, config) => config.copy(profile = Some(profile)) }
                .text("path to IPython's connection file")

            opt[Unit]('p', "parent")
                .action { (_, config) => config.copy(parent = true) }
                .text("indicate that IPython started this engine")

            opt[Unit]('v', "verbose")
                .action { (_, config) => config.copy(verbose = true) }
                .text("print debug messages to the terminal")

            opt[Unit]('J', "no-javacp")
                .action { (_, config) => config.copy(javacp = false) }
                .text("use java's classpath for the embedded interpreter")

            opt[List[ModuleID]]('m', "modules")
                .unbounded()
                .action { (modules, config) => config.copy(modules = config.modules ++ modules) }
                .text("automatically managed dependencies")

            opt[List[Resolver]]('r', "resolvers")
                .unbounded()
                .action { (resolvers, config) => config.copy(resolvers = config.resolvers ++ resolvers) }
                .text("additional resolvers for automatically managed dependencies")

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
