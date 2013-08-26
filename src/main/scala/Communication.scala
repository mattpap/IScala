package org.refptr.iscala

import org.zeromq.ZMQ

import play.api.libs.json.{Reads,Writes}

import org.refptr.iscala.msg._
import org.refptr.iscala.msg.formats._

import org.refptr.iscala.Util.{log,debug}
import org.refptr.iscala.json.JsonUtil._

class Communication(zmq: Sockets, profile: Profile) {
    private val hmac = HMAC(profile.key)

    // TODO: move msg_header, msg_pub and msg_reply to Msg when knownDirectSubclasses is fixed.
    private def msg_header(m: Msg[_], msg_type: MsgType): Header =
        Header(msg_id=UUID.uuid4(),
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

    private val DELIMITER = "<IDS|MSG>"

    def send[T<:Reply:Writes](socket: ZMQ.Socket, m: Msg[T]) {
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

    def recv(socket: ZMQ.Socket): Msg[Request] = {
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

    def publish[T<:Reply:Writes](msg: Msg[T]) = send(zmq.publish, msg)

    def send_status(state: ExecutionState) {
        val msg = Msg(
            "status" :: Nil,
            Header(msg_id=UUID.uuid4(),
                   username="scala_kernel",
                   session=UUID.uuid4(),
                   msg_type=MsgType.status),
            None,
            Metadata(),
            status(
                execution_state=state))
        send(zmq.publish, msg)
    }

    def send_ok(msg: Msg[_], execution_count: Int) {
        val user_variables: List[String] = Nil
        val user_expressions: Map[String, String] = Map()

        send(zmq.requests, msg_reply(msg, MsgType.execute_reply,
            execute_ok_reply(
                execution_count=execution_count,
                payload=Nil,
                user_variables=user_variables,
                user_expressions=user_expressions)))
    }

    def send_error(msg: Msg[_], execution_count: Int, error: String) {
        send_error(msg, pyerr(execution_count, "", "", error.split("\n").toList))
    }

    def send_error(msg: Msg[_], err: pyerr) {
        send(zmq.publish, msg_pub(msg, MsgType.pyerr, err))
        send(zmq.requests, msg_reply(msg, MsgType.execute_reply,
            execute_error_reply(
                execution_count=err.execution_count,
                ename=err.ename,
                evalue=err.evalue,
                traceback=err.traceback)))
    }

    def send_stream(msg: Msg[_], name: String, data: String) {
        send(zmq.publish, msg_pub(msg, MsgType.stream,
            stream(
                name=name,
                data=data)))
    }
}
