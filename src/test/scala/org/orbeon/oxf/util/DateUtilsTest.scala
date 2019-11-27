/**
 * Copyright (C) 2012 Orbeon, Inc.
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
package org.orbeon.oxf.util

import org.scalatest.funspec.AnyFunSpec


class DateUtilsTest extends AnyFunSpec {

  describe("Date parsing") {

    val utcTimezones = List("+00:00", "-00:00", "Z")

    val valuesNoExplicitTz = List(
      "1970-01-01T00:00:00.000" → 0L,
      "1970-01-01T00:00:00"     → 0L,
      "1970-01-01"              → 0L,
      "2012-05-29T19:35:04.123" → 1338320104123L,
      "2012-05-29T19:35:04"     → 1338320104000L,
      "2012-05-29"              → 1338249600000L
    )

    // How many minutes to add to UTC time to get the local time
    val defaultTzOffsetMs = DateUtils.DefaultOffsetMinutes * 60 * 1000

    for ((value, expected) ← valuesNoExplicitTz)
      it(s"must parse `$value` with no explicit timezone") {
        assert(expected - defaultTzOffsetMs === DateUtils.parseISODateOrDateTime(value))
      }

    for {
      (value, expected) ← valuesNoExplicitTz
      utcTimezone       ← utcTimezones
      valueWithTz       = value + utcTimezone
    } locally {
      it(s"must parse `$value` with timezone `$utcTimezone`") {
        assert(expected === DateUtils.parseISODateOrDateTime(valueWithTz))
      }
    }

    // Test all 3 HTTP date formats
    for (value ← List("Tue, 29 May 2012 19:35:04 GMT", "Tuesday, 29-May-12 19:35:04 GMT", "Tue May 29 19:35:04 2012"))
      it(s"must parse `$value` in all formats") {
        assert(1338320104000L === DateUtils.parseRFC1123(value))
      }

  }

  describe("Date formatting") {

    val data = List(
      ("1970-01-01T00:00:00.000Z"     , "DateTime",    DateUtils.DateTime   , 0L),
      ("Thu, 01 Jan 1970 00:00:00 GMT", "RFC1123Date", DateUtils.RFC1123Date, 0L),
      ("2012-05-29T19:35:04.123Z"     , "DateTime",    DateUtils.DateTime   , 1338320104123L),
      ("Tue, 29 May 2012 19:35:04 GMT", "RFC1123Date", DateUtils.RFC1123Date, 1338320104123L)
    )

    for ((expected, formatterName, formatter, instant) ← data)
      it(s"must format instant `$instant` using formatter `$formatterName`") {
        assert(expected === formatter.print(instant))
      }
  }
}
