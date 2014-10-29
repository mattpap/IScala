package org.refptr.iscala

import sun.misc.{Signal,SignalHandler}

import org.zeromq.ZMQ

import scalax.io.JavaConverters._
import scalax.file.Path

import json.JsonUtil._
import msg._

object IScala extends App {
    val options = new Options(args)

    val thread = new Thread {
        override def run() {
            val iscala = new IScala(options.config)
            iscala.heartBeat.join()
        }
    }

    thread.setName("IScala")
    thread.setDaemon(true)
    thread.start()
    thread.join()
}

class IScala(config: Options#Config) extends Parent {
    val connection = config.connection_file match {
        case Some(path) => Path(path).string.as[Connection]
        case None =>
            val file = Path(s"kernel-${Util.getpid()}.json")
            logger.info(s"connect ipython with --existing ${file.toAbsolute.path}")
            val connection = Connection.default
            file.write(toJSON(connection))
            connection
    }

    val classpath = {
        val (baseClasspath, baseModules) = config.javacp match {
            case false => ("",                           Modules.Compiler :: Nil)
            case true  => (sys.props("java.class.path"), Nil)
        }

        val modules = baseModules ++ config.modules
        val resolvers = config.resolvers

        val resolved = Sbt.resolve(modules, resolvers).map(_.classpath) getOrElse {
            sys.error("Failed to resolve dependencies")
        }

        ClassPath.join(baseClasspath, config.classpath, resolved)
    }

    val interpreter = new Interpreter(classpath, config.args)

    val zmq = new Sockets(connection)
    val ipy = new Communication(zmq, connection)

    def welcome() {
        import scala.util.Properties._
        println(s"Welcome to Scala $versionNumberString ($javaVmName, Java $javaVersion)")
    }

    Runtime.getRuntime().addShutdownHook(new Thread() {
        override def run() {
            logger.debug("Terminating IScala")
            interpreter.finish()
        }
    })

    Signal.handle(new Signal("INT"), new SignalHandler {
        private var previously = System.currentTimeMillis

        def handle(signal: Signal) {
            if (!config.parent) {
                val now = System.currentTimeMillis
                if (now - previously < 500) sys.exit() else previously = now
            }

            interpreter.cancel()
        }
    })

    class HeartBeat extends Thread {
        override def run() {
            ZMQ.proxy(zmq.heartbeat, zmq.heartbeat, null)
        }
    }

    (config.connection_file, config.parent) match {
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

    val ExecuteHandler    = new ExecuteHandler(this)
    val CompleteHandler   = new CompleteHandler(this)
    val KernelInfoHandler = new KernelInfoHandler(this)
    val ObjectInfoHandler = new ObjectInfoHandler(this)
    val ConnectHandler    = new ConnectHandler(this)
    val ShutdownHandler   = new ShutdownHandler(this)
    val HistoryHandler    = new HistoryHandler(this)
    val CommOpenHandler   = new CommOpenHandler(this)
    val CommMsgHandler    = new CommMsgHandler(this)
    val CommCloseHandler  = new CommCloseHandler(this)

    class Conn(msg: Msg[_]) extends display.Conn {
        def display_data(data: Data) {
            ipy.send_display_data(msg, data)
        }
    }

    class EventLoop(socket: ZMQ.Socket) extends Thread {
        def dispatch[T <: FromIPython](msg: Msg[T]) {
            display.IScala.withConn(new Conn(msg)) {
                msg.header.msg_type match {
                    case MsgType.execute_request     => ExecuteHandler(socket, msg.asInstanceOf[Msg[execute_request]])
                    case MsgType.complete_request    => CompleteHandler(socket, msg.asInstanceOf[Msg[complete_request]])
                    case MsgType.kernel_info_request => KernelInfoHandler(socket, msg.asInstanceOf[Msg[kernel_info_request]])
                    case MsgType.object_info_request => ObjectInfoHandler(socket, msg.asInstanceOf[Msg[object_info_request]])
                    case MsgType.connect_request     => ConnectHandler(socket, msg.asInstanceOf[Msg[connect_request]])
                    case MsgType.shutdown_request    => ShutdownHandler(socket, msg.asInstanceOf[Msg[shutdown_request]])
                    case MsgType.history_request     => HistoryHandler(socket, msg.asInstanceOf[Msg[history_request]])
                    case MsgType.comm_open           => CommOpenHandler(socket, msg.asInstanceOf[Msg[comm_open]])
                    case MsgType.comm_msg            => CommMsgHandler(socket, msg.asInstanceOf[Msg[comm_msg]])
                    case MsgType.comm_close          => CommCloseHandler(socket, msg.asInstanceOf[Msg[comm_close]])
                    case _                           =>
                }
            }
        }

        override def run() {
            try {
                while (true) {
                    ipy.recv(socket).foreach(dispatch)
                }
            } catch {
                case exc: Exception =>
                    zmq.terminate() // this will gracefully terminate heartbeat
                    throw exc
            }
        }
    }

    val heartBeat = new HeartBeat
    heartBeat.setName("HeartBeat")
    heartBeat.start()

    logger.debug("Starting kernel event loop")
    ipy.send_status(ExecutionState.starting)

    val requestsLoop = new EventLoop(zmq.requests)
    requestsLoop.setName("RequestsEventLoop")
    requestsLoop.start()

    welcome()
}
