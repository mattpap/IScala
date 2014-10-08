package org.refptr.iscala

import scala.reflect.runtime.universe

import scala.tools.nsc.Global
import scala.tools.nsc.interpreter.IMain

trait Compatibility {
    implicit class UniverseOps(u: universe.type) {
        def TermName(s: String): universe.TermName = u.newTermName(s)
    }

    implicit class GlobalOps(global: Global) {
        @inline final def exitingTyper[T](op: => T): T = global.afterTyper(op)
    }
}

trait InterpreterCompatibility extends Compatibility { self: Interpreter =>
    import intp.Request
    import intp.global.{nme,newTermName,afterTyper,Name,Symbol}

    implicit class IMainOps(imain: intp.type) {
        def originalPath(name: Name): String     = imain.pathToName(name)
        def originalPath(symbol: Symbol): String = backticked(afterTyper(symbol.fullName))

        def backticked(s: String): String = (
            (s split '.').toList map {
                case "_"                               => "_"
                case s if nme.keywords(newTermName(s)) => s"`$s`"
                case s                                 => s
            } mkString "."
        )
    }

    implicit class RequestOps(req: Request) {
        def value: Symbol =
            Some(req.handlers.last)
                .filter(_.definesValue)
                .map(handler => req.definedSymbols(handler.definesTerm.get))
                .getOrElse(intp.global.NoSymbol)
    }
}
