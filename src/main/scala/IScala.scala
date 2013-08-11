package org.refptr.iscala

import java.io.File
import java.util.UUID
import java.lang.management.ManagementFactory

import org.zeromq.ZMQ

import scala.collection.mutable
import scala.tools.nsc.interpreter.{IMain,CommandLine,IR}

import scalax.io.JavaConverters._

import net.liftweb.json.{JsonAST,JsonParser,Extraction,DefaultFormats,ShortTypeHints}
import net.liftweb.common.{Box,Full,Empty}
import net.liftweb.util.Helpers.tryo

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

    def toJSON[T:Manifest](obj: T): String = {
        JsonAST.compactRender(Extraction.decompose(obj))
    }

    def fromJSON[T:Manifest](json: String): T = {
        JsonParser.parse(json).extract[T]
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

    type Dict = Map[String, Any]
    val Dict = Map

    case class Msg(
        idents: List[String] = Nil,
        header: Dict = Dict(),
        content: Dict = Dict(),
        parent_header: Dict = Dict(),
        metadata: Dict = Dict())

    def parseJSON(json: String): Dict = {
        JsonParser.parse(json) match {
            case obj: JsonAST.JObject => obj.values
            case jv => sys.error("expected an object, got $jv")
        }
    }

    val profile = args.toList match {
        case path :: Nil =>
            fromJSON[Profile](new File(path).asInput.string)
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

            val file = new File(s"profile-${getpid()}.json")
            log(s"connect ipython with --existing ${file.getAbsolutePath}")
            file.asOutput.write(toJSON(profile))

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

    def msg_pub(m: Msg, msg_type: String, content: Dict, metadata: Dict=Dict()): Msg =
        Msg((if (msg_type == "stream") content("name").asInstanceOf[String] else msg_type) :: Nil,
            Map("msg_id" -> uuid4(),
                "username" -> m.header("username"),
                "session" -> m.header("session"),
                "msg_type" -> msg_type),
            content, m.header, metadata)

    def msg_reply(m: Msg, msg_type: String, content: Dict, metadata: Dict=Dict()): Msg =
        Msg(m.idents,
            Map("msg_id" -> uuid4(),
                "username" -> m.header("username"),
                "session" -> m.header("session"),
                "msg_type" -> msg_type),
            content, m.header, metadata)

    def send_ipython(socket: ZMQ.Socket, m: Msg) {
        log(s"SENDING $m")
        m.idents.foreach(socket.send(_, ZMQ.SNDMORE))
        /*
        for (i <- m.idents) {
            socket.send(i, ZMQ.SNDMORE)
        }
        */
        socket.send("<IDS|MSG>", ZMQ.SNDMORE)
        val header = toJSON(m.header)
        val parent_header = toJSON(m.parent_header)
        val metadata = toJSON(m.metadata)
        val content = toJSON(m.content)
        socket.send(hmac(header, parent_header, metadata, content), ZMQ.SNDMORE)
        socket.send(header, ZMQ.SNDMORE)
        socket.send(parent_header, ZMQ.SNDMORE)
        socket.send(metadata, ZMQ.SNDMORE)
        socket.send(content)
    }

    def recv_ipython(socket: ZMQ.Socket): Msg = {
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
            parseJSON(header),
            parseJSON(content),
            parseJSON(parent_header),
            parseJSON(metadata))
        log(s"RECEIVED $m")
        m
    }

    def send_status(state: String) {
        val msg = Msg(
            "status" :: Nil,
            Map("msg_id" -> uuid4(),
                "username" -> "scala_kernel",
                "session" -> uuid4(),
                "msg_type" -> "status"),
            Map("execution_state" -> state))
        send_ipython(publish, msg)
    }

    var _n: Int = 0
    var execute_msg: Msg = _
    val In = mutable.Map[Int, String]()
    val Out = mutable.Map[Int, Any]()

    def initInterpreter(args: Seq[String]) = {
        val command = new CommandLine(args.toList, println)
        command.settings.embeddedDefaults[this.type]
        val istr = new java.io.StringWriter
        val iout = new java.io.PrintWriter(istr)
        val imain = new IMain(command.settings, iout)
        (imain, istr)
    }

    lazy val (interpreter, output) = initInterpreter(args)

    def execute_request(socket: ZMQ.Socket, msg: Msg) {
        log(s"EXECUTING ${msg.content("code")}")
        execute_msg = msg

        val code = msg.content("code").asInstanceOf[String]
        val silent = msg.content("silent").asInstanceOf[Boolean] || code.trim.endsWith(";")
        val store_history = msg.content.get("store_history").asInstanceOf[Option[Boolean]].getOrElse(!silent)

        if (!silent) {
            _n += 1
            if (store_history) {
                In(_n) = code
            }
            send_ipython(publish, msg_pub(msg, "pyin",
                Map("execution_count" -> _n,
                    "code" -> code)))
        } else {
            log("SILENT")
        }

        send_status("busy")

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

                    val user_variables = Dict()
                    val user_expressions = Dict()

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
                        send_ipython(publish, msg_pub(msg, "pyout",
                            Map("execution_count" -> _n,
                                "metadata" -> Dict(), // qtconsole needs this
                                "data" -> Map("text/plain" -> result))))
                        // undisplay(result) // in case display was queued
                    }

                    /*
                    display() // flush pending display requests
                    */

                    send_ipython(requests, msg_reply(msg, "execute_reply",
                        Map("status" -> "ok",
                            "execution_count" -> _n,
                            "payload" -> Nil,
                            "user_variables" -> user_variables,
                            "user_expressions" -> user_expressions)))
                case IR.Error =>
                    // empty!(displayqueue) // discard pending display requests on an error
                    // val content = pyerr_content(e)
                    val content = Map(
                        "execution_count" -> _n,
                        "ename" -> "",
                        "evalue" -> "",
                        "traceback" -> output.toString.split("\n"))
                    send_ipython(publish, msg_pub(msg, "pyerr", content))
                    send_ipython(requests, msg_reply(msg, "execute_reply", content + ("status" -> "error")))
                case IR.Incomplete =>
                    // TODO
            }

        } catch {
            case e: Exception =>
                // empty!(displayqueue) // discard pending display requests on an error
                val content = pyerr_content(e)
                send_ipython(publish, msg_pub(msg, "pyerr", content))
                send_ipython(requests, msg_reply(msg, "execute_reply", content + ("status" -> "error")))
        } finally {
            output.getBuffer.setLength(0)
        }

        send_status("idle")
    }

    def complete_request(socket: ZMQ.Socket, msg: Msg) {
        send_ipython(socket, msg_reply(msg, "complete_reply",
            Map("status" -> "ok",
                "matches" -> Nil,
                "matched_text" -> "")))
    }

    def kernel_info_request(socket: ZMQ.Socket, msg: Msg) {
        send_ipython(socket, msg_reply(msg, "kernel_info_reply",
            Map("protocol_version" -> List(4, 0),
                "language_version" -> List(2, 10, 2),
                "language" -> "scala" )))
    }

    def connect_request(socket: ZMQ.Socket, msg: Msg) {
        send_ipython(socket, msg_reply(msg, "connect_reply",
            Map("shell_port" -> profile.shell_port,
                "iopub_port" -> profile.iopub_port,
                "stdin_port" -> profile.stdin_port,
                "hb_port"    -> profile.hb_port)))
    }

    def shutdown_request(socket: ZMQ.Socket, msg: Msg) {
        send_ipython(socket, msg_reply(msg, "shutdown_reply", msg.content))
        sys.exit()
    }

    def object_info_request(socket: ZMQ.Socket, msg: Msg) {
        send_ipython(socket, msg_reply(msg, "object_info_reply",
            Map("oname" -> msg.content("oname"),
                "found" -> false)))
    }

    def history_request(socket: ZMQ.Socket, msg: Msg) {
        send_ipython(socket, msg_reply(msg, "history_reply",
            Map("history" -> Nil)))
    }

    val handlers: Map[String, (ZMQ.Socket, Msg) => Unit] = Map(
        "execute_request" -> execute_request,
        "complete_request" -> complete_request,
        "kernel_info_request" -> kernel_info_request,
        "object_info_request" -> object_info_request,
        "connect_request" -> connect_request,
        "shutdown_request" -> shutdown_request,
        "history_request" -> history_request)

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
                    send_ipython(publish, msg_pub(execute_msg, "stream",
                        Map("name" -> name,
                            "data" -> s)))
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

    def pyerr_content(e: Exception): Dict = {
        val s = new java.io.StringWriter
        val p = new java.io.PrintWriter(s)
        e.printStackTrace(p)

        val ename = e.getClass.getName
        val evalue = e.getMessage
        val traceback = s.toString.split("\n")

        Map("execution_count" -> _n,
            "ename" -> ename,
            "evalue" -> evalue,
            "traceback" -> traceback)
    }

    class EventLoop(socket: ZMQ.Socket) extends Thread {
        override def run() {
            while (true) {
                try {
                    val msg = recv_ipython(socket)

                    try {
                        handlers(msg.header("msg_type").asInstanceOf[String])(socket, msg)
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
                                Map("msg_id" -> uuid4(),
                                    "username" -> "scala_kernel",
                                    "session" -> uuid4(),
                                    "msg_type" -> "pyerr"), pyerr_content(e)))
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
    send_status("starting")

    log("Starting kernel event loops.")
    watch_stdio()

    (new EventLoop(requests)).start()
    (new EventLoop(control)).start()

    waitloop()

    terminate()
}
