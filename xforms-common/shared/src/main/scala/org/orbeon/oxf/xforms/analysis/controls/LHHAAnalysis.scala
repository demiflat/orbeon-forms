/**
 *  Copyright (C) 2010 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.analysis.controls

import org.orbeon.dom._
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.xforms.analysis._
import org.orbeon.xforms.XFormsNames._
import org.orbeon.xforms.analysis.model.ValidationLevel
import org.orbeon.xforms.xbl.Scope

class LHHAAnalysis(
  part              : PartAnalysisImpl,
  index             : Int,
  element           : Element,
  parent            : Option[ElementAnalysis],
  preceding         : Option[ElementAnalysis],
  scope             : Scope,
  val staticValue   : Option[String],
  val isPlaceholder : Boolean,
  val containsHTML  : Boolean
) extends ElementAnalysis(part, index, element, parent, preceding, scope)
   with OptionalSingleNode
   with AppearanceTrait {

  self =>

  import LHHAAnalysis._

  require(parent.isDefined)

  def getParent: ElementAnalysis = parent.get // TODO: rename `parent` to `parentOpt`, and this `def` to `parent`

  def lhhaType: LHHA = LHHA.withNameOption(localName) getOrElse LHHA.Label // FIXME: Because `SelectionControlTrait` calls this for `value`!

  val forStaticIdOpt: Option[String] = element.attributeValueOpt(FOR_QNAME)
  val isLocal       : Boolean        = forStaticIdOpt.isEmpty
  val defaultToHTML : Boolean        = LHHAAnalysis.isHTML(element) // IIUC: starting point for nested `<xf:output>`.

  // Updated in `attachToControl()`
  var _isForRepeat                          : Boolean                                 = false
  var _forRepeatNesting                     : Int                                     = 0
  var _directTargetControlOpt               : Option[StaticLHHASupport]               = None
  var _effectiveTargetControlOrPrefixedIdOpt: Option[StaticLHHASupport Either String] = None

  def isForRepeat                          : Boolean           = _isForRepeat
  def forRepeatNesting                     : Int               = _forRepeatNesting
  def directTargetControl                  : StaticLHHASupport = _directTargetControlOpt getOrElse (throw new IllegalStateException)
  def effectiveTargetControlOrPrefixedIdOpt: Option[Either[StaticLHHASupport, String]] = _effectiveTargetControlOrPrefixedIdOpt

  // What we support for alert level/validation:
  //
  // - <xf:alert>                                  -> alert applies to all alert levels
  // - <xf:alert level="foo">                      -> same, unknown level is ignored [SHOULD WARN]
  // - <xf:alert level="warning info">             -> alert only applies to warning and info levels
  // - <xf:alert level="warning" validation="">    -> same, blank attribute is same as missing attribute [SHOULD WARN]
  // - <xf:alert validation="c1 c2">               -> alert only applies if either validation c1 or c2 fails
  // - <xf:alert level="" validation="c1 c2">      -> same, blank attribute is same as missing attribute [SHOULD WARN]
  // - <xf:alert level="error" validation="c1 c2"> -> same, level is ignored when a validation is present [SHOULD WARN]

  val forValidations: Set[String] =
    if (localName == "alert")
      gatherAlertValidations(element.attributeValueOpt(VALIDATION_QNAME))
    else
      Set.empty

  val forLevels: Set[ValidationLevel] =
    if (localName == "alert")
      gatherAlertLevels(element.attributeValueOpt(LEVEL_QNAME))
    else
      Set.empty

  def debugOut(): Unit =
    if (staticValue.isDefined)
      println("static value for control " + prefixedId + " => " + staticValue.get)
}

object LHHAAnalysis {

  def isHTML     (e: Element): Boolean = e.attributeValue(MEDIATYPE_QNAME) == "text/html"
  def isPlainText(e: Element): Boolean = e.attributeValue(MEDIATYPE_QNAME) == "text/plain"

  def gatherAlertValidations(validationAtt: Option[String]): Set[String] =
    stringOptionToSet(validationAtt)

  def gatherAlertLevels(levelAtt: Option[String]): Set[ValidationLevel] =
    stringOptionToSet(levelAtt) collect ValidationLevel.LevelByName
}