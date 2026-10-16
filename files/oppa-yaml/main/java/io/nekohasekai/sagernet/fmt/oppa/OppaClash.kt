package io.nekohasekai.sagernet.fmt.oppa

/** Clash YAML mapping; never trim or stringify credentials. */
fun parseClashOppa(proxy: Map<String, Any?>): OppaBean {
    fun string(key: String, default: String = ""): String {
        val value=proxy[key] ?: return default
        require(value is String) { "Invalid OPPA $key type" }
        return value
    }
    fun integer(key: String, default: Int): Int {
        val value=proxy[key] ?: return default
        return value.toString().toIntOrNull() ?: error("Invalid OPPA $key")
    }
    fun boolean(key: String): Boolean = when(val value=proxy[key]) {
        null -> false
        is Boolean -> value
        is String -> when(value.lowercase()) { "true" -> true; "false" -> false; else -> error("Invalid OPPA $key") }
        else -> error("Invalid OPPA $key")
    }
    val server=string("server").trim().removeSurrounding("[","]")
    val port=integer("port",443)
    val password=string("password")
    val preconnect=integer("preconnect",8)
    require(server.isNotBlank()) { "Missing OPPA server" }
    require(port in 1..65535) { "Invalid OPPA port" }
    require(password.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size<=4096) { "Invalid OPPA password length" }
    require(preconnect in 1..64) { "Invalid OPPA preconnect" }
    return OppaBean().apply {
        initializeDefaultValues()
        name=string("name")
        serverAddress=server
        serverPort=port
        this.password=password
        preConnect=preconnect
        sni=string("sni")
        allowInsecure=boolean("skip-cert-verify")
        certificatePinSHA256=string("pin-sha256",string("pin_sha256"))
    }
}
