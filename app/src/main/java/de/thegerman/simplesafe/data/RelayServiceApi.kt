package de.thegerman.simplesafe.data

import de.thegerman.simplesafe.BuildConfig
import de.thegerman.simplesafe.data.models.*
import retrofit2.http.*


interface RelayServiceApi {
    @POST("v1/safes/{address}/transactions/")
    suspend fun execute(@Path("address") address: String, @Body params: ExecuteParams): RelayExecution

    @POST("v1/safes/{address}/transactions/estimate/")
    suspend fun estimate(@Path("address") address: String, @Body params: EstimateParams): RelayEstimate

    @POST("v2/safes/")
    suspend fun safeCreation(@Body params: RelaySafeCreationParams): RelaySafeCreation

    @PUT("v2/safes/{address}/funded/")
    suspend fun notifySafeFunded(@Path("address") address: String): Unit

    @GET("v2/safes/{address}/funded/")
    suspend fun safeFundStatus(@Path("address") address: String): RelaySafeFundStatus

    companion object {
        const val BASE_URL = BuildConfig.RELAY_SERVICE_URL
    }
}
