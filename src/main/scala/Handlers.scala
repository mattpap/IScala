package org.refptr.iscala

import org.zeromq.ZMQ

import org.refptr.iscala.msg._
import org.refptr.iscala.msg.formats._

import Util.{log,debug}

trait Parent {
    val profile: Profile
    val ipy: Communication
    val interpreter: Interpreter
}

abstract class Handler[T <: FromIPython](parent: Parent) extends ((ZMQ.Socket, Msg[T]) => Unit)

class ExecuteHandler(parent: Parent) extends Handler[execute_request](parent) {
    import parent.{ipy,interpreter}

    private def capture[T](msg: Msg[_])(block: => T): T = {
        val size = 10240

        class WatchStream(input: java.io.InputStream, name: String) extends Thread {
            override def run() {
                val buffer = new Array[Byte](size)

                try {
                    while (true) {
                        val n = input.read(buffer)
                        ipy.send_stream(msg, name, new String(buffer.take(n)))

                        if (n < size) {
                            Thread.sleep(50) // a little delay to accumulate output
                        }
                    }
                } catch {
                    case _: java.io.IOException => // stream was closed so job is done
                }
            }
        }

        val stdoutIn = new java.io.PipedInputStream(size)
        val stdoutOut = new java.io.PipedOutputStream(stdoutIn)
        val stdout = new java.io.PrintStream(stdoutOut)

        val stderrIn = new java.io.PipedInputStream(size)
        val stderrOut = new java.io.PipedOutputStream(stderrIn)
        val stderr = new java.io.PrintStream(stderrOut)

        // This is a heavyweight solution to start stream watch threads per
        // input, but currently it's the cheapest approach that works well in
        // multiple thread setup. Note that piped streams work only in thread
        // pairs (producer -> consumer) and we start one thread per execution,
        // so technically speaking we have multiple producers, which completely
        // breaks the earlier intuitive approach.

        (new WatchStream(stdoutIn, "stdout")).start()
        (new WatchStream(stderrIn, "stderr")).start()

        try {
            val result =
                Console.withOut(stdout) {
                    Console.withErr(stderr) {
                        block
                    }
                }

            stdoutOut.flush()
            stderrOut.flush()

            // Wait until both streams get dry because we have to
            // send messages with streams' data before execute_reply
            // is send. Otherwise there will be no output in clients
            // or it will be incomplete.
            while (stdoutIn.available > 0 || stderrIn.available > 0)
                Thread.sleep(10)

            result
        } finally {
            // This will effectively terminate threads.
            stdoutOut.close()
            stderrOut.close()
            stdoutIn.close()
            stderrIn.close()
        }
    }

    private def pyerr_content(exception: Throwable, execution_count: Int): pyerr = {
        val ename = interpreter.stringify(exception.getClass.getName)
        val evalue = interpreter.stringify(exception.getMessage)
        val stacktrace = exception
             .getStackTrace()
             .takeWhile(_.getFileName != "<console>")
             .map(interpreter.stringify)
             .toList
        val traceback = s"$ename: $evalue" :: stacktrace.map("    " + _)

        pyerr(execution_count=execution_count,
              ename=ename,
              evalue=evalue,
              traceback=traceback)
    }

    private def builtins(msg: Msg[_]) {
        object Builtins {
            def raw_input(): String = {
                // TODO: drop stale replies
                // TODO: prompt
                ipy.send_stdin(msg, "")
                ipy.recv_stdin().collect {
                    case msg if msg.header.msg_type == MsgType.input_reply =>
                        msg.asInstanceOf[Msg[input_reply]]
                } map {
                    _.content.value match {
                        case "\u0004" => throw new java.io.EOFException()
                        case value    => value
                    }
                } getOrElse ""
            }
        }

        val bindings = List(
            ("raw_input", "() => String", Builtins.raw_input _))

        interpreter.intp.beSilentDuring {
            bindings.foreach { case (name, tpe, value) =>
                interpreter.intp.bind(name, tpe, value)
            }
        }
    }

    def apply(socket: ZMQ.Socket, msg: Msg[execute_request]) {
        import interpreter.{n,In,Out}

        val content = msg.content
        val code = content.code.replaceAll("\\s+$", "")
        val silent = content.silent || code.endsWith(";")
        val store_history = content.store_history getOrElse !silent

        if (code.trim.isEmpty) {
            ipy.send_ok(msg, interpreter.n)
            return
        }

        if (!silent) {
            interpreter++

            if (store_history) {
                In(n) = code
                interpreter.session.addHistory(n, code)
            }

            ipy.publish(msg.pub(MsgType.pyin,
                pyin(
                    execution_count=n,
                    code=code)))
        }

        ipy.send_status(ExecutionState.busy)

        try {
            builtins(msg)

            code match {
                case Magic(name, input, Some(magic)) =>
                    val ir = capture(msg) {
                        magic(interpreter, input)
                    }

                    ir match {
                        case Some(error) =>
                            ipy.send_error(msg, n, error)
                        case None =>
                            val output = interpreter.output.toString

                            if (!output.trim.isEmpty)
                                ipy.send_error(msg, n, output)
                            else
                                ipy.send_ok(msg, n)
                    }
                case Magic(name, _, None) =>
                    ipy.send_error(msg, n, s"ERROR: Line magic function `%$name` not found.")
                case _ =>
                    capture(msg) { interpreter.interpret(code) } match {
                        case Results.Success(Some(Results.Value(value, tpe))) if !silent =>
                            val result = interpreter.stringify(value)

                            if (store_history) {
                                Out(n) = value
                                interpreter.session.addOutputHistory(n, result)
                                interpreter.bind("_" + interpreter.n, tpe, value)
                            }

                            ipy.publish(msg.pub(MsgType.pyout,
                                pyout(
                                    execution_count=n,
                                    data=Data("text/plain" -> result))))

                            ipy.send_ok(msg, n)
                        case Results.Success(None) =>
                            ipy.send_ok(msg, n)
                        case Results.Failure(exception) =>
                            ipy.send_error(msg, pyerr_content(exception, n))
                        case Results.Error =>
                            ipy.send_error(msg, n, interpreter.output.toString)
                        case Results.Incomplete =>
                            ipy.send_error(msg, n, "incomplete")
                        case Results.Cancelled =>
                            ipy.send_abort(msg, n)
                    }
            }
        } catch {
            case exception: Throwable =>
                ipy.send_error(msg, pyerr_content(exception, n)) // Internal Error
        } finally {
            interpreter.resetOutput()
            ipy.send_status(ExecutionState.idle)
        }
    }
}

