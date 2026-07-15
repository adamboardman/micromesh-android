package com.bitchat.android.model

import android.os.Parcelable
import com.bitchat.android.protocol.BinaryProtocol
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

@Parcelize
enum class BitchatMessageType : Parcelable {
    Message,
    Audio,
    Image,
    File
}

/**
 * Delivery status for messages - exact same as iOS version
 */
sealed class DeliveryStatus : Parcelable {
    @Parcelize
    object Sending : DeliveryStatus()

    @Parcelize
    object Sent : DeliveryStatus()

    @Parcelize
    data class Delivered(val to: String, val at: Date) : DeliveryStatus()

    @Parcelize
    data class Read(val by: String, val at: Date) : DeliveryStatus()

    @Parcelize
    data class Failed(val reason: String) : DeliveryStatus()

    @Parcelize
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus()

    fun getDisplayText(): String {
        return when (this) {
            is Sending -> "Sending..."
            is Sent -> "Sent"
            is Delivered -> "Delivered to ${this.to}"
            is Read -> "Read by ${this.by}"
            is Failed -> "Failed: ${this.reason}"
            is PartiallyDelivered -> "Delivered to ${this.reached}/${this.total}"
        }
    }
}

/**
 * BitchatMessage
 */
@Parcelize
data class BitchatMessage(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val senderNickname: String? = null,
    val content: String,
    val type: BitchatMessageType = BitchatMessageType.Message,
    val timestamp: Date, //we don't include this in the payload but expect it to be set from the packet
    val channel: String? = null,
    val deliveryStatus: DeliveryStatus? = null,
    val powDifficulty: Int? = null
) : Parcelable {

    private enum class TLVType(val value: UByte) {
        TLV_MESSAGE_ID(0x00u),
        TLV_MESSAGE_CONTENT(0x01u),

        // bump up values to avoid clash with upstream bitchat - if they adopt compatible concepts then we can migrate and use their types
        TLV_MESSAGE_SENDER_NICKNAME(0x7du), // it's useful for a channel to have direct access to the name associated with the message
        TLV_MESSAGE_CHANNEL_CONTENT(0x7eu), // to avoid regular bitchat clients showing our channel messages to everyone we omit the regular content
        TLV_MESSAGE_CHANNEL(0x7fu); // the #channel name

        companion object {
            fun fromValue(value: UByte): BitchatMessage.TLVType? {
                return BitchatMessage.TLVType.values().find { it.value == value }
            }
        }
    }

    /**
     * Convert message to binary payload format - exactly same as iOS version
     */
    fun toBinaryPayload(): ByteArray? {
        val result = mutableListOf<Byte>()

        val idData = id.toByteArray()
        if (idData.size > 255) return null
        result.add(TLVType.TLV_MESSAGE_ID.value.toByte())
        result.add(idData.size.toByte())
        result.addAll(idData.toList())

        val contentData = content.toByteArray(Charsets.UTF_8)
        if (contentData.size > 255) return null
        if (channel != null) {
            val channelData = channel.toByteArray(Charsets.UTF_8)
            if (channelData.size > 255) return null
            result.add(TLVType.TLV_MESSAGE_CHANNEL.value.toByte())
            result.add(channelData.size.toByte())
            result.addAll(channelData.toList())
            result.add(TLVType.TLV_MESSAGE_CHANNEL_CONTENT.value.toByte())
        } else {
            result.add(TLVType.TLV_MESSAGE_CONTENT.value.toByte())
        }
        result.add(contentData.size.toByte())
        result.addAll(contentData.toList())

        if (senderNickname != null) {
            // TLV for nickname
            val nicknameData = senderNickname.toByteArray(Charsets.UTF_8)
            if (nicknameData.size > 255) return null
            result.add(TLVType.TLV_MESSAGE_SENDER_NICKNAME.value.toByte())
            result.add(nicknameData.size.toByte())
            result.addAll(nicknameData.toList())
        }

        return result.toByteArray()
    }

    companion object {
        /**
         * Parse message from binary payload - exactly same logic as iOS version
         */
        fun fromBinaryPayload(data: ByteArray, timestamp: ULong): BitchatMessage? {
            // Create defensive copy
            val dataCopy = data.copyOf()

            var offset = 0
            var messageId: String? = null
            var messageContent: String? = null
            var messageSenderNickname: String? = null
            var messageChannel: String? = null

            while (offset + 2 <= dataCopy.size) {
                // Read TLV type
                val typeValue = dataCopy[offset].toUByte()
                val type = TLVType.fromValue(typeValue)
                offset += 1

                // Read TLV length
                val length = dataCopy[offset].toUByte().toInt()
                offset += 1

                // Check bounds
                if (offset + length > dataCopy.size) return null

                // Read TLV value
                val value = dataCopy.sliceArray(offset until offset + length)
                offset += length

                // Process known TLV types, skip unknown ones for forward compatibility
                when (type) {
                    TLVType.TLV_MESSAGE_ID -> {
                        messageId = String(value, Charsets.UTF_8)
                    }
                    TLVType.TLV_MESSAGE_CONTENT -> {
                        messageContent = String(value, Charsets.UTF_8)
                    }
                    TLVType.TLV_MESSAGE_SENDER_NICKNAME -> {
                        messageSenderNickname = String(value, Charsets.UTF_8)
                    }
                    TLVType.TLV_MESSAGE_CHANNEL -> {
                        messageChannel = String(value, Charsets.UTF_8)
                    }
                    TLVType.TLV_MESSAGE_CHANNEL_CONTENT -> {
                        // We assume that there will never be content and channel content in the same message
                        messageContent = String(value, Charsets.UTF_8)
                    }
                    null -> {
                        // Unknown TLV; skip (tolerant decoder for forward compatibility)
                        continue
                    }
                }
            }

            // Only id and content is required
            return if (messageId != null && messageContent != null) {
                BitchatMessage(
                    id = messageId,
                    content = messageContent,
                    type = BitchatMessageType.Message,
                    senderNickname = messageSenderNickname,
                    timestamp = java.util.Date(timestamp.toLong()),
                    channel = messageChannel,
                )
            } else {
                null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BitchatMessage

        if (id != other.id) return false
        if (senderNickname != other.senderNickname) return false
        if (content != other.content) return false
        if (type != other.type) return false
        if (channel != other.channel) return false
        if (deliveryStatus != other.deliveryStatus) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + senderNickname.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + (channel?.hashCode() ?: 0)
        result = 31 * result + (deliveryStatus?.hashCode() ?: 0)
        return result
    }
}


