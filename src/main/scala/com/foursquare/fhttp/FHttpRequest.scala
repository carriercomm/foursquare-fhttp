// Copyright 2011 Foursquare Labs Inc. All Rights Reserved.

package com.foursquare.fhttp

import com.twitter.conversions.time._
import com.twitter.finagle.{Filter, Service, SimpleFilter}
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.util.Future
import java.nio.charset.Charset
import org.apache.commons.codec.binary.Base64
import org.apache.commons.httpclient.methods.multipart._
import org.apache.commons.httpclient.params.HttpMethodParams
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBufferInputStream, ChannelBufferOutputStream, ChannelBuffers}
import org.jboss.netty.channel.DefaultChannelConfig
import org.jboss.netty.handler.codec.http._
import scala.collection.JavaConversions._


object FHttpRequest { 
  type HttpOption = HttpMessage => Unit

  val UTF_8 = Charset.forName("UTF-8")
  val PARAM_TYPE = "application/x-www-form-urlencoded"
  val BOUNDARY = "gc0pMUlT1B0uNdArYc0p"
  val MULTIPART_PARAMS = {
    val p = new HttpMethodParams()
    p.setParameter(HttpMethodParams.MULTIPART_BOUNDARY, BOUNDARY)
    p
  }

  def apply(client: FHttpClient, uri: String): FHttpRequest = 
    FHttpRequest(client,
             HttpMethod.GET,
             uri,
             "",
             client.service, Nil).headers("Host" -> client.firstHostPort)

  // HttpResponse conversions

  /**
   * Extracts the raw contents as a ChannelBuffer
   */
  def asContentsBuffer: HttpResponse => ChannelBuffer = res => res.getContent

  /**
   * Extracts the contents as a String (default for all request methods)
   */
  def asString: HttpResponse => String = res => res.getContent.toString(UTF_8)

  /**
   * Extracts the contents as a byte array
   */
  def asBytes: HttpResponse => Array[Byte] = res => res.getContent.toByteBuffer.array

  /**
   * Extracts the contents as an input stream
   */
  def asInputStream: HttpResponse => java.io.InputStream = res => new ChannelBufferInputStream(res.getContent)

  /**
   * Extracts the contents as XML
   */
  def asXml: HttpResponse => scala.xml.Elem = res => scala.xml.XML.load(asInputStream(res))

  def asParams: HttpResponse => List[(String, String)] = res => {
    val params = new QueryStringDecoder("/null?" + asString(res)).getParameters
    params.map(kv => kv._2.toList.map(v => (kv._1, v))).flatten.toList
  }

  def asParamMap: HttpResponse => Map[String, String] = res => Map(asParams(res):_*)

  /**
   * Extracts the contents as an oauth Token
   */
  def asOAuth1Token: HttpResponse => Token = res => {
    val params = asParamMap(res)
    Token(params("oauth_token"), params("oauth_token_secret"))
  }

  /**
   * Returns the original response (convenient because asString is the default extraction type)
   */
  def asHttpResponse: HttpResponse => HttpResponse = res => res


  /**
   * A filter for printing the request and reponse as it passes through service stack
   */
  object debugFilter extends SimpleFilter[HttpRequest, HttpResponse] {
    val printMessage: HttpOption = r => {
      println(r)
      if(r.getContent != ChannelBuffers.EMPTY_BUFFER) {
        println("--CONTENT--")
        println(r.getContent.toString(UTF_8))
      }
    }

    def apply(request: HttpRequest, service: Service[HttpRequest, HttpResponse]) = {
      printMessage(request)
      service(request) map (response => {
        printMessage(response)
        response
      })
    }
  }
}

/**
 * An HTTP request
 */
