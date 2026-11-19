package app.genkan.romm

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A short, user-facing reason for a failed RomM request. Connection problems get named, because
 * Android's Log drops the stack trace of an UnknownHostException and the bare message
 * ("Unable to resolve host …") reads like a Genkan bug.
 */
fun describeFailure(e: Throwable, fallback: String): String = when (e) {
    is UnknownHostException -> "Can't find the RomM server from this network"
    is ConnectException -> "Can't reach the RomM server"
    is SocketTimeoutException -> "The RomM server stopped responding"
    is SSLException -> "Secure connection to RomM failed"
    else -> e.message ?: fallback
}
