package org.refptr.iscala

import sun.misc.{Signal,SignalHandler}

import org.zeromq.ZMQ

import scalax.io.JavaConverters._
import scalax.file.Path

import Util.{getpid,log,debug}
import json.JsonUtil._
import msg._

object IScala extends App {
    val options = new Options(args)

    val thread = new Thread {
        override def run() {
            val iscala = new IScala(options)
            iscala.heartBeat.join()
        }
    }

    thread.setName("IScala")
    thread.setDaemon(true)
    thread.start()
    thread.join()
}

class IScala(options: Options) extends Parent {
    val profile = options.profile match {
        case Some(path) => Path(path).string.as[Profile]
        case None =>
            val file = Path(s"profile-${getpid()}.json")
            log(s"connect ipython with --existing ${file.toAbsolute.path}")
            val profile = Profile.default
            file.write(toJSON(profile))
            profile
    }

    val zmq = new Sockets(profile)
    val ipy = new Communication(zmq, profile)

    lazy val interpreter = new Interpreter(options.tail)

    def welcome() {
        import scala.util.Properties._
        log(s"Welcome to Scala $versionNumberString ($javaVmName, Java $javaVersion)")
    }

    Runtime.getRuntime().addShutdownHook(new Thread() {
        override def run() {
            debug("Terminating IScala")
            interpreter.finish()
        }
    })

    Signal.handle(new Signal("INT"), new SignalHandler {
        private var previously: Long = 0

        def handle(signal: Signal) {
            interpreter.cancel()

            if (!options.parent) {
                val now = System.currentTimeMillis
                if (now - previously < 500) sys.exit() else previously = now
            }
        }
    })

    class HeartBeat extends Thread {
        override def run() {
            ZMQ.proxy(zmq.heartbeat, zmq.heartbeat, null)
        }
    }

    (options.profile, options.parent) match {
        case (Some(file), true) =>
            // This setup means that this kernel was started by IPython. Currently
            // IPython is unable to terminate IScala without explicitly killing it
            // or sending shutdown_request. To fix that, IScala watches the profile
            // file whether it exists or not. When the file is removed, IScala is
            // terminated.

            class FileWatcher(file: java.io.File, interval: Int) extends Thread {
                override def run() {
                    while (true) {
                        if (file.exists) Thread.sleep(interval)
                        else sys.exit()
                    }
                }
            }

            val fileWatcher = new FileWatcher(file, 1000)
            fileWatcher.setName(s"FileWatcher(${file.getPath})")
            fileWatcher.start()
        case _ =>
    }

    val ExecuteHandler = new ExecuteHandler(this)
    val CompleteHandler = new CompleteHandler(this)
    val KernelInfoHandler = new KernelInfoHandler(this)
    val ObjectInfoHandler = new ObjectInfoHandler(this)
    val ConnectHandler = new ConnectHandler(this)
    val ShutdownHandler = new ShutdownHandler(this)
    val HistoryHandler = new HistoryHandler(this)

    class EventLoop(socket: ZMQ.Socket) extends Thread {
        override def run() {
            while (true) {
                ipy.recv(socket).foreach { msg =>
                    msg.header.msg_type match {
                        case MsgType.execute_request => ExecuteHandler(socket, msg.asInstanceOf[Msg[execute_request]])
                        case MsgType.complete_request => CompleteHandler(socket, msg.asInstanceOf[Msg[complete_request]])
                        case MsgType.kernel_info_request => KernelInfoHandler(socket, msg.asInstanceOf[Msg[kernel_info_request]])
                        case MsgType.object_info_request => ObjectInfoHandler(socket, msg.asInstanceOf[Msg[object_info_request]])
                        case MsgType.connect_request => ConnectHandler(socket, msg.asInstanceOf[Msg[connect_request]])
                        case MsgType.shutdown_request => ShutdownHandler(socket, msg.asInstanceOf[Msg[shutdown_request]])
                        case MsgType.history_request => HistoryHandler(socket, msg.asInstanceOf[Msg[history_request]])
                    }
                }
            }
        }
    }

    val heartBeat = new HeartBeat
    heartBeat.setName("HeartBeat")
    heartBeat.start()

    ipy.send_status(ExecutionState.starting)

    debug("Starting kernel event loops")

    val requestsLoop = new EventLoop(zmq.requests)
    val controlLoop = new EventLoop(zmq.control)

    requestsLoop.setName("RequestsEventLoop")
    controlLoop.setName("ControlEventLoop")

    requestsLoop.start()
    controlLoop.start()

    welcome()
}
