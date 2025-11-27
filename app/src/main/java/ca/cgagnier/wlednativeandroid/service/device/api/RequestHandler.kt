package ca.cgagnier.wlednativeandroid.service.device.api

import ca.cgagnier.wlednativeandroid.service.device.api.request.Request
import ca.cgagnier.wlednativeandroid.service.device.api.request.SoftwareUpdateRequest

abstract class RequestHandler {

    suspend fun processRequest(request: Request) {
        when(request) {
            is SoftwareUpdateRequest -> handleSoftwareUpdateRequest(request)
            else -> throw Exception("Unknown request type: ${request.javaClass}")
        }
    }

    abstract suspend fun handleSoftwareUpdateRequest(request: SoftwareUpdateRequest)
}