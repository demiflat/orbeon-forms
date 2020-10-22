/**
 * Copyright (C) 2011 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.analysis

import cats.syntax.option._
import org.orbeon.dom
import org.orbeon.dom.Element
import org.orbeon.oxf.util.IndentedLogger
import org.orbeon.oxf.xforms.XFormsStaticStateImpl.StaticStateDocument
import org.orbeon.oxf.xforms._
import org.orbeon.oxf.xforms.analysis.controls._
import org.orbeon.oxf.xforms.analysis.model._
import org.orbeon.oxf.xforms.xbl.{AbstractBinding, XBLSupport}
import org.orbeon.oxf.xml.SAXStore
import org.orbeon.oxf.xml.dom.Extensions.DomElemOps
import org.orbeon.saxon.om
import org.orbeon.xforms.xbl.Scope
import org.orbeon.xml.NamespaceMapping

import scala.collection.mutable


// Immutable part of the context
trait PartAnalysisContextImmutable {

  protected def staticState: XFormsStaticState

  def parent              : Option[PartAnalysis] // `getControlAnalysis`
  def startScope          : Scope
  def metadata            : Metadata             // FIXME: not really immutable, check. `getNamespaceMapping`, `findAbstractBindingByPrefixedId`, `findAbstractBindingByPrefixedId`
  def staticStateDocument : StaticStateDocument

  def scopeForPrefixedId(prefixedId: String): Scope
  def abstractBindingsWithGlobals: Iterable[AbstractBinding]

  def isTopLevelPart: Boolean = startScope.isTopLevelScope
  def ancestorIterator: Iterator[PartAnalysis]
  def ancestorOrSelfIterator: Iterator[PartAnalysis]

  def getNamespaceMapping(prefixedId: String)      = metadata.getNamespaceMapping(prefixedId)
  def functionLibrary                              = staticState.functionLibrary
  def staticBooleanProperty(name: String): Boolean = staticState.staticBooleanProperty(name)
  def staticStringProperty (name: String): String  = staticState.staticStringProperty(name)
}

trait PartAnalysisContextMutable extends TransientState {

  def controlAnalysisMap: mutable.LinkedHashMap[String, ElementAnalysis]
  def controlTypes: mutable.HashMap[String, mutable.LinkedHashMap[String, ElementAnalysis]]

  def initializeScopes(): Unit
  def newScope(parent: Scope, scopeId: String): Scope
  def mapScopeIds(staticId: String, prefixedId: String, scope: Scope, ignoreIfPresent: Boolean): Unit
  def xblSupport: Option[XBLSupport]

  def abstractBindingsWithGlobals: mutable.Buffer[AbstractBinding]
  def allGlobals: mutable.ArrayBuffer[Global]
  def iterateGlobals: Iterator[Global]

  // Shortcut for `attachToControl`
  def findControlAnalysis(prefixedId: String): Option[ElementAnalysis] =
    controlAnalysisMap.get(prefixedId)

  def freeTransientState(): Unit
}

trait PartAnalysisForXblSupport
  extends PartAnalysisContextImmutable {

  def findControlAnalysis(prefixedId: String): Option[ElementAnalysis]
}

trait PartAnalysisContextForTree
  extends PartAnalysisContextImmutable
     with PartAnalysisContextMutable
     with PartAnalysisForXblSupport

trait PartAnalysisContextAfterTree extends PartAnalysisContextForTree {

  def indexModel(model: Model): Unit
  def registerEventHandlers(eventHandlers: Seq[EventHandler])(implicit logger: IndentedLogger): Unit
  def analyzeCustomControls(attributes: mutable.Buffer[AttributeControl]): Unit

  def iterateControlsNoModels: Iterator[ElementAnalysis]
  def iterateModels: Iterator[Model]
  def getModel(prefixedId: String): Model
  def getModelByScopeAndBind(scope: Scope, bindStaticId: String): Model
  def getModelByInstancePrefixedId(prefixedId: String): Model
  def getDefaultModelForScope(scope: Scope): Option[Model]
  def findInstancePrefixedId(startScope: Scope, instanceStaticId: String): Option[String]

  def getAttributeControl(prefixedForAttribute: String, attributeName: String): AttributeControl

  def isXPathAnalysis         = staticState.isXPathAnalysis
  def isCalculateDependencies = staticState.isCalculateDependencies
}

trait PartAnalysisRuntimeOps extends PartAnalysisContextImmutable with PartGlobalOps {

  def hasControls: Boolean
  def defaultModel: Option[Model]
  def getModel(prefixedId: String): Model
  def findControlAnalysis(prefixedId: String): Option[ElementAnalysis]
  def getTopLevelControls: List[ElementAnalysis]
  def observerHasHandlerForEvent(observerPrefixedId: String, eventName: String): Boolean
  def controlElement(prefixedId: String): Option[om.NodeInfo]
  def getNamespaceMapping(prefix: String, element: dom.Element): NamespaceMapping

  def getNamespaceMapping(scope: Scope, id: String): NamespaceMapping =
    getNamespaceMapping(scope.prefixedIdForStaticId(id)) getOrElse
      (throw new IllegalStateException(s"namespace mappings not cached for scope `$scope` on element with id `$id`"))
}

trait PartAnalysis
  extends PartAnalysisContextImmutable
     with PartAnalysisRuntimeOps
     with PartAnalysisForXblSupport

trait TopLevelPartAnalysis
  extends PartAnalysis

trait NestedPartAnalysis
  extends PartAnalysisContextAfterTree
     with PartAnalysis {
  def deindexTree(tree: ElementAnalysis, self: Boolean): Unit
  def deregisterScope(scope: Scope): Unit
  def unmapScopeIds(control: ElementAnalysis): Unit
}

// Context for building the tree, including immutable and mutable parts
case class PartAnalysisContextImpl(
  protected val staticState: XFormsStaticState,
  parent                   : Option[PartAnalysis],
  startScope               : Scope,
  metadata                 : Metadata,
  staticStateDocument      : StaticStateDocument
) extends PartAnalysis
     with TopLevelPartAnalysis
     with NestedPartAnalysis
     with PartModelAnalysis
     with PartEventHandlerAnalysis
     with PartControlsAnalysis
     with PartXBLAnalysis {

  def hasControls: Boolean =
    getTopLevelControls.nonEmpty || (iterateGlobals map (_.compactShadowTree.getRootElement)).nonEmpty

  /**
   * Return the namespace mappings for a given element. If the element does not have an id, or if the mapping is not
   * cached, compute the mapping on the fly. Note that in this case, the resulting mapping is not added to the cache
   * as the mapping is considered transient and not sharable among pages.
   */
  def getNamespaceMapping(prefix: String, element: Element): NamespaceMapping = {

    val id = element.idOrThrow
    val prefixedId = if (prefix ne null) prefix + id else id

    metadata.getNamespaceMapping(prefixedId) getOrElse
      (throw new IllegalStateException(s"namespace mappings not cached for prefix `$prefix` on element `${element.toDebugString}`"))
  }

  def getMark(prefixedId: String): Option[SAXStore#Mark] = metadata.getMark(prefixedId)
  def xblSupport: Option[XBLSupport] = staticState.xblSupport

  private def partAnalysisIterator(start: Option[PartAnalysis]): Iterator[PartAnalysis] =
    new Iterator[PartAnalysis] {

      private[this] var theNext = start

      def hasNext: Boolean = theNext.isDefined

      def next(): PartAnalysis = {
        val newResult = theNext.get
        theNext = newResult.parent
        newResult
      }
    }

  def ancestorIterator      : Iterator[PartAnalysis] = partAnalysisIterator(parent)
  def ancestorOrSelfIterator: Iterator[PartAnalysis] = partAnalysisIterator(this.some)

  override def freeTransientState(): Unit = super.freeTransientState()
}
