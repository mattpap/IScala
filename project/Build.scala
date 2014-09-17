import sbt._
import Keys._

import sbtassembly.{Plugin=>SbtAssembly}
import org.sbtidea.SbtIdeaPlugin
import com.typesafe.sbt.SbtNativePackager

object Dependencies {
    val isScala_2_10 = Def.setting {
        scalaVersion.value.startsWith("2.10")
    }

    def scala_2_10(moduleID: ModuleID) =
        Def.setting { if (isScala_2_10.value) Seq(moduleID) else Seq.empty }

    def scala_2_11_+(moduleID: ModuleID) =
        Def.setting { if (!isScala_2_10.value) Seq(moduleID) else Seq.empty }

    val scalaio = {
        val namespace = "com.github.scala-incubator.io"
        val version = "0.4.3"
        Seq(namespace %% "scala-io-core" % version,
            namespace %% "scala-io-file" % version)
    }

    val ivy = "org.scala-sbt" % "ivy" % "0.13.5"

    val jopt = "net.sf.jopt-simple" % "jopt-simple" % "4.5"

    val jeromq = "org.zeromq" % "jeromq" % "0.3.4"

    val play_json = "com.typesafe.play" %% "play-json" % "2.4.0-M1"

    val slick = "com.typesafe.slick" %% "slick" % "2.1.0"

    val h2 = "com.h2database" % "h2" % "1.4.181"

    val sqlite = "org.xerial" % "sqlite-jdbc" % "3.7.2"

    val slf4j = "org.slf4j" % "slf4j-nop" % "1.6.4"

    val specs2 = "org.specs2" %% "specs2" % "2.3.11" % Test

    val reflect = Def.setting { "org.scala-lang" % "scala-reflect" % scalaVersion.value }

    val compiler = Def.setting { "org.scala-lang" % "scala-compiler" % scalaVersion.value }

    val paradise = "org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full

    val quasiquotes = scala_2_10("org.scalamacros" %% "quasiquotes" % "2.0.0")
}

object IScalaBuild extends Build {
    override lazy val settings = super.settings ++ Seq(
        organization := "org.refptr.iscala",
        name := "IScala",
        version := "0.3-SNAPSHOT",
        description := "Scala-language backend for IPython",
        homepage := Some(url("http://iscala.github.io")),
        licenses := Seq("MIT-style" -> url("http://www.opensource.org/licenses/mit-license.php")),
        scalaVersion := "2.11.2",
        crossScalaVersions := Seq("2.10.4", "2.11.2"),
        scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-language:_"),
        addCompilerPlugin(Dependencies.paradise),
        shellPrompt := { state =>
            "refptr (%s)> ".format(Project.extract(state).currentProject.id)
        },
        cancelable := true)

    val release = taskKey[File]("Create a set of archives and installers for new release")

    val ipyCommands = settingKey[Seq[(String, String)]]("IPython commands (e.g. console) and their command line options")

    val debugPort = settingKey[Int]("Port for remote debugging")
    val debugCommand = taskKey[Seq[String]]("JVM command line options enabling remote debugging")

    val develScripts = taskKey[Seq[File]]("Development scripts generated in bin/")
    val userScripts = taskKey[Seq[File]]("User scripts generated in target/scala-2.xx/bin/")

    lazy val ideaSettings = SbtIdeaPlugin.settings

    lazy val assemblySettings = SbtAssembly.assemblySettings ++ {
        import SbtAssembly.AssemblyKeys._
        Seq(test in assembly := {},
            jarName in assembly := s"${name.value}.jar",
            target in assembly := crossTarget.value / "lib",
            assembly <<= assembly dependsOn userScripts)
    }

