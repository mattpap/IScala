package org.refptr.iscala

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HMAC {
    def apply(key: String): HMAC = if (key.isEmpty) NoHMAC else new DoHMAC(key)
}

sealed trait HMAC {
    def hexdigest(args: Seq[String]): String

    final def apply(args: String*) = hexdigest(args)
}

final class DoHMAC(key: String, algorithm: String="HmacSHA256") extends HMAC {
    private val mac = Mac.getInstance(algorithm)
    private val keySpec = new SecretKeySpec(key.getBytes, algorithm)
    mac.init(keySpec)

    def hexdigest(args: Seq[String]): String = {
        mac synchronized {
            args.map(_.getBytes).foreach(mac.update)
            Util.hex(mac.doFinal())
        }
    }
}

object NoHMAC extends HMAC {
    def hexdigest(args: Seq[String]): String = ""
}