case class FHttpRequest ( client: FHttpClient, 
                          method: HttpMethod,
                          uri: String,
                          traceName: String,
                          service: Service[HttpRequest, HttpResponse], 
                          options: List[FHttpRequest.HttpOption]) {

  /**
   * Adds parameters to the request
   * @param p The parameters to add
   */
  def params(p: (String, String)*): FHttpRequest = params(p.toList)

  /**
   * Adds parameters to the request
   * @param p The parameters to add
   */
  def params(p: List[(String, String)]): FHttpRequest = {
    // QueryStringEncoder doesn't handle existing params well, so decode first
    val qOld = new QueryStringDecoder(uri)
    val qEnc = new QueryStringEncoder(qOld.getPath)
    (paramList ++ p).foreach(kv => qEnc.addParam(kv._1, kv._2))
    FHttpRequest(client, method, qEnc.toString, traceName, service, options)
  }

  /**
   * Sets the HTTP Method of the request (automatically done with the get* and post* methods)
   * @param m The HTTP method to use
   */
  def httpMethod(m: HttpMethod): FHttpRequest = FHttpRequest(client, m, uri, traceName, service, options)

  /**
   * Sets the content type header of the request
   * @param t The content type
   */
  def contentType(t: String): FHttpRequest = headers(HttpHeaders.Names.CONTENT_TYPE -> t)

  /**
   * Sets the keep alive header of the request (useful for HTTP 1.0)
   */
  def keepAlive(isKeepAlive: Boolean): FHttpRequest = 
    option((r: HttpMessage) => HttpHeaders.setKeepAlive(r, isKeepAlive))

  /**
   * Adds headers to the request
   * @param p The headers to add
   */
  def headers(h: (String, String)*): FHttpRequest = headers(h.toList)

  /**
   * Adds headers to the request
   * @param p The headers to add
   */
  def headers(h: List[(String, String)]): FHttpRequest = 
    option((r: HttpMessage) => h.foreach(kv => r.addHeader(kv._1, kv._2)))

  /**
   * Adds a basic http auth header to the request
   * @param user The username
   * @param password The password
   */
  def auth(user: String, password: String) = headers("Authorization" -> ("Basic " + base64(user + ":" + password)))

  /**
   * Adds a filter to the service
   * @param f the filter to add to the stack
   */
  def filter(f: Filter[HttpRequest, HttpResponse, HttpRequest, HttpResponse]): FHttpRequest = 
    FHttpRequest(client, method, uri, traceName, f andThen service, options)

  /**
   * Adds a request timeout (using the TimeoutFilter) to the stack.  Applies blocking or future responses.
   * @param millis The number of milliseconds to wait
   */
  def timeout(millis: Int) = 
    filter(new TimeoutFilter(millis.milliseconds))

  /**
   * Adds a debugging filter to print the request and the response.  Can be added multiple times
   * to inspect the filter transformations
   */
  def debug() =
    filter(FHttpRequest.debugFilter)

  /**
   * Adds a pre-filter transformation to the HttpMessage
   * @param o A function to transform the HttpMessage
   */
  def option(o: FHttpRequest.HttpOption): FHttpRequest =
    FHttpRequest(client, method, uri, traceName, service, o :: options)

  /**
   * Adds a consumer token to the request. The request will be signed with this token
   * @param consumer The token to add
   */
  def oauth(consumer: Token): FHttpRequest = oauth(consumer, None, None)

  /**
   * Adds tokens to the request. The request will be signed with both tokens
   * @param consumer The consumer token
   * @param token The oauth token
   */
  def oauth(consumer: Token, token: Token): FHttpRequest = oauth(consumer, Some(token), None)

  /**
   * Adds tokens to the request. The request will be signed with both tokens
   * @param consumer The consumer token
   * @param token The oauth token
   * @param verifier The verifier parameter (1.0a)
   */
  def oauth(consumer: Token, token: Token, verifier: String): FHttpRequest =
    oauth(consumer, Some(token), Some(verifier))

  protected def oauth(consumer: Token, token: Option[Token], verifier: Option[String]): FHttpRequest = {
    val hostPort = client.firstHostPort.split(":", 2) match {
      case Array(k,v) => Some(k, v)
      case _ => None
    }

    filter(new OAuth1Filter(client.scheme, 
                            hostPort.get._1,
                            hostPort.get._2.toInt,
                            consumer,
                            token,
                            verifier))
  }

  def hasParams: Boolean = uri.indexOf('?') != -1

  def paramList: List[(String, String)] = 
    new QueryStringDecoder(uri).getParameters.map(kv => kv._2.toList.map(v=>(kv._1, v))).flatten.toList


  // Response retrieval methods

  /**
   * Issue a non-blocking GET request
   * @param resMap a function to convert the HttpResponse to the desired response type
   */
  def getFuture[T] (resMap: HttpResponse => T = FHttpRequest.asString): Future[T] = 
    process(f => f.map(resMap))
  
  /**
   * Issue a non-blocking POST request
   * @param data The content to provide in the message
   * @param resMap A function to convert the HttpResponse to the desired response type
   */
  def postFuture[T] (data: Array[Byte], resMap: HttpResponse => T): Future[T] = 
    prepPost(data).getFuture(resMap)

  /**
   * Issue a non-blocking POST request
   * @param data The content to provide in the message
   * @param resMap A function to convert the HttpResponse to the desired response type
   */
  def postFuture[T] (data: String = "", resMap: HttpResponse => T = FHttpRequest.asString): Future[T] = 
    postFuture(data.getBytes(FHttpRequest.UTF_8), resMap)

  /**
   * Issue a non-blocking multipart POST request
   * @param data The parts to provide in the multipart message
   * @param resMap A function to convert the HttpResponse to the desired response type
   */
  def postFuture[T] (data: List[MultiPart], resMap: HttpResponse => T): Future[T] =
    prepMultipart(data.map(toPart(_))).getFuture(resMap)


  // Blocking Option
  /**
   * Issue a blocking GET request
   * @param resMap a function to convert the HttpResponse to the desired response type
   */
  def getOption[T] (resMap: HttpResponse => T = FHttpRequest.asString): Option[T] = 
    process(block) match {
      case ClientResponse(response: HttpResponse) => Some(resMap(response))
      case _ => None
    }

  /**
   * Issue a blocking POST request
   * @param data The content to provide in the message
   * @param resMap A function to convert the HttpResponse to the desired response type
   */
  def postOption[T] (data: Array[Byte], resMap: HttpResponse => T): Option[T] = 
    prepPost(data).getOption(resMap)

  /**
   * Issue a blocking POST request
   * @param data The content to provide in the message
   * @param resMap A function to convert the HttpResponse to the desired response type
   */
  def postOption[T] (data: String = "", resMap: HttpResponse => T = FHttpRequest.asString): Option[T] = 
    postOption(data.getBytes(FHttpRequest.UTF_8), resMap)

  /**
   * Issue blocking multipart POST request
   * @param data The parts to provide in the multipart message
   * @param resMap A function to convert the HttpResponse to the desired response type
   */
  def postOption[T] (data: List[MultiPart], resMap: HttpResponse => T): Option[T] =
    prepMultipart(data.map(toPart(_))).getOption(resMap)


  // Blocking Throw
  /**
   * Issue a blocking GET request and throw on failure
   * @param resMap a function to convert the HttpResponse to the desired response type
   */
  def get_![T] (resMap: HttpResponse => T = FHttpRequest.asString): T = 
    process(block) match {
      case ClientResponse(response: HttpResponse) => resMap(response)
      case ClientException(e) => throw e
    }
  
  /**
   * Issue a blocking POST request and throw on failure
   * @param data The content to provide in the message
   * @param resMap A function to convert the HttpResponse to the desired response type
   */
  def post_![T] (data: Array[Byte], resMap: HttpResponse => T): T = 
    prepPost(data).get_!(resMap)

  /**
   * Issue a blocking POST request and throw on failure
   * @param data The content to provide in the message
   * @param resMap A function to convert the HttpResponse to the desired response type
   */
  def post_![T] (data: String = "", resMap: HttpResponse => T = FHttpRequest.asString): T = 
    post_!(data.getBytes(FHttpRequest.UTF_8), resMap)

  /**
   * Issue blocking multipart POST request and throw on failure
   * @param data The parts to provide in the multipart message
   * @param resMap A function to convert the HttpResponse to the desired response type
   */
  def post_![T] (data: List[MultiPart], resMap: HttpResponse => T): T = 
    prepMultipart(data.map(toPart(_))).get_!(resMap)

  // Request issuing internals

  protected def content(data: Array[Byte]): FHttpRequest = {
    option((r: HttpMessage) => {
      r.setContent(new DefaultChannelConfig().getBufferFactory.getBuffer(data, 0, data.length))
      HttpHeaders.setContentLength(r, data.length)
    })
  }

  protected def process[T] (processor: Future[HttpResponse] => T): T = {
    val uriObj = new java.net.URI(uri)
    val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                                     method, 
                                     uri.substring(uri.indexOf(uriObj.getPath)))
    options.reverse.foreach(_(req))
    processor(service(req))
  }

  protected val block: Future[HttpResponse] => ClientResponseOrException = 
    responseFuture => {
      try{
        val r = responseFuture.apply
        ClientResponse[HttpResponse](r)
      } catch {
        case e => {
          ClientException(e)
        }
      }
    }

  protected def prepPost(data: Array[Byte]) = {
    // If there is no data, but params,
    // then the params are the data
    if(data.length == 0 && hasParams) {
      val qDec = new QueryStringDecoder(uri)
      FHttpRequest(client, method, qDec.getPath, traceName, service, options)
        .contentType(FHttpRequest.PARAM_TYPE)
        .prepData(uri.substring(qDec.getPath.length + 1).getBytes(FHttpRequest.UTF_8))
    } else {
      prepData(data)
    }
  }

  protected def toPart(part: MultiPart) = {
    new FilePart(part.name, 
      new ByteArrayPartSource(part.filename, part.data), 
      part.mime, 
      FHttpRequest.UTF_8.name)
  }

  protected def prepMultipart(parts: List[Part]): FHttpRequest = {
    if(hasParams) {
      val qDec = new QueryStringDecoder(uri)
      return FHttpRequest(client, method, qDec.getPath, traceName, service, options)
        .prepMultipart(paramList.map(p =>
           new StringPart(p._1, p._2, FHttpRequest.UTF_8.name)) ::: parts)
    }
    val mpr = new MultipartRequestEntity(parts.toArray, FHttpRequest.MULTIPART_PARAMS)
    val os = new ChannelBufferOutputStream( new DefaultChannelConfig()
                                            .getBufferFactory
                                            .getBuffer(mpr.getContentLength.toInt))
    mpr.writeRequest(os)
    prepData(os.buffer.toByteBuffer.array)
      .contentType("multipart/form-data; boundary=" + FHttpRequest.BOUNDARY)
      .headers("MIME-Version" -> "1.0")
  
  } 

  protected def prepData(data: Array[Byte]) = {
    httpMethod(HttpMethod.POST).content(data)
  }
  protected def base64(bytes: Array[Byte]) = new String(Base64.encodeBase64(bytes))

  protected def base64(in: String): String = base64(in.getBytes(FHttpRequest.UTF_8))

}


case class HttpStatusException(code: Int, reason: String, response: HttpResponse) extends RuntimeException {
  var clientId = ""
  def addName(name: String) = { 
    clientId = " in " + name
    this
  }

  def asString: String = FHttpRequest.asString(response)

  override def getMessage(): String = {
    return "HttpStatusException%s: Code: %d Reason: %s" format(clientId, code, reason)
  }
}


abstract class ClientResponseOrException
case class ClientResponse[T](response: T) extends ClientResponseOrException
case class ClientException(exception: Throwable) extends ClientResponseOrException


object MultiPart {
  def apply(name: String, filename: String, mime: String, data: String): MultiPart = 
    MultiPart(name, filename, mime, data.getBytes(FHttpRequest.UTF_8))
}

case class MultiPart(val name: String, val filename: String, val mime: String, val data: Array[Byte])