class CompleteHandler(parent: Parent) extends Handler[complete_request](parent) {
    import parent.{ipy,interpreter}

    def apply(socket: ZMQ.Socket, msg: Msg[complete_request]) {
        val text = if (msg.content.text.isEmpty) {
            // Notebook only gives us line and cursor_pos
            val pos = msg.content.cursor_pos
            val upToCursor = msg.content.line.splitAt(pos)._1
            upToCursor.split("""[^\w.%]""").last
        } else {
            msg.content.text
        }

        val matches = if (msg.content.line.startsWith("%")) {
            val prefix = text.stripPrefix("%")
            Magic.magics.map(_.name.name).filter(_.startsWith(prefix)).map("%" + _)
        } else {
            val completions = interpreter.completion.completions(text)
            val common = Util.commonPrefix(completions)
            var prefix = Util.suffixPrefix(text, common)
            completions.map(_.stripPrefix(prefix)).map(text + _)
        }

        ipy.send(socket, msg.reply(MsgType.complete_reply,
            complete_reply(
                status=ExecutionStatus.ok,
                matches=matches,
                matched_text=text)))
    }
}

class KernelInfoHandler(parent: Parent) extends Handler[kernel_info_request](parent) {
    import parent.ipy

    def apply(socket: ZMQ.Socket, msg: Msg[kernel_info_request]) {
        val scalaVersion = Util.scalaVersion
            .split(Array('.', '-'))
            .take(3)
            .map(_.toInt)
            .toList

        ipy.send(socket, msg.reply(MsgType.kernel_info_reply,
            kernel_info_reply(
                protocol_version=(4, 0),
                language_version=scalaVersion,
                language="scala")))
    }
}

class ConnectHandler(parent: Parent) extends Handler[connect_request](parent) {
    import parent.ipy

    def apply(socket: ZMQ.Socket, msg: Msg[connect_request]) {
        ipy.send(socket, msg.reply(MsgType.connect_reply,
            connect_reply(
                shell_port=parent.profile.shell_port,
                iopub_port=parent.profile.iopub_port,
                stdin_port=parent.profile.stdin_port,
                hb_port=parent.profile.hb_port)))
    }
}

class ShutdownHandler(parent: Parent) extends Handler[shutdown_request](parent) {
    import parent.ipy

    def apply(socket: ZMQ.Socket, msg: Msg[shutdown_request]) {
        ipy.send(socket, msg.reply(MsgType.shutdown_reply,
            shutdown_reply(
                restart=msg.content.restart)))
        sys.exit()
    }
}

class ObjectInfoHandler(parent: Parent) extends Handler[object_info_request](parent) {
    import parent.ipy

    def apply(socket: ZMQ.Socket, msg: Msg[object_info_request]) {
        ipy.send(socket, msg.reply(MsgType.object_info_reply,
            object_info_notfound_reply(
                name=msg.content.oname)))
    }
}

class HistoryHandler(parent: Parent) extends Handler[history_request](parent) {
    import parent.{ipy,interpreter}

    def apply(socket: ZMQ.Socket, msg: Msg[history_request]) {
        import org.refptr.iscala.db.{DB,History,OutputHistory}

        import scala.slick.driver.SQLiteDriver.simple._
        import Database.threadLocalSession

        val raw = msg.content.raw

        var query = for {
            (input, output) <- History leftJoin OutputHistory on ((in, out) => in.session === out.session && in.line === out.line)
        } yield input.session ~ input.line ~ (if (raw) input.source_raw else input.source) ~ output.output.?

        msg.content.hist_access_type match {
            case HistAccessType.range =>
                val session = msg.content.session getOrElse 0

                val actualSession =
                    if (session == 0) interpreter.session.id
                    else if (session > 0) session
                    else interpreter.session.id - session

                query = query.filter(_._1 === actualSession)

                for (start <- msg.content.start)
                    query = query.filter(_._2 >= start)

                for (stop <- msg.content.stop)
                    query = query.filter(_._2 < stop)
            case HistAccessType.tail | HistAccessType.search =>
                // TODO: add support for `pattern` and `unique`
                query = query.sortBy(r => (r._1.desc, r._2.desc))

                for (n <- msg.content.n)
                    query = query.take(n)
        }

        val rawHistory = DB.db.withSession { query.list }
        val history =
            if (msg.content.output)
                rawHistory.map { case (session, line, input, output) => (session, line, Right((input, output))) }
            else
                rawHistory.map { case (session, line, input, output) => (session, line, Left(input)) }

        ipy.send(socket, msg.reply(MsgType.history_reply,
            history_reply(
                history=history)))
    }
}
