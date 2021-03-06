/**
 * Copyright (c) 2020-present, Dash Core Team
 *
 * This source code is licensed under the MIT license found in the
 * COPYING file in the root directory of this source tree.
 */

package org.dashevo.dapiclient.grpc

import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.dashevo.dapiclient.DapiClient
import org.dashevo.dapiclient.model.DocumentQuery
import org.dashevo.dapiclient.model.GrpcExceptionInfo
import org.dashevo.dpp.StateRepository
import org.dashevo.dpp.contract.DataContractCreateTransition
import org.dashevo.dpp.document.DocumentsBatchTransition
import org.dashevo.dpp.identifier.Identifier
import org.dashevo.dpp.identity.IdentityCreateTransition
import org.dashevo.dpp.identity.IdentityTopupTransition
import org.dashevo.dpp.statetransition.StateTransition
import org.slf4j.LoggerFactory

/**
 * This interface contains a shouldRetry method which will be used by
 * [DapiClient.grpcRequest] to determine if a retry of the last DAPI call
 * should be attempted given the exception.
 *
 * This allows customization based on the [GrpcMethod] used.
 */
interface GrpcMethodShouldRetryCallback {
    fun shouldRetry(grpcMethod: GrpcMethod, e: StatusRuntimeException): Boolean
    fun shouldThrowException(e: StatusRuntimeException): Boolean
}

/**
 * shouldRetry always returns true
 */
open class DefaultShouldRetryCallback : GrpcMethodShouldRetryCallback {
    override fun shouldRetry(grpcMethod: GrpcMethod, e: StatusRuntimeException): Boolean {
        return when (e.status.code) {
            Status.INVALID_ARGUMENT.code  -> {
                // do not retry any invalid argument errors
                false
            }
            Status.NOT_FOUND.code -> {
                // do not retry any not found errors
                false
            }
            else -> true
        }
    }

    override fun shouldThrowException(e: StatusRuntimeException): Boolean {
        return e.status.code != Status.DEADLINE_EXCEEDED.code
                && e.status.code != Status.UNAVAILABLE.code
                && e.status.code != Status.INTERNAL.code
                && e.status.code != Status.CANCELLED.code
                && e.status.code != Status.UNKNOWN.code
    }
}

open class DefaultGetDocumentsRetryCallback() : DefaultShouldRetryCallback()

open class DefaultGetIdentityRetryCallback : DefaultShouldRetryCallback()

open class DefaultGetDocumentsWithContractIdRetryCallback(protected open val retryContractIds: List<Identifier>) :
    DefaultShouldRetryCallback() {
    companion object {
        private val logger = LoggerFactory.getLogger(DefaultGetDocumentsWithContractIdRetryCallback::class.java.name)
    }

    override fun shouldRetry(grpcMethod: GrpcMethod, e: StatusRuntimeException): Boolean {
        grpcMethod as GetDocumentsMethod
        if (e.status.code == Status.INVALID_ARGUMENT.code) {
            val error = GrpcExceptionInfo(e).errors[0]
            if (error.containsKey("name") && error["name"] == "InvalidContractIdError") {
                if (retryContractIds.contains(Identifier.from(grpcMethod.request.dataContractId.toByteArray()))) {
                    logger.info("Retry ${grpcMethod.javaClass.simpleName} ${e.status.code} since error was InvalidContractIdError")
                    return true
                }
            }
            // throw exception for any other invalid argument errors
            throw e
        }
        return super.shouldRetry(grpcMethod, e)
    }

    override fun shouldThrowException(e: StatusRuntimeException): Boolean {
        return super.shouldThrowException(e) && e.status.code != Status.INVALID_ARGUMENT.code
    }
}

open class DefaultGetIdentityWithIdentitiesRetryCallback(protected open val retryIdentityIds: List<Identifier> = listOf()) :
    DefaultShouldRetryCallback() {
    companion object {
        private val logger = LoggerFactory.getLogger(DefaultGetIdentityWithIdentitiesRetryCallback::class.java.name)
    }

    override fun shouldRetry(grpcMethod: GrpcMethod, e: StatusRuntimeException): Boolean {
        grpcMethod as GetIdentityMethod
        if (e.status.code == Status.NOT_FOUND.code) {
            if (retryIdentityIds.contains(Identifier.from(grpcMethod.request.id.toByteArray()))) {
                logger.info("Retry $grpcMethod): ${e.status.code} since error was NOT_FOUND")
                return true
            }
        }
        return super.shouldRetry(grpcMethod, e)
    }

    override fun shouldThrowException(e: StatusRuntimeException): Boolean {
        return super.shouldThrowException(e) && e.status.code != Status.INVALID_ARGUMENT.code
    }
}


