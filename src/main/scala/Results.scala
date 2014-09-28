package org.refptr.iscala

object Results {
    sealed trait Result
    sealed trait Success extends Result
    sealed trait Failure extends Result

    final case class Value(value: AnyRef, tpe: String, repr: Data) extends Success
    final case object NoValue extends Success

    final case class Exception(exception: Throwable) extends Failure
    final case object Error extends Failure
    final case object Incomplete extends Failure
    final case object Cancelled extends Failure
}
