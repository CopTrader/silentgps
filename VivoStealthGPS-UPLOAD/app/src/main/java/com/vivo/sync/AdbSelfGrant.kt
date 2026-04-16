package com.vivo.sync

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

object AdbSelfGrant {

    interface Callback {
        fun onStatus(msg: String)
        fun onSuccess()
        fun onFailure(error: String)
    }

    fun pairAndGrant(context: Context, host: String, pairingPort: Int, pairingCode: String, callback: Callback) {
        Thread {
            try {
                val manager = AdbConnectionManager(context)
                manager.setApi(Build.VERSION.SDK_INT)
                manager.setHostAddress(host)
                manager.setTimeout(10000, TimeUnit.MILLISECONDS)
                
                callback.onStatus("Pairing with wireless debugging...")
                val paired = manager.pair(host, pairingPort, pairingCode)
                if (!paired) {
                    callback.onFailure("Pairing failed. Check code and try again.")
                    return@Thread
                }
                
                callback.onStatus("Paired! Finding connection port...")
                Thread.sleep(1000)
                
                // Try auto-connect via mDNS (longer timeout)
                var connected = false
                try {
                    connected = manager.autoConnect(context, 15000)
                } catch (e: Exception) {
                    callback.onStatus("Scanning for ADB port...")
                    // Try common wireless debugging port range
                    for (port in 30000..50000) {
                        if (port == pairingPort) continue
                        try {
                            val sock = java.net.Socket()
                            sock.connect(java.net.InetSocketAddress(host, port), 50)
                            sock.close()
                            // Port is open, try ADB connect
                            try {
                                connected = manager.connect(host, port)
                                if (connected) {
                                    callback.onStatus("Connected on port $port!")
                                    break
                                }
                            } catch (e3: Exception) { continue }
                        } catch (e2: Exception) { continue }
                    }
                }
                
                if (!connected) {
                    callback.onFailure("Could not connect. Ensure Wireless Debugging is ON.")
                    return@Thread
                }
                
                callback.onStatus("Connected! Granting permission...")
                
                val stream = manager.openStream("shell:pm grant com.vivo.sync android.permission.WRITE_SECURE_SETTINGS")
                val output = readStream(stream)
                
                manager.disconnect()
                
                if (output.contains("Exception") || output.contains("Error") || output.contains("Unknown")) {
                    callback.onFailure("Grant failed: $output")
                } else {
                    callback.onStatus("Permission granted!")
                    callback.onSuccess()
                }
            } catch (e: Exception) {
                callback.onFailure("Error: ${e.message}")
            }
        }.start()
    }

    private fun readStream(stream: AdbStream): String {
        val output = StringBuilder()
        try {
            val inputStream = stream.openInputStream()
            val buf = ByteArray(4096)
            var len: Int
            while (inputStream.read(buf).also { len = it } != -1) {
                output.append(String(buf, 0, len, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // Stream closed — normal
        }
        return output.toString().trim()
    }

    private class AdbConnectionManager(private val context: Context) : AbsAdbConnectionManager() {
        private val _privKey: PrivateKey
        private val _cert: Certificate

        init {
            val keyFile = File(context.filesDir, "adb_priv.key")
            val certFile = File(context.filesDir, "adb_cert.der")
            
            var pk: PrivateKey? = null
            var ct: Certificate? = null
            
            if (keyFile.exists() && certFile.exists()) {
                try {
                    val kf = java.security.KeyFactory.getInstance("RSA")
                    pk = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                    ct = cf.generateCertificate(certFile.readBytes().inputStream())
                } catch (e: Exception) {
                    keyFile.delete()
                    certFile.delete()
                    pk = null
                    ct = null
                }
            }
            
            if (pk == null || ct == null) {
                val pair = generateKeyAndCert()
                pk = pair.first
                ct = pair.second
                keyFile.writeBytes(pk.encoded)
                certFile.writeBytes(ct.encoded)
            }
            
            _privKey = pk
            _cert = ct
        }

        private fun generateKeyAndCert(): Pair<PrivateKey, X509Certificate> {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val keyPair = kpg.generateKeyPair()
            
            // Self-signed cert using BouncyCastle
            val now = Date()
            val until = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000)
            val subject = org.bouncycastle.asn1.x500.X500Name("CN=adb")
            val serial = BigInteger.valueOf(System.currentTimeMillis())
            
            val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                subject, serial, now, until, subject, keyPair.public
            )
            val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256WithRSA")
                .build(keyPair.private)
            val certHolder = builder.build(signer)
            val certificate = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(certHolder)
            
            return Pair(keyPair.private, certificate)
        }

        override fun getPrivateKey(): PrivateKey = _privKey
        override fun getCertificate(): Certificate = _cert
        override fun getDeviceName(): String = "${Build.MODEL}@VivoSync"
    }
}
