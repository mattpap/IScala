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

object IFrame {
    def apply(src: URL, width: Int, height: Int): IFrame = new IFrame(src, width, height)
}

case class YouTubeVideo(id: String, width: Int=400, height: Int=300)
    extends IFrame(new URL("https", "www.youtube.com", s"/embed/$id"), width, height)

case class VimeoVideo(id: String, width: Int=400, height: Int=300)
    extends IFrame(new URL("https", "player.vimeo.com", s"/video/$id"), width, height)

case class ScribdDocument(id: String, width: Int=400, height: Int=300)
    extends IFrame(new URL("https", "www.scribd.com", s"/embeds/$id/content"), width, height)

case class ImageURL(url: URL, width: Option[Int], height: Option[Int]) extends HTMLDisplayObject {
    def toHTML = <img src={url.toString}
                      width={width.map(w => xml.Text(w.toString))}
                      height={height.map(h => xml.Text(h.toString))}></img> toString
}

object ImageURL {
    def apply(url: URL): ImageURL = ImageURL(url, None, None)
    def apply(url: String): ImageURL = ImageURL(new URL(url))
    def apply(url: URL, width: Int, height: Int): ImageURL = ImageURL(url, Some(width), Some(height))
    def apply(url: String, width: Int, height: Int): ImageURL = ImageURL(new URL(url), width, height)
}
