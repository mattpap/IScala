package org.refptr.iscala

import java.io.{InputStream,PipedInputStream,OutputStream,
    PipedOutputStream,PrintStream,StringWriter,PrintWriter}

import org.zeromq.ZMQ

import scalax.io.JavaConverters._
import scalax.file.Path

import org.refptr.iscala.msg._
import org.refptr.iscala.formats._

import org.refptr.iscala.json.{Json,JsonUtil}
import play.api.libs.json.{Reads,Writes,Format}

object IScala extends App {
    import Util._
    import JsonUtil._

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

    val hmac = new HMAC(profile.key)
    val zmq = new Sockets(profile)

    def welcome() {
        import scala.util.Properties._
        log(s"Welcome to Scala $versionNumberString ($javaVmName, Java $javaVersion)")
    }

    def terminate() {
        log("Shutting down")
        zmq.terminate()
    }

    Runtime.getRuntime().addShutdownHook(new Thread() {
        override def run() {
            log("Terminating IScala")
            // terminate()
        }
    })

    def msg_header(m: Msg[_], msg_type: MsgType): Header =
        Header(msg_id=uuid4(),
               username=m.header.username,
               session=m.header.session,
               msg_type=msg_type)

    def msg_pub[T<:Reply](m: Msg[_], msg_type: MsgType, content: T, metadata: Metadata=Metadata()): Msg[T] = {
        val tpe = content match {
            case content: stream => content.name
            case _ => msg_type.toString
        }
        Msg(tpe :: Nil, msg_header(m, msg_type), Some(m.header), metadata, content)
    }

    def msg_reply[T<:Reply](m: Msg[_], msg_type: MsgType, content: T, metadata: Metadata=Metadata()): Msg[T] =
        Msg(m.idents, msg_header(m, msg_type), Some(m.header), metadata, content)

    val DELIMITER = "<IDS|MSG>"

    def send_ipython[T<:Reply:Writes](socket: ZMQ.Socket, m: Msg[T]) {
        debug(s"sending: $m")
        m.idents.foreach(socket.send(_, ZMQ.SNDMORE))
        socket.send(DELIMITER, ZMQ.SNDMORE)
        val header = toJSON(m.header)
        val parent_header = m.parent_header match {
            case Some(parent_header) => toJSON(parent_header)
            case None => "{}"
        }
        val metadata = toJSON(m.metadata)
        val content = toJSON(m.content)
        debug(s"json: $content")
        socket.send(hmac(header, parent_header, metadata, content), ZMQ.SNDMORE)
        socket.send(header, ZMQ.SNDMORE)
        socket.send(parent_header, ZMQ.SNDMORE)
        socket.send(metadata, ZMQ.SNDMORE)
        socket.send(content)
    }

    def recv_ipython(socket: ZMQ.Socket): Msg[Request] = {
        val idents = Stream.continually {
            socket.recvStr()
        }.takeWhile(_ != DELIMITER).toList
        val signature = socket.recvStr()
        val header = socket.recvStr()
        val parent_header = socket.recvStr()
        val metadata = socket.recvStr()
        val content = socket.recvStr()
        if (signature != hmac(header, parent_header, metadata, content)) {
            sys.error("Invalid HMAC signature") // What should we do here?
        }
        val _header = header.as[Header]
        val _parent_header = parent_header.as[Option[Header]]
        val _metadata = metadata.as[Metadata]
        val _content = _header.msg_type match {
            case MsgType.execute_request => content.as[execute_request]
            case MsgType.complete_request => content.as[complete_request]
            case MsgType.kernel_info_request => content.as[kernel_info_request]
            case MsgType.object_info_request => content.as[object_info_request]
            case MsgType.connect_request => content.as[connect_request]
            case MsgType.shutdown_request => content.as[shutdown_request]
            case MsgType.history_request => content.as[history_request]
        }
        val msg = Msg(idents, _header, _parent_header, _metadata, _content)
        debug(s"received: $msg")
        msg
    }

    def send_status(state: ExecutionState) {
        val msg = Msg(
            "status" :: Nil,
            Header(msg_id=uuid4(),
                   username="scala_kernel",
                   session=uuid4(),
                   msg_type=MsgType.status),
            None,
            Metadata(),
            status(
                execution_state=state))
        send_ipython(zmq.publish, msg)
    }

    def send_ok(msg: Msg[_], execution_count: Int) {
        val user_variables: List[String] = Nil
        val user_expressions: Map[String, String] = Map()

        send_ipython(zmq.requests, msg_reply(msg, MsgType.execute_reply,
            execute_ok_reply(
                execution_count=execution_count,
                payload=Nil,
                user_variables=user_variables,
                user_expressions=user_expressions)))
    }

    def pyerr_content(exception: Throwable, execution_count: Int): pyerr = {
        val ename = exception.getClass.getName
        val evalue = exception.getMessage
        val traceback =
            s"$ename: $evalue" ::
            exception
                .getStackTrace()
                .takeWhile(_.getFileName != "<console>")
                .map("    " + _)
                .toList

        pyerr(execution_count=execution_count,
              ename=ename,
              evalue=evalue,
              traceback=traceback)
    }

    def send_error(msg: Msg[_], execution_count: Int, error: String) {
        send_error(msg, pyerr(execution_count, "", "", error.split("\n").toList))
    }

    def send_error(msg: Msg[_], err: pyerr) {
        send_ipython(zmq.publish, msg_pub(msg, MsgType.pyerr, err))
        send_ipython(zmq.requests, msg_reply(msg, MsgType.execute_reply,
            execute_error_reply(
                execution_count=err.execution_count,
                ename=err.ename,
                evalue=err.evalue,
                traceback=err.traceback)))
    }

