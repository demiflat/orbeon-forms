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
package org.orbeon.oxf.xml.dom4j

import java.io.{InputStream, Reader, StringReader}
import java.{util => ju}

import org.orbeon.dom._
import org.orbeon.dom.io._
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.properties.Properties
import org.orbeon.oxf.util.StringUtils
import org.orbeon.oxf.xml._
import org.xml.sax.helpers.AttributesImpl

import scala.collection.JavaConverters._

// TODO: move this to Scala/remove unneeded stuff
object Dom4jUtils {

  private def createSAXReader(parserConfiguration: XMLParsing.ParserConfiguration): SAXReader =
    new SAXReader(XMLParsing.newXMLReader(parserConfiguration))

  private def createSAXReader: SAXReader =
    createSAXReader(XMLParsing.ParserConfiguration.XINCLUDE_ONLY)

  /**
    * Convert an XML string to a prettified XML string.
    */
  def prettyfy(xmlString: String): String =
    readDom4j(xmlString).getRootElement.serializeToString(XMLWriter.PrettyFormat)

  def domToPrettyStringJava(document: Document): String =
    document.getRootElement.serializeToString(XMLWriter.PrettyFormat)

  def domToCompactStringJava(document: Document): String =
    document.getRootElement.serializeToString(XMLWriter.CompactFormat)

  def domToStringJava(elem: Document): String =
    elem.getRootElement.serializeToString(XMLWriter.DefaultFormat)

  def readDom4j(reader: Reader): Document =
    createSAXReader.read(reader)

  def readDom4j(reader: Reader, uri: String): Document =
    createSAXReader.read(reader, uri)

  def readDom4j(xmlString: String): Document = {
    val stringReader = new StringReader(xmlString)
    createSAXReader(XMLParsing.ParserConfiguration.PLAIN).read(stringReader)
  }

  def readDom4j(inputStream: InputStream, uri: String, parserConfiguration: XMLParsing.ParserConfiguration): Document =
    createSAXReader(parserConfiguration).read(inputStream, uri)

  def readDom4j(inputStream: InputStream): Document =
    createSAXReader(XMLParsing.ParserConfiguration.PLAIN).read(inputStream)

  def getDocumentSource(d: Document): DocumentSource = {
    val lds = new LocationDocumentSource(d)
    val rdr = lds.getXMLReader
    rdr.setErrorHandler(XMLParsing.ERROR_HANDLER)
    lds
  }

  def getDigest(document: Document): Array[Byte] = {
    val ds = getDocumentSource(document)
    DigestContentHandler.getDigest(ds)
  }

  /**
    * Clean-up namespaces. Some tools generate namespace "un-declarations" or
    * the form xmlns:abc="". While this is needed to keep the XML infoset
    * correct, it is illegal to generate such declarations in XML 1.0 (but it
    * is legal in XML 1.1). Technically, this cleanup is incorrect at the DOM
    * and SAX level, so this should be used only in rare occasions, when
    * serializing certain documents to XML 1.0.
    *
    * 2020-08-27: 1 legacy Java caller.
    */
  def adjustNamespaces(document: Document, xml11: Boolean): Document = {

    if (xml11)
      return document

    val writer = new LocationSAXWriter
    val ch = new LocationSAXContentHandler
    writer.setContentHandler(new NamespaceCleanupXMLReceiver(ch, xml11))
    writer.write(document)

    ch.getDocument
  }

  /**
    * Return a Map of namespaces in scope on the given element, without the default namespace.
    */
  def getNamespaceContextNoDefault(elem: Element): ju.Map[String, String] =
    elem.allInScopeNamespacesAsStrings.filterKeys(_ != "").asJava

  /**
    * Extract a QName from an Element and an attribute name. The prefix of the QName must be in
    * scope. Return null if the attribute is not found.
    */
  def extractAttributeValueQName(elem: Element, attributeName: String): QName =
    extractTextValueQName(elem, elem.attributeValue(attributeName), unprefixedIsNoNamespace = true)

  /**
    * Extract a QName from an Element and an attribute QName. The prefix of the QName must be in
    * scope. Return null if the attribute is not found.
    */
  def extractAttributeValueQName(elem: Element, attributeQName: QName): QName =
    extractTextValueQName(elem, elem.attributeValue(attributeQName), unprefixedIsNoNamespace = true)

