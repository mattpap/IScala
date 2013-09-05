import sbt._
import Keys._

import sbtassembly.{Plugin=>SbtAssembly}
import org.sbtidea.SbtIdeaPlugin
import com.typesafe.sbt.SbtProguard
import com.typesafe.sbt.SbtNativePackager

object ProjectBuild extends Build {
    override lazy val settings = super.settings ++ Seq(
        organization := "org.refptr",
        version := "0.1",
        scalaVersion := "2.10.2",
        scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-language:_"),
        shellPrompt := { state =>
            "refptr (%s)> ".format(Project.extract(state).currentProject.id)
        },
        cancelable := true,
        resolvers ++= Seq(
            "Sonatype Releases" at "http://oss.sonatype.org/content/repositories/releases",
            "Sonatype Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
            "Mandubian Releases" at "https://github.com/mandubian/mandubian-mvn/raw/master/releases/",
            "Mandubian Snapshots" at "https://github.com/mandubian/mandubian-mvn/raw/master/snapshots/")
    )

    object Dependencies {
        val scalaio = {
            val namespace = "com.github.scala-incubator.io"
            val version = "0.4.2"
            Seq(namespace %% "scala-io-core" % version,
                namespace %% "scala-io-file" % version)
        }

        val ivy = "org.scala-sbt" % "ivy" % "0.13.0"

        val jopt = "net.sf.jopt-simple" % "jopt-simple" % "4.5"

        val jeromq = "org.jeromq" % "jeromq" % "0.3.0-SNAPSHOT"

        val play_json = "play" %% "play-json" % "2.2-SNAPSHOT"

        val slick = "com.typesafe.slick" %% "slick" % "1.0.1"

        val h2 = "com.h2database" % "h2" % "1.3.173"

        val sqlite = "org.xerial" % "sqlite-jdbc" % "3.7.2"

        val slf4j = "org.slf4j" % "slf4j-nop" % "1.6.4"

        val specs2 = "org.specs2" %% "specs2" % "2.1.1" % "test"
    }

    def info(msg: => String) {
        ConsoleLogger().log(Level.Info, msg)
    }

    val release = TaskKey[File]("release")

    val scripts = TaskKey[Seq[File]]("scripts")

    val jrebelRunning = SettingKey[Boolean]("jrebel-running")

    lazy val ideaSettings = SbtIdeaPlugin.settings

    lazy val assemblySettings = SbtAssembly.assemblySettings ++ {
        import SbtAssembly.AssemblyKeys._
        Seq(test in assembly := {},
            jarName in assembly := "IScala.jar",
            target in assembly := baseDirectory.value / "lib",
            assemblyDirectory in assembly := {
                val tmpDir = IO.createTemporaryDirectory
                info(s"Using $tmpDir for sbt-assembly temporary files")
                tmpDir
            },
            assembly <<= (assembly, assemblyDirectory in assembly) map { (outputFile, tmpDir) =>
                info(s"Cleaning up $tmpDir")
                IO.delete(tmpDir)
                outputFile
            })
    }

    lazy val proguardSettings = SbtProguard.proguardSettings ++ {
        import SbtProguard.{Proguard,ProguardOptions}
        import SbtProguard.ProguardKeys._
        Seq(javaOptions in (Proguard, proguard) := Seq("-Xmx2G"),
            options in Proguard += "@" + (baseDirectory.value / "project" / "IScala.pro"),
            options in Proguard += ProguardOptions.keepMain(organization.value + ".iscala.IScala"))
    }

    lazy val packagerSettings = SbtNativePackager.packagerSettings ++ {
        import SbtNativePackager.NativePackagerKeys._
        import SbtNativePackager.Universal
        Seq(mappings in Universal <++= (SbtAssembly.AssemblyKeys.assembly, baseDirectory) map { (jar, base) =>
                jar x relativeTo(base)
            },
            mappings in Universal ++= {
                val paths = Seq("README.md", "LICENSE", "bin/console", "bin/qtconsole", "bin/notebook")
                paths.map(path => (file(path), path))
            },
            name in Universal := "IScala",
            release <<= packageZipTarball in Universal)
    }

    lazy val pluginSettings = ideaSettings ++ assemblySettings ++ proguardSettings ++ packagerSettings

    lazy val projectSettings = Project.defaultSettings ++ pluginSettings ++ Seq(
        unmanagedBase := baseDirectory.value / "custom_lib",
        fork in run := true,
        javaOptions in run ++= List("-Xdebug", "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"),
        jrebelRunning := {
            val jrebel = java.lang.Package.getPackage("com.zeroturnaround.javarebel") != null
            info(s"JRebel is ${if (jrebel) "enabled" else "disabled"}")
            jrebel
        },
        libraryDependencies ++= {
            import Dependencies._
            scalaio ++ Seq(ivy, jopt, jeromq, play_json, slick, h2, sqlite, slf4j, specs2)
        },
        libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-compiler" % _),
        initialCommands := """
            import scala.reflect.runtime.{universe=>u}
            import scala.tools.nsc.interpreter._
            import scalax.io.JavaConverters._
            import scalax.file.Path
            import scala.slick.driver.SQLiteDriver.simple._
            import Database.threadLocalSession
            """,
        scripts in Compile <<= (fullClasspath in Compile, mainClass in Compile, target in Compile) map { (fullClasspath, mainClass, target) =>
            val classpath = fullClasspath.files.mkString(":")
            val main = mainClass getOrElse sys.error("Unknown main class")
            val commands = List(
                "console" -> "--no-banner",
                "qtconsole" -> "",
                "notebook" -> "")

            commands map { case (command, options) =>
                val output =
                    s"""
                    |#!/bin/bash
                    |KERNEL_CMD="[\\"java\\", \\"-cp\\", \\"$classpath\\", \\"$main\\", \\"--profile\\", \\"{connection_file}\\", \\"--parent\\", \\"$$@\\"]"
                    |ipython $command --profile scala --KernelManager.kernel_cmd="$$KERNEL_CMD" $options
                    """.stripMargin.trim + "\n"
                val file = target / command
                IO.write(file, output)
                s"chmod +x ${file.getPath}"!;
                file
            }
        }
    )

    lazy val macrosSettings = Project.defaultSettings ++ Seq(
        libraryDependencies ++= {
            import Dependencies._
            Seq(play_json, specs2)
        },
        libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-reflect" % _)
    )

    lazy val IScala = Project(id="IScala", base=file("."), settings=projectSettings) dependsOn(Macros)

    lazy val Macros = Project(id="Macros", base=file("macros"), settings=macrosSettings)

    override def projects = Seq(IScala, Macros)
}