    sealed trait Std {
        val name: String
        val input: InputStream
        val output: OutputStream
        val stream: PrintStream
    }

    object StdOut extends Std {
        val name = "stdout"

        val input = new PipedInputStream()
        val output = new PipedOutputStream(input)
        val stream = new PrintStream(output)
    }

    object StdErr extends Std {
        val name = "stderr"

        val input = new PipedInputStream()
        val output = new PipedOutputStream(input)
        val stream = new PrintStream(output)
    }

    def send_stream(msg: Msg[_], std: Std, data: String) {
        send_ipython(zmq.publish, msg_pub(msg, MsgType.stream,
            stream(
                name=std.name,
                data=data)))
    }

    def finish_stream(msg: Msg[_], std: Std) {
        std.output.flush()
        val n = std.input.available
        if (n > 0) {
            val buffer = new Array[Byte](n)
            std.input.read(buffer)
            send_stream(msg, std, new String(buffer))
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
            try {
                while (true) {
                    val n = std.input.read(buffer)
                    send_stream(executeMsg, std, new String(buffer.take(n)))
                    if (n < size) {
                        Thread.sleep(100) // a little delay to accumulate output
                    }
                }
            } catch {
                case _: InterruptedException =>
                    // the IPython manager may send us a SIGINT if the user
                    // chooses to interrupt the kernel; don't crash on this
            }
        }
    }

    val watchOut = new WatchStream(StdOut)
    val watchErr = new WatchStream(StdErr)

    watchOut.start()
    watchErr.start()

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

            send_ipython(zmq.publish, msg_pub(msg, MsgType.pyin,
                pyin(
                    execution_count=interpreter.n,
                    code=code)))
        }

        send_status(ExecutionState.busy)

        try {
            code match {
                case Magic(name, input, Some(magic)) =>
                    capture { magic(interpreter, input) } match {
                        case None =>
                            finish_streams(msg)
                            send_ok(msg, interpreter.n)
                        case Some(error) =>
                            finish_streams(msg)
                            send_error(msg, interpreter.n, error)
                    }
                case Magic(name, _, None) =>
                    finish_streams(msg)
                    send_error(msg, interpreter.n, s"ERROR: Line magic function `%$name` not found.")
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
                                send_ipython(zmq.publish, msg_pub(msg, MsgType.pyout,
                                    pyout(
                                        execution_count=interpreter.n,
                                        data=Data("text/plain" -> data))))
                            }

                            send_ok(msg, interpreter.n)
                        case Results.Failure(exception) =>
                            finish_streams(msg)
                            send_error(msg, pyerr_content(exception, interpreter.n))
                        case Results.Error =>
                            finish_streams(msg)
                            send_error(msg, interpreter.n, interpreter.output.toString)
                        case Results.Incomplete =>
                            finish_streams(msg)
                            send_error(msg, interpreter.n, "incomplete")
                    }
            }
        } catch {
            case exception: Throwable =>
                finish_streams(msg)
                send_error(msg, pyerr_content(exception, interpreter.n)) // Internal Error
        } finally {
            interpreter.resetOutput()
            send_status(ExecutionState.idle)
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

        send_ipython(socket, msg_reply(msg, MsgType.complete_reply,
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

        send_ipython(socket, msg_reply(msg, MsgType.kernel_info_reply,
            kernel_info_reply(
                protocol_version=(4, 0),
                language_version=scalaVersion,
                language="scala")))
    }

    def handle_connect_request(socket: ZMQ.Socket, msg: Msg[connect_request]) {
        send_ipython(socket, msg_reply(msg, MsgType.connect_reply,
            connect_reply(
                shell_port=profile.shell_port,
                iopub_port=profile.iopub_port,
                stdin_port=profile.stdin_port,
                hb_port=profile.hb_port)))
    }

    def handle_shutdown_request(socket: ZMQ.Socket, msg: Msg[shutdown_request]) {
        send_ipython(socket, msg_reply(msg, MsgType.shutdown_reply,
            shutdown_reply(
                restart=msg.content.restart)))
        sys.exit()
    }

    def handle_object_info_request(socket: ZMQ.Socket, msg: Msg[object_info_request]) {
        send_ipython(socket, msg_reply(msg, MsgType.object_info_reply,
            object_info_notfound_reply(
                name=msg.content.oname)))
    }

    def handle_history_request(socket: ZMQ.Socket, msg: Msg[history_request]) {
        send_ipython(socket, msg_reply(msg, MsgType.history_reply,
            history_reply(
                history=Nil)))
    }

    class HeartBeat(socket: ZMQ.Socket) extends Thread {
        override def run() {
            ZMQ.proxy(socket, socket, null)
        }
    }

    def start_heartbeat(socket: ZMQ.Socket) {
        val thread = new HeartBeat(socket)
        thread.start()
    }

    class EventLoop(socket: ZMQ.Socket) extends Thread {
        override def run() {
            while (!Thread.interrupted) {
                val msg = recv_ipython(socket)

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
            try {
                Thread.sleep(1000*60)
            } catch {
                case _: InterruptedException =>
                    // the IPython manager may send us a SIGINT if the user
                    // chooses to interrupt the kernel; don't crash on this
            }
        }
    }

    start_heartbeat(zmq.heartbeat)
    send_status(ExecutionState.starting)

    debug("Starting kernel event loops")

    (new EventLoop(zmq.requests)).start()
    (new EventLoop(zmq.control)).start()

    welcome()
    waitloop()

    terminate()
}
