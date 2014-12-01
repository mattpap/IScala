package org.refptr.iscala

trait ScalaIMainBackendCompatibility extends IMainBackendCompatibility {
  	self: ScalaIMainBackend =>

  	import global._

	def originalPath(name: Name): String = imain.pathToName(name)
  	def originalPath(symbol: Symbol): String = backticked(afterTyper(symbol.fullName))

  	trait RequestWrapperImplCompatibility {
    	self2: RequestWrapperImpl =>
    	def value = Some(request.handlers.last)
	    	.filter(_.definesValue)
	    	.map(handler => request.definedSymbols(handler.definesTerm.get))
	    	.getOrElse(imain.global.NoSymbol)
  	}
}