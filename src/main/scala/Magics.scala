package org.refptr.iscala

import scala.util.parsing.combinator.JavaTokenParsers
import sbt.{ModuleID,CrossVersion,Resolver,MavenRepository}

trait MagicParsers[T] extends JavaTokenParsers {
    def string: Parser[String] = stringLiteral ^^ {
        case string => string.stripPrefix("\"").stripSuffix("\"")
    }

    def magic: Parser[T]

    def parse(input: String): Either[T, String] = {
        parseAll(magic, input) match {
            case Success(result, _) => Left(result)
            case failure: NoSuccess => Right(failure.toString)
        }
    }
}

object EmptyParsers extends MagicParsers[Unit] {
    def magic: Parser[Unit] = "".^^^(())
}

object EntireParsers extends MagicParsers[String] {
    def magic: Parser[String] = ".*".r
}

sealed trait Op
case object Add extends Op
case object Del extends Op
case object Show extends Op
case class LibraryDependencies(op: Op, modules: List[ModuleID]=Nil)
case class Resolvers(op: Op, resolvers: List[Resolver]=Nil)
case class TypeSpec(code: String, verbose: Boolean)

object LibraryDependenciesParser extends MagicParsers[LibraryDependencies] {
    def crossVersion: Parser[CrossVersion] = "%%" ^^^ CrossVersion.binary | "%" ^^^ CrossVersion.Disabled

    def module: Parser[ModuleID] = string ~ crossVersion ~ string ~ "%" ~ string ^^ {
        case organization ~ crossVersion ~ name ~ _ ~ revision =>
            ModuleID(organization, name, revision, crossVersion=crossVersion)
    }

    def op: Parser[Op] = "+=" ^^^ Add | "-=" ^^^ Del

    def modify: Parser[LibraryDependencies] = op ~ module ^^ {
        case op ~ module => LibraryDependencies(op, List(module))
    }

    def show: Parser[LibraryDependencies] = "" ^^^ LibraryDependencies(Show)

    def magic: Parser[LibraryDependencies] = modify | show
}

object TypeParser extends MagicParsers[TypeSpec] {
    def magic: Parser[TypeSpec] = opt("-v" | "--verbose") ~ ".*".r ^^ {
        case verbose ~ code => TypeSpec(code, verbose.isDefined)
    }
}

object ResolversParser extends MagicParsers[Resolvers] {

    def sonatypeReleases: Parser[Resolver] =
        "sonatypeReleases"  ^^^ Resolver.sonatypeRepo("releases")
    def sonatypeSnapshots: Parser[Resolver] =
        "sonatypeSnapshots" ^^^ Resolver.sonatypeRepo("snapshots")

    def optsRepo: Parser[Resolver] =
        sonatypeReleases | sonatypeSnapshots

    def sonatypeRepo: Parser[Resolver] =
        "sonatypeRepo"    ~> "(" ~> string <~ ")" ^^ { case name => Resolver.sonatypeRepo(name) }
    def typesafeRepo: Parser[Resolver] =
        "typesafeRepo"    ~> "(" ~> string <~ ")" ^^ { case name => Resolver.typesafeRepo(name) }
    def typesafeIvyRepo: Parser[Resolver] =
        "typesafeIvyRepo" ~> "(" ~> string <~ ")" ^^ { case name => Resolver.typesafeIvyRepo(name) }
    def sbtPluginRepo: Parser[Resolver] =
        "sbtPluginRepo"   ~> "(" ~> string <~ ")" ^^ { case name => Resolver.sbtPluginRepo(name) }
    def bintrayRepo: Parser[Resolver] =
        "bintrayRepo"     ~> "(" ~> string ~ "," ~ string <~ ")" ^^ { case user ~ _ ~ repo => Resolver.bintrayRepo(user, repo) }
    def jcenterRepo: Parser[Resolver] =
        "jcenterRepo" ^^^ Resolver.jcenterRepo

    def resolverRepo: Parser[Resolver] =
        sonatypeRepo | typesafeRepo | typesafeIvyRepo | sbtPluginRepo | bintrayRepo | jcenterRepo

    def optsRepos: Parser[Resolver] = "Opts" ~> "." ~> "resolver" ~> "." ~> optsRepo

    def resolverRepos: Parser[Resolver] = "Resolver" ~> "." ~> resolverRepo

    def mavenRepo: Parser[Resolver] = string ~ "at" ~ string ^^ {
        case name ~ _ ~ root => MavenRepository(name, root)
    }

    def resolver: Parser[Resolver] = optsRepos | resolverRepos | mavenRepo

    def op: Parser[Op] = "+=" ^^^ Add | "-=" ^^^ Del

