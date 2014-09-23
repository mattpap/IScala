package org.refptr.iscala

import java.io.File
import scopt.{OptionParser,Read}
import scala.util.parsing.combinator.JavaTokenParsers
import sbt.{ModuleID,CrossVersion,Resolver,MavenRepository,Level}

private object CustomReads {
    implicit val modulesReads: Read[List[ModuleID]] = Read.reads { string =>
        object ModulesParsers extends JavaTokenParsers {
            def crossVersion: Parser[CrossVersion] = "::" ^^^ CrossVersion.binary | ":" ^^^ CrossVersion.Disabled

            def string: Parser[String] = "[^,:]+".r

            def module: Parser[ModuleID] = string ~ crossVersion ~ string ~ ":" ~ string ^^ {
                case organization ~ crossVersion ~ name ~ _ ~ revision =>
                    ModuleID(organization, name, revision, crossVersion=crossVersion)
            }

            def modules: Parser[List[ModuleID]] = rep1sep(module, ",")

            def parse(input: String): List[ModuleID]  = {
                parseAll(modules, input) match {
                    case Success(result, _) =>
                        result
                    case failure: NoSuccess =>
                        throw new IllegalArgumentException(s"Invalid module specification.")
                }
            }
        }

        ModulesParsers.parse(string)
    }

    implicit val resolversReads: Read[List[Resolver]] = Read.reads { string =>
        object Bintray {
            def unapply(string: String): Option[(String, String)] = {
                string.split(":") match {
                    case Array("bintray", user, repo) => Some((user, repo))
                    case _ => None
                }
            }
        }

        string.split(",").toList.flatMap {
            case "sonatype" => Resolver.sonatypeRepo("releases") :: Resolver.sonatypeRepo("snapshots") :: Nil
            case "sonatype:releases" => Resolver.sonatypeRepo("releases") :: Nil
            case "sonatype:snapshots" => Resolver.sonatypeRepo("snapshots") :: Nil
            case "typesafe" => Resolver.typesafeRepo("releases") :: Resolver.typesafeRepo("snapshots") :: Nil
            case "typesafe:releases" => Resolver.typesafeRepo("releases") :: Nil
            case "typesafe:snapshots" => Resolver.typesafeRepo("snapshots") :: Nil
            case "typesafe-ivy" => Resolver.typesafeIvyRepo("releases") :: Resolver.typesafeIvyRepo("snapshots") :: Nil
            case "typesafe-ivy:releases" => Resolver.typesafeIvyRepo("releases") :: Nil
            case "typesafe-ivy:snapshots" => Resolver.typesafeIvyRepo("snapshots") :: Nil
            case Bintray(user, repo) => Resolver.bintrayRepo(user, repo) :: Nil
            case url => MavenRepository(url, url) :: Nil
        }
    }
}

class Options(args: Array[String]) {
    case class Config(
        profile: Option[File] = None,
        parent: Boolean = false,
        debug: Boolean = false,
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

            opt[Unit]('d', "debug")
                .action { (_, config) => config.copy(debug = true) }
                .text("print debug messages to the terminal")

            opt[Unit]('J', "no-javacp")
                .action { (_, config) => config.copy(javacp = false) }
                .text("use java's classpath for the embedded interpreter")

            opt[List[ModuleID]]('m', "modules")
                .unbounded()
                .action { (modules, config) => config.copy(modules = config.modules ++ modules) }
                .text("automatically managed dependencies, e.g. -m org.parboiled::parboiled:2.0.1")

            opt[List[Resolver]]('r', "resolvers")
                .unbounded()
                .action { (resolvers, config) => config.copy(resolvers = config.resolvers ++ resolvers) }
                .text("additional resolvers for automatically managed dependencies, e.g. -r sonatype:releases")

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

    if (config.debug) {
        logger.setLevel(Level.Debug)
    }
}
