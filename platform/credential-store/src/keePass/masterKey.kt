// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.credentialStore.keePass

import com.intellij.credentialStore.LOG
import com.intellij.credentialStore.windows.WindowsCryptUtils
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import com.intellij.openapi.util.io.setOwnerPermissions
import com.intellij.util.EncryptionSupport
import com.intellij.util.io.delete
import com.intellij.util.io.readBytes
import com.intellij.util.io.writeSafe
import org.yaml.snakeyaml.composer.Composer
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader
import org.yaml.snakeyaml.resolver.Resolver
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.Key
import java.util.*
import javax.crypto.spec.SecretKeySpec

internal const val MASTER_KEY_FILE_NAME = "c.pwd"
private const val OLD_MASTER_PASSWORD_FILE_NAME = "pdb.pwd"

internal interface MasterKeyStorage {
  fun get(): ByteArray?

  /**
   * [MasterKey.value] will be cleared on set
   */
  fun set(key: MasterKey?)
}

private class WindowsEncryptionSupport(key: Key): EncryptionSupport(key) {
  override fun encrypt(data: ByteArray, size: Int): ByteArray = WindowsCryptUtils.protect(super.encrypt(data, size))

  override fun decrypt(data: ByteArray): ByteArray = super.decrypt(WindowsCryptUtils.unprotect(data))
}

internal enum class EncryptionType {
  BUILT_IN, CRYPT_32
}

internal class MasterKey() {
  constructor(password: ByteArray, encryption: EncryptionType = getDefaultEncryptionType()) : this() {
    this.value = password
    this.encryption = encryption
  }

  @JvmField
  var encryption: EncryptionType? = null
  @JvmField
  var value: ByteArray? = null

  /**
   * Clear byte array to avoid sensitive data in memory
   */
  fun clear() {
    Arrays.fill(value!!, 0)
    value = null
  }
}

internal fun getDefaultEncryptionType() = if (SystemInfo.isWindows) EncryptionType.CRYPT_32 else EncryptionType.BUILT_IN

internal class MasterKeyFileStorage(private val passwordFile: Path) : MasterKeyStorage {
  companion object {
    private val builtInEncryptionKey = SecretKeySpec(byteArrayOf(
      0x50, 0x72, 0x6f.toByte(), 0x78.toByte(), 0x79.toByte(), 0x20.toByte(),
      0x43.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x66.toByte(), 0x69.toByte(), 0x67.toByte(),
      0x20.toByte(), 0x53.toByte(), 0x65.toByte(), 0x63.toByte()), "AES")

    private fun createEncryptionSupport(encryptionType: EncryptionType): EncryptionSupport {
      return when (encryptionType) {
        EncryptionType.BUILT_IN -> EncryptionSupport(builtInEncryptionKey)
        EncryptionType.CRYPT_32 -> {
          if (!SystemInfo.isWindows) {
            throw IllegalArgumentException("Crypt32 encryption is supported only on Windows")
          }
          WindowsEncryptionSupport(builtInEncryptionKey)
        }
      }
    }
  }

  private var encryptionType = getDefaultEncryptionType()

  override fun get(): ByteArray? {
    var data: ByteArray
    var isOld = false
    try {
      data = passwordFile.readBytes()
    }
    catch (e: NoSuchFileException) {
      try {
        data = passwordFile.parent.resolve(OLD_MASTER_PASSWORD_FILE_NAME).readBytes()
      }
      catch (e: NoSuchFileException) {
        return null
      }
      isOld = true
    }

    try {
      val masterKey: MasterKey
      if (isOld) {
        masterKey = MasterKey()
        masterKey.encryption = getDefaultEncryptionType()
        masterKey.value = data
      }
      else {
        val composer = Composer(ParserImpl(StreamReader(data.inputStream().reader())), object : Resolver() {
          override fun resolve(kind: NodeId, value: String?, implicit: Boolean): Tag {
            return when (kind) {
              NodeId.scalar -> Tag.STR
              else -> super.resolve(kind, value, implicit)
            }
          }
        })
        val list = (composer.singleNode as? MappingNode)?.value ?: emptyList()
        masterKey = MasterKey()
        for (node in list) {
          val keyNode = node.keyNode
          val valueNode = node.valueNode
          if (keyNode is ScalarNode && valueNode is ScalarNode) {
            when (keyNode.value) {
              "encryption" -> masterKey.encryption = EncryptionType.valueOf(valueNode.value.toUpperCase())
              "value" -> masterKey.value = Base64.getDecoder().decode(valueNode.value)
            }
          }
        }
        if (masterKey.encryption == null) {
          LOG.error("encryption type not specified in $passwordFile, default one will be used (file content:\n${data.toString(Charsets.UTF_8)})")
          masterKey.encryption = getDefaultEncryptionType()
        }
        if (masterKey.value == null) {
          LOG.error("password not specified in $passwordFile, automatically generated will be used (file content:\n${data.toString(Charsets.UTF_8)})")
          return null
        }
      }

      encryptionType = masterKey.encryption!!
      return createEncryptionSupport(encryptionType).decrypt(masterKey.value!!)
    }
    catch (e: Exception) {
      LOG.warn("Cannot decrypt master key, file content:\n${if (isOld) Base64.getEncoder().encodeToString(data) else data.toString(Charsets.UTF_8)}", e)
      return null
    }
  }

  override fun set(key: MasterKey?) {
    if (key == null) {
      passwordFile.delete()
      return
    }

    val out = BufferExposingByteArrayOutputStream()
    val encryptionType = key.encryption!!
    out.writer().use {
      it.append("encryption: ").append(encryptionType.name).append('\n')
      it.append("value: !!binary ")
    }
    out.write(Base64.getEncoder().encode(createEncryptionSupport(encryptionType).encrypt(key.value!!)))

    key.clear()

    passwordFile.writeSafe(out.internalBuffer, 0, out.size())
    passwordFile.setOwnerPermissions()
  }
}