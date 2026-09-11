package com.github.melancholic.fintrace.core.service.transfer

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.Transfer
import com.github.melancholic.fintrace.core.domain.entity.TransferLeg
import com.github.melancholic.fintrace.core.exception.BrokenTransferException
import org.springframework.stereotype.Service
import java.util.*

interface TransferLoader {
    fun loadTransfer(workspaceId: UUID, transferId: UUID): Transfer
}

@Service
class TransferLoaderImpl(
    private val operationProjectionDAO: OperationProjectionDAO,
    private val accountProjectionDAO: AccountProjectionDAO
) : TransferLoader {

    override fun loadTransfer(
        workspaceId: UUID,
        transferId: UUID
    ): Transfer {
        val (sourceId, targetId) = operationProjectionDAO.getTransferPartiesById(workspaceId, transferId)

        val source = operationProjectionDAO.getById(workspaceId, sourceId)
        val sourceCurrency = accountProjectionDAO.getById(workspaceId, source.accountId).currency

        val target = operationProjectionDAO.getById(workspaceId, targetId)
        val targetCurrency = accountProjectionDAO.getById(workspaceId, target.accountId).currency

        return Transfer(
            id = transferId,
            workspaceId = workspaceId,
            occurredAt = assertSame(
                source.occurredAt,
                target.occurredAt,
                "`occurredAt` (source: ${source.occurredAt}, target: ${target.occurredAt})`"
            ),
            recordedAt = assertSame(
                source.recordedAt,
                target.recordedAt,
                "`recordedAt` (source: ${source.recordedAt}, target: ${target.recordedAt})"
            ),
            comment = assertSame(source.comment, target.comment, "`comment`"),
            source = TransferLeg(
                operationId = source.id,
                accountId = source.accountId,
                currency = sourceCurrency,
                amount = source.amount,
            ),
            target = TransferLeg(
                operationId = target.id,
                accountId = target.accountId,
                currency = targetCurrency,
                amount = target.amount,
            )
        )
    }

    private fun <T> assertSame(
        a: T,
        b: T,
        errorMsg: String
    ): T {
        if (a != b) {
            throw BrokenTransferException("Broken transfer transfer parties: inconsistency in $errorMsg")
        }
        return a
    }

}