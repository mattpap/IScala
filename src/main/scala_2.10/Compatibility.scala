package org.refptr.iscala

import scala.tools.nsc.Global

object Compatibility {
    implicit class GlobalOps(global: Global) {
        @inline final def exitingTyper[T](op: => T): T = global.afterTyper(op)
    }
}
