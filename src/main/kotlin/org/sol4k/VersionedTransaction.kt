package org.sol4k

import okio.Buffer
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import kotlin.math.max

class VersionedTransaction(
    val message: Message,
    val signatures: MutableList<String>,
) {

    fun sign(keypair: Keypair) {
        val data = message.serialize()
        val signature = keypair.sign(data)
        for (i in 0 until message.header.numRequireSignatures) {
            val a = message.accounts[i]
            if (a.verify(signature, data)) {
                if (signatures.isEmpty()) {
                    signatures.add(Base58.encode(signature))
                } else {
                    signatures[i] = Base58.encode(signature)
                }
                break
            }
        }
    }

    fun serialize(): ByteArray {
        if (signatures.isEmpty() || signatures.size != message.header.numRequireSignatures) {
            throw Exception("Signature verification failed")
        }

        val messageData = message.serialize()

        val b = Buffer()
        b.write(Binary.encodeLength(signatures.size))
        for (s in signatures) {
            b.write(Base58.decode(s))
        }
        b.write(messageData)
        return b.readByteArray()
    }

    fun calcFee(): BigDecimal {
        val sigFee = lamportToSol(BigDecimal(5000 * max(signatures.size, 1)))
        val accounts = message.accounts
        val data = mutableListOf<ByteArray>()
        for (i in message.instructions) {
            if (accounts[i.programIdIndex] != Constants.COMPUTE_BUDGET__PROGRAM_ID) {
                continue
            }
            data.add(i.data)
        }
        val msgFee = computeBudget(data)
        return sigFee.add(msgFee).setScale(9, RoundingMode.CEILING)
    }

    companion object {
        const val PUBLIC_KEY_LENGTH = 32
        private const val SIGNATURE_LENGTH = 64

        @JvmStatic
        fun from(encodedTransaction: String): VersionedTransaction {
            var byteArray = Base64.getDecoder().decode(encodedTransaction)
            val signaturesDecodedLength = Binary.decodeLength(byteArray)
            byteArray = signaturesDecodedLength.bytes
            val signatures = mutableListOf<String>()
            for (i in 0 until signaturesDecodedLength.length) {
                val signature = byteArray.slice(0 until SIGNATURE_LENGTH)
                byteArray = byteArray.drop(SIGNATURE_LENGTH).toByteArray()
                val encodedSignature = Base58.encode(signature.toByteArray())
                signatures.add(encodedSignature)
            }

            val message = Message.deserialize(byteArray)

            if (signaturesDecodedLength.length > 0 && message.header.numRequireSignatures != signaturesDecodedLength.length) {
                throw Exception("numRequireSignatures is not equal to signatureCount")
            }
            return VersionedTransaction(message, signatures)
        }
    }
}
