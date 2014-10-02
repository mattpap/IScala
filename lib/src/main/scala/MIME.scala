package org.refptr.iscala
package display

sealed abstract class MIME(val name: String)
object MIME {
    case object `text/plain` extends MIME("text/plain")
    case object `text/html` extends MIME("text/html")
    case object `text/markdown` extends MIME("text/markdown")
    case object `text/latex` extends MIME("text/latex")
    case object `application/json` extends MIME("application/json")
    case object `application/javascript` extends MIME("application/javascript")
    case object `image/png` extends MIME("image/png")
    case object `image/jpeg` extends MIME("image/jpeg")
    case object `image/svg+xml` extends MIME("image/svg+xml")
}
