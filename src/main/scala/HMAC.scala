package org.refptr.iscala

import java.util.UUID

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HMAC(key: String, algorithm: String = "HmacSHA256") {
    def this(key: UUID) = this(key.toString)

    private val mac = Mac.getInstance(algorithm)
    private val keySpec = new SecretKeySpec(key.getBytes, algorithm)
    mac.init(keySpec)

    def hexdigest(args: String*): String = {
        mac synchronized {
            args.map(_.getBytes).foreach(mac.update)
            Util.hex(mac.doFinal())
        }
    }

    def apply(args: String*) = hexdigest(args: _*)
}
