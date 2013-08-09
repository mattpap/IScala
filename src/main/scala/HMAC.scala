package org.refptr.iscala

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HMAC(key: String, algorithm: String = "HmacSHA256") {
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
