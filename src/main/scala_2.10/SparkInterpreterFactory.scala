package org.refptr.iscala

import java.io.PrintWriter

import scala.tools.nsc.{ Settings => ISettings }
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.repl.SparkCommandLine
import org.apache.spark.repl.SparkILoop
import org.apache.spark.repl.SparkIMain


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
        // TODO currently the classpath is full of duplicates - perhaps dedup?
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
        new IScalaInterpreter(settings, backendInit) {
            import IScalaInterpreter._
            
            setupCode += sparkContextCreator
            setupCode += code("import org.apache.spark.SparkContext")

            tearDownCode += code("if (sc != null) sc.stop()")
        }
    }

    private val sparkContextCreator = (intp0: IMainBackend) => {
        // Code below taken from ISparkLoop methods 'createSparkContext' and 'getMaster'
        val master = {
            val envMaster = sys.env.get("MASTER")
            val propMaster = sys.props.get("spark.master")
            propMaster.orElse(envMaster).getOrElse("local[*]")
        }
        val execUri = System.getenv("SPARK_EXECUTOR_URI")
        val jars = SparkILoop.getAddedJars // TODO add classpath JARS... We should get this from the settings object...
        val conf = new SparkConf()
            .setMaster(master)
            .setAppName("IScala")
            .setJars(jars)
            .set("spark.repl.class.uri", intp0.imain.asInstanceOf[SparkIMain].classServer.uri) // TODO see if we can improve the casting
        if (execUri != null) {
            conf.set("spark.executor.uri", execUri)
        }
        val sparkContext = new SparkContext(conf)

        // Bind it to the interpreter session
        // FIXME this currently causes problems when we are resetting the context, and binding a 
        // variable with a name which has been bound in the previous session.
        intp0.bind("sc", "org.apache.spark.SparkContext",  sparkContext, List("@transient"))
    }

    override def toString = "<SparkInterpreterFactory>"
}