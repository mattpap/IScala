package org.refptr.iscala

import java.io.File
import java.util.{Timer,TimerTask}
import java.lang.management.ManagementFactory
import scala.tools.nsc.util.ClassPath

trait ClassPathUtil {
    def classpath(paths: Seq[File]): String = {
        ClassPath.join(paths.map(_.getAbsolutePath): _*)
    }
}

trait ScalaUtil {
    def scalaVersion = scala.util.Properties.versionNumberString
}

trait OSUtil {
    def getpid(): Int = {
        val name = ManagementFactory.getRuntimeMXBean().getName()
        name.takeWhile(_ != '@').toInt
    }
}

trait IOUtil {
    def newThread(fn: Thread => Unit)(body: => Unit): Thread = {
        val thread = new Thread(new Runnable {
            override def run() = body
        })
        fn(thread)
        thread.start
        thread
    }

    def timer(seconds: Int)(body: => Unit): Timer = {
        val alarm = new Timer(true)
        val task  = new TimerTask { def run() = body }
        alarm.schedule(task, seconds*1000)
        alarm
    }
}

trait ConsoleUtil {
    val origOut = System.out
    val origErr = System.err

    def log[T](message: => T) {
        origOut.println(message)
    }

    def debug[T](message: => T) {
        if (IScala.options.config.verbose) {
            origOut.println(message)
        }
    }
}

trait StringUtil {
    /** Find longest common prefix of a list of strings.
     */
    def commonPrefix(xs: List[String]): String = {
        if (xs.isEmpty || xs.contains("")) ""
        else xs.head.head match {
            case ch =>
                if (xs.tail forall (_.head == ch)) "" + ch + commonPrefix(xs map (_.tail))
                else ""
        }
    }

    /** Find longest string that is a suffix of `head` and prefix of `tail`.
     *
     *  Example:
     *
     *    isInstance
     *  x.is
     *    ^^
     *
     *  >>> Util.suffixPrefix("x.is", "isInstance")
     *  "is"
     */
    def suffixPrefix(head: String, tail: String): String = {
        var prefix = head
        while (!tail.startsWith(prefix)) {
            prefix = prefix.drop(1)
        }
        prefix
    }

    def hex(bytes: Seq[Byte]): String = {
        bytes.map("%02x" format _).mkString
    }
}

trait Util extends ClassPathUtil with ScalaUtil with OSUtil with IOUtil with ConsoleUtil with StringUtil
object Util extends Util
