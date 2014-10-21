package org.refptr.iscala
package display

import scala.annotation.implicitNotFound

trait Display[-T] {
    def mime: MIME
    def stringify(obj: T): String
}

object Display extends DisplayImplicits with DisplayObjectImplicits with ImageImplicits

@implicitNotFound(msg="Can't find Plain display type class for type ${T}.")
trait PlainDisplay[-T] extends Display[T] { val mime = MIME.`text/plain` }
@implicitNotFound(msg="Can't find HTML display type class for type ${T}.")
trait HTMLDisplay[-T] extends Display[T] { val mime = MIME.`text/html` }
@implicitNotFound(msg="Can't find Markdown display type class for type ${T}.")
trait MarkdownDisplay[-T] extends Display[T] { val mime = MIME.`text/markdown` }
@implicitNotFound(msg="Can't find Latex display type class for type ${T}.")
trait LatexDisplay[-T] extends Display[T] { val mime = MIME.`text/latex` }
@implicitNotFound(msg="Can't find JSON display type class for type ${T}.")
trait JSONDisplay[-T] extends Display[T] { val mime = MIME.`application/json` }
@implicitNotFound(msg="Can't find Javascript display type class for type ${T}.")
trait JavascriptDisplay[-T] extends Display[T] { val mime = MIME.`application/javascript` }
@implicitNotFound(msg="Can't find SVG display type class for type ${T}.")
trait SVGDisplay[-T] extends Display[T] { val mime = MIME.`image/svg+xml` }
@implicitNotFound(msg="Can't find PNG display type class for type ${T}.")
trait PNGDisplay[-T] extends Display[T] { val mime = MIME.`image/png` }
@implicitNotFound(msg="Can't find JPEG display type class for type ${T}.")
trait JPEGDisplay[-T] extends Display[T] { val mime = MIME.`image/jpeg` }

object PlainDisplay {
    def apply[T](fn: T => String): PlainDisplay[T] = new PlainDisplay[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object HTMLDisplay {
    def apply[T](fn: T => String): HTMLDisplay[T] = new HTMLDisplay[T] {
        def stringify(obj: T) = fn(obj)
    }

    def apply[T, U: HTMLDisplay](fn: T => U): HTMLDisplay[T] = new HTMLDisplay[T] {
        def stringify(obj: T) = implicitly[HTMLDisplay[U]].stringify(fn(obj))
    }
}

object MarkdownDisplay {
    def apply[T](fn: T => String): MarkdownDisplay[T] = new MarkdownDisplay[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object LatexDisplay {
    def apply[T](fn: T => String): LatexDisplay[T] = new LatexDisplay[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object JSONDisplay {
    def apply[T](fn: T => String): JSONDisplay[T] = new JSONDisplay[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object JavascriptDisplay {
    def apply[T](fn: T => String): JavascriptDisplay[T] = new JavascriptDisplay[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object SVGDisplay {
    def apply[T](fn: T => String): SVGDisplay[T] = new SVGDisplay[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object PNGDisplay {
    def apply[T](fn: T => String): PNGDisplay[T] = new PNGDisplay[T] {
        def stringify(obj: T) = fn(obj)
    }
}

object JPEGDisplay {
    def apply[T](fn: T => String): JPEGDisplay[T] = new JPEGDisplay[T] {
        def stringify(obj: T) = fn(obj)
    }
}

trait DisplayImplicits {
    implicit val PlainDisplayAny    = PlainDisplay[Any](scala.runtime.ScalaRunTime.stringOf _)
    implicit val HTMLDisplayNodeSeq = HTMLDisplay[xml.NodeSeq](_.toString)
}
