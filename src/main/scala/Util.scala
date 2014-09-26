package org.refptr.iscala

import java.io.File
import java.util.{Timer,TimerTask}
import java.lang.management.ManagementFactory

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

trait Util extends ScalaUtil with OSUtil with IOUtil with StringUtil
object Util extends Util
