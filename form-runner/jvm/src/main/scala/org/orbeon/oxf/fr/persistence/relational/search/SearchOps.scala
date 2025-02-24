/**
 * Copyright (C) 2016 Orbeon, Inc.
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
package org.orbeon.oxf.fr.persistence.relational.search

import org.orbeon.oxf.externalcontext.{Credentials, Organization, ParametrizedRole, UserAndGroup}
import org.orbeon.oxf.fr.permission.Operation.{Delete, Read, Update}
import org.orbeon.oxf.fr.permission.PermissionsAuthorization.CheckWithDataUser
import org.orbeon.oxf.fr.permission._
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.SimplePath._

import scala.collection.compat._

object SearchOps {

  val SearchOperations: Set[Operation] = Set(Read, Update, Delete)

  // Used by eXist only
  //@XPathFunction
  def xpathAuthorizedIfOrganizationMatch(formPermissionsElOrNull: NodeInfo): List[String] =
    authorizedIfOrganizationMatch(
      permissions = PermissionsXML.parse(Option(formPermissionsElOrNull)),
      credentialsOpt = PermissionsAuthorization.findCurrentCredentialsFromSession
    )

  def authorizedIfOrganizationMatch(
    permissions   : Permissions,
    credentialsOpt: Option[Credentials]
  ): List[String] = {
    val check  = PermissionsAuthorization.CheckAssumingOrganizationMatch
    val userParametrizedRoles = credentialsOpt.to(List).flatMap(_.roles).collect{ case role @ ParametrizedRole(_, _) => role }
    val usefulUserParametrizedRoles = userParametrizedRoles.filter(role => {
      val credentialsWithJustThisRoleOpt = credentialsOpt.map(_.copy(roles = List(role)))
      val authorizedOperations           = PermissionsAuthorization.authorizedOperations(permissions, credentialsWithJustThisRoleOpt, check)
      Operations.allowsAny(authorizedOperations, SearchOperations)
    } )
    usefulUserParametrizedRoles.map(_.organizationName)
  }

  // Used by eXist only
  //@XPathFunction
  def authorizedOperations(
    formPermissionsElOrNull : NodeInfo,
    metadataOrNullEl        : NodeInfo
  ): String = {

    val checkWithData = {

      def childValue(name: String): Option[String] =
        Option(metadataOrNullEl)
          .flatMap(_.firstChildOpt(name))
          .map(_.stringValue)

      val organization = {
        val levels = Option(metadataOrNullEl)
          .flatMap(_.firstChildOpt("organization"))
          .map(_.child("level").to(List).map(_.stringValue))
        levels.map(Organization.apply)
      }

      CheckWithDataUser(
        UserAndGroup.fromStrings(
          childValue("username").getOrElse(""),
          childValue("groupname").getOrElse("")
        ),
        organization
      )
    }

    val operations =
      PermissionsAuthorization.authorizedOperations(
        permissions           = PermissionsXML.parse(Option(formPermissionsElOrNull)),
        currentCredentialsOpt = PermissionsAuthorization.findCurrentCredentialsFromSession,
        check                 = checkWithData
      )

    Operations.serialize(operations, normalized = true).mkString(" ")
  }
}
