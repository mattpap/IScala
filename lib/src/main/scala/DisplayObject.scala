package org.refptr.iscala
package display

trait DisplayObject

trait PlainDisplayObject extends DisplayObject {
    def toPlain: String
}

trait HTMLDisplayObject extends DisplayObject {
    def toHTML: String
}

trait MarkdownDisplayObject extends DisplayObject {
    def toMarkdown: String
}

trait LatexDisplayObject extends DisplayObject {
    def toLatex: String
}

trait JSONDisplayObject extends DisplayObject {
    def toJSON: String
}

trait JavascriptDisplayObject extends DisplayObject {
    def toJavascript: String
}

trait SVGDisplayObject extends DisplayObject {
    def toSVG: String
}

trait PNGDisplayObject extends DisplayObject {
    def toPNG: String
}

trait JPEGDisplayObject extends DisplayObject {
    def toJPEG: String
}

trait DisplayObjectImplicits {
    implicit val PlainDisplayPlainObject           = PlainDisplay[PlainDisplayObject]           (_.toPlain)
    implicit val HTMLDisplayHTMLObject             = HTMLDisplay[HTMLDisplayObject]             (_.toHTML)
    implicit val MarkdownDisplayMarkdownObject     = MarkdownDisplay[MarkdownDisplayObject]     (_.toMarkdown)
    implicit val LatexDisplayLatexObject           = LatexDisplay[LatexDisplayObject]           (_.toLatex)
    implicit val JSONDisplayJSONObject             = JSONDisplay[JSONDisplayObject]             (_.toJSON)
    implicit val JavascriptDisplayJavaScriptObject = JavascriptDisplay[JavascriptDisplayObject] (_.toJavascript)
    implicit val SVGDisplaySVGObject               = SVGDisplay[SVGDisplayObject]               (_.toSVG)
    implicit val PNGDisplayPNGObject               = PNGDisplay[PNGDisplayObject]               (_.toPNG)
    implicit val JPEGDisplayJPEGObject             = JPEGDisplay[JPEGDisplayObject]             (_.toJPEG)
}
