package org.refptr.iscala

import java.io.File
import joptsimple.{OptionParser,OptionSpec}

class Options(args: Seq[String]) {
    private val parser = new OptionParser()
    private val _verbose = parser.accepts("verbose")
    private val _profile = parser.accepts("profile").withRequiredArg().ofType(classOf[File])
    private val options = parser.parse(args: _*)

    private def has[T](spec: OptionSpec[T]): Boolean =
        options.has(spec)

    private def get[T](spec: OptionSpec[T]): Option[T] =
        Some(options.valueOf(spec)).filter(_ != null)

    val verbose: Boolean = has(_verbose)
    val profile: Option[File] = get(_profile)
}
