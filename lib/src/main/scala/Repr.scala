package org.refptr.iscala
package display

case class Repr[-T](
    plain      : Option[PlainDisplay[T]]      = None,
    html       : Option[HTMLDisplay[T]]       = None,
    markdown   : Option[MarkdownDisplay[T]]   = None,
    latex      : Option[LatexDisplay[T]]      = None,
    json       : Option[JSONDisplay[T]]       = None,
    javascript : Option[JavascriptDisplay[T]] = None,
    svg        : Option[SVGDisplay[T]]        = None,
    png        : Option[PNGDisplay[T]]        = None,
    jpeg       : Option[JPEGDisplay[T]]       = None) {

    def stringify(obj: T): Data = {
        val displays = List(plain, html, markdown, latex, json, javascript, svg, png, jpeg)
        Data(displays.flatten.map { display => display.mime -> display.stringify(obj) }: _*)
    }
}

object Repr {
    import scala.reflect.macros.Context

    def displaysImpl[T: c.WeakTypeTag](c: Context): c.Expr[Repr[T]] = {
        import c.universe._
        import Core.implicitlyOptImpl

        reify {
            Repr(plain      = implicitlyOptImpl[PlainDisplay[T]](c)      splice,
                 html       = implicitlyOptImpl[HTMLDisplay[T]](c)       splice,
                 markdown   = implicitlyOptImpl[MarkdownDisplay[T]](c)   splice,
                 latex      = implicitlyOptImpl[LatexDisplay[T]](c)      splice,
                 json       = implicitlyOptImpl[JSONDisplay[T]](c)       splice,
                 javascript = implicitlyOptImpl[JavascriptDisplay[T]](c) splice,
                 svg        = implicitlyOptImpl[SVGDisplay[T]](c)        splice,
                 png        = implicitlyOptImpl[PNGDisplay[T]](c)        splice,
                 jpeg       = implicitlyOptImpl[JPEGDisplay[T]](c)       splice)
        }
    }

    def displays[T]: Repr[T] = macro displaysImpl[T]

    def stringifyImpl[T: c.WeakTypeTag](c: Context)(obj: c.Expr[T]): c.Expr[Data] = {
        import c.universe._
        reify { displaysImpl[T](c).splice.stringify(obj.splice) }
    }

    def stringify[T](obj: T): Data = macro stringifyImpl[T]
}
