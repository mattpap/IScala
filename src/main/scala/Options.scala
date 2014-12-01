package org.refptr.iscala

import java.io.File

import scalax.io.JavaConverters._
import scalax.file.Path

import scopt.{OptionParser,Read}
import scala.util.parsing.combinator.JavaTokenParsers
import sbt.{ModuleID,CrossVersion,Resolver,MavenRepository,Level}

private object CustomReads {
    implicit val pathReads: Read[Path] = Read.reads(Path.fromString _)

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

    implicit val interpreterFactoryReads:Read[InterpreterFactory] = Read.reads { interpreterClassName =>

        // Get the java.lang.Class and covert into a scala class symbol
        val interpreterClass = try {
            Class.forName(interpreterClassName)
        } catch {
            case e:ClassNotFoundException => throw new IllegalArgumentException(s"Class $interpreterClassName is not on the classpath", e)
        }

        val cm = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
        val classSymbol = cm.classSymbol(interpreterClass)

        // Get the companion object symbol.
        // TODO deprecation in 2.11 =>
        // TODO I cannot get the moduleSymbol.isModule to work properly (always returns true even 
        // when there is no object in sight). So we currently handle the exception.
        //
        val instance = try {
            val moduleSymbol = classSymbol.companionSymbol
            val moduleMirror = cm.reflectModule(moduleSymbol.asModule)
            moduleMirror.instance
        } catch {
            case e:ClassNotFoundException => throw new IllegalArgumentException(s"Class $interpreterClassName has no companion object", e)
        }

        // Check and convert the module instance        
        require(instance.isInstanceOf[InterpreterFactory], s"Companion object $interpreterClassName does not implement the InterpreterFactory trait")
        instance.asInstanceOf[InterpreterFactory]
    }
}

class Options(args: Array[String]) {
    val IPyHome = Path.fromString(System.getProperty("user.home")) / ".ipython"

    case class Config(
        connection_file: Option[Path] = None,
        parent: Boolean = false,
        profile_dir: Path = IPyHome / "profile_scala",
        debug: Boolean = false,
        javacp: Boolean = true,
        classpath: String = "",
        modules: List[ModuleID] = Nil,
        resolvers: List[Resolver] = Nil,
        args: List[String] = Nil,
        interpreterFactory: InterpreterFactory = ScalaInterpreterFactory,
        embed:Boolean = false) {

        def completeClasspath: String = {
            val (baseClasspath, baseModules) = javacp match {
                case false => ("", Modules.Compiler :: Nil)
                case true  => (sys.props("java.class.path"), Nil)
            }

            val resolved = Sbt.resolve(baseModules ++ modules, resolvers).map(_.classpath) getOrElse {
                sys.error("Failed to resolve dependencies")
            }
            ClassPath.join(baseClasspath, classpath, resolved)
        }

        def interpreter: Interpreter = interpreterFactory(this)
    }

    val config: Config = {
        import CustomReads._

        val parser = new scopt.OptionParser[Config]("IScala") {
            opt[Path]('f', "connection-file")
                .action { (connection_file, config) => config.copy(connection_file = Some(connection_file)) }
                .text("path to IPython's connection file")

            opt[Path]("profile")
                .action { (profile, config) => config.copy(connection_file = Some(profile)) }
                .text("alias for --connection-file=FILE")

            opt[Unit]("parent")
                .action { (_, config) => config.copy(parent = true) }
                .text("indicate that IPython started this engine")

            opt[Path]("profile-dir")
                .action { (profile_dir, config) => config.copy(profile_dir = profile_dir) }
                .text("location of the IPython profile to use")

            opt[Unit]('d', "debug")
                .action { (_, config) => config.copy(debug = true) }
                .text("print debug messages to the terminal")

            opt[Unit]('J', "no-javacp")
                .action { (_, config) => config.copy(javacp = false) }
                .text("use java's classpath for the embedded interpreter")

            opt[String]('c', "classpath")
                .unbounded()
                .action { (classpath, config) => config.copy(classpath = ClassPath.join(config.classpath, classpath)) }
                .text("scpecify where to find user class files, e.g. -c my_project/target/scala-2.11/classes")

            opt[List[ModuleID]]('m', "modules")
                .unbounded()
                .action { (modules, config) => config.copy(modules = config.modules ++ modules) }
                .text("automatically managed dependencies, e.g. -m org.parboiled::parboiled:2.0.1")

            opt[List[Resolver]]('r', "resolvers")
                .unbounded()
                .action { (resolvers, config) => config.copy(resolvers = config.resolvers ++ resolvers) }
                .text("additional resolvers for automatically managed dependencies, e.g. -r sonatype:releases")

            opt[InterpreterFactory]('i', "interp")
                .action { (interpreterFactory, config) => config.copy(interpreterFactory = interpreterFactory) }
                .text("class name of the interpreter to use, e.g. -i org.refptr.iscala.ScalaInterpreterFactory")

            opt[Unit]("embed")
                .action { (_, config) => config.copy(embed = true) }
                .text("embed the interpreter")
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

    Settings.libraryDependencies = config.modules
    Settings.resolvers = config.resolvers
}
