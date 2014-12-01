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