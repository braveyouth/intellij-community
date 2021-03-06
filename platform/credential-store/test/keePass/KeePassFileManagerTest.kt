// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.credentialStore.keePass

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.KeePassCredentialStore
import com.intellij.testFramework.assertions.Assertions.assertThat
import com.intellij.testFramework.rules.InMemoryFsRule
import com.intellij.util.io.copy
import com.intellij.util.io.delete
import com.intellij.util.io.readText
import org.junit.Rule
import org.junit.Test

private val testCredentialAttributes = CredentialAttributes("foo", "bar")

internal class KeePassFileManagerTest {
  @JvmField
  @Rule
  val fsRule = InMemoryFsRule()

  private fun createTestStoreWithCustomMasterKey(): KeePassCredentialStore {
    val store = KeePassCredentialStore(baseDirectory = fsRule.fs.getPath("/"))
    store.set(testCredentialAttributes, Credentials("u", "p"))
    store.setMasterKey("foo")
    return store
  }

  @Test
  fun clear() {
    val store = createTestStoreWithCustomMasterKey()
    val dbFile = store.dbFile
    KeePassFileManager(store).clear()
    assertThat(dbFile).exists()
    assertThat(store.masterKeyFile).exists()
    assertThat(KeePassCredentialStore(store.dbFile, store.masterKeyFile).get(testCredentialAttributes)).isNull()
  }

  @Test
  fun `clear and remove if master password file doesn't exist`() {
    val store = createTestStoreWithCustomMasterKey()
    store.masterKeyFile.delete()
    val dbFile = store.dbFile
    KeePassFileManager(store).clear()
    assertThat(dbFile).doesNotExist()
    assertThat(store.masterKeyFile).doesNotExist()
  }

  @Test
  fun `clear and remove if master password file with incorrect master password`() {
    val store = createTestStoreWithCustomMasterKey()

    val oldMasterPasswordFile = store.masterKeyFile.parent.resolve("old.pwd")
    store.masterKeyFile.copy(oldMasterPasswordFile)
    store.setMasterKey("boo")
    oldMasterPasswordFile.copy(store.masterKeyFile)

    val dbFile = store.dbFile
    KeePassFileManager(store).clear()
    assertThat(dbFile).doesNotExist()
    assertThat(store.masterKeyFile).exists()
  }

  @Test
  fun `set master password - new database`() {
    val baseDirectory = fsRule.fs.getPath("/")
    KeePassFileManager(KeePassCredentialStore(baseDirectory = baseDirectory)).setMasterKey(MasterKey("boo".toByteArray(), encryption = EncryptionType.BUILT_IN))

    val store = KeePassCredentialStore(baseDirectory = baseDirectory)
    assertThat(store.dbFile).exists()
    assertThat(store.masterKeyFile.readText()).startsWith("""
      encryption: BUILT_IN
      value: !!binary
    """.trimIndent())
  }

  @Test
  fun `set master password - existing database with the same master password but incorrect master key file`() {
    KeePassFileManager(createTestStoreWithCustomMasterKey()).setMasterKey(MasterKey("foo".toByteArray()))

    val store = KeePassCredentialStore(baseDirectory = fsRule.fs.getPath("/"))
    assertThat(store.dbFile).exists()
    assertThat(store.masterKeyFile).exists()
  }
}

internal fun KeePassCredentialStore.setMasterKey(value: String) = setMasterPassword(MasterKey(value.toByteArray()))

@Suppress("TestFunctionName")
private fun KeePassFileManager(store: KeePassCredentialStore) = KeePassFileManager(store.dbFile, store.masterKeyFile)