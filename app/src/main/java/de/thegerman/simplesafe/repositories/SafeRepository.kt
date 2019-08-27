package de.thegerman.simplesafe.repositories

import android.content.Context
import de.thegerman.simplesafe.BuildConfig
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.data.JsonRpcApi
import de.thegerman.simplesafe.data.RelayServiceApi
import de.thegerman.simplesafe.data.models.RelaySafeCreationParams
import de.thegerman.simplesafe.repositories.SafeRepository.Safe
import de.thegerman.simplesafe.Compound
import de.thegerman.simplesafe.data.models.EstimateParams
import de.thegerman.simplesafe.data.models.ExecuteParams
import de.thegerman.simplesafe.data.models.ServiceSignature
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.rx2.await
import okio.ByteString
import pm.gnosis.crypto.KeyGenerator
import pm.gnosis.crypto.KeyPair
import pm.gnosis.crypto.utils.Sha3Utils
import pm.gnosis.crypto.utils.asEthereumAddressChecksumString
import pm.gnosis.mnemonic.Bip39
import pm.gnosis.model.Solidity
import pm.gnosis.svalinn.common.utils.edit
import pm.gnosis.svalinn.security.EncryptionManager
import pm.gnosis.utils.*
import java.math.BigInteger
import java.nio.charset.Charset

interface SafeRepository {
    suspend fun loadSafe(): Safe

    suspend fun checkStatus(): Safe.Status

    suspend fun loadSafeBalances(): SafeBalances

    suspend fun triggerSafeDeployment()

    suspend fun submitSafeTransaction(to: Solidity.Address, value: BigInteger, data: String, operation: SafeTxOperation): String

    data class Safe(val address: Solidity.Address, val status: Status) {
        sealed class Status {
            object Ready : Status()
            data class Unfunded(val paymentAmount: BigInteger) : Status()
            data class Deploying(val transactionHash: String) : Status()
        }
    }

    data class SafeBalances(val daiBalance: BigInteger, val cdaiBalance: BigInteger)

    enum class SafeTxOperation(val id: Int) {
        CALL(0),
        DELEGATE(1)
    }
}

