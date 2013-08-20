package org.refptr.iscala

import java.util.UUID
import java.lang.management.ManagementFactory

object Util {
    def uuid4(): UUID = UUID.randomUUID()

    def hex(bytes: Seq[Byte]): String = bytes.map("%02x" format _).mkString

    def getpid(): Int = {
        val name = ManagementFactory.getRuntimeMXBean().getName()
        name.takeWhile(_ != '@').toInt
    }

    val origOut = System.out
    val origErr = System.err

    def log[T](message: => T) {
        origOut.println(message)
    }

    def debug[T](message: => T) {
        if (IScala.options.verbose) {
            origOut.println(message)
        }
    }
}
