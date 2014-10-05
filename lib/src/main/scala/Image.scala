package org.refptr.iscala
package display

import java.io.ByteArrayOutputStream
import java.awt.image.RenderedImage
import javax.imageio.ImageIO

import org.apache.commons.codec.binary.Base64

trait ImageImplicits {
    private def encodeImage(format: String)(image: RenderedImage): String = {
        val output = new ByteArrayOutputStream()
        val bytes = try {
            ImageIO.write(image, format, output)
            output.toByteArray
        } finally {
            output.close()
        }
        Base64.encodeBase64String(bytes)
    }

    implicit val PNGRenderedImage = PNG[RenderedImage](encodeImage("PNG") _)
}
