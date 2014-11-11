package org.refptr.iscala

import java.io.PrintWriter

import scala.tools.nsc.{ Settings => ISettings }
import org.apache.spark.repl.SparkCommandLine
import org.apache.spark.repl.SparkILoop


object SparkInterpreterFactory extends InterpreterFactory {

    def apply(config: Options#Config): IScalaInterpreter = {
        SparkInterpreterFactory(config.completeClasspath, config.args, config.javacp)
    }

    def apply(additionalClasspath: String, args: List[String] = Nil, javacp: Boolean = false): IScalaInterpreter = {

        // Setup Settings via CommandLine
        val settings = new SparkCommandLine(args).settings

        // Use the Java ClassPath if any
        settings.usejavacp.value = javacp

        // Setup the classpath
        val fullClasspath = settings.classpath
        val sparkClasspath = ClassPath.join(SparkILoop.getAddedJars: _*)
        fullClasspath.value = ClassPath.join(fullClasspath.value, sparkClasspath, additionalClasspath)

        // Embed (???) the interpreter
        // TODO figure out why this is the default?
        settings.embeddedDefaults[IScalaInterpreter]

        // Create the backend creation function.
        val backendInit = (settings:ISettings, printer:PrintWriter) => {
            new SparkIMainBackend(settings, printer)
        }

        // Create the interpreter
        // TODO add SparkContext initialize and reset facilities
        new IScalaInterpreter(settings, backendInit)
    }

    override def toString = "<SparkInterpreterFactory>"
}