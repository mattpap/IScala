package org.refptr.iscala

trait ScalaIMainBackendCompatibility extends IMainBackendCompatibility {
	self: ScalaIMainBackend =>

	import global._
	def originalPath(name: Name): String = imain.originalPath(name)
	def originalPath(symbol: Symbol): String = imain.originalPath(symbol)

	trait RequestWrapperImplCompatibility {
		self2: RequestWrapperImpl =>
		def value: Symbol = request.value
	}
}