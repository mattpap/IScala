package org.refptr.iscala

import scala.tools.nsc.Global
import scala.tools.nsc.interpreter.IMain

trait Compatibility {
    implicit class GlobalOps(global: Global) {
        @inline final def exitingTyper[T](op: => T): T = global.afterTyper(op)
    }
}

trait InterpreterCompatibility extends Compatibility { self: Interpreter =>
    val intp: IMain

    import intp.global.{nme,newTermName,afterTyper}

    implicit class IMainOps(imain: intp.type) {
        def originalPath(name: intp.global.Name): String     = imain.pathToName(name)
        def originalPath(symbol: intp.global.Symbol): String = backticked(afterTyper(symbol.fullName))

        def backticked(s: String): String = (
            (s split '.').toList map {
                case "_"                               => "_"
                case s if nme.keywords(newTermName(s)) => s"`$s`"
                case s                                 => s
            } mkString "."
        )
    }

    implicit class RequestOps(req: intp.Request) {
        def value: intp.global.Symbol =
            Some(req.handlers.last)
                .filter(_.definesValue)
                .map(handler => req.definedSymbols(handler.definesTerm.get))
                .getOrElse(intp.global.NoSymbol)
    }
}
