package org.refptr.iscala

import scala.util.parsing.combinator.JavaTokenParsers
import scala.tools.nsc.util.ClassPath

import sbt.{ModuleID,CrossVersion,Resolver,MavenRepository}

import Util.{log,debug}

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
    def resolver: Parser[Resolver] = string ~ "at" ~ string ^^ {
        case name ~ _ ~ root =>
            MavenRepository(name, root)
    }

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
    def apply(interpreter: Interpreter, input: String) = {
        parser.parse(input) match {
            case Left(result) =>
                handle(interpreter, result)
                None
            case Right(error) =>
                Some(error)
        }
    }

    def handle(interpreter: Interpreter, result: T): Unit
}

object Magic {
    val magics = List(LibraryDependenciesMagic, ResolversMagic, UpdateMagic, TypeMagic, ResetMagic)
    val pattern = "^%([a-zA-Z_][a-zA-Z0-9_]*)(.*)\n*$".r

    def unapply(code: String): Option[(String, String, Option[Magic[_]])] = code match {
        case pattern(name, input) => Some((name, input, magics.find(_.name.name == name)))
        case _ => None
    }
}

abstract class EmptyMagic(name: Symbol) extends Magic(name, EmptyParsers) {
    def handle(interpreter: Interpreter, unit: Unit) = handle(interpreter)
    def handle(interpreter: Interpreter): Unit
}

abstract class EntireMagic(name: Symbol) extends Magic(name, EntireParsers) {
    def handle(interpreter: Interpreter, code: String)
}

object LibraryDependenciesMagic extends Magic('libraryDependencies, LibraryDependenciesParser) {
    def handle(interpreter: Interpreter, dependencies: LibraryDependencies) {
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
    }
}

object ResolversMagic extends Magic('resolvers, ResolversParser) {
    def handle(interpreter: Interpreter, resolvers: Resolvers) {
        resolvers match {
            case Resolvers(Show, _) =>
                println(Settings.resolvers)
            case Resolvers(Add, resolvers) =>
                Settings.resolvers ++= resolvers
            case Resolvers(Del, resolvers) =>
                Settings.resolvers = Settings.resolvers.filterNot(resolvers contains _)
        }
    }
}

object UpdateMagic extends EmptyMagic('update) {
    def handle(interpreter: Interpreter) {
        Sbt.resolve(Settings.libraryDependencies, Settings.resolvers) map { jars =>
            interpreter.classpath(jars)
            interpreter.reset()
        }
    }
}

object TypeMagic extends Magic('type, TypeParser) {
    def handle(interpreter: Interpreter, spec: TypeSpec) {
        interpreter.typeInfo(spec.code, spec.verbose).map(println)
    }
}

object ResetMagic extends EmptyMagic('reset) {
    def handle(interpreter: Interpreter) {
        interpreter.reset()
    }
}
