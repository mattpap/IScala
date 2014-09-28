package org.refptr.iscala

sealed abstract class MIME(val name: String)
object MIME {
    object `text/plain` extends MIME("text/plain")
    object `text/html` extends MIME("text/html")
    object `text/markdown` extends MIME("text/markdown")
    object `text/latex` extends MIME("text/latex")
    object `application/json` extends MIME("application/json")
    object `application/javascript` extends MIME("application/javascript")
    object `image/png` extends MIME("image/png")
    object `image/jpeg` extends MIME("image/jpeg")
    object `image/svg+xml` extends MIME("image/svg+xml")
}

case class Data(items: (MIME, String)*) {
    def apply(mime: MIME): Option[String] = items.find(_._1 == mime).map(_._2)
}
