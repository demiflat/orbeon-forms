package org.orbeon.fr.offline

import org.orbeon.facades.TextDecoder
import org.orbeon.oxf.http.{Headers, HttpMethod, StatusCode}
import org.orbeon.xforms.embedding.{SubmissionProvider, SubmissionRequest, SubmissionResponse}
import org.scalajs.dom.experimental.{Headers => FetchHeaders}

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.typedarray.Uint8Array


object DemoSubmissionProvider extends SubmissionProvider {

//  val logger: Logger = LoggerFactory.createLogger("org.orbeon.offline.DemoSubmissionProvider")
//  val logger: Logger = LoggerFactory.createLogger("org")
//  implicit val indentedLogger = new IndentedLogger(logger, true)

  import org.orbeon.oxf.util.Logging._
  import org.orbeon.xforms.offline.OfflineSupport._

  case class FormData(contentTypeOpt: Option[String], data: Uint8Array, workflowStageOpt: Option[String])

  private var store = Map[String, FormData]()

  def submit(req: SubmissionRequest): SubmissionResponse = {

    def headersAsString =
      req.headers.iterator map { array =>
        val name = array(0)
        val value = array(1)

        s"$name=$value"
      } mkString "&"

    debug(
      s"handling submission",
      List(
        "method"      -> req.method,
        "path"        -> req.url.pathname,
        "body length" -> req.body.map(_.length.toString).orNull,
        "headers"     -> headersAsString
      )
    )

    HttpMethod.withNameInsensitive(req.method) match {
      case HttpMethod.GET =>

        // TODO: check pathname is persistence path
        store.get(req.url.pathname) match {
          case Some(FormData(responseContentTypeOpt, responseBody, workflowStageOpt)) =>

            val headersList =
              responseContentTypeOpt.map(Headers.ContentType ->    ).toList :::
              workflowStageOpt      .map("Orbeon-Workflow-Stage" ->).toList

            new SubmissionResponse {
              val statusCode = StatusCode.Ok
              val headers    = new FetchHeaders(headersList.toJSArray.map{ case (k, v) => js.Array(k, v) })
              val body       = responseBody
            }
          case None =>
            new SubmissionResponse {
              val statusCode = StatusCode.NotFound
              val headers    = new FetchHeaders
              val body       = new Uint8Array(0)
            }
        }
      case HttpMethod.PUT =>

        if (logger.isDebugEnabled && req.headers.get(Headers.ContentType).exists(_.contains("xml"))) {
          val body = new TextDecoder().decode(req.body.get)
          debug(s"PUT body", List("body" -> body))
        }

        // TODO: check pathname is persistence path
        val existing = store.contains(req.url.pathname)
        store += req.url.pathname ->
          FormData(
            req.headers.get(Headers.ContentType).toOption,
            req.body.getOrElse(throw new IllegalArgumentException),
            req.headers.get("Orbeon-Workflow-Stage").toOption
          )

        new SubmissionResponse {
          val statusCode = if (existing) StatusCode.Ok else StatusCode.Created
          val headers    = new FetchHeaders
          val body       = new Uint8Array(0)
        }
      case _ => ???
    }
  }

  def submitAsync(req: SubmissionRequest): js.Promise[SubmissionResponse] = ???
}