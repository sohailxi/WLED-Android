package ca.cgagnier.wlednativeandroid.service.api

import ca.cgagnier.wlednativeandroid.model.wledapi.Info
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface DeviceApi {
    @GET("json/info")
    suspend fun getInfo(): Response<Info>

    @Multipart
    @POST("update")
    suspend fun updateDevice(
        @Part binaryFile: MultipartBody.Part
    ): Response<ResponseBody>
}