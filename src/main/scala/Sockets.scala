package org.refptr.iscala

import org.zeromq.ZMQ

class Sockets(profile: Profile) {
    val ctx = ZMQ.context(1)

    val publish = ctx.socket(ZMQ.PUB)
    val raw_input = ctx.socket(ZMQ.ROUTER)
    val requests = ctx.socket(ZMQ.ROUTER)
    val control = ctx.socket(ZMQ.ROUTER)
    val heartbeat = ctx.socket(ZMQ.REP)

    private def toURI(port: Int) =
        s"${profile.transport}://${profile.ip}:$port"

    publish.bind(toURI(profile.iopub_port))
    requests.bind(toURI(profile.shell_port))
    control.bind(toURI(profile.control_port))
    raw_input.bind(toURI(profile.stdin_port))
    heartbeat.bind(toURI(profile.hb_port))

    def terminate() {
        publish.close()
        raw_input.close()
        requests.close()
        control.close()
        heartbeat.close()

        ctx.term()
    }
}
