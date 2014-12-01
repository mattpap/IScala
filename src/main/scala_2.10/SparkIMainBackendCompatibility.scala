package org.refptr.iscala

trait SparkIMainBackendCompatibility extends IMainBackendCompatibility {
  self: SparkIMainBackend =>

  import global._

	  def originalPath(name: Name): String = imain.pathToName(name)
  	def originalPath(symbol: Symbol): String = originalPath(symbol.name)

    trait RequestWrapperImplCompatibility {
    	self2: RequestWrapperImpl =>
    	def value = Some(request.handlers.last)
	    	.filter(_.definesValue)
	    	.map(handler => request.definedSymbols(handler.definesTerm.get))
	    	.getOrElse(imain.global.NoSymbol)
  	}
}
