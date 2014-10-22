package org.refptr.iscala
package display

import scala.reflect.macros.Context

object DisplayFunctions extends DisplayFunctions {
    def displayImpl[T: c.WeakTypeTag](c: Context)(obj: c.Expr[T]): c.Expr[Unit] = {
        import c.universe._
        reify { display_data(Repr.displaysImpl[T](c).splice, obj.splice) }
    }
}

trait DisplayFunctions {
    protected def display_data[T](repr: Repr[T], obj: T) {
        IScala.display_data(repr.stringify(obj))
    }

    def display[T](obj: T): Unit = macro DisplayFunctions.displayImpl[T]

    def display_plain[T:PlainDisplay](obj: T) = {
        display_data(Repr(plain=Some(implicitly[PlainDisplay[T]])), obj)
    }

    def display_html[T:HTMLDisplay](obj: T) = {
        display_data(Repr(html=Some(implicitly[HTMLDisplay[T]])), obj)
    }

    def display_markdown[T:MarkdownDisplay](obj: T) = {
        display_data(Repr(markdown=Some(implicitly[MarkdownDisplay[T]])), obj)
    }

    def display_latex[T:LatexDisplay](obj: T) = {
        display_data(Repr(latex=Some(implicitly[LatexDisplay[T]])), obj)
    }

    def display_json[T:JSONDisplay](obj: T) = {
        display_data(Repr(json=Some(implicitly[JSONDisplay[T]])), obj)
    }

    def display_javascript[T:JavascriptDisplay](obj: T) = {
        display_data(Repr(javascript=Some(implicitly[JavascriptDisplay[T]])), obj)
    }

    def display_svg[T:SVGDisplay](obj: T) = {
        display_data(Repr(svg=Some(implicitly[SVGDisplay[T]])), obj)
    }

    def display_png[T:PNGDisplay](obj: T) = {
        display_data(Repr(png=Some(implicitly[PNGDisplay[T]])), obj)
    }

    def display_jpeg[T:JPEGDisplay](obj: T) = {
        display_data(Repr(jpeg=Some(implicitly[JPEGDisplay[T]])), obj)
    }
}
