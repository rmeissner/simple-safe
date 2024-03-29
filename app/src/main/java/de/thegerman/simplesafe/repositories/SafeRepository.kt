package de.thegerman.simplesafe.repositories

import android.content.Context
import de.thegerman.simplesafe.BuildConfig
import de.thegerman.simplesafe.Compound
import de.thegerman.simplesafe.GnosisSafe
import de.thegerman.simplesafe.R
import de.thegerman.simplesafe.data.JsonRpcApi
import de.thegerman.simplesafe.data.RelayServiceApi
import de.thegerman.simplesafe.data.TransactionServiceApi
import de.thegerman.simplesafe.data.models.*
import de.thegerman.simplesafe.repositories.SafeRepository.Safe
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.rx2.await
import okio.ByteString
import org.walleth.khex.toHexString
import pm.gnosis.crypto.ECDSASignature
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

    suspend fun loadDeviceId(): Solidity.Address

    suspend fun loadSafe(): Safe

    suspend fun validateSafe(address: Solidity.Address): Boolean

    suspend fun joinSafe(address: Solidity.Address): Safe

    suspend fun loadMnemonic(): String

    suspend fun checkStatus(): Safe.Status

    suspend fun loadSafeBalances(): SafeBalances

    suspend fun triggerSafeDeployment()

    suspend fun confirmSafeTransaction(transaction: SafeTx, execInfo: SafeTxExecInfo, confirmations: List<Pair<Solidity.Address, String?>>?): String?

    suspend fun recover(safe: Solidity.Address, mnemonic: String)

    suspend fun safeTransactionExecInfo(transaction: SafeTx): SafeTxExecInfo

    suspend fun checkPendingTransaction(): TxStatus?

    suspend fun loadModules(): List<SafeModule>

    suspend fun loadSafeInfo(): SafeInfo

    suspend fun loadPendingTransactions(): List<PendingSafeTx>

    fun getPendingTransactionHash(): String?

    data class Safe(val address: Solidity.Address, val status: Status) {
        sealed class Status {
            object Ready : Status()
            object Unknown : Status()
            data class Unfunded(val paymentAmount: BigInteger) : Status()
            data class Deploying(val transactionHash: String) : Status()
        }
    }

    data class SafeInfo(
        val address: Solidity.Address,
        val masterCopy: Solidity.Address,
        val owners: List<Solidity.Address>,
        val threshold: BigInteger
    )

    data class SafeModule(val address: Solidity.Address, val masterCopy: Solidity.Address)

    data class SafeBalances(val daiBalance: BigInteger, val cdaiBalance: BigInteger, val referenceBalance: BigInteger)

    data class PendingSafeTx(
        val hash: String,
        val tx: SafeTx,
        val execInfo: SafeTxExecInfo,
        val confirmations: List<Pair<Solidity.Address, String?>>
    )

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
        val refundReceiver: Solidity.Address,
        val nonce: BigInteger
    ) {
        val fees by lazy { (baseGas + txGas) * gasPrice }
    }

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
    private val relayServiceApi: RelayServiceApi,
    private val transactionServiceApi: TransactionServiceApi
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

    override suspend fun loadDeviceId(): Solidity.Address {
        return getKeyPair().address.toAddress()
    }

    override suspend fun loadSafe(): Safe {
        enforceEncryption()
        return Safe(getSafeAddress(), getSafeStatus())
    }

    override suspend fun validateSafe(address: Solidity.Address) =
        relayServiceApi.safeFundStatus(address.asEthereumAddressChecksumString()).blockNumber != null

    override suspend fun joinSafe(address: Solidity.Address): Safe {
        enforceEncryption()
        accountPrefs.edit {
            putString(PREF_KEY_SAFE_ADDRESS, address.asEthereumAddressString())
        }
        return Safe(address, getSafeStatus())
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
        // TODO: convert to batched call
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
        val referenceBalance = getReferenceBalance(safeAddress)
        return SafeRepository.SafeBalances(daiBalance.await(), cdaiBalance.await(), referenceBalance.await())
    }

    private fun getReferenceBalance(safeAddress: Solidity.Address) = GlobalScope.async {
        // TODO parse events
        BigInteger.ONE
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

    override suspend fun loadSafeInfo(): SafeRepository.SafeInfo {
        val safeAddress = getSafeAddress()
        val responses = jsonRpcApi.post(
            listOf(
                JsonRpcApi.JsonRpcRequest(
                    id = 0,
                    method = "eth_getStorageAt",
                    params = listOf(safeAddress, BigInteger.ZERO.toHexString(), "latest")
                ),
                JsonRpcApi.JsonRpcRequest(
                    id = 1,
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safeAddress,
                            "data" to GnosisSafe.GetOwners.encode()
                        ), "latest"
                    )
                ),
                JsonRpcApi.JsonRpcRequest(
                    id = 2,
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safeAddress,
                            "data" to GnosisSafe.GetThreshold.encode()
                        ), "latest"
                    )
                )
            )
        )
        val masterCopy = responses[0].result!!.asEthereumAddress()!!
        val owners = GnosisSafe.GetOwners.decode(responses[1].result!!).param0.items
        val threshold = GnosisSafe.GetThreshold.decode(responses[2].result!!).param0.value
        return SafeRepository.SafeInfo(safeAddress, masterCopy, owners, threshold)
    }

    override suspend fun loadPendingTransactions(): List<SafeRepository.PendingSafeTx> {
        val safeAddress = getSafeAddress()
        val currentNonce = loadSafeNonce(safeAddress)
        val transactions = transactionServiceApi.loadTransactions(safeAddress.asEthereumAddressChecksumString())
        return transactions.results.mapNotNull {
            val nonce = it.nonce.decimalAsBigIntegerOrNull()
            if (it.isExecuted || nonce == null || nonce < currentNonce) return@mapNotNull null
            SafeRepository.PendingSafeTx(
                hash = it.safeTxHash,
                tx = SafeRepository.SafeTx(
                    to = it.to?.asEthereumAddress() ?: Solidity.Address(BigInteger.ZERO),
                    value = it.value.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                    data = it.data ?: "",
                    operation = it.operation.toOperation()
                ),
                execInfo = SafeRepository.SafeTxExecInfo(
                    baseGas = it.baseGas.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                    txGas = it.safeTxGas.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                    gasPrice = it.gasPrice.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                    gasToken = it.gasToken?.asEthereumAddress() ?: Solidity.Address(BigInteger.ZERO),
                    refundReceiver = it.refundReceiver?.asEthereumAddress() ?: Solidity.Address(BigInteger.ZERO),
                    nonce = nonce
                ),
                confirmations = it.confirmations.map { confirmation ->
                    confirmation.owner.asEthereumAddress()!! to confirmation.signature
                }
            )
        }
    }

    private fun Int.toOperation() =
        when (this) {
            0 -> SafeRepository.SafeTx.Operation.CALL
            1 -> SafeRepository.SafeTx.Operation.DELEGATE
            else -> throw IllegalArgumentException("Unsupported operation")
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
        val nonce = loadSafeNonce(safeAddress)
        val relayNonce = estimate.lastUsedNonce?.decimalAsBigInteger()?.let { it + BigInteger.ONE } ?: BigInteger.ZERO
        return SafeRepository.SafeTxExecInfo(
            estimate.dataGas.decimalAsBigInteger(),
            estimate.safeTxGas.decimalAsBigInteger(),
            estimate.gasPrice.decimalAsBigInteger(),
            estimate.gasToken,
            Solidity.Address(BigInteger.ZERO),
            relayNonce.max(nonce)
        )
    }

    private suspend fun loadSafeNonce(safeAddress: Solidity.Address): BigInteger {
        return GnosisSafe.Nonce.decode(
            jsonRpcApi.post(
                JsonRpcApi.JsonRpcRequest(
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safeAddress,
                            "data" to GnosisSafe.Nonce.encode()
                        ), "latest"
                    )
                )
            ).result!!
        ).param0.value
    }

    override suspend fun confirmSafeTransaction(
        transaction: SafeRepository.SafeTx,
        execInfo: SafeRepository.SafeTxExecInfo,
        confirmations: List<Pair<Solidity.Address, String?>>?
    ): String? {
        val safeAddress = getSafeAddress()
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
                execInfo.nonce
            )

        val keyPair = getKeyPair()
        val deviceId = keyPair.address.toAddress()
        val signature = keyPair.sign(hash)

        val confirmation = ServiceTransactionRequest(
            to = transaction.to.asEthereumAddressChecksumString(),
            value = transaction.value.asDecimalString(),
            data = transaction.data,
            operation = transaction.operation.id,
            gasToken = execInfo.gasToken.asEthereumAddressChecksumString(),
            safeTxGas = execInfo.txGas.asDecimalString(),
            baseGas = execInfo.baseGas.asDecimalString(),
            gasPrice = execInfo.gasPrice.asDecimalString(),
            refundReceiver = execInfo.refundReceiver.asEthereumAddressChecksumString(),
            nonce = execInfo.nonce.asDecimalString(),
            safeTxHash = hash.toHexString(),
            sender = deviceId.asEthereumAddressChecksumString(),
            confirmationType = ServiceTransactionRequest.CONFIRMATION,
            signature = signature.toSignatureString()
        )
        transactionServiceApi.confirmTransaction(safeAddress.asEthereumAddressChecksumString(), confirmation)

        val info = loadSafeInfo()
        // Relay required refund
        if (execInfo.gasPrice == BigInteger.ZERO) return null

        // Relay only allows ECDSA signatures
        val signatureMap = mutableMapOf<Solidity.Address, Pair<Solidity.Address, ECDSASignature>>()
        confirmations?.forEach { (signer, signature) ->
            if (signature == null) return@forEach
            signatureMap[signer] = signer to signature.removeHexPrefix().toECDSASignature()
        }
        signatureMap[deviceId] = deviceId to signature
        if (info.threshold > signatureMap.size.toBigInteger()) return null

        val ethHash = submitSafeTransaction(transaction, execInfo, signatureMap.values)
        try {
            transactionServiceApi.confirmTransaction(
                safeAddress.asEthereumAddressChecksumString(),
                confirmation.copy(transactionHash = ethHash, signature = null, confirmationType = ServiceTransactionRequest.EXECUTION)
            )
        } catch (e: Exception) {
            // Transaction has been submitted, status update on history service is secondary
            e.printStackTrace()
        }
        return ethHash
    }

    private fun ECDSASignature.toSignatureString() =
        r.toString(16).padStart(64, '0').substring(0, 64) +
                s.toString(16).padStart(64, '0').substring(0, 64) +
                v.toString(16).padStart(2, '0')

    private fun String.toECDSASignature(): ECDSASignature {
        require(length == 130)
        val r = BigInteger(substring(0, 64), 16)
        val s = BigInteger(substring(64, 128), 16)
        val v = substring(128, 130).toByte(16)
        return ECDSASignature(r, s).apply { this.v = v }
    }

    private suspend fun submitSafeTransaction(
        transaction: SafeRepository.SafeTx,
        execInfo: SafeRepository.SafeTxExecInfo,
        confirmations: Collection<Pair<Solidity.Address, ECDSASignature>>
    ): String {
        val safeAddress = getSafeAddress()
        val response = relayServiceApi.execute(
            safeAddress.asEthereumAddressChecksumString(),
            ExecuteParams(
                transaction.to.asEthereumAddressChecksumString(),
                transaction.value.asDecimalString(),
                transaction.data,
                transaction.operation.id,
                confirmations.sortedBy { it.first.value }.map { ServiceSignature.fromSignature(it.second) },
                execInfo.txGas.asDecimalString(),
                execInfo.baseGas.asDecimalString(),
                execInfo.gasPrice.asDecimalString(),
                execInfo.gasToken.asEthereumAddressChecksumString(),
                execInfo.nonce.toLong()
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

        private const val PREF_KEY_PENDING_TRANSACTION_HASH = "accounts.string.pending_transaction_hash"

        private const val ENC_PASSWORD = "ThisShouldNotBeHardcoded"
    }
}
