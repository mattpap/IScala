package org.refptr.iscala

import java.io.File

import sbt.{
    BasicLogger,Level,StackTrace,
    ModuleID,ModuleInfo,
    Resolver,MavenRepository,
    IvyPaths,InlineIvyConfiguration,
    CrossVersion,
    IvySbt,IvyScala,IvyActions,
    InlineConfiguration,
    UpdateLogging,UpdateConfiguration}

object Logger extends BasicLogger {
    def log(level: Level.Value, message: => String) {
        // if (atLevel(level))
            log(level.toString, message)
    }

    def success(message: => String) {
        log(Level.SuccessLabel, message)
    }

    def trace(t: => Throwable) {
        val traceLevel = getTrace
        log("trace", StackTrace.trimmed(t, traceLevel))
    }

    private def log(label: String, message: => String) {
        synchronized {
            message.split("\n").foreach(line => println(s"[$label] $line"))
        }
    }

    def control(event: sbt.ControlEvent.Value, message: => String): Unit = ???
    def logAll(events: Seq[sbt.LogEvent]): Unit = ???
}

object Sbt {
    val defaultDependencies: Seq[ModuleID] = Seq()
    val defaultResolvers: Seq[Resolver] = Seq(Resolver.sonatypeRepo("releases"))

    def resolve(projectName: String, dependencies: Seq[ModuleID], resolvers: Seq[Resolver]): Seq[File] = {
        val paths = new IvyPaths(new File("."), None)
        val ivyConf = new InlineIvyConfiguration(paths, resolvers, Nil, Nil, false, None, Seq("sha1", "md5"), None, Logger)
        val ivySbt = new IvySbt(ivyConf)
        val scalaVersion = Util.scalaVersion
        val binaryScalaVersion = CrossVersion.binaryScalaVersion(scalaVersion)
        val ivyScala = new IvyScala(scalaVersion, binaryScalaVersion, Nil, checkExplicit=true, filterImplicit=true, overrideScalaVersion=false)
        val project = ModuleID("org.example", projectName, "1")
        val settings = new InlineConfiguration(project, ModuleInfo("IScala Session"), dependencies, ivyScala=Some(ivyScala))
        val module = new ivySbt.Module(settings)
        val updateConf = new UpdateConfiguration(None, false, UpdateLogging.DownloadOnly)
        val updateReport = IvyActions.update(module, updateConf, Logger)
        updateReport.toSeq.map { case (_, _, _, jar) => jar }.distinct
    }
}