    lazy val packagerSettings = SbtNativePackager.packagerSettings ++ {
        import SbtNativePackager.NativePackagerKeys._
        import SbtNativePackager.Universal
        import SbtAssembly.AssemblyKeys.assembly
        Seq(packageName in Universal := {
                s"${name.value}-${scalaBinaryVersion.value}-${version.value}"
            },
            mappings in Universal ++= {
                assembly.value pair relativeTo(crossTarget.value)
            },
            mappings in Universal ++= {
                userScripts.value pair relativeTo(crossTarget.value)
            },
            mappings in Universal ++= {
                val paths = Seq("README.md", "LICENSE")
                paths.map(path => (file(path), path))
            },
            release <<= packageZipTarball in Universal)
    }

    lazy val pluginSettings = ideaSettings ++ assemblySettings ++ packagerSettings

    lazy val iscalaSettings = Defaults.coreDefaultSettings ++ pluginSettings ++ {
        import SbtAssembly.AssemblyKeys.{assembly,jarName}
        Seq(libraryDependencies ++= {
                import Dependencies._
                scalaio ++ Seq(ivy, jopt, jeromq, play_json, slick, h2, sqlite, slf4j, specs2, compiler.value)
            },
            fork in run := true,
            initialCommands in Compile := """
                import scala.reflect.runtime.{universe=>u}
                import scala.tools.nsc.interpreter._
                import scalax.io.JavaConverters._
                import scalax.file.Path
                import scala.slick.driver.SQLiteDriver.simple._
                import Database.threadLocalSession
                """,
            ipyCommands := List(
                "console"   -> "--no-banner",
                "qtconsole" -> "",
                "notebook"  -> ""),
            debugPort := 5005,
            debugCommand <<= (debugPort) map { (port) =>
                Seq("-Xdebug", s"-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$port")
            },
            develScripts <<= (fullClasspath in Compile, mainClass in Compile, baseDirectory, ipyCommands, debugCommand, streams) map {
                    (fullClasspath, mainClass, base, commands, debug, streams) =>
                val classpath = fullClasspath.files.mkString(java.io.File.pathSeparator)
                val main = mainClass getOrElse sys.error("unknown main class")

                val cmd = Seq("java") ++ debug ++ Seq("-cp", classpath, main, "--profile", "{connection_file}", "--parent", "$@")
                val kernel_cmd = cmd.map(arg => s"""\\"$arg\\"""").mkString(", ")

                val bin = base / "bin"
                IO.createDirectory(bin)

                commands map { case (command, options) =>
                    val output =
                        s"""
                        |#!/bin/bash
                        |ipython $command --profile scala --KernelManager.kernel_cmd="[$kernel_cmd]" $options
                        """.stripMargin.trim + "\n"
                    val file = bin / command
                    streams.log.info(s"Writing ${file.getPath}")
                    IO.write(file, output)
                    file.setExecutable(true)
                    file
                }
            },
            userScripts <<= (jarName in assembly, crossTarget, ipyCommands, streams) map { (jarName, target, commands, streams) =>
                val bin = target / "bin"
                IO.createDirectory(bin)

                commands map { case (command, options) =>
                    val output = s"""
                        |#!/bin/bash
                        |JAR_PATH="$$(dirname $$(dirname $$(readlink -f $$0)))/lib/$jarName"
                        |KERNEL_CMD="[\\"java\\", \\"-jar\\", \\"$$JAR_PATH\\", \\"--profile\\", \\"{connection_file}\\", \\"--parent\\", \\"$$@\\"]"
                        |ipython $command --profile scala --KernelManager.kernel_cmd="$$KERNEL_CMD" $options
                        """.stripMargin.trim + "\n"
                    val file = bin / command
                    streams.log.info(s"Writing ${file.getPath}")
                    IO.write(file, output)
                    file.setExecutable(true)
                    file
                }
            })
    }

    lazy val macrosSettings = Defaults.coreDefaultSettings ++ Seq(
        libraryDependencies ++= {
            import Dependencies._
            quasiquotes.value ++ Seq(reflect.value, play_json, specs2)
        }
    )

    lazy val IScala = project in file(".")  settings(iscalaSettings: _*) dependsOn(macros)

    lazy val macros = project in file("macros") settings(macrosSettings: _*)

    override def projects = Seq(IScala, macros)
}
