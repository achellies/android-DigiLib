/**
 * Copyright (c) 2012 Alexey Aksenov ezh@ezh.msk.ru
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.digimead.digi.ctrl.lib.storage

import java.io.File
import java.net.URI
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import java.util.ArrayList

import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.conn.params.ConnRoutePNames
import org.apache.http.conn.scheme.PlainSocketFactory
import org.apache.http.conn.scheme.Scheme
import org.apache.http.conn.scheme.SchemeRegistry
import org.apache.http.conn.ssl.SSLSocketFactory
import org.apache.http.entity.FileEntity
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager
import org.apache.http.message.BasicNameValuePair
import org.apache.http.params.BasicHttpParams
import org.apache.http.params.HttpProtocolParams
import org.apache.http.util.EntityUtils
import org.apache.http.HttpHost
import org.apache.http.HttpVersion
import org.apache.http.NameValuePair
import org.digimead.digi.ctrl.lib.aop.RichLogger.rich2plain
import org.digimead.digi.ctrl.lib.aop.Logging
import org.digimead.digi.ctrl.lib.base.AppActivity
import org.digimead.digi.ctrl.lib.util.Android
import org.json.JSONObject

import android.provider.Settings
import android.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/*
 * act as web server ;-) strictly within Google OAuth2 draft10 manual, Ezh
 */
object GoogleCloud extends Logging {
  private lazy val accessToken = new AtomicReference[Option[AccessToken]](None)
  val tokenURL = "https://accounts.google.com/o/oauth2/token"
  val uploadURL = "https://commondatastorage.googleapis.com"
  protected lazy val httpclient = AppActivity.Context.map {
    context =>
      // register the "http" and "https" protocol schemes, they are
      // required by the default operator to look up socket factories.
      val supportedSchemes = new SchemeRegistry()
      supportedSchemes.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80))
      supportedSchemes.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443))
      // prepare parameters
      val params = new BasicHttpParams()
      HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1)
      HttpProtocolParams.setContentCharset(params, "UTF-8")
      HttpProtocolParams.setUseExpectContinue(params, true)
      val ccm = new ThreadSafeClientConnManager(params, supportedSchemes)
      val client = new DefaultHttpClient(ccm, params)
      /*
       * attach proxy
       */
      val proxyString = Option(Settings.Secure.getString(context.getContentResolver(), Settings.System.HTTP_PROXY)).
        getOrElse(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.HTTP_PROXY))
      if (proxyString != null) {
        log.info("detect proxy at " + proxyString)
        try {
          val Array(proxyAddress, proxyPort) = proxyString.split(":")
          val proxy = new HttpHost(proxyAddress, Integer.parseInt(proxyPort), "http")
          client.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy)
        } catch {
          case e =>
            log.warn(e.getMessage(), e)
        }
      } else
        log.info("proxy not detected")
      /*
       * trust all TLS certs
       */
      val ctx = SSLContext.getInstance("TLS")
      val tm = new X509TrustManager() {
        def checkClientTrusted(xcs: Array[X509Certificate], string: String) {}
        def checkServerTrusted(xcs: Array[X509Certificate], string: String) {}
        def getAcceptedIssuers() = Array[X509Certificate]()
      }
      ctx.init(null, Array[TrustManager](tm), null)
      try {
        val ssf = new SSLSocketFactory(null)
        ssf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
        client.getConnectionManager.getSchemeRegistry().register(new Scheme("https", ssf, 443))
      } catch {
        case e =>
          log.warn(e.getMessage(), e)
          HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory())
      }
      client
  }
  def upload(file: File) = AppActivity.Context.foreach {
    context =>
      log.debug("upload " + file.getName + " with default credentials")
      val result = for {
        clientID_64 <- Android.getString(context, "APPLICATION_GS_CLIENT_ID")
        clientSecret_64 <- Android.getString(context, "APPLICATION_GS_CLIENT_SECRET")
        refreshToken_64 <- Android.getString(context, "APPLICATION_GS_TOKEN")
        backet_64 <- Android.getString(context, "APPLICATION_GS_BUCKET")
        httpclient <- httpclient
      } yield try {
        val clientID = new String(Base64.decode(clientID_64, Base64.DEFAULT), "UTF-8")
        val clientSecret = new String(Base64.decode(clientSecret_64, Base64.DEFAULT), "UTF-8")
        val refreshToken = new String(Base64.decode(refreshToken_64, Base64.DEFAULT), "UTF-8")
        val bucket = new String(Base64.decode(backet_64, Base64.DEFAULT), "UTF-8")
        getAccessToken(clientID, clientSecret, refreshToken) match {
          case Some(token) =>
            try {
              val uri = new URI(Seq(uploadURL, bucket, file.getName).mkString("/"))
              log.debug("uploading to " + uri)
              val host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme())
              val httpput = new HttpPut(uri.getPath())
              httpput.setHeader("x-goog-api-version", "2")
              httpput.setHeader("Authorization", "OAuth " + token.access_token)
              httpput.setEntity(new FileEntity(file, "binary/octet-stream"))
              val response = httpclient.execute(host, httpput)
              val entity = response.getEntity()
              log.debug(uri + " result: " + response.getStatusLine())
              response.getStatusLine().getStatusCode() match {
                case 200 =>
                  log.info("upload " + file + " successful")
                case _ =>
                  log.warn("upload " + file + " failed")
              }
            } catch {
              case e =>
                log.error("unable to get access token", e)
                None
            }
          case None =>
            log.error("access token not exists")
        }
      } catch {
        case e =>
          log.error("unable to upload", e)
      }
      if (result == None)
        log.warn("unable to upload " + file)
  }
  def getAccessToken(clientID: String, clientSecret: String, refreshToken: String): Option[AccessToken] = {
    accessToken.get.foreach(t => if (t.expired > System.currentTimeMillis) {
      log.debug("get cached access token " + t.access_token)
      return Some(t)
    })
    log.debug("aquire new access token")
    val result = httpclient.flatMap {
      httpclient =>
        try {
          val uri = new URI(tokenURL)
          val host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme())
          val httppost = new HttpPost(uri.getPath())
          val nameValuePairs = new ArrayList[NameValuePair](2)
          nameValuePairs.add(new BasicNameValuePair("client_id", clientID))
          nameValuePairs.add(new BasicNameValuePair("client_secret", clientSecret))
          nameValuePairs.add(new BasicNameValuePair("refresh_token", refreshToken))
          nameValuePairs.add(new BasicNameValuePair("grant_type", "refresh_token"))
          httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs))
          val response = httpclient.execute(host, httppost)
          val entity = response.getEntity()
          log.debug(tokenURL + " result: " + response.getStatusLine())
          response.getStatusLine().getStatusCode() match {
            case 200 =>
              val o = new JSONObject(EntityUtils.toString(entity))
              Some(AccessToken(o.getString("access_token"),
                System.currentTimeMillis - 1000 + o.getInt("expires_in"),
                o.getString("token_type")))
            case _ =>
              None
          }
        } catch {
          case e =>
            log.error("unable to get access token", e)
            None
        }
    }
    if (result != None)
      accessToken.set(result)
    result
  }
  case class AccessToken(
    val access_token: String,
    val expired: Long,
    val token_type: String)
}
