/*
 * Copyright 2020 Jeremy Jamet / Kunzisoft.
 *     
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.database.search

/**
 * Parameters for searching strings in the database.
 */
class SearchParameters {
    var searchQuery: String = ""

    var searchInTitles = true
    var searchInUserNames = true
    var searchInPasswords = false
    var searchInUrls = true
    var searchInNotes = true
    var searchInOTP = false
    var searchInOther = true
    var searchInUUIDs = false
    var searchInTags = true

    var searchInTemplates = false
}
