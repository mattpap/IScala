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
@implicitNotFound(msg="Can't find Math display type class for type ${T}.")
trait Math[-T] extends Display[T] { val mime = MIME.`text/latex` }
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

object Display {
    implicit val PlainDisplayAny = new Plain[Any] {
        def stringify(obj: Any) = obj.toString
    }

    implicit val HTMLDisplayNodeSeq = new HTML[xml.NodeSeq] {
        def stringify(obj: xml.NodeSeq) = obj.toString
    }
}

case class Repr[-T](
    plain: Option[Plain[T]],
    html: Option[HTML[T]],
    markdown: Option[Markdown[T]],
    math: Option[Math[T]],
    latex: Option[Latex[T]],
    json: Option[JSON[T]],
    javascript: Option[Javascript[T]],
    svg: Option[SVG[T]],
    png: Option[PNG[T]],
    jpeg: Option[JPEG[T]])

object DisplayUtil {
    import scala.reflect.macros.Context

    def displaysImpl[T: c.WeakTypeTag](c: Context): c.Expr[Repr[T]] = {
        import c.universe._
        import Core.implicitlyOptImpl

        reify {
            Repr(plain      = implicitlyOptImpl[Plain[T]](c)      splice,
                 html       = implicitlyOptImpl[HTML[T]](c)       splice,
                 markdown   = implicitlyOptImpl[Markdown[T]](c)   splice,
                 math       = implicitlyOptImpl[Math[T]](c)       splice,
                 latex      = implicitlyOptImpl[Latex[T]](c)      splice,
                 json       = implicitlyOptImpl[JSON[T]](c)       splice,
                 javascript = implicitlyOptImpl[Javascript[T]](c) splice,
                 svg        = implicitlyOptImpl[SVG[T]](c)        splice,
                 png        = implicitlyOptImpl[PNG[T]](c)        splice,
                 jpeg       = implicitlyOptImpl[JPEG[T]](c)       splice)
        }
    }

    def displays[T]: Repr[T] = macro displaysImpl[T]
}
