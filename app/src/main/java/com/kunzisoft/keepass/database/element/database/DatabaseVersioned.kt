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
 *
 */
package com.kunzisoft.keepass.database.element.database

import com.kunzisoft.encrypt.HashManager
import com.kunzisoft.keepass.database.crypto.EncryptionAlgorithm
import com.kunzisoft.keepass.database.element.binary.AttachmentPool
import com.kunzisoft.keepass.database.element.binary.BinaryCache
import com.kunzisoft.keepass.database.element.entry.EntryVersioned
import com.kunzisoft.keepass.database.element.group.GroupVersioned
import com.kunzisoft.keepass.database.element.icon.IconImageStandard
import com.kunzisoft.keepass.database.element.icon.IconsManager
import com.kunzisoft.keepass.database.element.node.NodeId
import com.kunzisoft.keepass.database.element.node.Type
import com.kunzisoft.keepass.database.exception.DuplicateUuidDatabaseException
import org.apache.commons.codec.binary.Hex
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.util.*

abstract class DatabaseVersioned<
        GroupId,
        EntryId,
        Group : GroupVersioned<GroupId, EntryId, Group, Entry>,
        Entry : EntryVersioned<GroupId, EntryId, Group, Entry>
        > {

    // Algorithm used to encrypt the database
    protected var algorithm: EncryptionAlgorithm? = null

    abstract val kdfEngine: com.kunzisoft.keepass.database.crypto.kdf.KdfEngine?

    abstract val kdfAvailableList: List<com.kunzisoft.keepass.database.crypto.kdf.KdfEngine>

    var masterKey = ByteArray(32)
    var finalKey: ByteArray? = null
        protected set

    /**
     * To manage binaries in faster way
     * Cipher key generated when the database is loaded, and destroyed when the database is closed
     * Can be used to temporarily store database elements
     */
    var binaryCache = BinaryCache()
    val iconsManager = IconsManager(binaryCache)
    var attachmentPool = AttachmentPool(binaryCache)

    var changeDuplicateId = false

    private var groupIndexes = LinkedHashMap<NodeId<GroupId>, Group>()
    protected var entryIndexes = LinkedHashMap<NodeId<EntryId>, Entry>()

    abstract val version: String

    protected abstract val passwordEncoding: String

    abstract var numberKeyEncryptionRounds: Long

    var encryptionAlgorithm: EncryptionAlgorithm
        get() {
            return algorithm ?: EncryptionAlgorithm.AESRijndael
        }
        set(algorithm) {
            this.algorithm = algorithm
        }

    abstract val availableEncryptionAlgorithms: List<EncryptionAlgorithm>

    var rootGroup: Group? = null
        set(value) {
            field = value
            value?.let {
                removeGroupIndex(it)
                addGroupIndex(it)
            }
        }

    fun getAllGroupsWithoutRoot(): List<Group> {
        return getGroupIndexes().filter { it != rootGroup }
    }

    @Throws(IOException::class)
    protected abstract fun deriveMasterKey(password: String?, keyInputStream: InputStream?, yubikeyResponse: ByteArray?): ByteArray

    @Throws(IOException::class)
    fun retrieveMasterKey(password: String?, keyfileInputStream: InputStream?, yubikeyResponse: ByteArray?) {
        masterKey = deriveMasterKey(password, keyfileInputStream, yubikeyResponse)
    }

    @Throws(IOException::class)
    protected fun getCompositeKey(password: String, keyfileInputStream: InputStream): ByteArray {
        val part1 = getPasswordKey(password)
        val part2 = getFileKey(keyfileInputStream)
        return HashManager.hashSha256(part1, part2)
    }

    protected fun getCompositeKey(password: String, yubikeyResponse: ByteArray): ByteArray {
        val part1 = getPasswordKey(password)
        val part2 = HashManager.hashSha256(yubikeyResponse)
        return HashManager.hashSha256(part1, part2)
    }

    @Throws(IOException::class)
    protected fun getPasswordKey(key: String): ByteArray {
        val bKey: ByteArray = try {
            key.toByteArray(charset(passwordEncoding))
        } catch (e: UnsupportedEncodingException) {
            key.toByteArray()
        }
        return HashManager.hashSha256(bKey)
    }

    @Throws(IOException::class)
    protected fun getFileKey(keyInputStream: InputStream): ByteArray {
        try {
            val keyData = keyInputStream.readBytes()

            // Check XML key file
            val xmlKeyByteArray = loadXmlKeyFile(ByteArrayInputStream(keyData))
            if (xmlKeyByteArray != null) {
                return xmlKeyByteArray
            }

            // Check 32 bytes key file
            when (keyData.size) {
                32 -> return keyData
                64 -> try {
                    return Hex.decodeHex(String(keyData).toCharArray())
                } catch (ignoredException: Exception) {
                    // Key is not base 64, treat it as binary data
                }
            }
            // Hash file as binary data
            return HashManager.hashSha256(keyData)
        } catch (outOfMemoryError: OutOfMemoryError) {
            throw IOException("Keyfile data is too large", outOfMemoryError)
        }
    }

    protected open fun loadXmlKeyFile(keyInputStream: InputStream): ByteArray? {
        return null
    }

    open fun validatePasswordEncoding(password: String?, containsKeyFile: Boolean): Boolean {
        if (password == null && !containsKeyFile)
            return false

        if (password == null)
            return true

        val encoding = passwordEncoding

        val bKey: ByteArray
        try {
            bKey = password.toByteArray(charset(encoding))
        } catch (e: UnsupportedEncodingException) {
            return false
        }

        val reEncoded: String
        try {
            reEncoded = String(bKey, charset(encoding))
        } catch (e: UnsupportedEncodingException) {
            return false
        }
        return password == reEncoded
    }

    /*
     * -------------------------------------
     *          Node Creation
     * -------------------------------------
     */

    abstract fun newGroupId(): NodeId<GroupId>

    abstract fun newEntryId(): NodeId<EntryId>

    abstract fun createGroup(): Group

    abstract fun createEntry(): Entry

    /*
     * -------------------------------------
     *          Index Manipulation
     * -------------------------------------
     */

    fun doForEachGroupInIndex(action: (Group) -> Unit) {
        for (group in groupIndexes) {
            action.invoke(group.value)
        }
    }

    /**
     * Determine if an id number is already in use
     *
     * @param id
     * ID number to check for
     * @return True if the ID is used, false otherwise
     */
    fun isGroupIdUsed(id: NodeId<GroupId>): Boolean {
        return groupIndexes.containsKey(id)
    }

    fun getGroupIndexes(): Collection<Group> {
        return groupIndexes.values
    }

    fun setGroupIndexes(groupList: List<Group>) {
        this.groupIndexes.clear()
        for (currentGroup in groupList) {
            this.groupIndexes[currentGroup.nodeId] = currentGroup
        }
    }

    fun getGroupById(id: NodeId<GroupId>): Group? {
        return this.groupIndexes[id]
    }

    fun addGroupIndex(group: Group) {
        val groupId = group.nodeId
        if (groupIndexes.containsKey(groupId)) {
            if (changeDuplicateId) {
                val newGroupId = newGroupId()
                group.nodeId = newGroupId
                group.parent?.addChildGroup(group)
                this.groupIndexes[newGroupId] = group
            } else {
                throw DuplicateUuidDatabaseException(Type.GROUP, groupId)
            }
        } else {
            this.groupIndexes[groupId] = group
        }
    }

    fun removeGroupIndex(group: Group) {
        this.groupIndexes.remove(group.nodeId)
    }

    fun numberOfGroups(): Int {
        return groupIndexes.size
    }

    fun doForEachEntryInIndex(action: (Entry) -> Unit) {
        for (entry in entryIndexes) {
            action.invoke(entry.value)
        }
    }

    fun isEntryIdUsed(id: NodeId<EntryId>): Boolean {
        return entryIndexes.containsKey(id)
    }

    fun getEntryIndexes(): Collection<Entry> {
        return entryIndexes.values
    }

    fun getEntryById(id: NodeId<EntryId>): Entry? {
        return this.entryIndexes[id]
    }

    fun addEntryIndex(entry: Entry) {
        val entryId = entry.nodeId
        if (entryIndexes.containsKey(entryId)) {
            if (changeDuplicateId) {
                val newEntryId = newEntryId()
                entry.nodeId = newEntryId
                entry.parent?.addChildEntry(entry)
                this.entryIndexes[newEntryId] = entry
            } else {
                throw DuplicateUuidDatabaseException(Type.ENTRY, entryId)
            }
        } else {
            this.entryIndexes[entryId] = entry
        }
    }

    fun removeEntryIndex(entry: Entry) {
        this.entryIndexes.remove(entry.nodeId)
    }

    fun numberOfEntries(): Int {
        return entryIndexes.size
    }

    open fun clearCache() {
        this.groupIndexes.clear()
        this.entryIndexes.clear()
    }

    /*
     * -------------------------------------
     *          Node Manipulation
     * -------------------------------------
     */

    abstract fun rootCanContainsEntry(): Boolean

    abstract fun getStandardIcon(iconId: Int): IconImageStandard

    fun addGroupTo(newGroup: Group, parent: Group?) {
        // Add tree to parent tree
        parent?.addChildGroup(newGroup)
        newGroup.parent = parent
        addGroupIndex(newGroup)
    }

    fun updateGroup(group: Group) {
        group.parent?.updateChildGroup(group)
        val groupId = group.nodeId
        if (groupIndexes.containsKey(groupId)) {
            groupIndexes[groupId] = group
        }
    }

    fun removeGroupFrom(groupToRemove: Group, parent: Group?) {
        // Remove tree from parent tree
        parent?.removeChildGroup(groupToRemove)
        removeGroupIndex(groupToRemove)
    }

    open fun addEntryTo(newEntry: Entry, parent: Group?) {
        // Add entry to parent
        parent?.addChildEntry(newEntry)
        newEntry.parent = parent
        addEntryIndex(newEntry)
    }

    open fun updateEntry(entry: Entry) {
        entry.parent?.updateChildEntry(entry)
        val entryId = entry.nodeId
        if (entryIndexes.containsKey(entryId)) {
            entryIndexes[entryId] = entry
        }
    }

    open fun removeEntryFrom(entryToRemove: Entry, parent: Group?) {
        // Remove entry from parent
        parent?.removeChildEntry(entryToRemove)
        removeEntryIndex(entryToRemove)
    }

    // TODO Delete group
    fun undoDeleteGroupFrom(group: Group, origParent: Group?) {
        addGroupTo(group, origParent)
    }

    open fun undoDeleteEntryFrom(entry: Entry, origParent: Group?) {
        addEntryTo(entry, origParent)
    }

    abstract fun isInRecycleBin(group: Group): Boolean

    fun isGroupSearchable(group: Group?, omitBackup: Boolean): Boolean {
        if (group == null)
            return false
        if (omitBackup && isInRecycleBin(group))
            return false
        return true
    }

    companion object {

        private const val TAG = "DatabaseVersioned"

        val UUID_ZERO = UUID(0, 0)
    }
}
