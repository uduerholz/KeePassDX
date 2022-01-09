/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
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
 */

package com.kunzisoft.keepass.database.element.database

import com.kunzisoft.encrypt.HashManager
import com.kunzisoft.encrypt.aes.AESTransformer
import com.kunzisoft.keepass.database.crypto.EncryptionAlgorithm
import com.kunzisoft.keepass.database.crypto.kdf.KdfEngine
import com.kunzisoft.keepass.database.crypto.kdf.KdfFactory
import com.kunzisoft.keepass.database.element.binary.BinaryData
import com.kunzisoft.keepass.database.element.entry.EntryKDB
import com.kunzisoft.keepass.database.element.group.GroupKDB
import com.kunzisoft.keepass.database.element.icon.IconImageStandard
import com.kunzisoft.keepass.database.element.node.NodeIdInt
import com.kunzisoft.keepass.database.element.node.NodeIdUUID
import com.kunzisoft.keepass.database.element.node.NodeVersioned
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.collections.ArrayList

class DatabaseKDB : DatabaseVersioned<Int, UUID, GroupKDB, EntryKDB>() {

    private var kdfListV3: MutableList<KdfEngine> = ArrayList()

    override val version: String
        get() = "V1"

    init {
        // New manual root because KDB contains multiple root groups (here available with getRootGroups())
        rootGroup = createGroup().apply {
            icon.standard = getStandardIcon(IconImageStandard.DATABASE_ID)
        }
        kdfListV3.add(KdfFactory.aesKdf)
    }

    private fun getGroupById(groupId: Int): GroupKDB? {
        if (groupId == -1)
            return null
        return getGroupById(NodeIdInt(groupId))
    }

    val backupGroup: GroupKDB?
        get() {
            return retrieveBackup()
        }

    val groupNamesNotAllowed: List<String>
        get() {
            return listOf(BACKUP_FOLDER_TITLE)
        }

    override val kdfEngine: KdfEngine
        get() = kdfListV3[0]

    override val kdfAvailableList: List<KdfEngine>
        get() = kdfListV3

    override val availableEncryptionAlgorithms: List<EncryptionAlgorithm>
        get() {
            val list = ArrayList<EncryptionAlgorithm>()
            list.add(EncryptionAlgorithm.AESRijndael)
            list.add(EncryptionAlgorithm.Twofish)
            return list
        }

    val rootGroups: List<GroupKDB>
        get() {
            return rootGroup?.getChildGroups() ?: ArrayList()
        }

    override val passwordEncoding: String
        get() = "ISO-8859-1"

    override var numberKeyEncryptionRounds = 300L

    init {
        algorithm = EncryptionAlgorithm.AESRijndael
    }

    /**
     * Generates an unused random tree id
     *
     * @return new tree id
     */
    override fun newGroupId(): NodeIdInt {
        var newId: NodeIdInt
        do {
            newId = NodeIdInt()
        } while (isGroupIdUsed(newId))

        return newId
    }

    /**
     * Generates an unused random tree id
     *
     * @return new tree id
     */
    override fun newEntryId(): NodeIdUUID {
        var newId: NodeIdUUID
        do {
            newId = NodeIdUUID()
        } while (isEntryIdUsed(newId))

        return newId
    }

    @Throws(IOException::class)
    override fun deriveMasterKey(password: String?, keyInputStream: InputStream?, yubikeyResponse: ByteArray?): ByteArray {

        return if (password != null && keyInputStream != null) {
            getCompositeKey(password, keyInputStream)
        } else if (password != null && yubikeyResponse != null) {
            getCompositeKey(password, yubikeyResponse)
        } else if (password != null) {
            getPasswordKey(password)
        } else if (keyInputStream != null) {
            getFileKey(keyInputStream)
        } else {
            throw IllegalArgumentException("Key cannot be empty.")
        }
    }

    @Throws(IOException::class)
    fun makeFinalKey(masterSeed: ByteArray, transformSeed: ByteArray, numRounds: Long) {
        // Encrypt the master key a few times to make brute-force key-search harder
        val transformedKey = AESTransformer.transformKey(transformSeed, masterKey, numRounds) ?: ByteArray(0)
        // Write checksum Checksum
        finalKey = HashManager.hashSha256(masterSeed, transformedKey)
    }

    override fun createGroup(): GroupKDB {
        return GroupKDB()
    }

    override fun createEntry(): EntryKDB {
        return EntryKDB()
    }

    override fun rootCanContainsEntry(): Boolean {
        return false
    }

    override fun getStandardIcon(iconId: Int): IconImageStandard {
        return this.iconsManager.getIcon(iconId)
    }

    override fun isInRecycleBin(group: GroupKDB): Boolean {
        var currentGroup: GroupKDB? = group
        val currentBackupGroup = backupGroup ?: return false

        if (currentGroup == currentBackupGroup)
            return true

        val backupGroupId = currentBackupGroup.id
        while (currentGroup != null) {
            if (backupGroupId == currentGroup.id) {
                return true
            }
            currentGroup = currentGroup.parent
        }
        return false
    }

    /**
     * Retrieve backup group with his name
     */
    private fun retrieveBackup(): GroupKDB? {
        return rootGroup?.searchChildGroup {
            it.title.equals(BACKUP_FOLDER_TITLE, ignoreCase = true)
        }
    }

    /**
     * Ensure that the backup tree exists if enabled, and create it
     * if it doesn't exist
     */
    fun ensureBackupExists() {
        if (backupGroup == null) {
            // Create recycle bin
            val recycleBinGroup = createGroup().apply {
                title = BACKUP_FOLDER_TITLE
                icon.standard = getStandardIcon(IconImageStandard.TRASH_ID)
            }
            addGroupTo(recycleBinGroup, rootGroup)
        }
    }

    /**
     * Define if a Node must be delete or recycle when remove action is called
     * @param node Node to remove
     * @return true if node can be recycle, false elsewhere
     */
    fun canRecycle(node: NodeVersioned<*, GroupKDB, EntryKDB>): Boolean {
        if (backupGroup == null)
            ensureBackupExists()
        if (node == backupGroup)
            return false
        backupGroup?.let {
            if (node.isContainedIn(it))
                return false
        }
        return true
    }

    fun recycle(group: GroupKDB) {
        removeGroupFrom(group, group.parent)
        addGroupTo(group, backupGroup)
        group.afterAssignNewParent()
    }

    fun recycle(entry: EntryKDB) {
        removeEntryFrom(entry, entry.parent)
        addEntryTo(entry, backupGroup)
        entry.afterAssignNewParent()
    }

    fun undoRecycle(group: GroupKDB, origParent: GroupKDB) {
        removeGroupFrom(group, backupGroup)
        addGroupTo(group, origParent)
    }

    fun undoRecycle(entry: EntryKDB, origParent: GroupKDB) {
        removeEntryFrom(entry, backupGroup)
        addEntryTo(entry, origParent)
    }

    fun buildNewAttachment(): BinaryData {
        // Generate an unique new file
        return attachmentPool.put { uniqueBinaryId ->
            binaryCache.getBinaryData(uniqueBinaryId, false)
        }.binary
    }

    companion object {
        val TYPE = DatabaseKDB::class.java

        const val BACKUP_FOLDER_TITLE = "Backup"
    }
}
