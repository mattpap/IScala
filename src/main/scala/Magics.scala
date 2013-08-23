package org.refptr.iscala

import scala.util.parsing.combinator.JavaTokenParsers

import scala.tools.nsc.interpreter.IMain
import scala.tools.nsc.util.ClassPath

import sbt.{ModuleID,CrossVersion,Resolver,MavenRepository}

trait MagicParsers[T] extends JavaTokenParsers {
    def string: Parser[String] = stringLiteral ^^ {
        case string => string.stripPrefix("\"").stripSuffix("\"")
    }

    def magic: Parser[T]

    def parse(input: String): Either[T, String] = {
        parseAll(magic, input) match {
            case Success(result, _) => Left(result)
            case failure: NoSuccess => Right(failure.msg)
        }
    }
}

object EmptyParsers extends MagicParsers[Unit] {
    def magic: Parser[Unit] = "" ^^^ ()
}

sealed trait Op
case object Add extends Op
case object Del extends Op
case class LibraryDependencies(op: Op, module: ModuleID)
case class Resolvers(op: Op, resolver: Resolver)

object LibraryDependenciesParser extends MagicParsers[LibraryDependencies] {
    def crossVersion: Parser[CrossVersion] = "%%" ^^^ CrossVersion.binary | "%" ^^^ CrossVersion.Disabled

    def module: Parser[ModuleID] = string ~ crossVersion ~ string ~ "%" ~ string ^^ {
        case organization ~ crossVersion ~ name ~ _ ~ revision =>
            ModuleID(organization, name, revision, crossVersion=crossVersion)
    }

    def op: Parser[Op] = "+=" ^^^ Add | "-=" ^^^ Del

    def magic: Parser[LibraryDependencies] = op ~ module ^^ {
        case op ~ module => LibraryDependencies(op, module)
    }
}

object ResolversParser extends MagicParsers[Resolvers] {
    def resolver: Parser[Resolver] = string ~ "at" ~ string ^^ {
        case name ~ _ ~ root =>
            MavenRepository(name, root)
    }

    def op: Parser[Op] = "+=" ^^^ Add | "-=" ^^^ Del

    def magic: Parser[Resolvers] = op ~ resolver ^^ {
        case op ~ resolver => Resolvers(op, resolver)
    }
}

object Settings {
    var projectName = "Untitled"
    var libraryDependencies: List[ModuleID] = Nil
    var resolvers: List[Resolver] = Sbt.defaultResolvers.toList
    var managedJars: List[java.io.File] = Nil
}

abstract class Magic[T](val name: Symbol, parser: MagicParsers[T]) {
    def apply(interpreter: IMain, input: String) = {
        parser.parse(input) match {
            case Left(result) =>
                handle(interpreter, result)
                None
            case Right(error) =>
                Some(error)
        }
    }

    def handle(interpreter: IMain, result: T): Unit
}

abstract class EmptyMagic(name: Symbol) extends Magic(name, EmptyParsers) {
    def handle(interpreter: IMain, unit: Unit) = handle(interpreter)
    def handle(interpreter: IMain): Unit
}

object LibraryDependenciesMagic extends Magic('libraryDependencies, LibraryDependenciesParser) {
    def handle(interpreter: IMain, dependency: LibraryDependencies) {
        dependency match {
            case LibraryDependencies(Add, dependency) =>
                Settings.libraryDependencies :+= dependency
            case LibraryDependencies(Del, dependency) =>
                Settings.libraryDependencies = Settings.libraryDependencies.filter(_ != dependency)
        }
    }
}

object ResolversMagic extends Magic('resolvers, ResolversParser) {
    def handle(interpreter: IMain, resolver: Resolvers) {
        resolver match {
            case Resolvers(Add, resolver) =>
                Settings.resolvers :+= resolver
            case Resolvers(Del, resolver) =>
                Settings.resolvers = Settings.resolvers.filter(_ != resolver)
        }
    }
}

object UpdateMagic extends EmptyMagic('update) {
    def handle(interpreter: IMain) {
        val jars = Sbt.resolve(Settings.projectName, Settings.libraryDependencies, Settings.resolvers)
        Settings.managedJars = jars.toList
        val paths = jars.map(_.getAbsolutePath)
        interpreter.settings.classpath.append(ClassPath.join(paths: _*))
        // println(s"New classpath: ${interpreter.settings.classpath.value}")
        interpreter.reset()
    }
}
