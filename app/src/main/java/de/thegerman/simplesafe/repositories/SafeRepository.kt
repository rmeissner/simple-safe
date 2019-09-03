package de.thegerman.simplesafe.repositories

import android.content.Context
import de.thegerman.simplesafe.BuildConfig
import de.thegerman.simplesafe.Compound
import de.thegerman.simplesafe.GnosisSafe
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.data.JsonRpcApi
import de.thegerman.simplesafe.data.RelayServiceApi
import de.thegerman.simplesafe.data.models.EstimateParams
import de.thegerman.simplesafe.data.models.ExecuteParams
import de.thegerman.simplesafe.data.models.RelaySafeCreationParams
import de.thegerman.simplesafe.data.models.ServiceSignature
import de.thegerman.simplesafe.repositories.SafeRepository.Safe
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

    fun isInitialized(): Boolean

    suspend fun loadSafe(): Safe

    suspend fun loadMnemonic(): String

    suspend fun checkStatus(): Safe.Status

    suspend fun loadSafeBalances(): SafeBalances

    suspend fun triggerSafeDeployment()

    suspend fun submitSafeTransaction(
        transaction: SafeTx,
        execInfo: SafeTxExecInfo
    ): String

    suspend fun recover(safe: Solidity.Address, mnemonic: String)

    suspend fun safeTransactionExecInfo(transaction: SafeTx): SafeTxExecInfo

    suspend fun checkPendingTransaction(): TxStatus?

    suspend fun loadModules(): List<SafeModule>

    fun getPendingTransactionHash(): String?

    fun addToReferenceBalance(value: BigInteger)

    fun removeFromReferenceBalance(value: BigInteger)

    data class Safe(val address: Solidity.Address, val status: Status) {
        sealed class Status {
            object Ready : Status()
            object Unknown : Status()
            data class Unfunded(val paymentAmount: BigInteger) : Status()
            data class Deploying(val transactionHash: String) : Status()
        }
    }

    data class SafeModule(val address: Solidity.Address, val masterCopy: Solidity.Address)

    data class SafeBalances(val daiBalance: BigInteger, val cdaiBalance: BigInteger, val referenceBalance: BigInteger)

    data class SafeTx(
        val to: Solidity.Address,
        val value: BigInteger,
        val data: String,
        val operation: Operation
    ) {

        enum class Operation(val id: Int) {
            CALL(0),
            DELEGATE(1)
        }
    }

    data class SafeTxExecInfo(
        val baseGas: BigInteger,
        val txGas: BigInteger,
        val gasPrice: BigInteger,
        val gasToken: Solidity.Address,
        val nonce: BigInteger
    )

    sealed class TxStatus {
        abstract val hash: String

        data class Pending(override val hash: String) : TxStatus()
        data class Success(override val hash: String) : TxStatus()
        data class Failed(override val hash: String) : TxStatus()
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

    override fun isInitialized(): Boolean =
        accountPrefs.getString(PREF_KEY_APP_MNEMONIC, null) != null

    override suspend fun recover(safe: Solidity.Address, mnemonic: String) {
        enforceEncryption()
        accountPrefs.edit {
            bip39.validateMnemonic(mnemonic)
            putString(PREF_KEY_APP_MNEMONIC, encryptionManager.encrypt(mnemonic.toByteArray(Charset.defaultCharset())).toString())
            putString(PREF_KEY_SAFE_ADDRESS, safe.asEthereumAddressString())
        }
    }

    private suspend fun enforceEncryption() {
        if (!encryptionManager.initialized().await() && !encryptionManager.setupPassword(ENC_PASSWORD.toByteArray()).await())
            throw RuntimeException("Could not setup encryption")
    }

    override suspend fun loadSafe(): Safe {
        enforceEncryption()
        return Safe(getSafeAddress(), getSafeStatus())
    }

    override suspend fun loadMnemonic(): String {
        encryptionManager.unlockWithPassword(ENC_PASSWORD.toByteArray()).await()
        return (accountPrefs.getString(PREF_KEY_APP_MNEMONIC, null) ?: run {
            val generateMnemonic =
                encryptionManager.encrypt(bip39.generateMnemonic(languageId = R.id.english).toByteArray(Charset.defaultCharset())).toString()
            accountPrefs.edit { putString(PREF_KEY_APP_MNEMONIC, generateMnemonic) }
            generateMnemonic
        }).let {
            encryptionManager.decrypt(EncryptionManager.CryptoData.fromString(it)).toString(Charset.defaultCharset())
        }
    }

    private suspend fun getSafeAddress() =
        accountPrefs.getString(PREF_KEY_SAFE_ADDRESS, null)?.asEthereumAddress() ?: run {
            val address = getKeyPair().address.toAddress()
            val creationParams = relayServiceApi.safeCreation(
                RelaySafeCreationParams(
                    listOf(address),
                    1,
                    System.currentTimeMillis(),
                    BuildConfig.PAYMENT_TOKEN_ADDRESS?.asEthereumAddress() ?: BuildConfig.DAI_ADDRESS.asEthereumAddress()!!
                )
            )
            // TODO: check response
            accountPrefs.edit {
                putString(PREF_KEY_SAFE_ADDRESS, creationParams.safe.asEthereumAddressString())
                putString(PREF_KEY_SAFE_PAYMENT_AMOUNT, creationParams.payment.toHexString())
            }
            creationParams.safe
        }

    private suspend fun getKeyPair(): KeyPair {
        val seed = bip39.mnemonicToSeed(loadMnemonic())
        val hdNode = KeyGenerator.masterNode(ByteString.of(*seed))
        return hdNode.derive(KeyGenerator.BIP44_PATH_ETHEREUM).deriveChild(0).keyPair
    }

    private fun getSafeStatus() =
        accountPrefs.getString(PREF_KEY_SAFE_CREATION_TX, null)?.let { Safe.Status.Deploying(it) }
            ?: accountPrefs.getString(PREF_KEY_SAFE_PAYMENT_AMOUNT, null)?.let { Safe.Status.Unfunded(it.hexAsBigInteger()) }
            ?: accountPrefs.getString(PREF_KEY_SAFE_BLOCK, null)?.let { Safe.Status.Ready }
            ?: Safe.Status.Unknown

    override suspend fun checkStatus(): Safe.Status =
        when (val status = getSafeStatus()) {
            is Safe.Status.Ready -> Safe.Status.Ready
            is Safe.Status.Unknown, is Safe.Status.Unfunded, is Safe.Status.Deploying -> checkRelay(status)
        }

    private suspend fun checkRelay(currentStatus: Safe.Status): Safe.Status {
        val status = relayServiceApi.safeFundStatus(getSafeAddress().asEthereumAddressChecksumString())
        accountPrefs.edit { putString(PREF_KEY_SAFE_CREATION_TX, status.txHash) }
        accountPrefs.edit { putString(PREF_KEY_SAFE_BLOCK, status.blockNumber?.toString()) }
        return when {
            status.txHash == null -> currentStatus
            status.blockNumber == null -> Safe.Status.Deploying(status.txHash)
            else -> Safe.Status.Ready
        }
    }

    override fun getPendingTransactionHash(): String? =
        accountPrefs.getString(PREF_KEY_PENDING_TRANSACTION_HASH, null)

    override suspend fun checkPendingTransaction(): SafeRepository.TxStatus? {
        val txHash = getPendingTransactionHash() ?: return null
        val response = jsonRpcApi.receipt(JsonRpcApi.JsonRpcRequest(method = "eth_getTransactionReceipt", params = listOf(txHash)))
        response.error?.let { throw RuntimeException(response.error.message) }
        val receipt = response.result ?: return SafeRepository.TxStatus.Pending(txHash)
        accountPrefs.edit { remove(PREF_KEY_PENDING_TRANSACTION_HASH) }
        if (receipt.status == BigInteger.ZERO) return SafeRepository.TxStatus.Failed(txHash)
        receipt.logs.forEach {
            if (it.topics.firstOrNull() == GnosisSafe.Events.ExecutionFailed.EVENT_ID) return SafeRepository.TxStatus.Failed(txHash)
        }
        return SafeRepository.TxStatus.Success(txHash)
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
        val cdaiBalanceValue = cdaiBalance.await()
        val referenceBalance = accountPrefs.getString(PREF_KEY_REFERENCE_BALANCE, null)?.hexAsBigIntegerOrNull() ?: run {
            accountPrefs.edit { putString(PREF_KEY_REFERENCE_BALANCE, cdaiBalanceValue.toHexString()) }
            cdaiBalanceValue
        }
        return SafeRepository.SafeBalances(daiBalance.await(), cdaiBalanceValue, referenceBalance)
    }

    override suspend fun loadModules(): List<SafeRepository.SafeModule> {
        val safeAddress = getSafeAddress()
        val modules = GnosisSafe.GetModules.decode(
            jsonRpcApi.post(
                JsonRpcApi.JsonRpcRequest(
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safeAddress,
                            "data" to GnosisSafe.GetModules.encode()
                        ), "latest"
                    )
                )
            ).result!!
        ).param0.items

        val masterCopies = jsonRpcApi.post(modules.mapIndexed { index, address ->
            JsonRpcApi.JsonRpcRequest(
                id = index,
                method = "eth_getStorageAt",
                params = listOf(address.asEthereumAddressString(), BigInteger.ZERO.toHexString(), "latest")
            )
        })

        return masterCopies.mapNotNull {
            val module = modules.getOrNull(it.id) ?: return@mapNotNull null
            val masterCopy = it.result?.asEthereumAddress() ?: return@mapNotNull null
            SafeRepository.SafeModule(module, masterCopy)
        }
    }

    override fun addToReferenceBalance(value: BigInteger) {
        val prev = accountPrefs.getString(PREF_KEY_REFERENCE_BALANCE, null)?.hexAsBigIntegerOrNull() ?: return
        accountPrefs.edit { putString(PREF_KEY_REFERENCE_BALANCE, (prev + value).toHexString()) }
    }

    override fun removeFromReferenceBalance(value: BigInteger) {
        val prev = accountPrefs.getString(PREF_KEY_REFERENCE_BALANCE, null)?.hexAsBigIntegerOrNull() ?: return
        val new = (prev - value)
        if (new < BigInteger.ZERO)
            accountPrefs.edit { remove(PREF_KEY_REFERENCE_BALANCE) }
        else
            accountPrefs.edit { putString(PREF_KEY_REFERENCE_BALANCE, (prev - value).toHexString()) }
    }

    override suspend fun triggerSafeDeployment() {
        relayServiceApi.notifySafeFunded(getSafeAddress().asEthereumAddressChecksumString())
    }

    override suspend fun safeTransactionExecInfo(
        transaction: SafeRepository.SafeTx
    ): SafeRepository.SafeTxExecInfo {
        val safeAddress = getSafeAddress()
        val gasToken = BuildConfig.DAI_ADDRESS.asEthereumAddress()!!
        val estimate = relayServiceApi.estimate(
            safeAddress.asEthereumAddressChecksumString(), EstimateParams(
                transaction.to.asEthereumAddressChecksumString(),
                transaction.value.asDecimalString(),
                transaction.data,
                transaction.operation.id,
                1,
                gasToken
            )
        )
        return SafeRepository.SafeTxExecInfo(
            estimate.dataGas.decimalAsBigInteger(),
            estimate.safeTxGas.decimalAsBigInteger(),
            estimate.gasPrice.decimalAsBigInteger(),
            estimate.gasToken,
            estimate.lastUsedNonce?.decimalAsBigInteger()?.let { it + BigInteger.ONE } ?: BigInteger.ZERO
        )
    }

    override suspend fun submitSafeTransaction(
        transaction: SafeRepository.SafeTx,
        execInfo: SafeRepository.SafeTxExecInfo
    ): String {
        val safeAddress = getSafeAddress()
        println("estimate $execInfo")
        val nonce = execInfo.nonce
        val hash =
            calculateHash(
                safeAddress,
                transaction.to,
                transaction.value,
                transaction.data,
                transaction.operation,
                execInfo.txGas,
                execInfo.baseGas,
                execInfo.gasPrice,
                execInfo.gasToken,
                nonce
            )

        val signature = getKeyPair().sign(hash)
        val response = relayServiceApi.execute(
            safeAddress.asEthereumAddressChecksumString(),
            ExecuteParams(
                transaction.to.asEthereumAddressChecksumString(),
                transaction.value.asDecimalString(),
                transaction.data,
                transaction.operation.id,
                listOf(ServiceSignature.fromSignature(signature)),
                execInfo.txGas.asDecimalString(),
                execInfo.baseGas.asDecimalString(),
                execInfo.gasPrice.asDecimalString(),
                execInfo.gasToken.asEthereumAddressChecksumString(),
                nonce.toLong()
            )
        )
        println("execute $response")
        accountPrefs.edit { putString(PREF_KEY_PENDING_TRANSACTION_HASH, response.transactionHash) }
        return response.transactionHash
    }

    private fun calculateHash(
        safeAddress: Solidity.Address,
        txTo: Solidity.Address,
        txValue: BigInteger,
        txData: String?,
        txOperation: SafeRepository.SafeTx.Operation,
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
        private const val PREF_KEY_SAFE_BLOCK = "accounts.string.safe_block"
        private const val PREF_KEY_SAFE_CREATION_TX = "accounts.string.safe_creation_tx"

        private const val PREF_KEY_REFERENCE_BALANCE = "accounts.string.reference_balance"
        private const val PREF_KEY_PENDING_TRANSACTION_HASH = "accounts.string.pending_transaction_hash"

        private const val ENC_PASSWORD = "ThisShouldNotBeHardcoded"
    }
}
