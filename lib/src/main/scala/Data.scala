package org.refptr.iscala

import display.MIME

case class Data(items: (MIME, String)*) {
    def apply(mime: MIME): Option[String] = items.find(_._1 == mime).map(_._2)

    def default: Option[String] = apply(MIME.`text/plain`)
}