    def modify: Parser[Resolvers] = op ~ resolver ^^ {
        case op ~ resolver => Resolvers(op, List(resolver))
    }

    def show: Parser[Resolvers] = "" ^^^ Resolvers(Show)

    def magic: Parser[Resolvers] = modify | show
}

object Settings {
    var libraryDependencies: List[ModuleID] = Nil
    var resolvers: List[Resolver] = Nil
}

abstract class Magic[T](val name: Symbol, parser: MagicParsers[T]) {
    def apply(interpreter: Interpreter, input: String): Results.Result = {
        parser.parse(input) match {
            case Left(result) =>
                handle(interpreter, result)
            case Right(error) =>
                println(error)
                Results.Error  // TODO: error
        }
    }

    def handle(interpreter: Interpreter, result: T): Results.Result
}

object Magic {
    val magics = List(LibraryDependenciesMagic, ResolversMagic, UpdateMagic, TypeMagic, ResetMagic, ClassPathMagic)
    val pattern = "^%([a-zA-Z_][a-zA-Z0-9_]*)(.*)\n*$".r

    def unapply(code: String): Option[(String, String, Option[Magic[_]])] = code match {
        case pattern(name, input) => Some((name, input, magics.find(_.name.name == name)))
        case _ => None
    }
}

abstract class EmptyMagic(name: Symbol) extends Magic(name, EmptyParsers) {
    def handle(interpreter: Interpreter, unit: Unit): Results.Result = handle(interpreter)
    def handle(interpreter: Interpreter): Results.Result
}

abstract class EntireMagic(name: Symbol) extends Magic(name, EntireParsers) {
    def handle(interpreter: Interpreter, code: String): Results.Result
}

object LibraryDependenciesMagic extends Magic('libraryDependencies, LibraryDependenciesParser) {
    def handle(interpreter: Interpreter, dependencies: LibraryDependencies) = {
        dependencies match {
            case LibraryDependencies(Show, _) =>
                println(Settings.libraryDependencies)
            case LibraryDependencies(Add, dependencies) =>
                Settings.libraryDependencies ++= dependencies
            case LibraryDependencies(Del, dependencies) =>
                // TODO: should be
                //
                // Settings.libraryDependencies = Settings.libraryDependencies.filterNot(dependencies contains _)
                //
                // but `CrossVersion` doesn't implement `equals` method, so we have to compare manually.

                Settings.libraryDependencies = Settings.libraryDependencies.filterNot { existingDep =>
                    dependencies.find { removeDep =>
                        removeDep.organization == existingDep.organization &&
                        removeDep.name == existingDep.name &&
                        removeDep.revision == existingDep.revision &&
                        (removeDep.crossVersion == existingDep.crossVersion ||
                         (removeDep.crossVersion.isInstanceOf[CrossVersion.Binary] &&
                          existingDep.crossVersion.isInstanceOf[CrossVersion.Binary]))
                    } isDefined
                }
        }
        Results.NoValue
    }
}

object ResolversMagic extends Magic('resolvers, ResolversParser) {
    def handle(interpreter: Interpreter, resolvers: Resolvers) = {
        resolvers match {
            case Resolvers(Show, _) =>
                println(Settings.resolvers)
            case Resolvers(Add, resolvers) =>
                Settings.resolvers ++= resolvers
            case Resolvers(Del, resolvers) =>
                Settings.resolvers = Settings.resolvers.filterNot(resolvers contains _)
        }
        Results.NoValue
    }
}

object UpdateMagic extends EmptyMagic('update) {
    def handle(interpreter: Interpreter) = {
        Sbt.resolve(Settings.libraryDependencies, Settings.resolvers) map { cp =>
            interpreter.classpath(cp)
            if (interpreter.isInitialized) interpreter.reset()
            Results.NoValue
        } getOrElse {
            Results.Error
        }
    }
}

object TypeMagic extends Magic('type, TypeParser) {
    def handle(interpreter: Interpreter, spec: TypeSpec) = {
        interpreter.typeInfo(spec.code, spec.verbose).map(println)
        Results.NoValue
    }
}

object ResetMagic extends EmptyMagic('reset) {
    def handle(interpreter: Interpreter) = {
        interpreter.reset()
        Results.NoValue
    }
}

object ClassPathMagic extends EmptyMagic('classpath) {
    def handle(interpreter: Interpreter) = {
        val cp = interpreter.settings.classpath.value.split(java.io.File.pathSeparator).toList
        interpreter.intp.beSilentDuring { interpreter.bind("cp", "List[String]", cp) }
        println(interpreter.settings.classpath.value)
        Results.NoValue
    }
}
