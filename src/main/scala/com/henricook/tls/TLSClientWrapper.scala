package com.henricook.tls

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.util

import com.henricook.tls.internal.BCConversions.CipherSuiteId
import com.henricook.tls.internal.{SocketChannelWrapper, TLSUtils}
import com.henricook.tls.x509.CertificateVerifier
import org.bouncycastle.crypto.tls._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.language.postfixOps

class TLSClientWrapper(
    verifier: CertificateVerifier,
    address: InetSocketAddress = null,
    keySet: TLS.KeySet = null
) extends TLSConnectionWrapper {
  protected def getClientCertificate(
      certificateRequest: CertificateRequest
  ): Option[TLS.CertificateKey] = {
    if (keySet == null) None
    else TLSUtils.certificateFor(keySet, certificateRequest)
  }

  override def apply(connection: SocketChannel): SocketChannel = {
    val protocol = new TlsClientProtocol(
      SocketChannelWrapper.inputStream(connection),
      SocketChannelWrapper.outputStream(connection),
      SecureRandom.getInstanceStrong
    )
    val client = new DefaultTlsClient() {
      override def getClientExtensions(): util.Hashtable[Int, Array[Byte]] =
        super.getClientExtensions.asInstanceOf[util.Hashtable[Int, Array[Byte]]]

      override def getMinimumVersion: ProtocolVersion = {
        TLSUtils.minVersion()
      }

      override def getCipherSuites: Array[Int] = {
        TLSUtils.defaultCipherSuites()
      }

      override def notifyHandshakeComplete(): Unit = {
        handshake.trySuccess(true)
        onInfo(
          s"Selected cipher suite: ${CipherSuiteId.asString(selectedCipherSuite)}"
        )
      }

      override def getAuthentication: TlsAuthentication =
        new TlsAuthentication {
          override def getClientCredentials(
              certificateRequest: CertificateRequest
          ): TlsCredentials =
            wrapException("Could not provide client credentials") {
              getClientCertificate(certificateRequest)
                .map(
                  ck ⇒
                    new DefaultTlsSignerCredentials(
                      context,
                      ck.certificateChain,
                      ck.key.getPrivate,
                      TLSUtils.signatureAlgorithm(ck.key.getPrivate)
                    )
                ) // Ignores certificateRequest data
                .orNull
            }

          override def notifyServerCertificate(
              serverCertificate: TLS.CertificateChain
          ): Unit = wrapException("Server certificate error") {
            val chain: List[TLS.Certificate] =
              serverCertificate.getCertificateList.toList

            if (chain.nonEmpty) {
              onInfo(
                s"Server certificate chain: ${chain.map(_.getSubject).mkString("; ")}"
              )
              if (address != null && !verifier
                    .isHostValid(chain.head, address.getHostString)) {
                val message =
                  s"Certificate hostname not match: ${address.getHostString}"
                val exc = new TlsFatalAlert(
                  AlertDescription.bad_certificate,
                  new TLSException(message)
                )
                onError(message, exc)
                throw exc
              }
            }

            if (!verifier.isChainValid(chain)) {
              val message = s"Invalid server certificate: ${chain.headOption
                .fold("<none>")(_.getSubject.toString)}"
              val exc = new TlsFatalAlert(
                AlertDescription.bad_certificate,
                new TLSException(message)
              )
              onError(message, exc)
              throw exc
            }
          }
        }
    }

    val socket = wrapException(s"Error connecting to server: $address") {
      protocol.connect(client)
      new SocketChannelWrapper(connection, protocol)
    }
    Await.result(handshake.future, 3 minutes) // Wait for handshake
    socket
  }
}
