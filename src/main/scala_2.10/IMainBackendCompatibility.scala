package org.refptr.iscala

trait IMainBackendCompatibility {
  	self: IMainBackend =>

  	import global._

  	def backticked(s: String): String = (
	    (s split '.').toList map {
	      case "_" => "_"
	      case s if nme.keywords(newTermName(s)) => s"`$s`"
	      case s => s
	    } mkString ".")
}