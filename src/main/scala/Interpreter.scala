package org.refptr.iscala

import scala.tools.nsc.interpreter.{IMain,CommandLine}

class Interpreter(args: Seq[String], usejavacp: Boolean=true) {
    val commandLine = new CommandLine(args.toList, println)
    commandLine.settings.embeddedDefaults[this.type]
    commandLine.settings.usejavacp.value = usejavacp

    val output = new java.io.StringWriter
    val printer = new java.io.PrintWriter(output)

    private var _intp: IMain = _
    def intp = _intp

    reset()

    def settings = commandLine.settings

    def reset() {
        synchronized {
            _intp = new IMain(settings, printer)
        }
    }

    def resetOutput() {
        output.getBuffer.setLength(0)
    }

    def completion = new IScalaCompletion(intp)
}
