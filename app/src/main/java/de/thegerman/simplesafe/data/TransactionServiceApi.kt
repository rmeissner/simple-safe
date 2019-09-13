package de.thegerman.simplesafe.data

import de.thegerman.simplesafe.BuildConfig
import de.thegerman.simplesafe.data.models.*
import retrofit2.http.*


interface TransactionServiceApi {

    @GET("v1/safes/{address}/transactions/")
    suspend fun loadTransactions(@Path("address") address: String): PaginatedResult<ServiceTransaction>

    companion object {
        const val BASE_URL = BuildConfig.TRANSACTION_SERVICE_URL
    }
}