  def extractAttributeValueQName(elem: Element, attributeQName: QName, unprefixedIsNoNamespace: Boolean): QName =
    extractTextValueQName(elem, elem.attributeValue(attributeQName), unprefixedIsNoNamespace)

  /**
    * Extract a QName from an Element's string value. The prefix of the QName must be in scope.
    * Return null if the text is empty.
    */
  def extractTextValueQName(elem: Element, unprefixedIsNoNamespace: Boolean): QName =
    extractTextValueQName(elem, elem.getStringValue, unprefixedIsNoNamespace)

  /**
    * Extract a QName from an Element's string value. The prefix of the QName must be in scope.
    * Return null if the text is empty.
    *
    * @param elem                 Element containing the attribute
    * @param qNameString             QName to analyze
    * @param unprefixedIsNoNamespace if true, an unprefixed value is in no namespace; if false, it is in the default namespace
    * @return a QName object or null if not found
    */
  def extractTextValueQName(elem: Element, qNameString: String, unprefixedIsNoNamespace: Boolean): QName =
    extractTextValueQName(elem.allInScopeNamespacesAsStrings, qNameString, unprefixedIsNoNamespace)

  /**
    * Extract a QName from a string value, given namespace mappings. Return null if the text is empty.
    *
    * @param namespaces              prefix -> URI mappings
    * @param qNameStringOrig             QName to analyze
    * @param unprefixedIsNoNamespace if true, an unprefixed value is in no namespace; if false, it is in the default namespace
    * @return a QName object or null if not found
    */
  def extractTextValueQName(namespaces: Map[String, String], qNameStringOrig: String, unprefixedIsNoNamespace: Boolean): QName = {
    if (qNameStringOrig eq null)
      return null

    val qNameString = StringUtils.trimAllToEmpty(qNameStringOrig)

    if (qNameString.length == 0)
      return null

    val colonIndex = qNameString.indexOf(':')
    var prefix: String = null
    var localName: String  = null
    var namespaceURI: String  = null
    if (colonIndex == -1) {
      prefix = ""
      localName = qNameString
      if (unprefixedIsNoNamespace)
        namespaceURI = ""
      else {
        namespaceURI = namespaces.getOrElse(prefix, "")
      }
    } else if (colonIndex == 0) {
      throw new OXFException("Empty prefix for QName: " + qNameString)
    } else {
      prefix = qNameString.substring(0, colonIndex)
      localName = qNameString.substring(colonIndex + 1)
      namespaceURI = namespaces.getOrElse(prefix, throw new OXFException(s"No namespace declaration found for prefix: `$prefix`"))
    }
    QName(localName, Namespace(prefix, namespaceURI))
  }

  /**
    * Decode a String containing an exploded QName (also known as a "Clark name") into a QName.
    *
    * 2020-08-27: 1 caller.
    */
  def explodedQNameToQName(qName: String, prefix: String): QName = {

    val openIndex = qName.indexOf("{")
    if (openIndex == -1)
      return QName.apply(qName)

    val namespaceURI = qName.substring(openIndex + 1, qName.indexOf("}"))
    val localName = qName.substring(qName.indexOf("}") + 1)

    QName(localName, Namespace(prefix, namespaceURI))
  }

  /**
    * Return a new document with all parent namespaces copied to the new root element, assuming they are not already
    * declared on the new root element. The element passed is deep copied.
    *
    * @param newRoot element which must become the new root element of the document
    * @return new document
    */
  def createDocumentCopyParentNamespaces(newRoot: Element): Document =
    createDocumentCopyParentNamespaces(newRoot, detach = false)

  /**
    * Return a new document with all parent namespaces copied to the new root element, assuming they are not already
    * declared on the new root element.
    *
    * @param newRoot element which must become the new root element of the document
    * @param detach  if true the element is detached, otherwise it is deep copied
    * @return new document
    */
  def createDocumentCopyParentNamespaces(newRoot: Element, detach: Boolean): Document = {
    val parentElemOpt = Option(newRoot.getParent)
    val document =
      if (detach)
        Document(newRoot.detach().asInstanceOf[Element])
      else
        Document(newRoot.createCopy)
    copyMissingNamespaces(parentElemOpt, document.getRootElement)
    document
  }

  private val XmlNamespaceMap = Map(XMLConstants.XML_PREFIX -> XMLConstants.XML_URI)

