package org.refptr.iscala
package display

import java.net.URL

case class Math(math: String) extends LatexDisplayObject {
    def toLatex = "$$" + math + "$$"
}

case class LaTeX(latex: String) extends LatexDisplayObject {
    def toLatex = latex
}

class IFrame(src: URL, width: Int, height: Int) extends HTMLDisplayObject {
    protected def iframe() =
        <iframe width={width.toString}
                height={height.toString}
                src={src.toString}
                frameborder="0"
                allowfullscreen="allowfullscreen"></iframe>

    def toHTML = iframe().toString
}

case class YouTubeVideo(id: String, width: Int=400, height: Int=300)
    extends IFrame(new URL("https", "www.youtube.com", s"/embed/$id"), width, height)

case class VimeoVideo(id: String, width: Int=400, height: Int=300)
    extends IFrame(new URL("https", "player.vimeo.com", s"/video/$id"), width, height)

case class ScribdDocument(id: String, width: Int=400, height: Int=300)
    extends IFrame(new URL("https", "www.scribd.com", s"/embeds/$id/content"), width, height)
