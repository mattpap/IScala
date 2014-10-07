package org.refptr.iscala

import org.zeromq.ZMQ

import org.refptr.iscala.msg._
import org.refptr.iscala.msg.formats._

trait Parent {
    val profile: Profile
    val ipy: Communication
    val interpreter: Interpreter
}

abstract class Handler[T <: FromIPython](parent: Parent) extends ((ZMQ.Socket, Msg[T]) => Unit)

class ExecuteHandler(parent: Parent) extends Handler[execute_request](parent) {
    import parent.{ipy,interpreter}

    class StreamCapture(msg: Msg[_]) extends Capture {
        def stream(name: String, data: String) {
            ipy.silently {
                ipy.send_stream(msg, "stdout", data)
            }
        }

        def stdout(data: String) = stream("stdout", data)
        def stderr(data: String) = stream("stderr", data)
    }

    def apply(socket: ZMQ.Socket, msg: Msg[execute_request]) {
        import interpreter.n

        val content = msg.content
        val code = content.code
        val silent = content.silent || code.trim.endsWith(";")
        val store_history = content.store_history getOrElse !silent

        if (code.trim.isEmpty) {
            ipy.send_ok(msg, n)
            return
        }

        interpreter.nextInput()
        interpreter.storeInput(code)

        ipy.publish(msg.pub(MsgType.pyin,
            pyin(
                execution_count=n,
                code=code)))

        ipy.busy {
            val capture = new StreamCapture(msg)
            interpreter.resetOutput()

            code match {
                case Magic(name, input, Some(magic)) =>
                    val ir = capture {
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
                    val ir = capture {
                        interpreter.interpret(code)
                    }

                    ir match {
                        case result @ Results.Value(value, tpe, repr) if !silent =>
                            if (store_history) {
                                repr.default foreach { output =>
                                    interpreter.storeOutput(result, output)
                                }
                            }

                            ipy.publish(msg.pub(MsgType.pyout,
                                pyout(
                                    execution_count=n,
                                    data=repr)))

                            ipy.send_ok(msg, n)
                        case _: Results.Success =>
                            ipy.send_ok(msg, n)
                        case exc @ Results.Exception(name, message, _, _) =>
                            ipy.send_error(msg, pyerr(n, name, message, exc.traceback))
                        case Results.Error =>
                            ipy.send_error(msg, n, interpreter.output.toString)
                        case Results.Incomplete =>
                            ipy.send_error(msg, n, "incomplete")
                        case Results.Cancelled =>
                            ipy.send_abort(msg, n)
                    }
            }
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
        import Database.dynamicSession

        val raw = msg.content.raw

        var query = for {
            (input, output) <- DB.History leftJoin DB.OutputHistory on ((in, out) => in.session === out.session && in.line === out.line)
        } yield (input.session, input.line, (if (raw) input.source_raw else input.source), output.output.?)

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

        val rawHistory = DB.db.withDynSession { query.list }
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
