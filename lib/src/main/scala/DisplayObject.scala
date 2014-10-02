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
    implicit val PlainDisplay      = Plain[PlainDisplayObject]           (_.toPlain)
    implicit val HTMLDisplay       = HTML[HTMLDisplayObject]             (_.toHTML)
    implicit val MarkdownDisplay   = Markdown[MarkdownDisplayObject]     (_.toMarkdown)
    implicit val LatexDisplay      = Latex[LatexDisplayObject]           (_.toLatex)
    implicit val JSONDisplay       = JSON[JSONDisplayObject]             (_.toJSON)
    implicit val JavascriptDisplay = Javascript[JavascriptDisplayObject] (_.toJavascript)
    implicit val SVGDisplay        = SVG[SVGDisplayObject]               (_.toSVG)
    implicit val PNGDisplay        = PNG[PNGDisplayObject]               (_.toPNG)
    implicit val JPEGDisplay       = JPEG[JPEGDisplayObject]             (_.toJPEG)
}
