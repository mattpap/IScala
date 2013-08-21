package org.refptr.iscala

import scala.tools.nsc.interpreter.{IMain,JLineCompletion,CommandLine}

object Interpreter {
    def apply(args: Seq[String], usejavacp: Boolean=true) = {
        val commandLine = new CommandLine(args.toList, println)
        commandLine.settings.embeddedDefaults[this.type]
        commandLine.settings.usejavacp.value = usejavacp
        val output = new java.io.StringWriter
        val printer = new java.io.PrintWriter(output)
        val interpreter = new IMain(commandLine.settings, printer)
        val completion = new JLineCompletion(interpreter)
        (interpreter, completion, output)
    }
}
