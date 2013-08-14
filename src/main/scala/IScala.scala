package org.refptr.iscala

import java.io.File
import java.util.UUID
import java.lang.management.ManagementFactory

import org.zeromq.ZMQ

import scala.collection.mutable
import scala.tools.nsc.interpreter.{IMain,JLineCompletion,CommandLine,IR}

import scalax.io.JavaConverters._
import scalax.file.Path

import org.refptr.iscala.msg._
import org.refptr.iscala.json.{Json,JsonUtil}
import play.api.libs.json.{Reads,Writes,Format}

object Util {
    def uuid4(): String = UUID.randomUUID().toString

    def hex(bytes: Seq[Byte]): String = bytes.map("%02x" format _).mkString

    def getpid(): Int = {
        val name = ManagementFactory.getRuntimeMXBean().getName()
        name.takeWhile(_ != '@').toInt
    }

    val origOut = System.out
    val origErr = System.err

    def log[T](message: => T) {
        origOut.println(message)
    }
}

object IScala extends App {
    import Util._
    import JsonUtil._

    case class Profile(
        ip: String,
        transport: String,
        stdin_port: Int,
        control_port: Int,
        hb_port: Int,
        shell_port: Int,
        iopub_port: Int,
        key: String)

    object Profile {
        implicit val ProfileJSON = Json.format[Profile]
    }

    val profile = args.toList match {
        case path :: Nil =>
            Path.fromString(path).string.as[Profile]
        case Nil =>
            val port0 = 5678
            val profile = Profile(
                ip="127.0.0.1",
                transport="tcp",
                stdin_port=port0,
                control_port=port0+1,
                hb_port=port0+2,
                shell_port=port0+3,
                iopub_port=port0+4,
                key=uuid4())

            val file = Path(s"profile-${getpid()}.json")
            log(s"connect ipython with --existing ${file.toAbsolute.path}")
            file.write(toJSON(profile))

            profile
        case _=>
            throw new IllegalArgumentException("expected zero or one arguments")
    }

    val hmac = new HMAC(profile.key)

    val ctx = ZMQ.context(1)

    val publish = ctx.socket(ZMQ.PUB)
    val raw_input = ctx.socket(ZMQ.ROUTER)
    val requests = ctx.socket(ZMQ.ROUTER)
    val control = ctx.socket(ZMQ.ROUTER)
    val heartbeat = ctx.socket(ZMQ.REP)

    def terminate() {
        log("TERMINATING")

        publish.close()
        raw_input.close()
        requests.close()
        control.close()
        heartbeat.close()

        ctx.term()
    }

    /*
    Runtime.getRuntime().addShutdownHook(new Thread() {
        override def run() {
            terminate()
        }
    })
    */

    def uri(port: Int) = s"${profile.transport}://${profile.ip}:$port"

    publish.bind(uri(profile.iopub_port))
    requests.bind(uri(profile.shell_port))
    control.bind(uri(profile.control_port))
    raw_input.bind(uri(profile.stdin_port))
    heartbeat.bind(uri(profile.hb_port))

    def msg_pub[T<:Reply](m: Msg[_], msg_type: MsgType, content: T, metadata: Metadata=Metadata()): Msg[T] = {
        val tpe = (msg_type, content) match {
            case (MsgType.stream, content: stream) => content.name
            case _ => msg_type.toString
        }
        Msg(tpe :: Nil,
            Header(msg_id=uuid4(),
                   username=m.header.username,
                   session=m.header.session,
                   msg_type=msg_type),
            Some(m.header), metadata, content)
    }

    def msg_reply[T<:Reply](m: Msg[_], msg_type: MsgType, content: T, metadata: Metadata=Metadata()): Msg[T] = {
        Msg(m.idents,
            Header(msg_id=uuid4(),
                   username=m.header.username,
                   session=m.header.session,
                   msg_type=msg_type),
            Some(m.header), metadata, content)
    }

