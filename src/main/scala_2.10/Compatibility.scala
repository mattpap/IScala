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

    implicit class IMainOps(imain: intp.type) {
        def originalPath(name: intp.global.Name): String = imain.pathToName(name)
    }
}
