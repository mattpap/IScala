package org.refptr.iscala

case class Repr[-T](
    plain      : Option[Plain[T]]      = None,
    html       : Option[HTML[T]]       = None,
    markdown   : Option[Markdown[T]]   = None,
    latex      : Option[Latex[T]]      = None,
    json       : Option[JSON[T]]       = None,
    javascript : Option[Javascript[T]] = None,
    svg        : Option[SVG[T]]        = None,
    png        : Option[PNG[T]]        = None,
    jpeg       : Option[JPEG[T]]       = None) {

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
            Repr(plain      = implicitlyOptImpl[Plain[T]](c)      splice,
                 html       = implicitlyOptImpl[HTML[T]](c)       splice,
                 markdown   = implicitlyOptImpl[Markdown[T]](c)   splice,
                 latex      = implicitlyOptImpl[Latex[T]](c)      splice,
                 json       = implicitlyOptImpl[JSON[T]](c)       splice,
                 javascript = implicitlyOptImpl[Javascript[T]](c) splice,
                 svg        = implicitlyOptImpl[SVG[T]](c)        splice,
                 png        = implicitlyOptImpl[PNG[T]](c)        splice,
                 jpeg       = implicitlyOptImpl[JPEG[T]](c)       splice)
        }
    }

    def displays[T]: Repr[T] = macro displaysImpl[T]

    def stringifyImpl[T: c.WeakTypeTag](c: Context)(obj: c.Expr[T]): c.Expr[Data] = {
        import c.universe._
        reify { displaysImpl[T](c).splice.stringify(obj.splice) }
    }

    def stringify[T](obj: T): Data = macro stringifyImpl[T]
}

case class Data(items: (MIME, String)*) {
    def apply(mime: MIME): Option[String] = items.find(_._1 == mime).map(_._2)
}