open class DefaultGetContractRetryCallback : DefaultShouldRetryCallback()

open class DefaultGetDataContractWithContractIdRetryCallback(protected open val retryContractIds: List<Identifier> = listOf()) :
    DefaultShouldRetryCallback() {
    companion object {
        private val logger = LoggerFactory.getLogger(DefaultGetDataContractWithContractIdRetryCallback::class.java.name)
    }

    override fun shouldRetry(grpcMethod: GrpcMethod, e: StatusRuntimeException): Boolean {
        grpcMethod as GetContractMethod
        if (e.status.code == Status.NOT_FOUND.code) {
            if (retryContractIds.contains(Identifier.from(grpcMethod.request.id.toByteArray()))) {
                logger.info("Retry $grpcMethod: ${e.status.code} since error was contract not found")
                return true
            }

            // throw exception for any other invalid argument errors
            throw e
        }
        return super.shouldRetry(grpcMethod, e)
    }

    override fun shouldThrowException(e: StatusRuntimeException): Boolean {
        return super.shouldThrowException(e) && e.status.code != Status.INVALID_ARGUMENT.code
    }
}

/**
 * DefaultBroadcastRetryCallback will determine if a state transition was successful, but it only called
 * when [DapiClient.broadcastStateTransition] returns an error
 *
 * For [DocumentsBatchTransition]s that contain more than one document, they are all assumed to be
 * the same type as the first transition.
 *
 * Retry functionality
 *    INTERNAL, DEADLINE exceptions: check to see if the document, identity or contract exists
 *    INVALID_ARGUMENT: Invalid contract or identity. Check if the contract or identity is part of the
 *      retry Id lists which should be verified through other calls to getDocuments, getIdentity, getContract
 *
 * @property stateRepository StateRepository Used for DAPI calls to fetch documents, identities and contracts
 * @property updatedAt Long If a document was updated in the broadcast, this will be used to identify the updated document.
 * @constructor
 */
