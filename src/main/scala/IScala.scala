package org.refptr.iscala

import sun.misc.{Signal,SignalHandler}

import org.zeromq.ZMQ

import scalax.io.JavaConverters._
import scalax.file.Path

import org.refptr.iscala.msg._
import org.refptr.iscala.formats._

import org.refptr.iscala.Util.{getpid,log,debug}
import org.refptr.iscala.json.JsonUtil._

object IScala extends App {
    val options = new Options(args)

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

    def welcome() {
        import scala.util.Properties._
        log(s"Welcome to Scala $versionNumberString ($javaVmName, Java $javaVersion)")
    }

    Runtime.getRuntime().addShutdownHook(new Thread() {
        override def run() {
            log("Terminating IScala")
        }
    })

    Signal.handle(new Signal("INT"), new SignalHandler {
        private var previously: Long = 0

        def handle(signal: Signal) {
            if (!options.parent) {
                val now = System.currentTimeMillis
                if (now - previously < 500) sys.exit() else previously = now
            }
        }
    })

    def finish_stream(msg: Msg[_], std: Std) {
        std.output.flush()
        val n = std.input.available
        if (n > 0) {
            val buffer = new Array[Byte](n)
            std.input.read(buffer)
            ipy.send_stream(msg, std, new String(buffer))
        }
    }

    def finish_streams(msg: Msg[_]) {
        finish_stream(msg, StdOut)
        finish_stream(msg, StdErr)
    }

    var executeMsg: Msg[Request] = _

    class WatchStream(std: Std) extends Thread {
        override def run() {
            val size = 10240
            val buffer = new Array[Byte](size)

            while (true) {
                val n = std.input.read(buffer)
                ipy.send_stream(executeMsg, std, new String(buffer.take(n)))

                if (n < size) {
                    Thread.sleep(100) // a little delay to accumulate output
                }
            }
        }
    }

    def capture[T](block: => T): T = {
        Console.withOut(StdOut.stream) {
            Console.withErr(StdErr.stream) {
                block
            }
        }
    }

    lazy val interpreter = new Interpreter(options.tail)

    def handle_execute_request(socket: ZMQ.Socket, msg: Msg[execute_request]) {
        executeMsg = msg

        val content = msg.content
        val code = content.code
        val silent = content.silent || code.trim.endsWith(";")
        val store_history = content.store_history getOrElse !silent

        if (!silent) {
            interpreter.increment

            if (store_history) {
                interpreter.In(interpreter.n) = code
            }

            ipy.send(zmq.publish, ipy.msg_pub(msg, MsgType.pyin,
                pyin(
                    execution_count=interpreter.n,
                    code=code)))
        }

        ipy.send_status(ExecutionState.busy)

        try {
            code match {
                case Magic(name, input, Some(magic)) =>
                    capture { magic(interpreter, input) } match {
                        case None =>
                            finish_streams(msg)
                            ipy.send_ok(msg, interpreter.n)
                        case Some(error) =>
                            finish_streams(msg)
                            ipy.send_error(msg, interpreter.n, error)
                    }
                case Magic(name, _, None) =>
                    finish_streams(msg)
                    ipy.send_error(msg, interpreter.n, s"ERROR: Line magic function `%$name` not found.")
                case _ =>
                    capture { interpreter.interpret(code) } match {
                        case Results.Success(value) =>
                            val result = if (silent) None else value.map(interpreter.stringify)

                            if (!silent && store_history) {
                                value.foreach(interpreter.Out(interpreter.n) = _)

                                interpreter.intp.beSilentDuring {
                                    value.foreach(interpreter.intp.bind("_" + interpreter.n, "Any", _))
                                }
                            }

                            finish_streams(msg)

                            result.foreach { data =>
                                ipy.send(zmq.publish, ipy.msg_pub(msg, MsgType.pyout,
                                    pyout(
                                        execution_count=interpreter.n,
                                        data=Data("text/plain" -> data))))
                            }

                            ipy.send_ok(msg, interpreter.n)
                        case Results.Failure(exception) =>
                            finish_streams(msg)
                            ipy.send_error(msg, ipy.pyerr_content(exception, interpreter.n))
                        case Results.Error =>
                            finish_streams(msg)
                            ipy.send_error(msg, interpreter.n, interpreter.output.toString)
                        case Results.Incomplete =>
                            finish_streams(msg)
                            ipy.send_error(msg, interpreter.n, "incomplete")
                    }
            }
        } catch {
            case exception: Throwable =>
                finish_streams(msg)
                ipy.send_error(msg, ipy.pyerr_content(exception, interpreter.n)) // Internal Error
        } finally {
            interpreter.resetOutput()
            ipy.send_status(ExecutionState.idle)
        }
    }

