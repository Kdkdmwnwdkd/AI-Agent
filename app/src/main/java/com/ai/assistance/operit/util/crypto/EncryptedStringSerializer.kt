package com.ai.assistance.operit.util.crypto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 加密字符串序列化器：序列化(写入存储)时加密、反序列化(读取)时解密。
 *
 * 配合 [SecureStringCrypto] 的前缀识别实现向后兼容——旧的明文数据读取时原样返回，
 * 新的加密数据自动解密，二者可安全共存，无需数据迁移。
 */
object EncryptedStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("EncryptedString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(SecureStringCrypto.encrypt(value))
    }

    override fun deserialize(decoder: Decoder): String {
        return SecureStringCrypto.decrypt(decoder.decodeString())
    }
}