open class DefaultBroadcastRetryCallback(
    private val stateRepository: StateRepository,
    private val updatedAt: Long = -1,
    private val retryCount: Int = DEFAULT_RETRY_COUNT,
    protected open val retryContractIds: List<Identifier> = listOf(),
    protected open val retryIdentityIds: List<Identifier> = listOf(),
    protected open val retryDocumentIds: List<Identifier> = listOf()
) : DefaultShouldRetryCallback() {
    companion object {
        private val logger = LoggerFactory.getLogger(DefaultBroadcastRetryCallback::class.java.name)
        const val DEFAULT_RETRY_COUNT = 5
    }

    override fun shouldRetry(grpcMethod: GrpcMethod, e: StatusRuntimeException): Boolean {
        logger.info("Determining if we should retry ${grpcMethod.javaClass.simpleName} ${e.status.code}")
        if (grpcMethod is BroadcastStateTransitionMethod) {
            if (e.status.code == Status.INVALID_ARGUMENT.code) {
                logger.info("--> INVALID_ARGUMENT")
                // only retry if it is DocumentsBatchTransition
                // throw exception for any other invalid argument errors
                val errorInfo = GrpcExceptionInfo(e)
                if (errorInfo.errors.isNotEmpty() && errorInfo.errors[0].containsKey("name")) {
                    logger.info("-->${errorInfo.errors[0]["name"]} was the invalid argument type")
                    when (errorInfo.errors[0]["name"]) {
                        "IdentityNotFoundError" -> {
                            if (shouldRetryIdentityNotFound(grpcMethod.stateTransition)) {
                                logger.info("---retry based on IdentityNotFoundError")
                                return true
                            } else {
                                logger.info("---will not retry based on IdentityNotFoundError")
                            }
                        }
                        "DataTriggerConditionError" -> {
                            if (errorInfo.errors[0]["message"] == "preorderDocument was not found") {
                               if (shouldRetryDocumentNotFound(grpcMethod.stateTransition))
                                   return true
                            }
                        }
                    }
                }
                // there is another case that needs to be handled below for DocumentsBatchTransition
                if (grpcMethod.stateTransition !is DocumentsBatchTransition)
                    throw e
            }

            when (grpcMethod.stateTransition) {
                is DataContractCreateTransition -> {
                    val contactCreateTransition = grpcMethod.stateTransition as DataContractCreateTransition
                    for (i in 0 until retryCount) {
                        //how to delay
                        delay()
                        val identityData = stateRepository.fetchDataContract(contactCreateTransition.dataContract.id)

                        if (identityData != null) {
                            logger.info("contract found. No need to retry: ${contactCreateTransition.dataContract.id}")
                            return false
                        }
                    }
                    logger.info("contract not found, need to retry: ${contactCreateTransition.dataContract.id}")
                }
                is IdentityCreateTransition -> {
                    val identityCreateTransition = grpcMethod.stateTransition as IdentityCreateTransition
                    for (i in 0 until retryCount) {
                        //how to delay
                        delay()
                        val identityData = stateRepository.fetchIdentity(identityCreateTransition.identityId)

                        if (identityData != null) {
                            logger.info("identity found. No need to retry: ${identityCreateTransition.identityId}")
                            return false
                        }
                    }
                    logger.info("identity not found, need to retry: ${identityCreateTransition.identityId}")
                }
                is DocumentsBatchTransition -> {
                    val documentTransitions = grpcMethod.stateTransition.transitions

                    // this only works for document create transitions, assume the first is similar to all the
                    // rest using the same contract and document type
                    val idList = documentTransitions.map { it.id }
                    val dataContractId = documentTransitions[0].dataContractId
                    val type = documentTransitions[0].type

                    if (e.status.code == Status.INVALID_ARGUMENT.code) {
                        val error = GrpcExceptionInfo(e).errors[0]
                        if (error.containsKey("name") && error["name"] == "InvalidContractIdError") {
                            if (retryContractIds.contains(Identifier.from(dataContractId))) {
                                logger.info("Retry ${grpcMethod.javaClass.simpleName} ${e.status.code} since error was InvalidContractIdError")
                                return true
                            }
                        }
                        // throw exception for any other invalid argument errors
                        throw e
                    }

                    val queryBuilder = DocumentQuery.builder()
                        .where(listOf("\$id", "in", idList))

                    if (updatedAt != -1L) {
                        queryBuilder.where(listOf("updatedAt", "==", updatedAt))
                    }

                    val query = queryBuilder.build()


                    for (i in 0 until retryCount) {
                        //how to delay
                        delay()
                        val documentsData = stateRepository.fetchDocuments(dataContractId, type, query)

                        if (documentsData != null && documentsData.isNotEmpty()) {
                            logger.info("document(s) found. No need to retry: $idList")
                            return false
                        }
                    }
                    logger.info("document(s) not found, need to retry: $idList")
                }
            }
        }
        return true
    }

    private fun shouldRetryIdentityNotFound(stateTransition: StateTransition): Boolean {
        return when (stateTransition) {
            is DocumentsBatchTransition -> {
                logger.info ("---looking for ${stateTransition.ownerId} in $retryIdentityIds")
                retryIdentityIds.contains(stateTransition.ownerId)
            }
            is DataContractCreateTransition -> {
                logger.info ("---looking for ${stateTransition.dataContract.ownerId} in $retryIdentityIds")
                retryIdentityIds.contains(stateTransition.dataContract.ownerId)
            }
            is IdentityTopupTransition -> {
                logger.info ("---looking for ${stateTransition.identityId} in $retryIdentityIds")
                retryIdentityIds.contains(stateTransition.identityId)
            }
            else -> false
        }
    }

    private fun shouldRetryDocumentNotFound(stateTransition: StateTransition): Boolean {
        if (stateTransition is DocumentsBatchTransition) {
            logger.info ("---looking for ${stateTransition.transitions[0].id} in $retryIdentityIds")
            if (retryDocumentIds.contains(stateTransition.transitions[0].id)) {
                return true
            }
        }
        return false
    }

    override fun shouldThrowException(e: StatusRuntimeException): Boolean {
        return super.shouldThrowException(e) && e.status.code != Status.INVALID_ARGUMENT.code
    }

    private fun delay(milliseconds: Long = 3000) {
        Thread.sleep(milliseconds)
    }
}