    def send_ipython[T<:Reply:Writes](socket: ZMQ.Socket, m: Msg[T]) {
        log(s"SENDING $m")
        m.idents.foreach(socket.send(_, ZMQ.SNDMORE))
        /*
        for (i <- m.idents) {
            socket.send(i, ZMQ.SNDMORE)
        }
        */
        socket.send("<IDS|MSG>", ZMQ.SNDMORE)
        val header = toJSON(m.header)
        val parent_header = m.parent_header match {
            case Some(parent_header) => toJSON(parent_header)
            case None => "{}"
        }
        val metadata = toJSON(m.metadata)
        val content = toJSON(m.content)
        socket.send(hmac(header, parent_header, metadata, content), ZMQ.SNDMORE)
        socket.send(header, ZMQ.SNDMORE)
        socket.send(parent_header, ZMQ.SNDMORE)
        socket.send(metadata, ZMQ.SNDMORE)
        socket.send(content)
    }

    def recv_ipython(socket: ZMQ.Socket): Msg[Request] = {
        val idents = Stream.continually {
            val s = socket.recvStr()
            log(s"got msg part $s")
            s
        }.takeWhile(_ != "<IDS|MSG>").toList
        /*
        var idents = new ArrayBuffer()
        var s = socket.recvStr()
        log(s"got msg part $s")
        while (s != "<IDS|MSG>") {
            idents.append(s)
            s = socket.recvStr()
            log(s"got msg part $s")
        }
        */
        val signature = socket.recvStr()
        val header = socket.recvStr()
        val parent_header = socket.recvStr()
        val metadata = socket.recvStr()
        val content = socket.recvStr()
        if (signature != hmac(header, parent_header, metadata, content)) {
            sys.error("Invalid HMAC signature") // What should we do here?
        }
        val h = header.as[Header]
        val c: Request = h.msg_type match {
            case MsgType.execute_request => content.as[execute_request]
            case MsgType.complete_request => content.as[complete_request]
            case MsgType.kernel_info_request => content.as[kernel_info_request]
            case MsgType.object_info_request => content.as[object_info_request]
            case MsgType.connect_request => content.as[connect_request]
            case MsgType.shutdown_request => content.as[shutdown_request]
            case MsgType.history_request => content.as[history_request]
        }
        val m = Msg(idents,
            h,
            parent_header.as[Option[Header]],
            // try { fromJSON[Option[Header]](parent_header) } catch { case _: MappingException => None },
            metadata.as[Metadata],
            c)
        log(s"RECEIVED $m")
        m
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
        send_ipython(publish, msg)
    }

    def pyerr_content(e: Exception): pyerr = {
        val s = new java.io.StringWriter
        val p = new java.io.PrintWriter(s)
        e.printStackTrace(p)

        val ename = e.getClass.getName
        val evalue = e.getMessage
        val traceback = s.toString.split("\n").toList

        pyerr(execution_count=_n,
              ename=ename,
              evalue=evalue,
              traceback=traceback)
    }

    def send_error(msg: Msg[_], err: pyerr) {
        send_ipython(publish, msg_pub(msg, MsgType.pyerr, err))
        send_ipython(requests, msg_reply(msg, MsgType.execute_reply,
            execute_error_reply(
                execution_count=err.execution_count,
                ename=err.ename,
                evalue=err.evalue,
                traceback=err.traceback)))
    }

    sealed abstract class Std(val name: String)
    object StdOut extends Std("stdout")
    object StdErr extends Std("stderr")

    def send_stream(msg: Msg[_], std: Std, data: String) {
        send_ipython(publish, msg_pub(msg, MsgType.stream,
            stream(
                name=std.name,
                data=data)))
    }

    def finish_stream(msg: Msg[_], std: Std, io: java.io.InputStream) {
        val n = io.available
        if (n > 0) {
            val buffer = new Array[Byte](n)
            io.read(buffer)
            send_stream(msg, std, new String(buffer))
        }
    }

    var executeMsg: Msg[Request] = _

