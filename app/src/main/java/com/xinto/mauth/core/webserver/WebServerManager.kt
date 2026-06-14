package com.xinto.mauth.core.webserver

import android.content.Context
import com.xinto.mauth.core.otp.generator.OtpGenerator
import com.xinto.mauth.core.otp.model.OtpType
import com.xinto.mauth.core.otp.transformer.KeyTransformer
import com.xinto.mauth.db.dao.account.AccountsDao
import com.xinto.mauth.db.dao.rtdata.RtdataDao
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class WebServerManager(
    private val context: Context,
    private val accountsDao: AccountsDao,
    private val rtdataDao: RtdataDao,
    private val otpGenerator: OtpGenerator,
    private val keyTransformer: KeyTransformer,
) {

    private var server: ServerSocket? = null
    private var running = false
    var port: Int = 8080
        private set
    var authToken: String? = null

    val isRunning: Boolean get() = running

    fun start(port: Int = 8080, authToken: String? = null) {
        stop()
        this.port = port
        this.authToken = authToken
        running = true
        server = ServerSocket(port)
        val thread = Thread { serve() }
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    fun getLocalAddress(): String? {
        val ip = getLocalIpAddress() ?: return null
        return "http://$ip:$port"
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun serve() {
        while (running) {
            try {
                val client = server?.accept() ?: break
                Thread { handleClient(client) }.start()
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val pathWithQuery = parts[1]
            val queryIndex = pathWithQuery.indexOf('?')
            val path = if (queryIndex >= 0) pathWithQuery.substring(0, queryIndex) else pathWithQuery
            val query = if (queryIndex >= 0) pathWithQuery.substring(queryIndex + 1) else ""
            val params = parseQuery(query)

            if (!checkAuth(params)) {
                sendResponse(client.getOutputStream(), 401, "text/plain", "Unauthorized")
                return
            }

            when {
                path == "/" || path == "" -> sendHtmlPage(client.getOutputStream())
                path == "/api" || path == "/api/codes" -> sendJsonResponse(client.getOutputStream())
                else -> sendResponse(client.getOutputStream(), 404, "text/plain", "Not Found")
            }
        } catch (_: Exception) { } finally {
            try { client.close() } catch (_: Exception) { }
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        if (query.isBlank()) return params
        query.split("&").forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq >= 0) {
                params[URLDecoder.decode(pair.substring(0, eq), "UTF-8")] =
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            } else {
                params[URLDecoder.decode(pair, "UTF-8")] = ""
            }
        }
        return params
    }

    private fun checkAuth(params: Map<String, String>): Boolean {
        val token = authToken ?: return true
        return token.isBlank() || params["token"] == token
    }

    private fun getCodes(): List<CodeEntry> {
        val accounts = runBlocking { accountsDao.getAll() }
        val counters = runBlocking { rtdataDao.getAllCounters() }
            .associateBy { it.accountId }
        val seconds = System.currentTimeMillis() / 1000

        return accounts.map { account ->
            val bytes = keyTransformer.transformToBytes(account.secret)
            when (account.type) {
                OtpType.TOTP -> {
                    val code = otpGenerator.generateTotp(
                        secret = bytes,
                        interval = account.period.toLong(),
                        seconds = seconds,
                        digits = account.digits,
                        digest = account.algorithm
                    )
                    val diff = seconds % account.period
                    CodeEntry(
                        label = account.label,
                        issuer = account.issuer,
                        code = code,
                        type = "totp",
                        period = account.period,
                        countdown = (account.period - diff).toInt(),
                        algorithm = account.algorithm.name,
                    )
                }
                OtpType.HOTP -> {
                    val count = counters[account.id]?.count ?: 0
                    val code = otpGenerator.generateHotp(
                        secret = bytes,
                        counter = count.toLong(),
                        digits = account.digits,
                        digest = account.algorithm
                    )
                    CodeEntry(
                        label = account.label,
                        issuer = account.issuer,
                        code = code,
                        type = "hotp",
                        period = 0,
                        countdown = 0,
                        algorithm = account.algorithm.name,
                    )
                }
            }
        }
    }

    private fun sendHtmlPage(output: OutputStream) {
        val apiPath = if (!authToken.isNullOrBlank()) "/api/codes?token=$authToken" else "/api/codes"

        val html = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>Mauth</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#1a1a2e;color:#e0e0e0;padding:16px}
h1{font-size:1.3rem;margin-bottom:16px;color:#7c7cf0}
.l{max-width:480px;margin:0 auto;display:flex;flex-direction:column;gap:10px}
.c{background:#16213e;border-radius:12px;padding:14px;display:flex;justify-content:space-between;align-items:center}
.l{font-size:.95rem;font-weight:500;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.iss{font-size:.78rem;color:#888;margin-top:2px}
.r{display:flex;align-items:center;gap:10px}
.d{font-size:1.3rem;font-weight:700;font-family:'SF Mono','Fira Code',monospace;letter-spacing:2px;color:#4fc3f7}
.cd{font-size:.82rem;color:#888;min-width:30px;text-align:center;font-variant-numeric:tabular-nums}
</style>
</head>
<body>
<h1>Mauth</h1>
<div class="l" id="list"></div>
<script>
const api = "$apiPath";
const es = function(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')};
const fc = function(c){if(c.length<=4)return c;var m=Math.floor(c.length/2);return c.substring(0,m)+' '+c.substring(m)};
function upd(){
  fetch(api).then(function(r){return r.json()}).then(function(d){
    var h='';var i=0;
    while(i<d.length){
      var e=d[i];i++;
      var is=e.issuer?'<div class="iss">'+es(e.issuer)+'</div>':'';
      var cd=e.type=='totp'?'<div class="cd">'+e.countdown+'s</div>':'';
      h+='<div class="c"><div class="i"><div class="l">'+es(e.label)+'</div>'+is+'</div><div class="r"><div class="d">'+fc(e.code)+'</div>'+cd+'</div></div>';
    }
    document.getElementById('list').innerHTML=h;
  }).catch(function(){});
}
upd();
setInterval(upd,1000);
</script>
</body>
</html>"""
        sendResponse(output, 200, "text/html; charset=utf-8", html)
    }

    private fun sendJsonResponse(output: OutputStream) {
        val codes = getCodes()
        val json = codes.joinToString(",\n", "[\n", "\n]") { entry ->
            """  {"label":"${jsonEsc(entry.label)}","issuer":"${jsonEsc(entry.issuer)}","code":"${entry.code}","type":"${entry.type}","period":${entry.period},"countdown":${entry.countdown},"algorithm":"${entry.algorithm}"}"""
        }
        sendResponse(output, 200, "application/json; charset=utf-8", json)
    }

    private fun sendResponse(output: OutputStream, status: Int, contentType: String, body: String) {
        val statusText = when (status) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Unknown" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(response.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun fmtCode(code: String): String {
        if (code.length <= 4) return code
        val mid = code.length / 2
        return "${code.substring(0, mid)} ${code.substring(mid)}"
    }

    private fun htmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun jsonEsc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    data class CodeEntry(
        val label: String,
        val issuer: String,
        val code: String,
        val type: String,
        val period: Int,
        val countdown: Int,
        val algorithm: String,
    )
}
