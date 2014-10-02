package org.refptr.iscala

import scala.annotation.implicitNotFound

trait Display[-T] {
    def mime: MIME
    def stringify(obj: T): String
}

@implicitNotFound(msg="Can't find Plain display type class for type ${T}.")
trait Plain[-T] extends Display[T] { val mime = MIME.`text/plain` }
@implicitNotFound(msg="Can't find HTML display type class for type ${T}.")
trait HTML[-T] extends Display[T] { val mime = MIME.`text/html` }
@implicitNotFound(msg="Can't find Markdown display type class for type ${T}.")
trait Markdown[-T] extends Display[T] { val mime = MIME.`text/markdown` }
@implicitNotFound(msg="Can't find Latex display type class for type ${T}.")
trait Latex[-T] extends Display[T] { val mime = MIME.`text/latex` }
@implicitNotFound(msg="Can't find JSON display type class for type ${T}.")
trait JSON[-T] extends Display[T] { val mime = MIME.`application/json` }
@implicitNotFound(msg="Can't find Javascript display type class for type ${T}.")
trait Javascript[-T] extends Display[T] { val mime = MIME.`application/javascript` }
@implicitNotFound(msg="Can't find SVG display type class for type ${T}.")
trait SVG[-T] extends Display[T] { val mime = MIME.`image/svg+xml` }
@implicitNotFound(msg="Can't find PNG display type class for type ${T}.")
trait PNG[-T] extends Display[T] { val mime = MIME.`image/png` }
@implicitNotFound(msg="Can't find JPEG display type class for type ${T}.")
trait JPEG[-T] extends Display[T] { val mime = MIME.`image/jpeg` }

object Plain {
    def apply[T](fn: T => String): Plain[T] = new Plain[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object HTML {
    def apply[T](fn: T => String): HTML[T] = new HTML[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object Markdown {
    def apply[T](fn: T => String): Markdown[T] = new Markdown[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object Latex {
    def apply[T](fn: T => String): Latex[T] = new Latex[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object JSON {
    def apply[T](fn: T => String): JSON[T] = new JSON[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object Javascript {
    def apply[T](fn: T => String): Javascript[T] = new Javascript[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object SVG {
    def apply[T](fn: T => String): SVG[T] = new SVG[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object PNG {
    def apply[T](fn: T => String): PNG[T] = new PNG[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object JPEG {
    def apply[T](fn: T => String): JPEG[T] = new JPEG[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object Display {
    implicit val PlainAny    = Plain[Any](_.toString)
    implicit val HTMLNodeSeq = HTML[xml.NodeSeq](_.toString)
    implicit val LatexMath   = Latex[Math]("$$" + _.math + "$$")
}

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

object DisplayUtil {
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

trait DisplayObject

case class Math(math: String) extends DisplayObject