    def handle_complete_request(socket: ZMQ.Socket, msg: Msg[complete_request]) {
        val text = msg.content.text

        val matches = if (msg.content.line.startsWith("%")) {
            val prefix = text.stripPrefix("%")
            Magic.magics.map(_.name.name).filter(_.startsWith(prefix)).map("%" + _)
        } else {
            val completions = interpreter.completion.completions(text)
            val common = Util.commonPrefix(completions)
            var prefix = Util.suffixPrefix(text, common)
            completions.map(_.stripPrefix(prefix)).map(text + _)
        }

        ipy.send(socket, ipy.msg_reply(msg, MsgType.complete_reply,
            complete_reply(
                status=ExecutionStatus.ok,
                matches=matches,
                text=text)))
    }

    def handle_kernel_info_request(socket: ZMQ.Socket, msg: Msg[kernel_info_request]) {
        val scalaVersion = Util.scalaVersion
            .split(Array('.', '-'))
            .take(3)
            .map(_.toInt)
            .toList

        ipy.send(socket, ipy.msg_reply(msg, MsgType.kernel_info_reply,
            kernel_info_reply(
                protocol_version=(4, 0),
                language_version=scalaVersion,
                language="scala")))
    }

    def handle_connect_request(socket: ZMQ.Socket, msg: Msg[connect_request]) {
        ipy.send(socket, ipy.msg_reply(msg, MsgType.connect_reply,
            connect_reply(
                shell_port=profile.shell_port,
                iopub_port=profile.iopub_port,
                stdin_port=profile.stdin_port,
                hb_port=profile.hb_port)))
    }

    def handle_shutdown_request(socket: ZMQ.Socket, msg: Msg[shutdown_request]) {
        ipy.send(socket, ipy.msg_reply(msg, MsgType.shutdown_reply,
            shutdown_reply(
                restart=msg.content.restart)))
        sys.exit()
    }

    def handle_object_info_request(socket: ZMQ.Socket, msg: Msg[object_info_request]) {
        ipy.send(socket, ipy.msg_reply(msg, MsgType.object_info_reply,
            object_info_notfound_reply(
                name=msg.content.oname)))
    }

    def handle_history_request(socket: ZMQ.Socket, msg: Msg[history_request]) {
        ipy.send(socket, ipy.msg_reply(msg, MsgType.history_reply,
            history_reply(
                history=Nil)))
    }

    class HeartBeat extends Thread {
        override def run() {
            ZMQ.proxy(zmq.heartbeat, zmq.heartbeat, null)
        }
    }

    class EventLoop(socket: ZMQ.Socket) extends Thread {
        override def run() {
            while (true) {
                val msg = ipy.recv(socket)

                msg.header.msg_type match {
                    case MsgType.execute_request => handle_execute_request(socket, msg.asInstanceOf[Msg[execute_request]])
                    case MsgType.complete_request => handle_complete_request(socket, msg.asInstanceOf[Msg[complete_request]])
                    case MsgType.kernel_info_request => handle_kernel_info_request(socket, msg.asInstanceOf[Msg[kernel_info_request]])
                    case MsgType.object_info_request => handle_object_info_request(socket, msg.asInstanceOf[Msg[object_info_request]])
                    case MsgType.connect_request => handle_connect_request(socket, msg.asInstanceOf[Msg[connect_request]])
                    case MsgType.shutdown_request => handle_shutdown_request(socket, msg.asInstanceOf[Msg[shutdown_request]])
                    case MsgType.history_request => handle_history_request(socket, msg.asInstanceOf[Msg[history_request]])
                }
            }
        }
    }

    def waitloop() {
        while (true) {
            Thread.sleep(1000*60)
        }
    }

    (new WatchStream(StdOut)).start()
    (new WatchStream(StdErr)).start()

    (new HeartBeat).start()
    ipy.send_status(ExecutionState.starting)

    debug("Starting kernel event loops")

    (new EventLoop(zmq.requests)).start()
    (new EventLoop(zmq.control)).start()

    welcome()
    waitloop()
}
