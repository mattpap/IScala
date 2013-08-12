package org.refptr.iscala

import java.io.File
import java.util.UUID
import java.lang.management.ManagementFactory

import org.zeromq.ZMQ

import scala.collection.mutable
import scala.tools.nsc.interpreter.{IMain,CommandLine,IR}

import scalax.io.JavaConverters._
import scalax.file.Path

import net.liftweb.json.{JsonAST,JsonParser,Extraction,DefaultFormats,ShortTypeHints}
import net.liftweb.common.{Box,Full,Empty}
import net.liftweb.util.Helpers.tryo

import org.refptr.iscala.msg._

object Util {
    def uuid4(): String = UUID.randomUUID().toString

    def hex(bytes: Seq[Byte]): String = bytes.map("%02x" format _).mkString

    def getpid(): Int = {
        val name = ManagementFactory.getRuntimeMXBean().getName()
        name.takeWhile(_ != '@').toInt
    }

    def log(message: => String) {
        println(message)
    }
}

object JSONUtil {
    implicit val formats = DefaultFormats

    def toJSON[T:Manifest](obj: T): String =
        JsonAST.compactRender(Extraction.decompose(obj))

    def fromJSON[T:Manifest](json: String): T =
        JsonParser.parse(json).extract[T]

    implicit class JsonString(json: String) {
        def as[T:Manifest]: T = JSONUtil.fromJSON[T](json)
    }
}

object IScala extends App {
    import Util._
    import JSONUtil._

    case class Profile(
        ip: String,
        transport: String,
        stdin_port: Int,
        control_port: Int,
        hb_port: Int,
        shell_port: Int,
        iopub_port: Int,
        key: String)

    def parseJSON(json: String): Metadata = {
        JsonParser.parse(json) match {
            case obj: JsonAST.JObject => obj.values
            case jv => sys.error("expected an object, got $jv")
        }
    }

