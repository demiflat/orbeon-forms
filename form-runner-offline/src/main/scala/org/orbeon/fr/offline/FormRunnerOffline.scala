package org.orbeon.fr.offline

import org.orbeon.fr.FormRunnerAPI
import org.orbeon.oxf.fr.library.{FormRunnerFunctionLibrary, FormRunnerInternalFunctionLibrary}
import org.orbeon.xforms.App
import org.orbeon.xforms.offline.demo.OfflineDemo
import org.scalajs.dom.{XMLHttpRequest, html}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}


@JSExportTopLevel("FormRunnerOffline")
object FormRunnerOffline extends App {

  def onOrbeonApiLoaded(): Unit = {
    OfflineDemo.onOrbeonApiLoaded()

    // Expose the API in the usual place
    val orbeonDyn = g.window.ORBEON

    val frDyn = {
      if (js.isUndefined(orbeonDyn.fr))
        orbeonDyn.fr = new js.Object
      orbeonDyn.fr
    }

    frDyn.FormRunnerOffline = js.Dynamic.global.FormRunnerOffline
  }

  def onPageContainsFormsMarkup(): Unit =
    OfflineDemo.onPageContainsFormsMarkup()

  @JSExport
  def renderDemoForm(
    container : html.Element,
    appName   : String,
    formName  : String
  ): Unit = {

//    fetchCompiledForm(s"http://localhost:9090/orbeon/fr/service/$appName/$formName/compile") foreach { text =>
    fetchCompiledForm(s"http://localhost:9090/orbeon/xforms-compiler/service/compile/date.xhtml") foreach { text =>
      OfflineDemo.renderCompiledForm(
        container,
        text,
        List(FormRunnerFunctionLibrary, FormRunnerInternalFunctionLibrary)
      )
    }
  }

  private def fetchCompiledForm(url: String): Future[String] = {
    val p = Promise[String]()
    val xhr = new XMLHttpRequest()
    xhr.open(
      method = "GET",
      url    = url
    )
    xhr.onload = { _ =>
      p.success(xhr.responseText)
    }
    xhr.send()

    p.future
  }
}