  def copyMissingNamespaces(sourceElem: Option[Element], destinationElement: Element) {

    val parentNamespaceContext =
      sourceElem map (_.allInScopeNamespacesAsStrings) getOrElse XmlNamespaceMap

    val rootElementNamespaceContext =
      destinationElement.allInScopeNamespacesAsStrings

    for ((prefix, uri) <- parentNamespaceContext)
      if (! rootElementNamespaceContext.contains(prefix))
        destinationElement.addNamespace(prefix, uri)
  }

  /**
    * Return a new document with a copy of newRoot as its root and all parent namespaces copied to the new root
    * element, except those with the prefixes appearing in the Map, assuming they are not already declared on the new
    * root element.
    */
  def createDocumentCopyParentNamespaces(newRoot: Element, prefixesToFilter: ju.Set[String]): Document = {
    val document = Document(newRoot.createCopy)
    val rootElement = document.getRootElement
    val parentElement = newRoot.getParent
    val parentNamespaceContext = parentElement.allInScopeNamespacesAsStrings
    val rootElemNamespaceContext = rootElement.allInScopeNamespacesAsStrings
    for ((prefix, uri) <- parentNamespaceContext) {
      if (! rootElemNamespaceContext.contains(prefix) && ! prefixesToFilter.contains(prefix))
        rootElement.addNamespace(prefix, uri)
    }
    document
  }

  /**
    * Return a copy of the given element which includes all the namespaces in scope on the element.
    *
    * @param sourceElem element to copy
    * @return copied element
    */
  def copyElementCopyParentNamespaces(sourceElem: Element): Element = {
    val newElement = sourceElem.createCopy
    copyMissingNamespaces(Option(sourceElem.getParent), newElement)
    newElement
  }

  /**
    * Workaround for Java's lack of an equivalent to C's __FILE__ and __LINE__ macros.  Use
    * carefully as it is not fast.
    *
    * Perhaps in 1.5 we will find a better way.
    *
    * @return LocationData of caller.
    */
  def getLocationData: LocationData =
    getLocationData(1, isDebug = false)

  private def getLocationData(depth: Int, isDebug: Boolean): LocationData = {
    // Enable this with a property for debugging only, as it is time consuming
    if (
      ! isDebug &&
      ! Properties.instance.getPropertySet.getBoolean("oxf.debug.enable-java-location-data", default = false)
    ) return null

    // Compute stack trace and extract useful information
    val e = new Exception
    val stkTrc = e.getStackTrace
    val depthToUse = depth + 1
    val sysID = stkTrc(depthToUse).getFileName
    val line = stkTrc(depthToUse).getLineNumber

    new LocationData(sysID, line, -1)
  }

  /**
    * Visit a subtree of a dom4j document.
    *
    * @param container       element containing the elements to visit
    * @param visitorListener listener to call back
    */
  def visitSubtree(container: Element, visitorListener: VisitorListener): Unit =
    visitSubtree(container, visitorListener, mutable = false)

  /**
    * Visit a subtree of a dom4j document.
    *
    * @param container       element containing the elements to visit
    * @param visitorListener listener to call back
    * @param mutable         whether the source tree can mutate while being visited
    */
  def visitSubtree(container: Element, visitorListener: VisitorListener, mutable: Boolean): Unit = {

    // If the source tree can mutate, copy the list first, otherwise dom4j might throw exceptions
    val immutableContent =
      if (mutable)
        List(container.content)
      else
        container.content

    // Iterate over the content
    for (childNode <- immutableContent) {
      childNode match {
        case childElem: Element =>
          visitorListener.startElement(childElem)
          visitSubtree(childElem, visitorListener, mutable)
          visitorListener.endElement(childElem)
        case text: Text => visitorListener.text(text)
        case _ =>
        // Ignore as we don't need other node types for now
      }
    }
  }

  def createDocument(debugXML: DebugXML): Document = {

    val identity = TransformerUtils.getIdentityTransformerHandler
    val result = new LocationDocumentResult
    identity.setResult(result)

    val helper = new XMLReceiverHelper(
      new ForwardingXMLReceiver(identity) {
        override def startDocument(): Unit = ()
        override def endDocument(): Unit = ()
      }
    )

    identity.startDocument()
    debugXML.toXML(helper)
    identity.endDocument()

    result.getDocument
  }

  trait VisitorListener {
    def startElement(element: Element)
    def endElement(element: Element)
    def text(text: Text)
  }

  trait DebugXML {
    def toXML(helper: XMLReceiverHelper): Unit
  }
}