    val profile = args.toList match {
        case path :: Nil =>
            Path(path).string.as[Profile]
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

    def msg_pub[T<:Content](m: Msg[_], msg_type: MsgType, content: T, metadata: Metadata=Metadata()): Msg[T] =
        // TODO: Msg((if (msg_type == "stream") content("name").asInstanceOf[String] else msg_type) :: Nil,
        Msg(msg_type.toString :: Nil,
            Header(msg_id=uuid4(),
                   username=m.header.username,
                   session=m.header.session,
                   msg_type=msg_type),
            Some(m.header), metadata, content)

    def msg_reply[T<:Content](m: Msg[_], msg_type: MsgType, content: T, metadata: Metadata=Metadata()): Msg[T] =
        Msg(m.idents,
            Header(msg_id=uuid4(),
                   username=m.header.username,
                   session=m.header.session,
                   msg_type=msg_type),
            Some(m.header), metadata, content)

    def send_ipython[T<:Content:Manifest](socket: ZMQ.Socket, m: Msg[T]) {
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

    def recv_ipython[T<:Content:Manifest](socket: ZMQ.Socket): Msg[T] = {
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
        val m = Msg(idents,
            fromJSON[Header](header),
            fromJSON[Option[Header]](parent_header),
            parseJSON(metadata),
            fromJSON[T](content))
            //parseJSON(header),
            //parseJSON(content),
            //parseJSON(parent_header),
            //parseJSON(metadata))
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

    var _n: Int = 0
    val In = mutable.Map[Int, String]()
    val Out = mutable.Map[Int, Any]()

    def initInterpreter(args: Seq[String]) = {
        val commandLine = new CommandLine(args.toList, println)
        commandLine.settings.embeddedDefaults[this.type]
        commandLine.settings.usejavacp.value = true
        val output = new java.io.StringWriter
        val printer = new java.io.PrintWriter(output)
        val interpreter = new IMain(commandLine.settings, printer)
        (interpreter, output)
    }

    lazy val (interpreter, output) = initInterpreter(args)

    def handle_execute_request(socket: ZMQ.Socket, msg: Msg[execute_request]) {
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

        send_status(Busy)

        try {
            interpreter.interpret(code) match {
                case IR.Success =>
                    val result = {
                        val result = output.toString

                        if (silent) {
                            ""
                        } else {
                            if (result.nonEmpty && store_history) {
                                Out(_n) = result
                            }

                            result
                        }
                    }

                    val user_variables: List[String] = Nil
                    val user_expressions: Map[String, String] = Map()

                    /*
                    for (v <- msg.content("user_variables")) {
                        user_variables[v] = eval(Main, parse(v))
                    }

                    for ((v, ex) <- msg.content("user_expressions")) {
                        user_expressions[v] = eval(Main, parse(ex))
                    }

                    for (hook <- postexecute_hooks) {
                        hook()
                    }
                    */

                    if (result.nonEmpty) {
                        send_ipython(publish, msg_pub(msg, MsgType.pyout,
                            pyout(
                                execution_count=_n,
                                data=Data("text/plain" -> result),
                                metadata=Metadata()))) // qtconsole needs this
                        // undisplay(result) // in case display was queued
                    }

                    /*
                    display() // flush pending display requests
                    */

                    send_ipython(requests, msg_reply(msg, MsgType.execute_reply,
                        execute_ok_reply(
                            // status=OK,
                            execution_count=_n,
                            payload=Nil,
                            user_variables=user_variables,
                            user_expressions=user_expressions)))
                case IR.Error =>
                    // empty!(displayqueue) // discard pending display requests on an error
                    // val content = pyerr_content(e)
                    val traceback = output.toString.split("\n").toList
                    send_ipython(publish, msg_pub(msg, MsgType.pyerr,
                        pyerr(
                            execution_count=_n,
                            ename="",
                            evalue="",
                            traceback=traceback)))
                    send_ipython(requests, msg_reply(msg, MsgType.execute_reply,
                        execute_error_reply(
                            // status=Error,
                            execution_count=_n,
                            ename="",
                            evalue="",
                            traceback=traceback)))
                case IR.Incomplete =>
                    // TODO
            }

        } catch {
            case e: Exception =>
                // empty!(displayqueue) // discard pending display requests on an error
                //val content = pyerr_content(e)
                //send_ipython(publish, msg_pub(msg, "pyerr", content))
                //send_ipython(requests, msg_reply(msg, "execute_reply", content + ("status" -> "error")))
        } finally {
            output.getBuffer.setLength(0)
        }

        send_status(Idle)
    }

    def handle_complete_request(socket: ZMQ.Socket, msg: Msg[complete_request]) {
        send_ipython(socket, msg_reply(msg, MsgType.complete_reply,
            complete_reply(
                status=OK,
                matches=Nil,
                text="")))
    }

    def handle_kernel_info_request(socket: ZMQ.Socket, msg: Msg[kernel_info_request]) {
        send_ipython(socket, msg_reply(msg, MsgType.kernel_info_reply,
            kernel_info_reply(
                protocol_version=(4, 0),
                language_version=List(2, 10, 2),
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

    /*
    def handlers[T<:Content]: PartialFunction[MsgType, (ZMQ.Socket, Msg[T]) => Unit] = {
        case MsgType.execute_request => handle_execute_request
        case MsgType.complete_request => handle_complete_request
        case MsgType.kernel_info_request => handle_kernel_info_request
        case MsgType.object_info_request => handle_object_info_request
        case MsgType.connect_request => handle_connect_request
        case MsgType.shutdown_request => handle_shutdown_request
        case MsgType.history_request => handle_history_request
    }
    */

    class HeartBeat(socket: ZMQ.Socket) extends Thread {
        override def run() {
            ZMQ.proxy(socket, socket, null)
        }
    }

    def start_heartbeat(socket: ZMQ.Socket) {
        val thread = new HeartBeat(socket)
        thread.start()
    }

    class WatchStream(rd: java.io.InputStream, name: String) extends Thread {
        override def run() {
            try {
                while (true) {
                    // s = readavailable(rd) // blocks until something available
                    val s = "abc"
                    log(s"STDIO($name) = $s")
                    /*
                    send_ipython(publish, msg_pub(execute_msg, MsgType.stream,
                        stream(
                            name=name,
                            data=s)))
                    */
                    Thread.sleep(100) // a little delay to accumulate output
                }
            } catch {
                case _: InterruptedException =>
                    // the IPython manager may send us a SIGINT if the user
                    // chooses to interrupt the kernel; don't crash on this
            }
        }
    }

    def watch_stdio() {
        // val (read_stdout, write_stdout) = redirect_stdout()
        // val (read_stderr, write_stderr) = redirect_stderr()
        // (new WatchStream(read_stdout, "stdout")).start()
        // (new WatchStream(read_stderr, "stderr")).start()
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

    class EventLoop(socket: ZMQ.Socket) extends Thread {
        override def run() {
            while (!Thread.interrupted) {
                try {
                    val msg = recv_ipython(socket)

                    try {
                        //handlers(msg.header.msg_type)(socket, msg)
                        msg.header.msg_type match {
                            case MsgType.execute_request => handle_execute_request(socket, msg.asInstanceOf[Msg[execute_request]])
                            case MsgType.complete_request => handle_complete_request(socket, msg.asInstanceOf[Msg[complete_request]])
                            case MsgType.kernel_info_request => handle_kernel_info_request(socket, msg.asInstanceOf[Msg[kernel_info_request]])
                            case MsgType.object_info_request => handle_object_info_request(socket, msg.asInstanceOf[Msg[object_info_request]])
                            case MsgType.connect_request => handle_connect_request(socket, msg.asInstanceOf[Msg[connect_request]])
                            case MsgType.shutdown_request => handle_shutdown_request(socket, msg.asInstanceOf[Msg[shutdown_request]])
                            case MsgType.history_request => handle_history_request(socket, msg.asInstanceOf[Msg[history_request]])
                        }
                    } catch {
                        // Try to keep going if we get an exception, but
                        // send the exception traceback to the front-ends.
                        // (Ignore SIGINT since this may just be a user-requested
                        //  kernel interruption to interrupt long calculations.)
                        case _: InterruptedException =>
                        case e: Exception =>
                            // log(orig_STDERR, "KERNEL EXCEPTION")
                            // Base.error_show(orig_STDERR, e, catch_backtrace())
                            // log(orig_STDERR)
                            send_ipython(publish, Msg("pyerr" :: Nil,
                                Header(msg_id=uuid4(),
                                       username="scala_kernel",
                                       session=uuid4(),
                                       msg_type=MsgType.pyerr),
                                None,
                                Metadata(),
                                pyerr_content(e)))
                    }
                } catch {
                    case _: InterruptedException =>
                        // the IPython manager may send us a SIGINT if the user
                        // chooses to interrupt the kernel; don't crash on this
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
    send_status(Starting)

    log("Starting kernel event loops.")
    watch_stdio()

    (new EventLoop(requests)).start()
    (new EventLoop(control)).start()

    waitloop()

    terminate()
}
