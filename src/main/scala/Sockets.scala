package org.refptr.iscala

import org.zeromq.ZMQ

class Sockets(connection: Connection) {
    val ctx = ZMQ.context(1)

    val publish = ctx.socket(ZMQ.PUB)
    val requests = ctx.socket(ZMQ.ROUTER)
    val control = ctx.socket(ZMQ.ROUTER)
    val stdin = ctx.socket(ZMQ.ROUTER)
    val heartbeat = ctx.socket(ZMQ.REP)

    private def toURI(port: Int) =
        s"${connection.transport}://${connection.ip}:$port"

    publish.bind(toURI(connection.iopub_port))
    requests.bind(toURI(connection.shell_port))
    control.bind(toURI(connection.control_port))
    stdin.bind(toURI(connection.stdin_port))
    heartbeat.bind(toURI(connection.hb_port))

    def terminate() {
        publish.close()
        requests.close()
        control.close()
        stdin.close()
        heartbeat.close()

        ctx.term()
    }
}
