package org.refptr.iscala

import scala.reflect.macros.Context

trait EnumType {
    def name = toString.toLowerCase
}

trait Enum[T <: EnumType] {
    type ValueType = T

    def values: Set[T] = macro EnumImpl.valuesImpl[T]
    def fromString: PartialFunction[String, T] = macro EnumImpl.fromStringImpl[T]
}

object EnumImpl {
    private def children[T <: EnumType : c.WeakTypeTag](c: Context): Set[c.universe.Symbol] = {
        import c.universe._

        val tpe = weakTypeOf[T]
        val cls = tpe.typeSymbol.asClass

        if (!cls.isSealed) c.error(c.enclosingPosition, "must be a sealed trait or class")
        val children = tpe.typeSymbol.asClass.knownDirectSubclasses
        if (children.isEmpty) c.error(c.enclosingPosition, "no enumerations found")

        children
    }

    def valuesImpl[T <: EnumType : c.WeakTypeTag](c: Context): c.Expr[Set[T]] = {
        import c.universe._

        val values = children[T](c).map { child => q"${c.prefix.tree}.$child" }
        c.Expr[Set[T]](q"Set(..$values)")
    }

    def fromStringImpl[T <: EnumType : c.WeakTypeTag](c: Context): c.Expr[PartialFunction[String, T]] = {
        import c.universe._

        val cases = children[T](c).map { child => cq"${child.name.decoded} => ${c.prefix.tree}.$child" }
        c.Expr[PartialFunction[String, T]](q"{ case ..$cases }")
    }
}
