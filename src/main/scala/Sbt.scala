package org.refptr.iscala

import java.io.File

import sbt.{
    ConsoleLogger,
    ModuleID,ModuleInfo,
    Resolver,MavenRepository,
    IvyPaths,InlineIvyConfiguration,
    CrossVersion,
    ShowLines,
    IvySbt,IvyScala,IvyActions,
    InlineConfiguration,UpdateConfiguration,UpdateOptions,
    UpdateLogging,UnresolvedWarningConfiguration}

object Sbt {
    val defaultDependencies: Seq[ModuleID] = Seq()
    val defaultResolvers: Seq[Resolver] = Seq(Resolver.sonatypeRepo("releases"))
    val logger = ConsoleLogger()

    def resolve(dependencies: Seq[ModuleID], resolvers: Seq[Resolver]): Option[Seq[File]] = {
        val paths = new IvyPaths(new File("."), None)
        val ivyConf = new InlineIvyConfiguration(paths, resolvers, Nil, Nil, false, None, Seq("sha1", "md5"), None, UpdateOptions(), logger)
        val ivySbt = new IvySbt(ivyConf)
        val scalaVersion = Util.scalaVersion
        val binaryScalaVersion = CrossVersion.binaryScalaVersion(scalaVersion)
        val ivyScala = new IvyScala(scalaVersion, binaryScalaVersion, Nil, checkExplicit=true, filterImplicit=true, overrideScalaVersion=false)
        val project = ModuleID("org.refptr.iscala", "IScala", "0.3-SNAPSHOT")
        val settings = new InlineConfiguration(project, ModuleInfo("IScala"), dependencies, ivyScala=Some(ivyScala))
        val module = new ivySbt.Module(settings)
        val updateConf = new UpdateConfiguration(None, false, UpdateLogging.DownloadOnly)
        val updateReport = IvyActions.updateEither(module, updateConf, UnresolvedWarningConfiguration(), logger)
        updateReport match {
            case Right(report) =>
                Some(report.toSeq.map { case (_, _, _, jar) => jar }.distinct)
            case Left(warning) =>
                import ShowLines._
                warning.lines.foreach(logger.error(_))
                None
        }
    }
}
