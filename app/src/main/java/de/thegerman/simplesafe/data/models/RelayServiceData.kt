package de.thegerman.simplesafe.data.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import de.thegerman.simplesafe.data.adapter.DecimalNumber
import pm.gnosis.model.Solidity
import pm.gnosis.models.Wei
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class ExecuteParams(
    @Json(name = "to")
    val to: String,
    @Json(name = "value")
    val value: String,
    @Json(name = "data")
    val data: String?,
    @Json(name = "operation")
    val operation: Int,
    @Json(name = "signatures")
    val signatures: List<ServiceSignature>,
    @Json(name = "safeTxGas")
    val safeTxGas: String,
    @Json(name = "dataGas")
    val dataGas: String,
    @Json(name = "gasPrice")
    val gasPrice: String,
    @Json(name = "gasToken")
    val gasToken: String,
    @Json(name = "nonce")
    val nonce: Long
)

@JsonClass(generateAdapter = true)
data class RelayExecution(
    @Json(name = "transactionHash")
    val transactionHash: String
)

@JsonClass(generateAdapter = true)
data class EstimateParams(
    @Json(name = "to")
    val to: String,
    @Json(name = "value")
    val value: String,
    @Json(name = "data")
    val data: String,
    @Json(name = "operation")
    val operation: Int,
    @Json(name = "threshold")
    val threshold: Int,
    @Json(name = "gasToken")
    val gasToken: Solidity.Address
)

@JsonClass(generateAdapter = true)
data class RelayEstimate(
    @Json(name = "safeTxGas")
    val safeTxGas: String,
    @Json(name = "dataGas")
    val dataGas: String,
    @Json(name = "operationalGas")
    val operationalGas: String,
    @Json(name = "gasPrice")
    val gasPrice: String,
    @Json(name = "gasToken")
    val gasToken: Solidity.Address,
    @Json(name = "lastUsedNonce")
    val lastUsedNonce: String?
)

@JsonClass(generateAdapter = true)
data class EstimatesParams(
    @Json(name = "to")
    val to: String,
    @Json(name = "value")
    val value: String,
    @Json(name = "data")
    val data: String,
    @Json(name = "operation")
    val operation: Int
)

@JsonClass(generateAdapter = true)
data class RelayEstimates(
    @Json(name = "safeTxGas")
    val safeTxGas: String,
    @Json(name = "operationalGas")
    val operationalGas: String,
    @Json(name = "lastUsedNonce")
    val lastUsedNonce: String?,
    @Json(name = "estimations")
    val estimations: List<RelayEstimatesDetails>
)

@JsonClass(generateAdapter = true)
data class RelayEstimatesDetails(
    @Json(name = "baseGas")
    val baseGas: String,
    @Json(name = "gasPrice")
    val gasPrice: String,
    @Json(name = "gasToken")
    val gasToken: Solidity.Address
)

@JsonClass(generateAdapter = true)
data class CreationEstimatesParams(
    @Json(name = "numberOwners")
    val numberOwners: Long
)

@JsonClass(generateAdapter = true)
data class CreationEstimate(
    @Json(name = "gas")
    val gas: String,
    @Json(name = "gasPrice")
    val gasPrice: String,
    @Json(name = "payment")
    val payment: String,
    @Json(name = "paymentToken")
    val paymentToken: Solidity.Address
)

@JsonClass(generateAdapter = true)
data class RelaySafeCreationParams(
    @Json(name = "owners") val owners: List<Solidity.Address>,
    @Json(name = "threshold") val threshold: Int,
    @Json(name = "saltNonce") val saltNonce: Long,
    @Json(name = "paymentToken") val paymentToken: Solidity.Address
)

@JsonClass(generateAdapter = true)
data class RelaySafeCreation(
    @Json(name = "safe") val safe: Solidity.Address,
    @Json(name = "masterCopy") val masterCopy: Solidity.Address,
    @Json(name = "proxyFactory") val proxyFactory: Solidity.Address,
    @Json(name = "setupData") val setupData: String,
    @Json(name = "payment") @field:DecimalNumber val payment: BigInteger,
    @Json(name = "paymentToken") val paymentToken: Solidity.Address,
    @Json(name = "paymentReceiver") val paymentReceiver: Solidity.Address
)

@JsonClass(generateAdapter = true)
data class RelaySafeCreationTx(
    @Json(name = "from") val from: Solidity.Address,
    @Json(name = "value") val value: Wei,
    @Json(name = "data") val data: String,
    @Json(name = "gas") @field:DecimalNumber val gas: BigInteger,
    @Json(name = "gasPrice") @field:DecimalNumber val gasPrice: BigInteger,
    @Json(name = "nonce") @field:DecimalNumber val nonce: BigInteger
)

@JsonClass(generateAdapter = true)
data class RelaySafeFundStatus(
    @Json(name = "blockNumber") val blockNumber: Long?,
    @Json(name = "txHash") val txHash: String?
)
