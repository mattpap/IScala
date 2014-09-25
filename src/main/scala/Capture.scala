package org.refptr.iscala

class WatchStream(input: java.io.InputStream, size: Int, fn: String => Unit) extends Thread {
    override def run() {
        val buffer = new Array[Byte](size)

        try {
            while (true) {
                val n = input.read(buffer)
                fn(new String(buffer.take(n)))

                if (n < size) {
                    Thread.sleep(50)       // a little delay to accumulate output
                }
            }
        } catch {
            case _: java.io.IOException => // stream was closed so job is done
        }
    }
}

abstract class Capture { self =>

    def stdout(data: String): Unit
    def stderr(data: String): Unit

    // This is a heavyweight solution to start stream watch threads per
    // input, but currently it's the cheapest approach that works well in
    // multiple thread setup. Note that piped streams work only in thread
    // pairs (producer -> consumer) and we start one thread per execution,
    // so technically speaking we have multiple producers, which completely
    // breaks the earlier intuitive approach.

    def apply[T](block: => T): T = {
        val size = 10240

        val stdoutIn = new java.io.PipedInputStream(size)
        val stderrIn = new java.io.PipedInputStream(size)

        val stderrOut = new java.io.PipedOutputStream(stderrIn)
        val stdoutOut = new java.io.PipedOutputStream(stdoutIn)

        val stdout = new java.io.PrintStream(stdoutOut)
        val stderr = new java.io.PrintStream(stderrOut)

        val stdoutThread = new WatchStream(stdoutIn, size, self.stdout)
        val stderrThread = new WatchStream(stderrIn, size, self.stderr)

        stdoutThread.start()
        stderrThread.start()

        try {
            val result =
                Console.withOut(stdout) {
                    Console.withErr(stderr) {
                        block
                    }
                }

            stdoutOut.flush()
            stderrOut.flush()

            // Wait until both streams get dry because we have to
            // send messages with streams' data before execute_reply
            // is send. Otherwise there will be no output in clients
            // or it will be incomplete.
            while (stdoutIn.available > 0 || stderrIn.available > 0)
                Thread.sleep(10)

            result
        } finally {
            // This will effectively terminate threads.
            stdoutOut.close()
            stderrOut.close()
            stdoutIn.close()
            stderrIn.close()
        }
    }
}

case class Output[T](value: T, out: String = "", err: String = "")

class StringCapture(out: StringBuilder, err: StringBuilder) extends Capture {
    def stdout(data: String) = out.append(data)
    def stderr(data: String) = err.append(data)
}

object Capture {
    def captureOutput[T](block: => T): Output[T] = {
        val out = new StringBuilder
        val err = new StringBuilder
        val capture = new StringCapture(out, err)
        val value = capture { block }
        Output(value, out.toString, err.toString)
    }
}
