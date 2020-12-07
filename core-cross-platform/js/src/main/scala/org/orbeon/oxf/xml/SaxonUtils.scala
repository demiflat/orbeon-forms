/**
 * Copyright (C) 2010 Orbeon, Inc.
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
package org.orbeon.oxf.xml

import java.net.URI

import cats.syntax.option._
import org.orbeon.dom.QName
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.util.StaticXPath
import org.orbeon.oxf.util.StaticXPath.{SaxonConfiguration, ValueRepresentationType}
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.saxon.expr.parser.ExpressionTool
import org.orbeon.saxon.expr.sort.{CodepointCollator, GenericAtomicComparer}
import org.orbeon.saxon.expr.{EarlyEvaluationContext, Expression}
import org.orbeon.saxon.functions.DeepEqual
import org.orbeon.saxon.model.{BuiltInAtomicType, BuiltInType, Converter, Type}
import org.orbeon.saxon.om
import org.orbeon.saxon.om._
import org.orbeon.saxon.pattern.{NameTest, NodeKindTest}
import org.orbeon.saxon.tree.iter.{EmptyIterator, ListIterator, SingletonIterator}
import org.orbeon.saxon.utils.Configuration
import org.orbeon.saxon.value._
import org.orbeon.scaxon.Implicits

import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.Breaks.{break, breakable}


object SaxonUtils {

  // Version of `StringValue` which supports `equals()` (universal equality).
  // Saxon throws on `equals()` to make a point that a collation should be used for `StringValue` comparison.
  // Here, we don't really care about equality, but we want to implement `equals()` as e.g. Jetty calls `equals()` on
  // objects stored into the session. See:
  // http://forge.ow2.org/tracker/index.php?func=detail&aid=315528&group_id=168&atid=350207
  class StringValueWithEquals(value: CharSequence) extends StringValue(value) {
    override def equals(other: Any): Boolean = {
      // Compare the CharSequence
      other.isInstanceOf[StringValue] && getStringValueCS == other.asInstanceOf[StringValue].getStringValueCS
    }

    override def hashCode(): Int = value.hashCode()
  }

  def fixStringValue[V <: Item](item: V): V =
    item match {
      case v: StringValue => new StringValueWithEquals(v.getStringValueCS).asInstanceOf[V] // we know it's ok...
      case v              => v
    }

  // Effective boolean value of the iterator
  def effectiveBooleanValue(iterator: SequenceIterator): Boolean =
    ExpressionTool.effectiveBooleanValue(iterator)

  def iterateExpressionTree(e: Expression): Iterator[Expression] =
    Iterator(e) ++
      (e.operands.iterator.asScala flatMap (o => iterateExpressionTree(o.childExpression)))

  // Parse the given qualified name and return the separated prefix and local name
  def parseQName(lexicalQName: String): (String, String) = {
    val checker = NameChecker
    val parts   = checker.getQNameParts(lexicalQName)

    (parts(0), parts(1))
  }

  // Make an NCName out of a non-blank string
  // Any characters that do not belong in an NCName are converted to `_`.
  // If `keepFirstIfPossible == true`, prepend `_` if first character is allowed within NCName and keep first character.
  //@XPathFunction
  def makeNCName(name: String, keepFirstIfPossible: Boolean): String = {

    require(name.nonAllBlank, "name must not be blank or empty")

    val name10Checker = NameChecker
    if (name10Checker.isValidNCName(name)) {
      name
    } else {
      val sb = new StringBuilder
      val start = name.charAt(0)

      if (name10Checker.isNCNameStartChar(start))
        sb.append(start)
      else if (keepFirstIfPossible && name10Checker.isNCNameChar(start)) {
        sb.append('_')
        sb.append(start)
      } else
        sb.append('_')

      for (i <- 1 until name.length) {
        val ch = name.charAt(i)
        sb.append(if (name10Checker.isNCNameChar(ch)) ch else '_')
      }
      sb.toString
    }
  }

  // 2020-12-05:
  //
  // - Called only from `XFormsVariableControl`
  // - With Saxon 10, this will be one of :
  //   - `SequenceExtent` reduced to `AtomicValue`, `NodeInfo`, or `Function` (unsupported yet below)
  //   - `SequenceExtent` with more than one item
  //   - `EmptySequence`
  // - Also, if a sequence, it's already reduced.
  //
  def compareValueRepresentations(valueRepr1: GroundedValue, valueRepr2: GroundedValue): Boolean =
    (
      // Ideally we wouldn't support `null` here but `XFormsVariableControl` can pass `null`
      if (valueRepr1 ne null) valueRepr1 else EmptySequence,
      if (valueRepr2 ne null) valueRepr2 else EmptySequence
    ) match {
      case (v1: Item,           v2: Item)           => compareItems(v1, v2)
      case ( _: Item,           _)                  => false
      case (_,                  _ : Item)           => false
      case (v1: SequenceExtent, v2: SequenceExtent) => compareSequenceExtents(v1, v2)
      case (_ : SequenceExtent, _)                  => false
      case (_,                  _: SequenceExtent)  => false
      case _                                        => throw new IllegalStateException
    }

  def compareSequenceExtents(v1: SequenceExtent, v2: SequenceExtent): Boolean =
    v1.getLength == v2.getLength &&
      (v1.iterator.asScala.zip(v2.iterator.asScala) forall (compareItems _).tupled)

  def compareItemSeqs(nodeset1: Seq[Item], nodeset2: Seq[Item]): Boolean =
    nodeset1.size == nodeset2.size &&
      (nodeset1.iterator.zip(nodeset2.iterator) forall (compareItems _).tupled)

  def compareItems(item1: Item, item2: Item): Boolean = {

    val Empty = EmptySequence // TODO: replace once `EmptySequence` no longer quires

    (
      if (item1 ne null) item1 else Empty,
      if (item2 ne null) item2 else Empty
    ) match {
      case (Empty,                     Empty)                     => true
      case (Empty,                     _)                         => false
      case (_,                         Empty)                     => false
      // `StringValue.equals()` throws (Saxon equality requires a collation)
      case (v1: StringValue,           v2: StringValue)           => v1.codepointEquals(v2)
      case ( _: StringValue,           _ )                        => false
      case ( _,                        _: StringValue)            => false
      case (v1: AtomicValue,           v2: AtomicValue)           => v1 == v2
      case ( _: AtomicValue,           _)                         => false
      case (_,                         _ : AtomicValue)           => false
      case (v1: NodeInfo,              v2: NodeInfo)              => v1 == v2
      case (_ : NodeInfo,              _)                         => false
      case (_,                         _ : NodeInfo)              => false
      case ( _: Function,              _ : Function)              => throw new UnsupportedOperationException
      case ( _: Function,              _)                         => throw new UnsupportedOperationException
      case (_,                         _ : Function)              => throw new UnsupportedOperationException
      case _                                                      => throw new IllegalStateException
    }
  }

  def buildNodePath(node: NodeInfo): List[String] = {

    def findNodePosition(node: NodeInfo): Int = {

      val nodeTestForSameNode =
        node.getFingerprint match {
          case -1 => NodeKindTest.makeNodeKindTest(node.getNodeKind)
          case _  => new NameTest(node.getNodeKind, node.getURI, node.getLocalPart, node.getConfiguration.getNamePool)
        }

      val precedingAxis =
        node.iterateAxis(AxisInfo.PRECEDING_SIBLING, nodeTestForSameNode)

      var i = 1
      while (precedingAxis.next ne null)
        i += 1
      i
    }

    def buildOne(node: NodeInfo): List[String] = {

      def buildNameTest(node: NodeInfo) =
        if (node.getURI == "")
          node.getLocalPart
        else
          s"*:${node.getLocalPart}[namespace-uri() = '${node.getURI}']"

      if (node ne null) {
        val parent = node.getParent
        node.getNodeKind match {
          case Type.DOCUMENT =>
            Nil
          case Type.ELEMENT =>
            if (parent eq null) {
              List(buildNameTest(node))
            } else {
              val pre = buildOne(parent)
              if (pre == Nil) {
                buildNameTest(node) :: pre
              } else {
                (buildNameTest(node) + '[' + findNodePosition(node) + ']') :: pre
              }
            }
          case Type.ATTRIBUTE =>
            ("@" + buildNameTest(node)) :: buildOne(parent)
          case Type.TEXT =>
            ("text()[" + findNodePosition(node) + ']') :: buildOne(parent)
          case Type.COMMENT =>
            "comment()[" + findNodePosition(node) + ']' :: buildOne(parent)
          case Type.PROCESSING_INSTRUCTION =>
            ("processing-instruction()[" + findNodePosition(node) + ']') :: buildOne(parent)
          case Type.NAMESPACE =>
            var test = node.getLocalPart
            if (test.isEmpty) {
              test = "*[not(local-name()]"
            }
            ("namespace::" + test) :: buildOne(parent)
          case _ =>
            throw new IllegalArgumentException
        }
      } else {
        throw new IllegalArgumentException
      }
    }

    buildOne(node).reverse
  }

  def convertJavaObjectToSaxonObject(o: Any): GroundedValue =
    o match {
      case v: GroundedValue       => v
      case v: String              => new StringValue(v)
      case v: java.lang.Boolean   => BooleanValue.get(v)
      case v: java.lang.Integer   => new Int64Value(v.toLong)
      case v: java.lang.Float     => new FloatValue(v)
      case v: java.lang.Double    => new DoubleValue(v)
      case v: URI                 => new AnyURIValue(v.toString)
      case _                      => throw new OXFException(s"Invalid variable type: ${o.getClass}")
    }

  // Return `true` iif `potentialAncestor` is an ancestor of `potentialDescendant`
  def isFirstNodeAncestorOfSecondNode(
    potentialAncestor   : NodeInfo,
    potentialDescendant : NodeInfo,
    includeSelf         : Boolean
  ): Boolean = {
    var parent = if (includeSelf) potentialDescendant else potentialDescendant.getParent
    while (parent ne null) {
      if (parent.isSameNodeInfo(potentialAncestor))
        return true
      parent = parent.getParent
    }
    false
  }

  def deepCompare(
    config                     : Configuration,
    it1                        : Iterator[om.Item],
    it2                        : Iterator[om.Item],
    excludeWhitespaceTextNodes : Boolean
  ): Boolean = {

    // Do our own filtering of top-level items as Saxon's `DeepEqual` doesn't
    def filterWhitespaceNodes(item: om.Item): Boolean = item match {
      case n: om.NodeInfo => ! Whitespace.isWhite(n.getStringValueCS)
      case _ => true
    }

    DeepEqual.deepEqual(
      Implicits.asSequenceIterator(if (excludeWhitespaceTextNodes) it1 filter filterWhitespaceNodes else it1),
      Implicits.asSequenceIterator(if (excludeWhitespaceTextNodes) it2 filter filterWhitespaceNodes else it2),
      new GenericAtomicComparer(CodepointCollator.getInstance, config.getConversionContext),
      new EarlyEvaluationContext(config),
      DeepEqual.INCLUDE_PREFIXES                  |
        DeepEqual.INCLUDE_COMMENTS                |
        DeepEqual.COMPARE_STRING_VALUES           |
        DeepEqual.INCLUDE_PROCESSING_INSTRUCTIONS |
        (if (excludeWhitespaceTextNodes) DeepEqual.EXCLUDE_WHITESPACE_TEXT_NODES else 0)
    )
  }

  // These are here to abstract some differences between Saxon 9 and 10
  def getStructuredQNameLocalPart(qName: om.StructuredQName): String = qName.getLocalPart
  def getStructuredQNameURI      (qName: om.StructuredQName): String = qName.getURI

  def itemIterator(i: om.Item): SequenceIterator = SingletonIterator.makeIterator(i)
  def listIterator(s: Seq[om.Item]): SequenceIterator = new ListIterator(s.asJava)
  def emptyIterator: SequenceIterator = EmptyIterator.getInstance
  def valueAsIterator(v: ValueRepresentationType): SequenceIterator = if (v eq null) emptyIterator else v.iterate()

  val ChildAxisInfo: Int = AxisInfo.CHILD
  val AttributeAxisInfo: Int = AxisInfo.ATTRIBUTE

  val NamespaceType: Short = Type.NAMESPACE

  def getInternalPathForDisplayPath(namespaces: Map[String, String], path: String): String = ???

  def attCompare(boundNodeOpt: Option[om.NodeInfo], att: om.NodeInfo): Boolean =
    boundNodeOpt exists (_.getAttributeValue(att.getURI, att.getLocalPart) == att.getStringValue)

  def xsiType(elem: om.NodeInfo): Option[QName] = {
    val fp = om.StandardNames.XSI_TYPE
    val typeQName = elem.getAttributeValue(om.StandardNames.getURI(fp), om.StandardNames.getLocalName(fp))
    if (typeQName ne null) {
      val parts = NameChecker.getQNameParts(typeQName)

      // No prefix
      if (parts(0).isEmpty)
        return QName(parts(1)).some

      // There is a prefix, resolve it
      val namespaceNodes = elem.iterateAxis(StaticXPath.NamespaceAxisType)
      breakable {
        while (true) {
          val currentNamespaceNode = namespaceNodes.next()
          if (currentNamespaceNode eq null)
            break()
          val prefix = currentNamespaceNode.getLocalPart
          if (prefix == parts(0))
            return QName(parts(1), "", currentNamespaceNode.getStringValue).some
        }
      }
    }
    None
  }

  def convertType(
    value               : StringValue,
    newTypeNamespaceURI : String,
    newTypeLocalName    : String,
    config              : SaxonConfiguration
  ): Try[Option[AtomicValue]] = Try {

    val requiredTypeFingerprint = om.StandardNames.getFingerprint(newTypeNamespaceURI, newTypeLocalName)
    require(requiredTypeFingerprint != -1)

    val targetType =
      BuiltInType.getSchemaType(requiredTypeFingerprint).asInstanceOf[BuiltInAtomicType]

    Try(Converter.convert(value, targetType, config.getConversionRules)).toOption
  }
}
