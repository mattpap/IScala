package org.refptr.iscala

import java.io.{InputStream,PipedInputStream,OutputStream,PipedOutputStream,PrintStream}

sealed trait Std {
    val name: String
    val input: InputStream
    val output: OutputStream
    val stream: PrintStream
}

object StdOut extends Std {
    val name = "stdout"

    val input = new PipedInputStream()
    val output = new PipedOutputStream(input)
    val stream = new PrintStream(output)
}

object StdErr extends Std {
    val name = "stderr"

    val input = new PipedInputStream()
    val output = new PipedOutputStream(input)
    val stream = new PrintStream(output)
}