class SafeRepositoryImpl(
    context: Context,
    private val bip39: Bip39,
    private val encryptionManager: EncryptionManager,
    private val jsonRpcApi: JsonRpcApi,
    private val relayServiceApi: RelayServiceApi
) : SafeRepository {

    private val accountPrefs = context.getSharedPreferences(ACC_PREF_NAME, Context.MODE_PRIVATE)

    override suspend fun loadSafe(): Safe {
        if (!encryptionManager.initialized().await() && !encryptionManager.setupPassword(ENC_PASSWORD.toByteArray()).await())
            throw RuntimeException("Could not setup encryption")
        return Safe(getSafeAddress(), getSafeStatus())
    }

    private fun getMnemonic() =
        (accountPrefs.getString(PREF_KEY_APP_MNEMONIC, null) ?: run {
            val generateMnemonic =
                encryptionManager.encrypt(bip39.generateMnemonic(languageId = R.id.english).toByteArray(Charset.defaultCharset())).toString()
            accountPrefs.edit { putString(PREF_KEY_APP_MNEMONIC, generateMnemonic) }
            generateMnemonic
        }).let {
            encryptionManager.decrypt(EncryptionManager.CryptoData.fromString(it)).toString(Charset.defaultCharset())
        }

    private suspend fun getSafeAddress() =
        accountPrefs.getString(PREF_KEY_SAFE_ADDRESS, null)?.asEthereumAddress() ?: run {
            val address = getKeyPair().address.toAddress()
            val creationParams = relayServiceApi.safeCreation(
                RelaySafeCreationParams(
                    listOf(address),
                    1,
                    System.currentTimeMillis(),
                    "0x0".asEthereumAddress()!!
                ) // DEBUG: BuildConfig.DAI_ADDRESS.asEthereumAddress()!!
            )
            // TODO: check response
            accountPrefs.edit {
                putString(PREF_KEY_SAFE_ADDRESS, creationParams.safe.asEthereumAddressString())
                putString(PREF_KEY_SAFE_PAYMENT_AMOUNT, creationParams.payment.toHexString())
            }
            creationParams.safe
        }

    private suspend fun getKeyPair(): KeyPair {
        encryptionManager.unlockWithPassword(ENC_PASSWORD.toByteArray()).await()
        val seed = bip39.mnemonicToSeed(getMnemonic())
        val hdNode = KeyGenerator.masterNode(ByteString.of(*seed))
        return hdNode.derive(KeyGenerator.BIP44_PATH_ETHEREUM).deriveChild(0).keyPair
    }

    private fun getSafeStatus() =
        accountPrefs.getString(PREF_KEY_SAFE_CREATION_TX, null)?.let { Safe.Status.Deploying(it) }
            ?: accountPrefs.getString(PREF_KEY_SAFE_PAYMENT_AMOUNT, null)?.let { Safe.Status.Unfunded(it.hexAsBigInteger()) }
            ?: Safe.Status.Ready

    override suspend fun checkStatus(): Safe.Status =
        getSafeStatus().let {
            when (getSafeStatus()) {
                is Safe.Status.Ready -> Safe.Status.Ready
                is Safe.Status.Unfunded, is Safe.Status.Deploying -> checkRelay(it)
            }
        }

    private suspend fun checkRelay(currentStatus: Safe.Status): Safe.Status {
        val status = relayServiceApi.safeFundStatus(getSafeAddress().asEthereumAddressChecksumString())
        accountPrefs.edit { putString(PREF_KEY_SAFE_CREATION_TX, status.txHash) }
        return if (status.txHash == null) currentStatus else
            if (status.blockNumber == null) Safe.Status.Deploying(status.txHash) else Safe.Status.Ready
    }

    override suspend fun loadSafeBalances(): SafeRepository.SafeBalances {
        val safeAddress = getSafeAddress()
        val daiBalance = GlobalScope.async {
            Compound.BalanceOfUnderlying.decode(
                jsonRpcApi.post(
                    JsonRpcApi.JsonRpcRequest(
                        method = "eth_call",
                        params = listOf(
                            mapOf(
                                "to" to BuildConfig.DAI_ADDRESS,
                                "data" to Compound.BalanceOf.encode(safeAddress)
                            ), "latest"
                        )
                    )
                ).result!!
            ).param0.value
        }
        val cdaiBalance = GlobalScope.async {
            Compound.BalanceOfUnderlying.decode(
                jsonRpcApi.post(
                    JsonRpcApi.JsonRpcRequest(
                        method = "eth_call",
                        params = listOf(
                            mapOf(
                                "to" to BuildConfig.CDAI_ADDRESS,
                                "data" to Compound.BalanceOfUnderlying.encode(safeAddress)
                            ), "latest"
                        )
                    )
                ).result!!
            ).param0.value
        }
        return SafeRepository.SafeBalances(daiBalance.await(), cdaiBalance.await())
    }

    override suspend fun triggerSafeDeployment() {
        relayServiceApi.notifySafeFunded(getSafeAddress().asEthereumAddressChecksumString())
    }

    override suspend fun submitSafeTransaction(
        to: Solidity.Address,
        value: BigInteger,
        data: String,
        operation: SafeRepository.SafeTxOperation
    ): String {
        val safeAddress = getSafeAddress()
        val gasToken = BuildConfig.DAI_ADDRESS.asEthereumAddress()!!
        val estimate = relayServiceApi.estimate(
            safeAddress.asEthereumAddressChecksumString(), EstimateParams(
                to.asEthereumAddressChecksumString(), value.asDecimalString(), data, operation.id, 1, gasToken
            )
        )
        println("estimate $estimate")
        val nonce = estimate.lastUsedNonce?.decimalAsBigInteger()?.let { it + BigInteger.ONE } ?: BigInteger.ZERO
        val hash =
            calculateHash(
                safeAddress,
                to,
                value,
                data,
                operation,
                estimate.safeTxGas.decimalAsBigInteger(),
                estimate.dataGas.decimalAsBigInteger(),
                estimate.gasPrice.decimalAsBigInteger(),
                estimate.gasToken,
                nonce
            )
        println("hash $hash")
        val signature = getKeyPair().sign(hash)
        val response = relayServiceApi.execute(
            safeAddress.asEthereumAddressChecksumString(),
            ExecuteParams(
                to.asEthereumAddressChecksumString(),
                value.asDecimalString(),
                data,
                operation.id,
                listOf(ServiceSignature.fromSignature(signature)),
                estimate.safeTxGas,
                estimate.dataGas,
                estimate.gasPrice,
                estimate.gasToken.asEthereumAddressChecksumString(),
                nonce.toLong()
            )
        )
        println("execute $response")
        return response.transactionHash
    }

    private fun calculateHash(
        safeAddress: Solidity.Address,
        txTo: Solidity.Address,
        txValue: BigInteger,
        txData: String?,
        txOperation: SafeRepository.SafeTxOperation,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address,
        txNonce: BigInteger
    ): ByteArray {
        val to = txTo.value.paddedHexString()
        val value = txValue.paddedHexString()
        val data = Sha3Utils.keccak(txData?.hexToByteArray() ?: ByteArray(0)).toHex().padStart(64, '0')
        val operationString = txOperation.id.toBigInteger().paddedHexString()
        val gasPriceString = gasPrice.paddedHexString()
        val txGasString = txGas.paddedHexString()
        val dataGasString = dataGas.paddedHexString()
        val gasTokenString = gasToken.value.paddedHexString()
        val refundReceiverString = BigInteger.ZERO.paddedHexString()
        val nonce = txNonce.paddedHexString()
        return hash(
            safeAddress,
            to,
            value,
            data,
            operationString,
            txGasString,
            dataGasString,
            gasPriceString,
            gasTokenString,
            refundReceiverString,
            nonce
        )
    }

    private fun hash(safeAddress: Solidity.Address, vararg parts: String): ByteArray {
        val initial = StringBuilder().append(ERC191_BYTE).append(ERC191_VERSION).append(domainHash(safeAddress)).append(valuesHash(parts))
        return Sha3Utils.keccak(initial.toString().hexToByteArray())
    }

    private fun domainHash(safeAddress: Solidity.Address) =
        Sha3Utils.keccak(
            ("0x035aff83d86937d35b32e04f0ddc6ff469290eef2f1b692d8a815c89404d4749" +
                    safeAddress.value.paddedHexString()).hexToByteArray()
        ).toHex()

    private fun valuesHash(parts: Array<out String>) =
        parts.fold(StringBuilder().append(getTypeHash())) { acc, part ->
            acc.append(part)
        }.toString().run {
            Sha3Utils.keccak(hexToByteArray()).toHex()
        }

    private fun BigInteger?.paddedHexString(padding: Int = 64) = (this?.toString(16) ?: "").padStart(padding, '0')

    private fun getTypeHash() = "0xbb8310d486368db6bd6f849402fdd73ad53d316b5a4b2644ad6efe0f941286d8"

    private fun ByteArray.toAddress() = Solidity.Address(this.asBigInteger())

    companion object {
        private const val ERC191_BYTE = "19"
        private const val ERC191_VERSION = "01"

        private const val ACC_PREF_NAME = "AccountRepositoryImpl_Preferences"
        private const val PREF_KEY_APP_MNEMONIC = "accounts.string.app_menmonic"
        private const val PREF_KEY_SAFE_ADDRESS = "accounts.string.safe_address"
        private const val PREF_KEY_SAFE_PAYMENT_AMOUNT = "accounts.string.safe_payment_amount"
        private const val PREF_KEY_SAFE_CREATION_TX = "accounts.string.safe_creation_tx"
        private const val ENC_PASSWORD = "ThisShouldNotBeHardcoded"
    }
}