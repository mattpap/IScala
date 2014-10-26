package org.refptr.iscala

object Utils {
    def snakify(name: String): String =
        name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
            .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
            .toLowerCase
}
