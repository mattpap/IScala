package org.refptr.iscala

import msg.{Msg,MsgType,input_reply}

class Builtins(interpreter: Interpreter, ipy: Communication, msg: Msg[_]) {

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

    val bindings = List(
        ("raw_input", "() => String", raw_input _))

    bindings.foreach{ case (name, tpe, value) =>
        interpreter.bind(name, tpe, value, quiet = true)
    }
}