    class WatchStream(io: java.io.InputStream, std: Std) extends Thread {
        override def run() {
            val size = 10240
            val buffer = new Array[Byte](size)
            try {
                while (true) {
                    val n = io.read(buffer)
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

    val outIn = new java.io.PipedInputStream()
    val outPipe = new java.io.PipedOutputStream(outIn)
    val newOut = new java.io.PrintStream(outPipe)

    val errIn = new java.io.PipedInputStream()
    val errPipe = new java.io.PipedOutputStream(errIn)
    val newErr = new java.io.PrintStream(errPipe)

    val watchOut = new WatchStream(outIn, StdOut)
    val watchErr = new WatchStream(errIn, StdErr)

    watchOut.start()
    watchErr.start()

    def capture[T](block: => T): T = {
        Console.withOut(newOut) {
            Console.withErr(newErr) {
                block
            }
        }
    }

    def initInterpreter(args: Seq[String]) = {
        val commandLine = new CommandLine(args.toList, println)
        commandLine.settings.embeddedDefaults[this.type]
        commandLine.settings.usejavacp.value = true
        val output = new java.io.StringWriter
        val printer = new java.io.PrintWriter(output)
        val interpreter = new IMain(commandLine.settings, printer)
        val completion = new JLineCompletion(interpreter)
        (interpreter, completion, output)
    }

    lazy val (interpreter, completion, output) = initInterpreter(args)

    var _n: Int = 0
    val In = mutable.Map[Int, String]()
    val Out = mutable.Map[Int, Any]()

    def handle_execute_request(socket: ZMQ.Socket, msg: Msg[execute_request]) {
        executeMsg = msg

        val content = msg.content
        val code = content.code
        val silent = content.silent || code.trim.endsWith(";")
        val store_history = content.store_history getOrElse !silent

        log(s"EXECUTING $code")

        if (!silent) {
            _n += 1
            if (store_history) {
                In(_n) = code
            }
            send_ipython(publish, msg_pub(msg, MsgType.pyin,
                pyin(
                    execution_count=_n,
                    code=code)))
        } else {
            log("SILENT")
        }

        send_status(ExecutionState.busy)

        try {
            val ir = capture {
                interpreter.interpret(code)
            }

            ir match {
                case IR.Success =>
                    val request = interpreter.prevRequestList.last
                    val handler = request.handlers.last
                    val eval = request.lineRep

                    val value =
                        if (!handler.definesValue) None
                        else eval.callOpt("$result").filter(_ != null)

                    val result =
                        if (silent) None
                        else value.map(_.toString)

                    if (!silent && store_history) {
                        value.foreach(Out(_n) = _)

                        interpreter.beSilentDuring {
                            value.foreach(interpreter.bindValue("_" + _n, _))
                        }
                    }

                    val user_variables: List[String] = Nil
                    val user_expressions: Map[String, String] = Map()

                    finish_stream(msg, StdOut, outIn)
                    finish_stream(msg, StdErr, errIn)

                    result.foreach { data =>
                        send_ipython(publish, msg_pub(msg, MsgType.pyout,
                            pyout(
                                execution_count=_n,
                                data=Data("text/plain" -> data))))
                    }

                    send_ipython(requests, msg_reply(msg, MsgType.execute_reply,
                        execute_ok_reply(
                            execution_count=_n,
                            payload=Nil,
                            user_variables=user_variables,
                            user_expressions=user_expressions)))
                case IR.Error =>
                    send_error(msg, pyerr(_n, "", "", output.toString.split("\n").toList))
                case IR.Incomplete =>
                    send_error(msg, pyerr(_n, "", "", List("incomplete")))
            }
        } catch {
            case e: Exception =>
                send_error(msg, pyerr_content(e))
        } finally {
            output.getBuffer.setLength(0)
            send_status(ExecutionState.idle)
        }
    }

    def handle_complete_request(socket: ZMQ.Socket, msg: Msg[complete_request]) {
        val result = completion.completer.complete(msg.content.line, msg.content.cursor_pos)

        send_ipython(socket, msg_reply(msg, MsgType.complete_reply,
            complete_reply(
                status=ExecutionStatus.ok,
                matches=result.candidates,
                text="")))
    }

    def handle_kernel_info_request(socket: ZMQ.Socket, msg: Msg[kernel_info_request]) {
        val scalaVersion = scala.util.Properties.versionNumberString
            .split(Array('.', '-')).take(3).map(_.toInt).toList

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

    start_heartbeat(heartbeat)
    send_status(ExecutionState.starting)

    log("Starting kernel event loops.")

    (new EventLoop(requests)).start()
    (new EventLoop(control)).start()

    waitloop()

    terminate()
}
